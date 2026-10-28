#include "reference_cpu.h"
#include "ternary_dot.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
extern "C" {
#include "quants.h"
}

std::atomic<bool> bonsai_reference_cpu{false};

// A scalar dot product alone is not an independent reference for Bonsai 2:
// its Hadamard rotations and fused graph operations would still use fast paths.
// llama creates this CPU backend for every new context, including configure().
extern "C" ggml_backend_t __real_ggml_backend_init_by_type(enum ggml_backend_dev_type, const char *);
extern "C" ggml_backend_t __wrap_ggml_backend_init_by_type(enum ggml_backend_dev_type type, const char * params) {
    auto backend = __real_ggml_backend_init_by_type(type, params);
    if (backend && ggml_backend_is_cpu(backend)) {
        ggml_backend_cpu_set_use_ref(backend, bonsai_reference_cpu.load(std::memory_order_relaxed));
    }
    return backend;
}

#if defined(__x86_64__) || defined(__i386__)
// The pinned x86 runtime already aliases Q2_0 to its scalar implementation.
#define ggml_vec_dot_q2_0_q8_0_generic __real_ggml_vec_dot_q2_0_q8_0
#endif

// Link-time wrappers keep the pinned runtime unchanged. The reference bypasses
// architecture-specific ternary dot products, including ARM NEON on the A55.
#define BONSAI_WRAP_DOT(name, fast) \
extern "C" void __real_##name(int, float *, size_t, const void *, size_t, const void *, size_t, int); \
extern "C" void __wrap_##name(int n, float * s, size_t bs, const void * x, size_t bx, const void * y, size_t by, int nrc) { \
    if (!bonsai_reference_cpu.load(std::memory_order_relaxed)) { \
        if (nrc == 1 && (fast)) return; \
        __real_##name(n, s, bs, x, bx, y, by, nrc); return; \
    } \
    for (int col = 0; col < nrc; ++col) \
        for (int row = 0; row < nrc; ++row) \
            name##_generic(n, s + col * bs + row, 0, static_cast<const char *>(x) + row * bx, 0, static_cast<const char *>(y) + col * by, 0, 1); \
}

BONSAI_WRAP_DOT(ggml_vec_dot_q1_0_q8_0, false)
BONSAI_WRAP_DOT(ggml_vec_dot_q2_0_q8_0, false)
BONSAI_WRAP_DOT(ggml_vec_dot_pq2_0_q8_0, false)
#if defined(__aarch64__) && BONSAI_FAST_TERNARY
BONSAI_WRAP_DOT(ggml_vec_dot_pq2_0_q8_K, bonsai_dot_pq2_q8k(n, s, x, y))
#else
BONSAI_WRAP_DOT(ggml_vec_dot_pq2_0_q8_K, false)
#endif
#if BONSAI_FAST_TERNARY
BONSAI_WRAP_DOT(ggml_vec_dot_ptq1_0_q8_0, bonsai_dot_ptq1_q80(n, s, x, y))
#else
BONSAI_WRAP_DOT(ggml_vec_dot_ptq1_0_q8_0, false)
#endif
