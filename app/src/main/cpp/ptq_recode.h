#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#ifndef BONSAI_PTQ_PLANAR
#define BONSAI_PTQ_PLANAR 0
#endif
#ifndef BONSAI_RECODE_BUTTERFLY
#define BONSAI_RECODE_BUTTERFLY 0
#endif
#ifndef BONSAI_RECODE_PROFILE
#define BONSAI_RECODE_PROFILE 0
#endif
#ifndef BONSAI_RECODE_DOT8
#define BONSAI_RECODE_DOT8 0
#endif
#ifndef BONSAI_RECODE_AVX2_ONLY
#define BONSAI_RECODE_AVX2_ONLY 0
#endif
#ifdef __cplusplus
extern "C" {
#endif
// Lossless PTQ1_0 -> two-bit cache. Each 28-byte source block becomes 34 bytes;
// original FP16 scale bits and all 128 decoded weights are preserved exactly.
// This block helper always emits canonical adjacent-code block_pq2_0 bytes.
// Source and destination must not overlap in either transcoding helper.
void bonsai_ptq_recode_blocks(const void * source, void * destination, size_t blocks);
typedef struct { uint16_t d[4]; uint8_t qs[128]; } BonsaiPtqCache4;
// Upload original block-aligned slices into four-row/block-major cache storage.
// Payload layout is build-specific: with BONSAI_PTQ_PLANAR each row byte j
// stores positions j,j+8,j+16,j+24 of one unchanged Q8_0 block. Never mix layouts.
void bonsai_ptq_recode_upload(const void * source, void * destination, size_t first_block, size_t blocks, size_t row_blocks);
// Four interleaved cache rows against one unchanged Q8_0 activation row,
// followed by n/32 int16 activation sums (used only by x86 kernels).
bool bonsai_ptq_recode_dot4(int n, float * output, const void * weights, const void * activations);
// Experimental x86 kernel: two consecutive Cache4[n/128] groups, eight outputs.
// Caller must check AVX512F/BW/DQ/VL/VNNI, FMA/F16C and n divisible by 128.
void bonsai_ptq_recode_dot8(int n, float * output, const void * weights, const void * activations);
// Resolve once per graph rather than repeating feature checks for every group.
// The returned kernel requires n >= 0 divisible by 128 and the layouts above.
typedef void (*BonsaiPtqDot4)(int n, float * output, const void * weights, const void * activations);
BonsaiPtqDot4 bonsai_ptq_recode_select_dot4(void);
const char * bonsai_ptq_recode_kernel_name(void);
#ifdef __cplusplus
}
struct ggml_backend_buffer_type;
// Register before the first device-extra-buffer enumeration (not merely before
// model loading): the runtime snapshots that list. Never mutate during compute.
void bonsai_ptq_recode_register();
ggml_backend_buffer_type * bonsai_ptq_recode_buffer_type();
uint64_t bonsai_ptq_recode_graph_calls();
// Number of worker graph batches that actually executed at least one dot8.
uint64_t bonsai_ptq_recode_dot8_batches();
#endif
