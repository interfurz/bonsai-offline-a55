#include "ptq_recode.h"
#define GGML_COMMON_DECL_CPP
#include "ggml-common.h"
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <cstdint>
#include <random>
#include <vector>

// Direct builds (Git Bash + Strawberry GCC); put artifacts outside the repo:
// export PATH=/c/Strawberry/c/bin:$PATH
// B='C:/Users/schaf/.hermes/profiles/bonsai-offline/cache/scratch/ptq-dot8-tdd'; mkdir -p "$B"
// For L=0 and L=1, use the same layout macro in all three translation units:
// gcc -std=c11 -O3 -Wall -Wextra -Werror -DBONSAI_PTQ_PLANAR=$L -DBONSAI_RECODE_BUTTERFLY=0 -DBONSAI_RECODE_VBMI=0 -DBONSAI_RECODE_VNNI_ONLY=1 -Iapp/src/main/cpp -Ivendor/llama.cpp/ggml/src -c app/src/main/cpp/ptq_recode_kernels.c -o "$B/ref-$L.o"
// gcc -std=c11 -O3 -Wall -Wextra -Werror -DBONSAI_PTQ_PLANAR=$L -Iapp/src/main/cpp -Ivendor/llama.cpp/ggml/src -c app/src/main/cpp/ptq_recode_dot8.c -o "$B/dot8-$L.o"
// g++ -std=c++17 -O3 -Wall -Wextra -Werror -DBONSAI_PTQ_PLANAR=$L -Iapp/src/main/cpp -Ivendor/llama.cpp/ggml/src tests/ptq_dot8_test.cpp "$B/ref-$L.o" "$B/dot8-$L.o" -o "$B/test-$L.exe"
// "$B/test-$L.exe"  # exit 77 means unsupported CPU, not a pass

extern "C" void bonsai_ptq_recode_dot8(int n, float * out, const void * weights, const void * activations);

static void fail(const char * message) {
    std::fprintf(stderr, "FAIL %s layout=%d\n", message, BONSAI_PTQ_PLANAR);
    std::exit(1);
}

static unsigned comparisons = 0;

static void fail_case(const char * reason, int n, unsigned tag, int row = -1) {
    std::fprintf(stderr, "FAIL %s n=%d tag=%u row=%d layout=%d\n", reason, n, tag, row, BONSAI_PTQ_PLANAR);
    std::exit(1);
}

// Independent finite-half conversion, not ggml's conversion or a SIMD helper.
static float half(uint16_t bits) {
    const unsigned exponent = (bits >> 10) & 31;
    const unsigned fraction = bits & 1023;
    const float magnitude = exponent ? std::ldexp(float(1024 + fraction), int(exponent) - 25)
                                     : std::ldexp(float(fraction), -24);
    return bits & 0x8000 ? -magnitude : magnitude;
}

struct Fixture {
    int blocks;
    std::vector<BonsaiPtqCache4> weight_storage;
    // Prefix/suffix guards, and enough naturally aligned room for appended sums.
    std::vector<block_q8_0> activation_storage;
    explicit Fixture(int count) : blocks(count), weight_storage(2 * count + 2),
        activation_storage(4 * count + (4 * count * sizeof(int16_t) + sizeof(block_q8_0) - 1) /
                                           sizeof(block_q8_0) + 2) {
        std::memset(weight_storage.data(), 0xa7, weight_storage.size() * sizeof(BonsaiPtqCache4));
        std::memset(activation_storage.data(), 0xb9, activation_storage.size() * sizeof(block_q8_0));
        std::memset(weights(), 0, 2 * count * sizeof(BonsaiPtqCache4));
        std::memset(activations(), 0, 4 * count * sizeof(block_q8_0));
    }
    BonsaiPtqCache4 * weights() { return weight_storage.data() + 1; }
    block_q8_0 * activations() { return activation_storage.data() + 1; }
    int16_t * sums() { return reinterpret_cast<int16_t *>(activations() + blocks * 4); }
    void update_sums() {
        for (int k = 0; k < blocks * 4; ++k) {
            int sum = 0;
            for (int j = 0; j < 32; ++j) sum += activations()[k].qs[j];
            sums()[k] = int16_t(sum);
        }
    }
};

// Strawberry's baseline Windows fmaf is not a reliable exact oracle: force
// hardware FMA inside this function, and call it only AFTER checking CPU flags.
__attribute__((target("fma"))) static void compare(Fixture & f, unsigned tag) {
    const int n = f.blocks * 128;
    f.update_sums();
    const auto before_weights = f.weight_storage;
    const auto before_activations = f.activation_storage;
    float expected[8], scalar[8] = {}, guarded[12];
    std::memset(guarded, 0xcd, sizeof(guarded));
    unsigned char before_output[sizeof(guarded)];
    std::memcpy(before_output, guarded, sizeof(guarded));
    const unsigned offset = 1 + tag % 3; // non-16/32-byte-aligned outputs too
    for (int group = 0; group < 2; ++group)
        if (!bonsai_ptq_recode_dot4(n, expected + group * 4, f.weights() + group * f.blocks, f.activations()))
            fail_case("reference rejected valid n", n, tag);
    bonsai_ptq_recode_dot8(n, guarded + offset, f.weights(), f.activations());
    for (int b = 0; b < f.blocks; ++b) {
        float sub[8] = {};
        for (int k = 0; k < 4; ++k) {
            const auto & q = f.activations()[b * 4 + k];
            for (int row = 0; row < 8; ++row) {
                const auto & w = f.weights()[(row / 4) * f.blocks + b];
                int dot = 0;
                for (int j = 0; j < 32; ++j) {
                    const int byte = BONSAI_PTQ_PLANAR ? j % 8 : j / 4;
                    const int shift = 2 * (BONSAI_PTQ_PLANAR ? j / 8 : j % 4);
                    const int code = (w.qs[k * 32 + (row % 4) * 8 + byte] >> shift) & 3;
                    // Deliberately do NOT use appended sums or VNNI reduction.
                    dot += (code - 1) * int(q.qs[j]);
                }
                sub[row] = std::fma(float(dot), half(q.d), sub[row]);
            }
        }
        for (int row = 0; row < 8; ++row)
            scalar[row] = std::fma(half(f.weights()[(row / 4) * f.blocks + b].d[row % 4]), sub[row], scalar[row]);
    }
    for (int row = 0; row < 8; ++row) {
        if (std::memcmp(expected + row, guarded + offset + row, sizeof(float))) {
            std::fprintf(stderr, "reference=%a dot8=%a\n", expected[row], guarded[offset + row]);
            fail_case("not bit-exact against two original dot4 calls", n, tag, row);
        }
        if (std::memcmp(scalar + row, guarded + offset + row, sizeof(float))) {
            std::fprintf(stderr, "oracle=%a reference=%a dot8=%a\n", scalar[row], expected[row], guarded[offset + row]);
            fail_case("independent integer/FMA oracle mismatch", n, tag, row);
        }
        if (!std::isfinite(guarded[offset + row])) fail_case("nonfinite output", n, tag, row);
    }
    for (unsigned j = 0; j < 12; ++j)
        if ((j < offset || j >= offset + 8) &&
            std::memcmp(guarded + j, before_output + j * sizeof(float), sizeof(float)))
            fail_case("output canary (must write exactly 8 floats / 32 bytes)", n, tag);
    if (std::memcmp(before_weights.data(), f.weight_storage.data(), f.weight_storage.size() * sizeof(BonsaiPtqCache4)) ||
        std::memcmp(before_activations.data(), f.activation_storage.data(), f.activation_storage.size() * sizeof(block_q8_0)))
        fail_case("input or input canary modified", n, tag);
    ++comparisons;
}

static void exhaustive_byte_positions() {
    for (unsigned raw = 0; raw < 256; ++raw) {
        for (int position = 0; position < 32; ++position) {
            Fixture f(1);
            for (int group = 0; group < 2; ++group) {
                auto & w = f.weights()[group];
                for (int row = 0; row < 4; ++row) w.d[row] = uint16_t(0x3801 + group * 0x300 + row * 57);
                // Each physical payload byte takes every value, but neighbouring
                // positions/rows/groups are different to expose permutations.
                for (int byte = 0; byte < 128; ++byte)
                    w.qs[byte] = uint8_t(raw + 37 * byte + 101 * group);
            }
            for (int k = 0; k < 4; ++k) {
                f.activations()[k].d = uint16_t(0x3401 + k * 0x400);
                f.activations()[k].qs[position] = int8_t(k % 2 ? -128 : 127);
            }
            compare(f, raw * 32 + position);
        }
    }
    std::puts("PASS all 256 bytecodes at every cache byte; position-dependent rows and all 32 one-hot activation positions");
}

static void one_hot_weights() {
    for (int row = 0; row < 8; ++row) {
        for (int position = 0; position < 128; ++position) {
            for (int code : {0,2,3}) {
                Fixture f(1);
                for (int group = 0; group < 2; ++group) {
                    for (auto & scale : f.weights()[group].d) scale = 0x3c00;
                    std::memset(f.weights()[group].qs, 0x55, 128); // all weights 0
                }
                const int k = position / 32, j = position % 32;
                const int byte = BONSAI_PTQ_PLANAR ? j % 8 : j / 4;
                const int shift = 2 * (BONSAI_PTQ_PLANAR ? j / 8 : j % 4);
                auto & packed = f.weights()[row / 4].qs[k * 32 + (row % 4) * 8 + byte];
                packed = uint8_t((packed & ~(3 << shift)) | (code << shift));
                for (int q = 0; q < 4; ++q) {
                    f.activations()[q].d = uint16_t(0x3001 + 333 * q);
                    for (int p = 0; p < 32; ++p)
                        f.activations()[q].qs[p] = int8_t(p % 3 == 0 ? -128 : p % 3 == 1 ? 127 : 3 * p - 47);
                }
                compare(f, unsigned((row * 128 + position) * 4 + code));
            }
        }
    }
    std::puts("PASS isolated weights at all 8 x 128 positions, including reserved two-bit code 3");
}

static void scales_extremes_long_rows() {
    const uint16_t scales[] = {0x0000,0x8000,0x0001,0x8001,0x03ff,0x83ff,0x0400,0x8400,
                              0x2e66,0xae66,0x3555,0xb555,0x3c01,0xbc01,0x4901,0xc901,0x7bff,0xfbff};
    const unsigned count = sizeof(scales) / sizeof(scales[0]);
    std::mt19937 rng(0x444f5438);
    for (int blocks : {0,1,2,3,17,129,513,1025}) {
        for (unsigned phase = 0; phase < count; ++phase) {
            for (int mode = 0; mode < 5; ++mode) {
                Fixture f(blocks);
                for (int b = 0; b < blocks; ++b) {
                    for (int group = 0; group < 2; ++group) {
                        auto & w = f.weights()[group * blocks + b];
                        for (int row = 0; row < 4; ++row) w.d[row] = scales[(phase + b + row + group * 7) % count];
                        for (auto & byte : w.qs) byte = uint8_t(rng());
                    }
                    for (int k = 0; k < 4; ++k) {
                        auto & q = f.activations()[b * 4 + k];
                        q.d = scales[(phase + 5 * b + 3 * k) % count];
                        for (int j = 0; j < 32; ++j)
                            q.qs[j] = int8_t(mode == 0 ? -128 : mode == 1 ? 127 :
                                            mode == 2 ? (j % 2 ? -128 : 127) :
                                            mode == 3 ? int(rng() % 256) - 128 : 0);
                    }
                }
                compare(f, phase * 5 + mode);
            }
        }
    }
    std::puts("PASS irregular finite FP16 (+/-0, subnormal, max), -128/127, random/cancellation/zero, n=0 and long rows");
}

static void zero_without_inputs() {
    float guarded[10];
    std::memset(guarded, 0xda, sizeof(guarded));
    unsigned char before[sizeof(guarded)];
    std::memcpy(before, guarded, sizeof(guarded));
    bonsai_ptq_recode_dot8(0, guarded + 1, nullptr, nullptr);
    const float zero[8] = {};
    if (std::memcmp(guarded + 1, zero, sizeof(zero))) fail("n=0 must write eight positive zeros");
    if (std::memcmp(guarded, before, sizeof(float)) ||
        std::memcmp(guarded + 9, before + 9 * sizeof(float), sizeof(float))) fail("n=0 output canary");
    ++comparisons;
    std::puts("PASS n=0 with null inputs writes exactly eight +0 floats");
}

int main() {
    __builtin_cpu_init();
    if (!(__builtin_cpu_supports("avx2") && __builtin_cpu_supports("avx512f") &&
          __builtin_cpu_supports("avx512bw") && __builtin_cpu_supports("avx512dq") &&
          __builtin_cpu_supports("avx512vl") && __builtin_cpu_supports("avx512vnni") &&
          __builtin_cpu_supports("fma") && __builtin_cpu_supports("f16c"))) {
        std::puts("SKIP: AVX512-VNNI/FMA/F16C unavailable");
        return 77;
    }
    const char * reference = bonsai_ptq_recode_kernel_name();
    if (std::strcmp(reference, BONSAI_PTQ_PLANAR ? "avx512-vnni-planar" : "avx512-vnni"))
        fail("reference is not original VNNI (disable VBMI and butterfly)");
    BonsaiPtqCache4 weights[2]{};
    for (int group = 0; group < 2; ++group) {
        for (int row = 0; row < 4; ++row) weights[group].d[row] = 0x3c00 + group * 0x400 + row * 17;
        std::memset(weights[group].qs, group ? 0x00 : 0xaa, sizeof(weights[group].qs));
    }
    struct { block_q8_0 q[4]; int16_t sums[4]; } activations{};
    static_assert(sizeof(activations) == 4 * sizeof(block_q8_0) + 4 * sizeof(int16_t), "unchanged Q8 + sums");
    for (int k = 0; k < 4; ++k) {
        activations.q[k].d = 0x3c00;
        std::memset(activations.q[k].qs, 127, 32);
        activations.sums[k] = 32 * 127;
    }
    float expected[8], guarded[10] = {123.25f, 0, 0, 0, 0, 0, 0, 0, 0, -456.5f};
    for (int group = 0; group < 2; ++group)
        if (!bonsai_ptq_recode_dot4(128, expected + group * 4, weights + group, &activations))
            fail("reference rejected valid n");
    bonsai_ptq_recode_dot8(128, guarded + 1, weights, &activations);
    if (std::memcmp(expected, guarded + 1, sizeof(expected))) fail("basic two-group bit-exact comparison");
    if (guarded[0] != 123.25f || guarded[9] != -456.5f) fail("output canary");
    std::printf("PASS basic dot8 bit-exact layout=%d reference=%s\n", BONSAI_PTQ_PLANAR, reference);
    exhaustive_byte_positions();
    one_hot_weights();
    scales_extremes_long_rows();
    zero_without_inputs();
    std::printf("PASS %u additional cases layout=%d (two dot4 + independent integer/FMA oracle; separate null-input n=0 case; no tolerance)\n",
                comparisons, BONSAI_PTQ_PLANAR);
}
