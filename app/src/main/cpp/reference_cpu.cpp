#include "reference_cpu.h"
extern "C" {
#include "quants.h"
}

std::atomic<bool> bonsai_reference_cpu{false};

#if defined(__x86_64__) || defined(__i386__)
// The pinned x86 runtime already aliases Q2_0 to its scalar implementation.
#define ggml_vec_dot_q2_0_q8_0_generic __real_ggml_vec_dot_q2_0_q8_0
#endif

// Link-time wrappers keep the pinned runtime unchanged. The reference bypasses
// architecture-specific ternary dot products, including ARM NEON on the A55.
#define BONSAI_WRAP_DOT(name) \
extern "C" void __real_##name(int, float *, size_t, const void *, size_t, const void *, size_t, int); \
extern "C" void __wrap_##name(int n, float * s, size_t bs, const void * x, size_t bx, const void * y, size_t by, int nrc) { \
    if (!bonsai_reference_cpu.load(std::memory_order_relaxed)) { __real_##name(n, s, bs, x, bx, y, by, nrc); return; } \
    for (int col = 0; col < nrc; ++col) \
        for (int row = 0; row < nrc; ++row) \
            name##_generic(n, s + col * bs + row, 0, static_cast<const char *>(x) + row * bx, 0, static_cast<const char *>(y) + col * by, 0, 1); \
}

BONSAI_WRAP_DOT(ggml_vec_dot_q1_0_q8_0)
BONSAI_WRAP_DOT(ggml_vec_dot_q2_0_q8_0)
BONSAI_WRAP_DOT(ggml_vec_dot_pq2_0_q8_0)
BONSAI_WRAP_DOT(ggml_vec_dot_pq2_0_q8_K)
BONSAI_WRAP_DOT(ggml_vec_dot_ptq1_0_q8_0)
