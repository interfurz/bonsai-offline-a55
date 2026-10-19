#include "reference_cpu.h"
#include "ggml.h"
#include "ggml-cpu.h"
#include "ggml-impl.h"
#include "ggml-quants.h"
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
    std::cout << "Bonsai optimized/scalar kernels and 2x2 fallback match independent dense dots\n";
}
