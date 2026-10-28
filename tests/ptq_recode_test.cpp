#include "ptq_recode.h"
#include "ggml.h"
#include "ggml-cpu.h"
#include "ggml-backend.h"
#include "ggml-alloc.h"
#include "quants.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <iostream>
#include <random>
#include <stdexcept>
#include <vector>

static void require(bool ok, const char * message) { if (!ok) throw std::runtime_error(message); }
static void codec() {
    std::vector<block_ptq1_0> source(256);
    std::vector<block_pq2_0> packed(256);
    std::vector<float> before(128),after(128);
    for (int i = 0; i < 256; ++i) {
        std::memset(&source[i],i,sizeof(source[i]));
        source[i].d = ggml_fp32_to_fp16(i%2 ? -0.125f : 0.25f);
    }
    bonsai_ptq_recode_blocks(source.data(),packed.data(),source.size());
    for (int i = 0; i < 256; ++i) {
        ggml_get_type_traits(GGML_TYPE_PTQ1_0)->to_float(&source[i],before.data(),128);
        ggml_get_type_traits(GGML_TYPE_PQ2_0)->to_float(&packed[i],after.data(),128);
        require(source[i].d == packed[i].d,"Scale bits changed");
        require(before == after,"Recode changed a decoded weight");
    }
}
static void graph(int width, int rows, int columns, int threads, bool reference) {
    const int blocks = width/128;
    std::mt19937 rng(unsigned(width+rows+columns));
    std::vector<block_ptq1_0> weights(blocks*rows);
    for (size_t i = 0; i < weights.size(); ++i) {
        for (auto & b : weights[i].qs) b = uint8_t(rng());
        for (auto & b : weights[i].qh) b = uint8_t(rng());
        weights[i].d = ggml_fp32_to_fp16(std::sin(float(i)*0.87f)*(0.0101f+int(i%7)*0.037f));
    }
    std::vector<float> input(width*columns);
    for (size_t i = 0; i < input.size(); ++i) input[i] = std::sin(float(i)*0.731f)*(1+int(i%7));
    auto * backend = ggml_backend_cpu_init();
    require(backend!=nullptr,"CPU backend missing");
    ggml_backend_cpu_set_n_threads(backend,threads);
    ggml_backend_cpu_set_use_ref(backend,reference);
    std::vector<float> original;
    for (bool recoded : {false,true}) {
        auto * wc = ggml_init({1024*1024,nullptr,true});
        auto * w = ggml_new_tensor_2d(wc,GGML_TYPE_PTQ1_0,width,rows);
        const auto buft = recoded ? bonsai_ptq_recode_buffer_type() : ggml_backend_cpu_buffer_type();
        const size_t allocation = ggml_backend_buft_get_alloc_size(buft,w);
        require(allocation == weights.size()*(recoded ? sizeof(block_pq2_0) : sizeof(block_ptq1_0)),"Wrong expanded allocation size");
        auto * wb = ggml_backend_alloc_ctx_tensors_from_buft(wc,buft);
        require(wb!=nullptr,"Weight allocation failed");
        // Both complete and block-aligned partial uploads must transcode safely.
        const size_t half = weights.size()/2;
        ggml_backend_tensor_set(w,weights.data(),0,half*sizeof(block_ptq1_0));
        ggml_backend_tensor_set(w,weights.data()+half,half*sizeof(block_ptq1_0),(weights.size()-half)*sizeof(block_ptq1_0));
        auto * ctx = ggml_init({1024*1024,nullptr,true});
        auto * x = ggml_new_tensor_2d(ctx,GGML_TYPE_F32,width,columns);
        auto * y = ggml_mul_mat(ctx,w,x);
        require(ggml_backend_dev_supports_op(ggml_backend_get_device(backend),y),"Custom buffer graph dispatch rejected");
        auto * gf = ggml_new_graph(ctx);
        ggml_build_forward_expand(gf,y);
        auto * buffer = ggml_backend_alloc_ctx_tensors(ctx,backend);
        require(buffer!=nullptr,"Activation allocation failed");
        ggml_backend_tensor_set(x,input.data(),0,input.size()*sizeof(float));
        const auto calls = bonsai_ptq_recode_graph_calls();
        #if BONSAI_RECODE_DOT8
        const auto dot8_batches = bonsai_ptq_recode_dot8_batches();
        #endif
        require(ggml_backend_graph_compute(backend,gf)==GGML_STATUS_SUCCESS,"Graph failed");
        require(bonsai_ptq_recode_graph_calls() == calls + (recoded ? 1 : 0),"Expected kernel did not execute");
        #if BONSAI_RECODE_DOT8
        const char * prefix = "avx512-vnni";
        const bool paired = recoded && !reference && rows >= 8 &&
            std::strncmp(bonsai_ptq_recode_kernel_name(),prefix,std::strlen(prefix))==0;
        require((bonsai_ptq_recode_dot8_batches()>dot8_batches)==paired,
                "Eight-row graph dispatch or scalar/tail fallback did not execute as expected");
        #endif
        std::vector<float> actual(rows*columns);
        ggml_backend_tensor_get(y,actual.data(),0,actual.size()*sizeof(float));
        if (!recoded) original=actual;
        else {
            for (size_t i = 0; i < actual.size(); ++i)
                require(std::isfinite(actual[i]) && std::abs(actual[i]-original[i]) <= 2e-4f*std::max(1.f,std::abs(original[i])),"Repacked graph differs from original PTQ graph");
            #if defined(__x86_64__)
            if (!reference && actual!=original) {
                for (size_t i=0;i<actual.size();++i) if (actual[i]!=original[i]) {
                    std::cerr << "Mismatch width="<<width<<" columns="<<columns<<" threads="<<threads<<" ref="<<reference<<" index="<<i
                              <<" original="<<std::hexfloat<<original[i]<<" candidate="<<actual[i]<<std::defaultfloat<<'\n';
                    break;
                }
            }
            require(reference || actual==original,"Host graph is not bit-identical to native PTQ");
            #endif
            // Independent dense product of dequantized weights and the SAME Q8 activations.
            std::vector<float> dw(width*rows),dq(width*columns);
            std::vector<block_q8_0> q(width/32*columns);
            ggml_get_type_traits(GGML_TYPE_PTQ1_0)->to_float(weights.data(),dw.data(),width*rows);
            for (int col = 0; col < columns; ++col) {
                ggml_get_type_traits_cpu(GGML_TYPE_Q8_0)->from_float(input.data()+col*width,q.data()+col*width/32,width);
                ggml_get_type_traits(GGML_TYPE_Q8_0)->to_float(q.data()+col*width/32,dq.data()+col*width,width);
                for (int row = 0; row < rows; ++row) {
                    double expected=0,magnitude=0;
                    for (int j=0;j<width;++j) {
                        const double product=double(dw[row*width+j])*dq[col*width+j];
                        expected+=product;magnitude+=std::abs(product);
                    }
                    require(std::abs(actual[col*rows+row]-expected) <= 1e-5*std::max(1.0,magnitude),"Independent dense graph mismatch");
                }
            }
        }
        ggml_backend_buffer_free(buffer);ggml_free(ctx);
        ggml_backend_buffer_free(wb);ggml_free(wc);
    }
    ggml_backend_free(backend);
}
static void upload_canaries() {
    constexpr size_t row_blocks=3, count=12*row_blocks, guard=64;
    std::vector<block_ptq1_0> source(count);
    std::vector<block_pq2_0> expected(count);
    for (size_t i=0;i<count;++i) {
        std::memset(&source[i],int(i*37),sizeof(source[i]));
        source[i].d=uint16_t(i*1793); // Preserve arbitrary FP16 bits, not only finite scales.
    }
    bonsai_ptq_recode_blocks(source.data(),expected.data(),count);
    std::vector<uint8_t> storage(count*sizeof(block_pq2_0)+2*guard,0xA5);
    auto * packed=reinterpret_cast<BonsaiPtqCache4 *>(storage.data()+guard);
    // Out-of-order partial uploads cross both row and four-row group boundaries.
    for (size_t end=count;end>0;) {
        const size_t first=end>5 ? end-5 : 0;
        bonsai_ptq_recode_upload(source.data()+first,packed,first,end-first,row_blocks);
        end=first;
    }
    for (size_t i=0;i<count;++i) {
        const size_t row=i/row_blocks, block=i%row_blocks;
        const auto & actual=packed[(row/4)*row_blocks+block];
        require(actual.d[row%4]==expected[i].d,"Partial upload changed scale bits");
        for (int k=0;k<4;++k) for (int j=0;j<32;++j) {
            const unsigned want=(expected[i].qs[k*8+j/4]>>(2*(j%4)))&3;
            #if BONSAI_PTQ_PLANAR
            const unsigned got=(actual.qs[k*32+(row%4)*8+j%8]>>(2*(j/8)))&3;
            #else
            const unsigned got=(actual.qs[k*32+(row%4)*8+j/4]>>(2*(j%4)))&3;
            #endif
            require(got==want,"Partial upload crossed cache rows");
        }
    }
    for (size_t i=0;i<guard;++i)
        require(storage[i]==0xA5 && storage[storage.size()-guard+i]==0xA5,"Cache upload overwrote canary");
}
static void rejects_invalid_weights() {
    auto * ctx=ggml_init({1024*1024,nullptr,true});
    auto * buffer=ggml_backend_buft_alloc_buffer(bonsai_ptq_recode_buffer_type(),4096);
    require(buffer!=nullptr,"Recode buffer allocation failed");
    auto * backend=ggml_backend_cpu_init();
    for (int rows : {1,3,5,7}) {
        auto * w=ggml_new_tensor_2d(ctx,GGML_TYPE_PTQ1_0,128,rows);
        require(ggml_backend_buffer_init_tensor(buffer,w)==GGML_STATUS_FAILED,"Tail rows accepted by cache init");
    }
    auto * w=ggml_new_tensor_2d(ctx,GGML_TYPE_PTQ1_0,128,4);
    require(ggml_backend_tensor_alloc(buffer,w,ggml_backend_buffer_get_base(buffer))==GGML_STATUS_SUCCESS,"Supported weight rejected");
    auto * view=ggml_view_2d(ctx,w,128,4,w->nb[1],0);
    require(ggml_backend_buffer_init_tensor(buffer,view)==GGML_STATUS_FAILED,"Weight view accepted by cache init");
    auto * x=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,128,1);
    view->buffer=buffer;
    auto * y=ggml_mul_mat(ctx,view,x);
    require(!ggml_backend_dev_supports_op(ggml_backend_get_device(backend),y),"Weight view selected custom graph dispatch");
    auto * wrong_type=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,128,4);
    require(ggml_backend_buffer_init_tensor(buffer,wrong_type)==GGML_STATUS_FAILED,"F32 weight accepted by cache init");
    auto * batched=ggml_new_tensor_3d(ctx,GGML_TYPE_PTQ1_0,128,4,2);
    require(ggml_backend_buffer_init_tensor(buffer,batched)==GGML_STATUS_FAILED,"Batched weight accepted by cache init");
    ggml_backend_free(backend);ggml_backend_buffer_free(buffer);ggml_free(ctx);
}
int main() {
    try {
        ggml_backend_load_all();ggml_cpu_init();
        auto * tables=ggml_init({1024*1024,nullptr,true});
        bonsai_ptq_recode_register();bonsai_ptq_recode_register();
        codec();upload_canaries();
        int cases=0;
        for (int width : {128,384,512,1024}) for (int columns : {1,3,8})
            for (int threads : {1,2,4}) for (bool reference : {false,true}) {
                graph(width,12,columns,threads,reference);++cases;
            }
        #if BONSAI_RECODE_DOT8
        for (int rows : {4,8,20}) for (int columns : {1,3}) for (int threads : {1,2,4})
            for (bool reference : {false,true}) {
                graph(384,rows,columns,threads,reference);++cases;
            }
        #endif
        rejects_invalid_weights();ggml_free(tables);
        std::cout << "PTQ lossless recode: all 256 bytecodes, expanded allocation, partial upload canaries, invalid shapes/views, " << cases
                  << " graph cases, scalar reference and dense dots passed\n";
    } catch (const std::exception & e) { std::cerr << e.what() << '\n';return 1; }
}
