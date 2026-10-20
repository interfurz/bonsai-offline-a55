#include "reference_cpu.h"
#include "ggml.h"
#include "ggml-cpu.h"
#include "ggml-impl.h"
#include "ggml-quants.h"
#include "ggml-alloc.h"
#include "ggml-backend.h"
#include <atomic>
#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <vector>

using Dot = void (*)(int, float *, size_t, const void *, size_t, const void *, size_t, int);
#define DECLARE_DOT(name) extern "C" void __wrap_##name(int, float *, size_t, const void *, size_t, const void *, size_t, int);
DECLARE_DOT(ggml_vec_dot_q1_0_q8_0)
DECLARE_DOT(ggml_vec_dot_q2_0_q8_0)
DECLARE_DOT(ggml_vec_dot_pq2_0_q8_0)
DECLARE_DOT(ggml_vec_dot_pq2_0_q8_K)
DECLARE_DOT(ggml_vec_dot_ptq1_0_q8_0)

// Observe the actual execution path, not just agreement between two runs of it.
static std::atomic<int> fwht_calls{0};
struct ggml_compute_params;
extern "C" void __real_ggml_compute_forward_fwht(const ggml_compute_params *, ggml_tensor *);
extern "C" void __wrap_ggml_compute_forward_fwht(const ggml_compute_params * params, ggml_tensor * dst) {
    ++fwht_calls;
    __real_ggml_compute_forward_fwht(params,dst);
}

static void check_hadamard(int n,int rows) {
    std::vector<float> weights(n*n),input(n*rows),output(n*rows),expected(n*rows);
    for(int r=0;r<n;++r)for(int c=0;c<n;++c)
        weights[r*n+c]=(__builtin_parity(unsigned(r&c))?-1.f:1.f)/std::sqrt(float(n));
    for(int r=0;r<rows;++r)for(int c=0;c<n;++c)input[r*n+c]=std::sin(c*.731f+r);
    for(int r=0;r<rows;++r)for(int i=0;i<n;++i) {
        double value=0;
        for(int c=0;c<n;++c)value+=double(weights[i*n+c])*input[r*n+c];
        expected[r*n+i]=float(value);
    }
    for(bool reference : {false,true,false}) {
        bonsai_reference_cpu=reference;
        auto backend=ggml_backend_init_by_type(GGML_BACKEND_DEVICE_TYPE_CPU,nullptr);
        if(!backend)throw std::runtime_error("CPU backend missing");
        ggml_backend_cpu_set_n_threads(backend,2);
        auto * ctx=ggml_init({1024*1024,nullptr,true});
        auto * w=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,n,n);
        auto * x=ggml_new_tensor_2d(ctx,GGML_TYPE_F32,n,rows);
        auto * y=ggml_mul_mat(ctx,w,x);
        ggml_mul_mat_set_hint(y,GGML_HINT_SRC0_IS_HADAMARD);
        auto * graph=ggml_new_graph(ctx);
        ggml_build_forward_expand(graph,y);
        auto buffer=ggml_backend_alloc_ctx_tensors(ctx,backend);
        if(!buffer)throw std::runtime_error("Test tensor allocation failed");
        ggml_backend_tensor_set(w,weights.data(),0,ggml_nbytes(w));
        ggml_backend_tensor_set(x,input.data(),0,ggml_nbytes(x));
        fwht_calls=0;
        if(ggml_backend_graph_compute(backend,graph)!=GGML_STATUS_SUCCESS)
            throw std::runtime_error("Hadamard graph failed");
        if(reference ? fwht_calls!=0 : fwht_calls==0)
            throw std::runtime_error("CPU reference did not select an independent Hadamard path");
        ggml_backend_tensor_get(y,output.data(),0,ggml_nbytes(y));
        for(size_t i=0;i<output.size();++i)
            if(!std::isfinite(output[i]) || std::abs(output[i]-expected[i])>2e-5f)
                throw std::runtime_error("Hadamard dense-reference mismatch");
        ggml_backend_buffer_free(buffer);ggml_free(ctx);ggml_backend_free(backend);
    }
}

static void check(ggml_type wt, ggml_type qt, Dot dot, int n) {
    const auto * wtraits = ggml_get_type_traits(wt);
    const auto * qtraits = ggml_get_type_traits(qt);
    const size_t wb = ggml_row_size(wt, n), qb = ggml_row_size(qt, n);
    std::vector<unsigned char> w(2 * wb), q(2 * qb);
    std::vector<float> input(n), decoded_w(2 * n), decoded_q(2 * n);
    for (int row = 0; row < 2; ++row) {
        for (int i = 0; i < n; ++i) input[i] = ((i * 17 + row * 5) % 3 - 1) * (1 + (i / 128) % 5) * .125f;
        wtraits->from_float_ref(input.data(), w.data() + row * wb, n);
        wtraits->to_float(w.data() + row * wb, decoded_w.data() + row * n, n);
        for (int i = 0; i < n; ++i) input[i] = std::sin(i * .731f + row) * (1 + (i / 32) % 7);
        if (qt == GGML_TYPE_Q8_K) {
            auto * blocks = reinterpret_cast<block_q8_K *>(q.data() + row * qb);
            quantize_row_q8_K_ref(input.data(), blocks, n);
            dequantize_row_q8_K(blocks, decoded_q.data() + row * n, n);
        } else {
            qtraits->from_float_ref(input.data(), q.data() + row * qb, n);
            qtraits->to_float(q.data() + row * qb, decoded_q.data() + row * n, n);
        }
    }
    auto verify = [&](float actual, int row, int col) {
        double expected = 0, magnitude = 0;
        for (int i = 0; i < n; ++i) {
            double product = double(decoded_w[row * n + i]) * decoded_q[col * n + i];
            expected += product; magnitude += std::abs(product);
        }
        if (!std::isfinite(actual) || std::abs(actual - expected) > 1e-5 * std::max(1.0, magnitude))
            throw std::runtime_error(std::string(ggml_type_name(wt)) + " dense-dot mismatch at width " + std::to_string(n));
    };
    for (bool reference : {false, true}) {
        bonsai_reference_cpu = reference;
        float actual = 0;
        dot(n, &actual, 0, w.data(), 0, q.data(), 0, 1);
        verify(actual, 0, 0);
    }
    bonsai_reference_cpu = true;
    float matrix[8] = {};
    dot(n, matrix, 4, w.data(), wb, q.data(), qb, 2);
    for (int col = 0; col < 2; ++col)
        for (int row = 0; row < 2; ++row) verify(matrix[col * 4 + row], row, col);
}

int main() {
    ggml_backend_load_all();
    ggml_cpu_init();
    // Initializes GGML's FP16 conversion tables.
    auto * context = ggml_init({1024 * 1024, nullptr, true});
    for (int n : {128, 256, 384, 1024, 5120}) {
        check(GGML_TYPE_Q1_0, GGML_TYPE_Q8_0, __wrap_ggml_vec_dot_q1_0_q8_0, n);
        check(GGML_TYPE_Q2_0, GGML_TYPE_Q8_0, __wrap_ggml_vec_dot_q2_0_q8_0, n);
        check(GGML_TYPE_PQ2_0, GGML_TYPE_Q8_0, __wrap_ggml_vec_dot_pq2_0_q8_0, n);
        check(GGML_TYPE_PTQ1_0, GGML_TYPE_Q8_0, __wrap_ggml_vec_dot_ptq1_0_q8_0, n);
        if (n % 256 == 0) check(GGML_TYPE_PQ2_0, GGML_TYPE_Q8_K, __wrap_ggml_vec_dot_pq2_0_q8_K, n);
    }
    ggml_free(context);
    for(int n : {128,1024})for(int rows : {1,3})check_hadamard(n,rows);
    bonsai_reference_cpu=false;
    std::cout << "Bonsai optimized/scalar kernels and 2x2 fallback match independent dense dots\n";
    std::cout << "CPU reference bypasses fast Hadamard paths and matches dense transforms\n";
}
