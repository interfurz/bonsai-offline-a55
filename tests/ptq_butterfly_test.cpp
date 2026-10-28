#include "ptq_recode.h"
#define GGML_COMMON_DECL_CPP
#include "ggml-common.h"
#include <cstdio>
#include <cstring>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <random>
#include <vector>

static unsigned comparisons = 0;
static void fail(const char * reason, int n, unsigned tag, int row = -1) {
    std::fprintf(stderr, "FAIL %s n=%d tag=%u row=%d layout=%d\n", reason, n, tag, row, BONSAI_PTQ_PLANAR);
    std::exit(1);
}

// Independent exact finite-half conversion: no ggml library or SIMD required.
static float half(uint16_t bits) {
    const unsigned exponent = (bits >> 10) & 31;
    const unsigned fraction = bits & 1023;
    const float magnitude = exponent ? std::ldexp(float(1024 + fraction), int(exponent) - 25)
                                     : std::ldexp(float(fraction), -24);
    return bits & 0x8000 ? -magnitude : magnitude;
}

struct Fixture {
    int n;
    std::vector<BonsaiPtqCache4> weights;
    // Extra Q8 objects provide contiguous, naturally aligned room for int16 sums.
    std::vector<block_q8_0> storage;
    explicit Fixture(int blocks) : n(blocks * 128), weights(blocks),
        storage(blocks * 4 + (blocks * 4 * sizeof(int16_t) + sizeof(block_q8_0) - 1) / sizeof(block_q8_0) + 1) {}
    int16_t * sums() { return reinterpret_cast<int16_t *>(storage.data() + n / 32); }
    void update_sums() {
        for (int k = 0; k < n / 32; ++k) {
            int sum = 0;
            for (int j = 0; j < 32; ++j) sum += storage[k].qs[j];
            sums()[k] = int16_t(sum);
        }
    }
};

extern "C" void bonsai_ptq_butterfly_dot4(int, float *, const void *, const void *);

// Force real fused arithmetic in this oracle. Strawberry's baseline fmaf path
// differed by one ULP in a two-block cancellation case; -mfma reproduced VNNI.
__attribute__((target("fma"))) static void compare(Fixture & f, unsigned tag) {
    f.update_sums();
    const auto before_weights = f.weights;
    const auto before_activations = f.storage;
    float expected[4], scalar[4] = {}, guarded[6] = {123.25f, 0, 0, 0, 0, -456.5f};
    if (!bonsai_ptq_recode_dot4(f.n, expected, f.weights.data(), f.storage.data()))
        fail("reference rejected valid n", f.n, tag);
    bonsai_ptq_butterfly_dot4(f.n, guarded + 1, f.weights.data(), f.storage.data());
    for (int b = 0; b < f.n / 128; ++b) {
        float sub[4] = {};
        for (int k = 0; k < 4; ++k) {
            const auto & q = f.storage[b * 4 + k];
            for (int row = 0; row < 4; ++row) {
                int dot = 0;
                for (int j = 0; j < 32; ++j) {
                    const int byte = BONSAI_PTQ_PLANAR ? j % 8 : j / 4;
                    const int shift = 2 * (BONSAI_PTQ_PLANAR ? j / 8 : j % 4);
                    const int code = (f.weights[b].qs[k * 32 + row * 8 + byte] >> shift) & 3;
                    dot += (code - 1) * int(q.qs[j]);
                }
                sub[row] = std::fma(float(dot), half(q.d), sub[row]);
            }
        }
        for (int row = 0; row < 4; ++row)
            scalar[row] = std::fma(half(f.weights[b].d[row]), sub[row], scalar[row]);
    }
    for (int row = 0; row < 4; ++row) {
        if (std::memcmp(expected + row, guarded + row + 1, sizeof(float))) {
            std::fprintf(stderr, "reference=%a butterfly=%a\n", expected[row], guarded[row + 1]);
            fail("not bit-exact against default VNNI", f.n, tag, row);
        }
        if (std::memcmp(scalar + row, guarded + row + 1, sizeof(float))) {
            std::fprintf(stderr, "scalar=%a reference=%a butterfly=%a\n", scalar[row], expected[row], guarded[row + 1]);
            fail("independent scalar mismatch", f.n, tag, row);
        }
        if (!std::isfinite(guarded[row + 1])) fail("nonfinite output", f.n, tag, row);
    }
    if (guarded[0] != 123.25f || guarded[5] != -456.5f) fail("output canary", f.n, tag);
    if ((!f.weights.empty() && std::memcmp(before_weights.data(), f.weights.data(), f.weights.size() * sizeof(BonsaiPtqCache4))) ||
        std::memcmp(before_activations.data(), f.storage.data(), f.storage.size() * sizeof(block_q8_0)))
        fail("input modified", f.n, tag);
    ++comparisons;
}

static void exhaustive_bytes() {
    for (uint32_t raw = 0; raw < 256; ++raw) {
        const uint32_t t = (raw | (raw << 12)) & 0x000f000f;
        const uint32_t result = (t | (t << 6)) & 0x03030303;
        uint32_t expected = 0;
        for (int k = 0; k < 4; ++k) expected |= ((raw >> (2 * k)) & 3) << (8 * k);
        if (result != expected) fail("butterfly byte identity", 0, raw);
        // Each byte value occupies every one of the 128 payload byte locations.
        // One-hot probes distinguish all 32 expanded activation positions.
        for (int position = 0; position < 32; ++position) {
            Fixture f(1);
            for (int row = 0; row < 4; ++row) f.weights[0].d[row] = 0x3c00;
            std::memset(f.weights[0].qs, int(raw), sizeof(f.weights[0].qs));
            for (int k = 0; k < 4; ++k) {
                f.storage[k].d = uint16_t(0x3400 + k * 0x400);
                f.storage[k].qs[position] = int8_t(k % 2 ? -128 : 127);
            }
            compare(f, raw * 32 + position);
        }
    }
    std::puts("PASS exhaustive 256 byte identities + all payload positions and one-hot activation lanes");
}

static void scales_extremes_long_rows() {
    const uint16_t scales[] = {0x0000,0x8000,0x0001,0x8001,0x03ff,0x83ff,0x0400,0x8400,
                              0x2e66,0xae66,0x3555,0xb555,0x3c00,0xbc00,0x4901,0xc901,0x7bff,0xfbff};
    std::mt19937 rng(0x42555454);
    for (int blocks : {0,1,2,3,17,129,513}) {
        for (unsigned phase = 0; phase < sizeof(scales) / sizeof(scales[0]); ++phase) {
            for (int mode = 0; mode < 4; ++mode) {
                Fixture f(blocks);
                for (int b = 0; b < blocks; ++b) {
                    for (int row = 0; row < 4; ++row)
                        f.weights[b].d[row] = scales[(phase + b + row) % 18];
                    for (auto & byte : f.weights[b].qs) byte = uint8_t(rng());
                    for (int k = 0; k < 4; ++k) {
                        auto & q = f.storage[b * 4 + k];
                        q.d = scales[(phase + 5 * b + 3 * k) % 18];
                        for (int j = 0; j < 32; ++j)
                            q.qs[j] = mode == 0 ? -128 : mode == 1 ? 127 :
                                      mode == 2 ? (j % 2 ? -128 : 127) : int(rng() % 256) - 128;
                    }
                }
                compare(f, phase * 4 + mode);
            }
        }
    }
    std::puts("PASS finite FP16 scales (+/-zero, subnormal, max), -128/127, random, zero/long rows");
}


int main() {
    __builtin_cpu_init();
    if (!(__builtin_cpu_supports("avx512f") && __builtin_cpu_supports("avx512bw") &&
          __builtin_cpu_supports("avx512dq") && __builtin_cpu_supports("avx512vl") &&
          __builtin_cpu_supports("avx512vnni") && __builtin_cpu_supports("fma") &&
          __builtin_cpu_supports("f16c"))) {
        std::puts("SKIP: AVX512-VNNI/FMA/F16C unavailable");
        return 77;
    }
    BonsaiPtqCache4 weights{};
    for (auto & scale : weights.d) scale = 0x3c00; // FP16 1
    std::memset(weights.qs, 0xaa, sizeof(weights.qs)); // every code is 2
    struct { block_q8_0 q[4]; int16_t sums[4]; } activations{};
    static_assert(sizeof(activations) == 4 * sizeof(block_q8_0) + 4 * sizeof(int16_t), "Q8 + sums layout");
    for (int k = 0; k < 4; ++k) {
        activations.q[k].d = 0x3c00;
        std::memset(activations.q[k].qs, 127, 32);
        activations.sums[k] = 32 * 127;
    }
    float expected[4], actual[4];
    if (!bonsai_ptq_recode_dot4(128, expected, &weights, &activations)) return 2;
    bonsai_ptq_butterfly_dot4(128, actual, &weights, &activations);
    if (std::memcmp(expected, actual, sizeof(actual))) {
        std::fprintf(stderr, "FAIL basic dot4: expected=%a actual=%a layout=%d\n", expected[0], actual[0], BONSAI_PTQ_PLANAR);
        return 1;
    }
    const char * reference = bonsai_ptq_recode_kernel_name();
    if (std::strcmp(reference, BONSAI_PTQ_PLANAR ? "avx512-vnni-planar" : "avx512-vnni"))
        fail("reference is not the default VNNI path", 0, 0);
    std::printf("PASS basic dot4 bit-exact layout=%d reference=%s\n", BONSAI_PTQ_PLANAR, reference);
    exhaustive_bytes();
    scales_extremes_long_rows();
    std::printf("PASS %u additional bit-exact comparisons layout=%d\n", comparisons, BONSAI_PTQ_PLANAR);
}
