#pragma once
#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif
// Return false without touching the result on unsupported architectures.
bool bonsai_dot_pq2_q8k(int n, float * result, const void * weights, const void * activations);
bool bonsai_dot_ptq1_q80(int n, float * result, const void * weights, const void * activations);
#ifdef __cplusplus
}
#endif
