#include "ptq_recode.h"
#include "traits.h"
#include "ggml-backend-impl.h"
#include "ggml-cpu.h"
#include "ggml-impl.h"
#include "quants.h"
#include <algorithm>
#include <atomic>
#include <climits>
#include <cstring>
#include <mutex>
#if BONSAI_RECODE_PROFILE
#include "ptq_recode_profile.h"
#include <chrono>
#endif
#ifndef BONSAI_RECODE_GRAPH_DISPATCH
#define BONSAI_RECODE_GRAPH_DISPATCH 0
#endif
#ifndef BONSAI_RECODE_COLUMN_TILE
#define BONSAI_RECODE_COLUMN_TILE 0
#endif
#if BONSAI_RECODE_GRAPH_DISPATCH && BONSAI_RECODE_COLUMN_TILE
#error Test graph dispatch and column tiling independently
#endif

namespace {
std::atomic<uint64_t> graph_calls{0};
std::atomic<uint64_t> dot8_batches{0};

bool weight_shape(const ggml_tensor * w) {
    return w->type == GGML_TYPE_PTQ1_0 && w->ne[0] > 0 && w->ne[0] <= INT_MAX &&
        w->ne[0] % 128 == 0 && w->ne[1] > 0 && w->ne[1] % 4 == 0 &&
        w->ne[2] == 1 && w->ne[3] == 1 && w->view_src == nullptr && ggml_is_contiguous(w);
}

size_t activation_row_bytes(int64_t n) {
    size_t bytes = ggml_row_size(GGML_TYPE_Q8_0,n);
    #if defined(__x86_64__) || defined(__i386__)
    bytes += size_t(n/32)*sizeof(int16_t);
    #endif
    return bytes;
}

bool matrix_shape(const ggml_tensor * op) {
    if (op->op != GGML_OP_MUL_MAT || !op->src[0] || !op->src[1]) return false;
    const auto * w = op->src[0];
    const auto * x = op->src[1];
    return weight_shape(w) && x->type == GGML_TYPE_F32 && op->type == GGML_TYPE_F32 &&
        x->ne[0] == w->ne[0] && x->ne[1] > 0 && x->ne[2] == 1 && x->ne[3] == 1 &&
        op->ne[0] == w->ne[1] && op->ne[1] == x->ne[1] && op->ne[2] == 1 && op->ne[3] == 1 &&
        ggml_is_contiguous(x) && ggml_is_contiguous(op);
}

bool our_op(const ggml_tensor * op) {
    return matrix_shape(op) && op->src[0]->buffer &&
        op->src[0]->buffer->buft == bonsai_ptq_recode_buffer_type();
}

void scalar_dot4(int n, float * output, const BonsaiPtqCache4 * weights, const block_q8_0 * activations) {
    const int blocks = n / 128;
    for (int row = 0; row < 4; ++row) {
        float total = 0;
        for (int b = 0; b < blocks; ++b) {
            const auto & w = weights[b];
            float sub = 0;
            for (int k = 0; k < 4; ++k) {
                const auto & q = activations[b*4+k];
                int dot = 0;
                for (int j = 0; j < 32; ++j) {
                    #if BONSAI_PTQ_PLANAR
                    dot += (int((w.qs[k*32+row*8+j%8] >> (2*(j/8))) & 3)-1)*int(q.qs[j]);
                    #else
                    dot += (int((w.qs[k*32+row*8+j/4] >> (2*(j%4))) & 3)-1)*int(q.qs[j]);
                    #endif
                }
                sub += ggml_fp16_to_fp32(q.d)*dot;
            }
            total += ggml_fp16_to_fp32(w.d[row])*sub;
        }
        output[row] = total;
    }
}

class RecodeTraits final : public ggml::cpu::tensor_traits {
public:
    bool work_size(int, const ggml_tensor * op, size_t & size) override {
        if (!our_op(op)) return false;
        size = activation_row_bytes(op->src[1]->ne[0])*size_t(op->src[1]->ne[1]);
        return true;
    }
    bool compute_forward(ggml_compute_params * params, ggml_tensor * op) override {
        if (!our_op(op)) return false;
        const auto * w = op->src[0];
        const auto * x = op->src[1];
        #if BONSAI_RECODE_PROFILE
        using ProfileClock = std::chrono::steady_clock;
        const bool profiling = bonsai_ptq_profile::enabled();
        const auto quant_start = profiling ? ProfileClock::now() : ProfileClock::time_point{};
        #endif
        const int n = int(w->ne[0]);
        const size_t row_bytes = activation_row_bytes(n);
        GGML_ASSERT(params->wsize >= row_bytes*size_t(x->ne[1]));
        const auto from_float = ggml_get_type_traits_cpu(GGML_TYPE_Q8_0)->from_float;
        // Same activation quantizer and 32-value scale boundaries as native PTQ1.
        const int64_t blocks = n/32;
        for (int64_t col = 0; col < x->ne[1]; ++col) {
            const int64_t first = blocks*params->ith/params->nth;
            const int64_t last = blocks*(params->ith+1)/params->nth;
            if (last > first)
                from_float(static_cast<const float *>(x->data)+col*n+first*32,
                    static_cast<char *>(params->wdata)+col*row_bytes+first*sizeof(block_q8_0),(last-first)*32);
            #if defined(__x86_64__) || defined(__i386__)
            const auto * quantized = reinterpret_cast<const block_q8_0 *>(static_cast<char *>(params->wdata)+col*row_bytes);
            auto * sums = reinterpret_cast<int16_t *>(static_cast<char *>(params->wdata)+col*row_bytes+ggml_row_size(GGML_TYPE_Q8_0,n));
            // Codes are w+1. Compute the activation-only correction ONCE, not
            // once for every four output rows. Values fit exactly in int16.
            for (int64_t b=first;b<last;++b) {
                int sum=0;
                for (int j=0;j<32;++j) sum+=quantized[b].qs[j];
                sums[b]=int16_t(sum);
            }
            #endif
        }
        #if BONSAI_RECODE_PROFILE
        const auto quant_end = profiling ? ProfileClock::now() : ProfileClock::time_point{};
        #endif
        ggml_barrier(params->threadpool);
        #if BONSAI_RECODE_PROFILE
        const auto dot_start = profiling ? ProfileClock::now() : ProfileClock::time_point{};
        #endif
        if (params->ith == 0) graph_calls.fetch_add(1,std::memory_order_relaxed);
        const int64_t groups = w->ne[1]/4;
        const auto * packed = static_cast<const BonsaiPtqCache4 *>(w->data);

        #if BONSAI_RECODE_DOT8
        const bool paired = !params->use_ref && __builtin_cpu_supports("avx512f") &&
            __builtin_cpu_supports("avx512bw") && __builtin_cpu_supports("avx512dq") &&
            __builtin_cpu_supports("avx512vl") && __builtin_cpu_supports("avx512vnni") &&
            __builtin_cpu_supports("fma") && __builtin_cpu_supports("f16c");
        if (paired) {
            const int64_t pairs = (groups+1)/2;
            const int64_t tasks = pairs*x->ne[1];
            const int64_t begin = tasks*params->ith/params->nth, end = tasks*(params->ith+1)/params->nth;
            bool used_dot8 = false;
            for (int64_t task = begin; task < end; ++task) {
                const int64_t col = task/pairs, group = (task%pairs)*2;
                auto * output = static_cast<float *>(op->data)+col*w->ne[1]+group*4;
                const auto * weights = packed+group*(n/128);
                const auto * activation = reinterpret_cast<const block_q8_0 *>(
                    static_cast<const char *>(params->wdata)+col*row_bytes);
                if (group+1 < groups) {
                    bonsai_ptq_recode_dot8(n,output,weights,activation);
                    used_dot8 = true;
                } else if (!bonsai_ptq_recode_dot4(n,output,weights,activation)) {
                    scalar_dot4(n,output,weights,activation);
                }
            }
            if (used_dot8) dot8_batches.fetch_add(1,std::memory_order_relaxed);
        } else
        #endif
        {
            const int64_t tasks = groups*x->ne[1];
            const int64_t begin = tasks*params->ith/params->nth, end = tasks*(params->ith+1)/params->nth;
            #if BONSAI_RECODE_GRAPH_DISPATCH
            int64_t col = begin/groups, group = begin%groups;
            const auto kernel = params->use_ref ? nullptr : bonsai_ptq_recode_select_dot4();
            #endif
            for (int64_t task = begin; task < end; ++task) {
                #if !BONSAI_RECODE_GRAPH_DISPATCH
                #if BONSAI_RECODE_COLUMN_TILE
                // Reuse a four-row weight slice across adjacent activation columns.
                // Individual dot products retain their exact reduction/FMA order.
                const int64_t col = task%x->ne[1], group = task/x->ne[1];
                #else
                const int64_t col = task/groups, group = task%groups;
                #endif
                #endif
                auto * output = static_cast<float *>(op->data)+col*w->ne[1]+group*4;
                const auto * weights = packed+group*(n/128);
                const auto * activation = reinterpret_cast<const block_q8_0 *>(static_cast<const char *>(params->wdata)+col*row_bytes);
                #if BONSAI_RECODE_GRAPH_DISPATCH
                if (kernel) kernel(n,output,weights,activation);
                else scalar_dot4(n,output,weights,activation);
                if (++group == groups) { group = 0; ++col; }
                #else
                if (params->use_ref || !bonsai_ptq_recode_dot4(n,output,weights,activation))
                    scalar_dot4(n,output,weights,activation);
                #endif
            }
        }
        #if BONSAI_RECODE_PROFILE
        if (profiling) {
            const auto dot_end = ProfileClock::now();
            const auto ns = [](auto duration) {
                return uint64_t(std::chrono::duration_cast<std::chrono::nanoseconds>(duration).count());
            };
            bonsai_ptq_profile::record({w->name,n,int(w->ne[1]),int(x->ne[1]),params->nth,params->ith,
                ns(quant_end-quant_start),ns(dot_start-quant_end),ns(dot_end-dot_start)});
        }
        #endif
        return true;
    }
};
RecodeTraits traits;

class RecodeBufferTraits final : public ggml::cpu::extra_buffer_type {
public:
    bool supports_op(ggml_backend_dev_t, const ggml_tensor * op) override {
        if (!our_op(op)) return false;
        const auto * x = op->src[1];
        // During model selection all probe inputs share a zero-size test buffer.
        return !x->buffer || x->buffer->buft == bonsai_ptq_recode_buffer_type() ||
               ggml_backend_buft_is_host(x->buffer->buft);
    }
    ggml::cpu::tensor_traits * get_tensor_traits(const ggml_tensor * op) override {
        return our_op(op) ? &traits : nullptr;
    }
};
RecodeBufferTraits buffer_traits;

size_t allocation_size(ggml_backend_buffer_type_t, const ggml_tensor * tensor) {
    // Invalid tensors are rejected by init_tensor before any upload. Avoid
    // asserting during allocation planning, which can also inspect such tensors.
    if (!weight_shape(tensor)) return ggml_nbytes(tensor);
    const size_t blocks = ggml_nbytes(tensor)/sizeof(block_ptq1_0);
    GGML_ASSERT(blocks <= SIZE_MAX/sizeof(block_pq2_0));
    return blocks*sizeof(block_pq2_0);
}
ggml_status init_tensor(ggml_backend_buffer_t, ggml_tensor * tensor) {
    return weight_shape(tensor) ? GGML_STATUS_SUCCESS : GGML_STATUS_FAILED;
}
void set_tensor(ggml_backend_buffer_t, ggml_tensor * tensor, const void * data, size_t offset, size_t size) {
    GGML_ASSERT(weight_shape(tensor) && offset%sizeof(block_ptq1_0)==0 && size%sizeof(block_ptq1_0)==0);
    GGML_ASSERT(offset <= ggml_nbytes(tensor) && size <= ggml_nbytes(tensor)-offset);
    bonsai_ptq_recode_upload(data,tensor->data,offset/sizeof(block_ptq1_0),size/sizeof(block_ptq1_0),size_t(tensor->ne[0]/128));
}
ggml_backend_buffer_t allocate(ggml_backend_buffer_type_t buft, size_t bytes) {
    auto * buffer = ggml_backend_buft_alloc_buffer(ggml_backend_cpu_buffer_type(),bytes);
    if (!buffer) return nullptr;
    buffer->buft = buft;
    buffer->iface.init_tensor = init_tensor;
    buffer->iface.set_tensor = set_tensor;
    // Expanded cache bytes are not standard PTQ1 bytes. Never advertise host
    // layout or let generic copy/memset/get operations reinterpret this memory.
    buffer->iface.get_tensor = nullptr;
    buffer->iface.cpy_tensor = nullptr;
    buffer->iface.memset_tensor = nullptr;
    buffer->iface.set_tensor_2d = nullptr;
    buffer->iface.get_tensor_2d = nullptr;
    return buffer;
}
size_t alignment(ggml_backend_buffer_type_t) {
    return ggml_backend_buft_get_alignment(ggml_backend_cpu_buffer_type());
}
const char * name(ggml_backend_buffer_type_t) { return BONSAI_PTQ_PLANAR ? "BONSAI_PTQ2_PLANAR_CACHE" : "BONSAI_PTQ2_CACHE"; }
}

ggml_backend_buffer_type * bonsai_ptq_recode_buffer_type() {
    static ggml_backend_buffer_type buft = {
        {name,allocate,alignment,nullptr,allocation_size,nullptr},
        ggml_backend_reg_dev_get(ggml_backend_cpu_reg(),0),&buffer_traits
    };
    return &buft;
}
void bonsai_ptq_recode_register() {
    static std::once_flag once;
    std::call_once(once,[] {
        auto & bufts = ggml_backend_cpu_get_extra_buffer_types();
        bufts.insert(bufts.begin(),bonsai_ptq_recode_buffer_type());
    });
}
uint64_t bonsai_ptq_recode_graph_calls() { return graph_calls.load(std::memory_order_relaxed); }
uint64_t bonsai_ptq_recode_dot8_batches() { return dot8_batches.load(std::memory_order_relaxed); }
