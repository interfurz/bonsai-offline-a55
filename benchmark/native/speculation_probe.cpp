// Prerequisites probe only: fixed-token target verification, NOT a speculative
// sampler. No draft model, acceptance distribution, production change or promise
// of end-to-end speedup. Host measurements are not Galaxy A55 measurements.
// API basis: llama.h and tests/test-recurrent-state-rollback.cpp in this tree.
#include "llama.h"
#include "ggml-backend.h"
#include "nlohmann/json.hpp"
#ifndef BONSAI_PTQ_RECODE
#define BONSAI_PTQ_RECODE 0
#endif
#ifndef BONSAI_FAST_TERNARY
#define BONSAI_FAST_TERNARY 0
#endif
#ifndef BONSAI_RECODE_COLUMN_TILE
#define BONSAI_RECODE_COLUMN_TILE 0
#endif
#ifndef BONSAI_PTQ_PLANAR
#define BONSAI_PTQ_PLANAR 0
#endif
#if BONSAI_PTQ_RECODE
#include "ptq_recode.h"
#endif
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <filesystem>
#include <iostream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
using Json = nlohmann::ordered_json;
using Clock = std::chrono::steady_clock;
using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
using Logits = std::vector<std::vector<float>>;
constexpr int prefix_size = 64;
constexpr int snapshots = 8;
constexpr int context_size = 4096;
constexpr int batch_size = 512;
constexpr int continuation_size = 4;
constexpr double abs_tol = 0.002;
constexpr double rel_tol = 0.0002;
// Identical graphs/states should match much more closely than serial vs GEMM.
// This is stricter, never a replacement/widening of the gate above.
constexpr double rollback_abs_tol = 1e-5;
constexpr std::array<int, 4> widths = {{1, 2, 4, 8}};

void require(bool condition, const std::string & message) {
    if (!condition) throw std::runtime_error(message);
}
double elapsed_ms(Clock::time_point start) {
    const double ms = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
    require(std::isfinite(ms) && ms >= 0, "Invalid elapsed time");
    return ms;
}
double median(std::vector<double> values) {
    require(!values.empty(), "Empty timing sample");
    std::sort(values.begin(), values.end());
    const size_t m = values.size() / 2;
    return values.size() % 2 ? values[m] : (values[m - 1] + values[m]) / 2;
}
int integer(const char * text, int low, int high, const char * name) {
    const std::string value(text);
    require(!value.empty() && value.find_first_not_of("0123456789") == std::string::npos,
            std::string("Invalid integer: ") + name);
    size_t used = 0;
    const long parsed = std::stol(value, &used);
    require(used == value.size() && parsed >= low && parsed <= high,
            std::string("Out of range: ") + name);
    return static_cast<int>(parsed);
}
struct Backend {
    Backend() { ggml_backend_load_all(); llama_backend_init(); }
    ~Backend() { llama_backend_free(); }
    Backend(const Backend &) = delete;
    Backend & operator=(const Backend &) = delete;
};
struct Batch {
    llama_batch value;
    explicit Batch(int capacity = batch_size) : value(llama_batch_init(capacity, 0, 1)) {
        // llama_batch_init owns allocations; destructor also runs after later errors.
        // All per-token fields are set by fill(), not common_batch_add().
        if (!value.token || !value.pos || !value.n_seq_id || !value.seq_id || !value.logits) {
            llama_batch_free(value);
            throw std::runtime_error("Batch allocation failed");
        }
        value.n_tokens = 0;
    }
    ~Batch() { llama_batch_free(value); }
    Batch(const Batch &) = delete;
    Batch & operator=(const Batch &) = delete;
    void fill(const std::vector<llama_token> & tokens, int offset, int count,
              int position, bool all_logits) {
        require(count > 0 && count <= batch_size && offset >= 0 &&
                static_cast<size_t>(offset + count) <= tokens.size(), "Invalid batch span");
        require(position >= 0 && position + count <= context_size, "Invalid batch positions");
        value.n_tokens = count;
        for (int i = 0; i < count; ++i) {
            require(value.seq_id[i] != nullptr, "Batch sequence allocation failed");
            value.token[i] = tokens[offset + i];
            value.pos[i] = position + i;
            value.n_seq_id[i] = 1;
            value.seq_id[i][0] = 0;
            value.logits[i] = all_logits || i + 1 == count;
        }
    }
};
void decode(llama_context * ctx, Batch & batch) {
    const int code = llama_decode(ctx, batch.value);
    require(code == 0, "llama_decode returned " + std::to_string(code));
    llama_synchronize(ctx);
}
Context make_context(llama_model * model, int threads) {
    auto cp = llama_context_default_params();
    cp.n_ctx = context_size;
    cp.n_batch = cp.n_ubatch = batch_size;
    cp.n_seq_max = 1;
    cp.n_rs_seq = snapshots;
    cp.n_outputs_max = cp.n_outputs_max_per_seq = batch_size;
    cp.n_threads = cp.n_threads_batch = threads;
    cp.type_k = cp.type_v = GGML_TYPE_F16;
    cp.offload_kqv = cp.op_offload = false;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cp.no_perf = true;
    Context ctx(llama_init_from_model(model, cp), llama_free);
    require(bool(ctx), "Context allocation failed");
    require(llama_n_rs_seq(ctx.get()) == snapshots, "Runtime did not enable n_rs_seq=8");
    require(llama_n_ctx(ctx.get()) == context_size && llama_n_ubatch(ctx.get()) == batch_size &&
            llama_n_batch(ctx.get()) == batch_size && llama_n_seq_max(ctx.get()) == 1,
            "Runtime context dimensions differ from requested probe configuration");
    require(llama_get_memory(ctx.get()) != nullptr, "Context has no memory");
    return ctx;
}
std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    require(text.size() < static_cast<size_t>(std::numeric_limits<int32_t>::max()), "Text too long");
    const int count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    require(count < 0 && count != std::numeric_limits<int32_t>::min(), "Token sizing failed");
    std::vector<llama_token> tokens(static_cast<size_t>(-count));
    const int actual = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                     tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    require(actual > 0 && actual <= -count, "Tokenization failed");
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}
void prefill(llama_context * ctx, Batch & batch, const std::vector<llama_token> & prompt) {
    require(prompt.size() == prefix_size, "Invalid prefix length");
    llama_memory_clear(llama_get_memory(ctx), true);
    batch.fill(prompt, 0, prefix_size, 0, false);
    decode(ctx, batch);
}
std::vector<float> copy_row(llama_context * ctx, int row, int vocab_size) {
    const float * logits = llama_get_logits_ith(ctx, row);
    require(logits != nullptr, "Missing requested logits row " + std::to_string(row));
    for (int i = 0; i < vocab_size; ++i) require(std::isfinite(logits[i]), "Nonfinite model logits");
    return std::vector<float>(logits, logits + vocab_size);
}
Logits replay(llama_context * ctx, Batch & batch, const std::vector<llama_token> & tokens,
              int width, int position, int vocab_size) {
    require(!tokens.empty() && tokens.size() % width == 0, "Replay is not divisible by width");
    Logits result;
    result.reserve(tokens.size());
    for (int offset = 0; offset < static_cast<int>(tokens.size()); offset += width) {
        batch.fill(tokens, offset, width, position + offset, true);
        decode(ctx, batch);
        for (int i = 0; i < width; ++i) result.push_back(copy_row(ctx, i, vocab_size));
    }
    return result;
}
struct Errors {
    uint64_t elements = 0, rows = 0, outside = 0, strict_outside = 0, top_mismatches = 0;
    double max_abs = 0, max_rel = 0, sum_squared = 0, max_top_abs = 0;
    Json first_outside = nullptr;
    Json tops = Json::array();
    void add(const Logits & reference, const Logits & actual, int first_position) {
        require(reference.size() == actual.size() && !reference.empty(), "Logit row count mismatch");
        for (size_t r = 0; r < reference.size(); ++r) {
            const auto & ref = reference[r];
            const auto & got = actual[r];
            require(ref.size() == got.size() && !ref.empty(), "Vocabulary size mismatch");
            size_t ref_top = 0, got_top = 0;
            for (size_t t = 0; t < ref.size(); ++t) {
                const double a = ref[t], b = got[t];
                require(std::isfinite(a) && std::isfinite(b), "Nonfinite comparison logits");
                const double delta = std::abs(a - b);
                max_abs = std::max(max_abs, delta);
                max_rel = std::max(max_rel, delta / std::max(std::abs(a), 1e-12));
                sum_squared += delta * delta;
                ++elements;
                if (delta > rollback_abs_tol) ++strict_outside;
                if (delta > std::max(abs_tol, rel_tol * std::max(std::abs(a), std::abs(b)))) {
                    ++outside;
                    if (first_outside.is_null()) first_outside = {
                        {"position", first_position + static_cast<int>(r)}, {"vocab_token", t},
                        {"reference", a}, {"actual", b}, {"abs_error", delta}};
                }
                if (a > ref[ref_top]) ref_top = t;
                if (b > got[got_top]) got_top = t;
            }
            // Evaluate both selected-token coordinates, not only max(logits).
            const double top_error = std::max(std::abs(double(ref[ref_top]) - got[ref_top]),
                                              std::abs(double(ref[got_top]) - got[got_top]));
            max_top_abs = std::max(max_top_abs, top_error);
            if (ref_top != got_top) ++top_mismatches;
            ++rows;
            tops.push_back({{"position", first_position + static_cast<int>(r)},
                {"reference_top_token", ref_top}, {"actual_top_token", got_top},
                {"reference_top_logit", ref[ref_top]}, {"actual_top_logit", got[got_top]},
                {"selected_coordinates_max_abs_error", top_error}});
        }
    }
    bool pass(bool strict = false) const {
        return elements > 0 && outside == 0 && top_mismatches == 0 && (!strict || strict_outside == 0);
    }
    Json json() const {
        require(elements > 0, "No logits compared");
        return {{"rows_compared", rows}, {"elements_compared", elements},
            {"full_max_abs_error", max_abs}, {"full_max_relative_error_floor_1e_12", max_rel},
            {"full_rmse", std::sqrt(sum_squared / double(elements))},
            {"elements_outside_tolerance", outside},
            {"elements_outside_strict_rollback_abs_tolerance", strict_outside},
            {"top_token_mismatches", top_mismatches}, {"top_coordinates_max_abs_error", max_top_abs},
            {"all_close", outside == 0}, {"strict_all_close", strict_outside == 0},
            {"top_tokens_equal", top_mismatches == 0}, {"pass", pass()},
            {"strict_pass", pass(true)}, {"first_outside_tolerance", first_outside}, {"top_rows", tops}};
    }
};
Json timed_replay(llama_context * ctx, Batch & batch, const std::vector<llama_token> & prompt,
                  const std::vector<llama_token> & tokens, int width) {
    prefill(ctx, batch, prompt);
    // Warm the same shape immediately before timing, without another prefill
    // replacing the last graph. Runtime graph updates remain included if any.
    for (int offset = 0; offset < snapshots; offset += width) {
        batch.fill(tokens, offset, width, prefix_size + offset, true);
        decode(ctx, batch);
    }
    #if BONSAI_PTQ_RECODE
    const auto calls_before = bonsai_ptq_recode_graph_calls();
    #endif
    std::vector<double> samples;
    samples.reserve(tokens.size() / width);
    double total = 0;
    for (int offset = 0; offset < static_cast<int>(tokens.size()); offset += width) {
        batch.fill(tokens, offset, width, prefix_size + snapshots + offset, true);
        const auto start = Clock::now();
        decode(ctx, batch); // Includes synchronization and all requested output logits.
        const double ms = elapsed_ms(start);
        require(ms > 0, "Zero decode time");
        total += ms;
        samples.push_back(ms);
    }
    const double rate = double(tokens.size()) * 1000 / total;
    require(std::isfinite(rate), "Nonfinite decode rate");
    Json result = {{"verify_width", width}, {"tokens", tokens.size()}, {"passes", samples.size()},
        {"timing_start_position", prefix_size + snapshots},
        {"decode_ms", total}, {"mean_pass_ms", total / samples.size()},
        {"median_pass_ms", median(samples)}, {"tokens_per_second", rate}, {"pass_ms", samples}};
    #if BONSAI_PTQ_RECODE
    const auto calls_delta = bonsai_ptq_recode_graph_calls() - calls_before;
    require(calls_delta > 0, "Recode build did not exercise recoded weights during timed replay");
    result["ptq_recode_graph_calls_delta"] = calls_delta;
    #endif
    return result;
}

// Reject k draft tokens from an (anchor + k drafts) verify batch. The runtime
// stores POST-token snapshots, not the input state before the batch: restoring
// the anchor requires k+1 written snapshots. Fresh reference sees the anchor
// but NEVER the discarded draft tokens; replay graph shapes are identical.
// The anchor is checked separately. Later mismatches still need diagnosis:
// internal serial-vs-multirow anchor states need not be bit-identical.
// Also serialize the pending rollback (rs_idx != 0) and load into a fresh ctx,
// following upstream's rollback/checkpoint/replay test, without common helpers.
Json rollback_case(llama_model * model, int threads, int width, int vocab_size,
                   const std::vector<llama_token> & prompt, const std::vector<llama_token> & fixed,
                   const std::vector<llama_token> & replacements) {
    auto rolled = make_context(model, threads);
    auto reference = make_context(model, threads);
    Batch batch;
    prefill(rolled.get(), batch, prompt);
    prefill(reference.get(), batch, prompt);
    const std::vector<llama_token> anchor(fixed.begin(), fixed.begin() + 1);
    const std::vector<llama_token> verify(fixed.begin(), fixed.begin() + width + 1);
    const std::vector<llama_token> discarded(fixed.begin() + 1, fixed.begin() + width + 1);
    const std::vector<llama_token> replay_tokens(replacements.begin(), replacements.begin() + width);
    const std::vector<llama_token> tail(fixed.begin() + snapshots + 1, fixed.begin() + snapshots + 1 + continuation_size);
    const int rollback_position = prefix_size + 1;
    batch.fill(anchor, 0, 1, prefix_size, true);
    decode(reference.get(), batch);
    const Logits anchor_reference = {copy_row(reference.get(), 0, vocab_size)};
    batch.fill(verify, 0, width + 1, prefix_size, true);
    decode(rolled.get(), batch);
    Errors anchor_errors;
    anchor_errors.add(anchor_reference, {copy_row(rolled.get(), 0, vocab_size)}, prefix_size);
    // Validate outputs as well as the state-changing decode return code.
    for (int i = 1; i <= width; ++i) (void) copy_row(rolled.get(), i, vocab_size);
    const auto start_rm = Clock::now();
    const bool removed = llama_memory_seq_rm(llama_get_memory(rolled.get()), 0, rollback_position, -1);
    const double remove_ms = elapsed_ms(start_rm);
    Json result = {{"verify_width", width}, {"rollback_depth", width}, {"accepted_in_batch", 0},
        {"verify_batch_tokens_including_anchor", width + 1}, {"accepted_anchor_tokens", 1},
        {"anchor_serial_vs_verify", anchor_errors.json()},
        {"reference_is_fresh_never_advanced_past_rollback", true},
        {"replay_and_continuation_graph_shapes_identical", true},
        {"discarded_tokens", discarded}, {"replacement_tokens", replay_tokens}, {"continuation_tokens", tail},
        {"seq_rm_returned_true", removed}, {"seq_rm_ms", remove_ms},
        {"seq_rm_ms_is_deferred_metadata_only", true}, {"pass", false}};
    if (!removed) {
        result["status"] = "rollback_refused";
        return result;
    }
    const auto pos = llama_memory_seq_pos_max(llama_get_memory(rolled.get()), 0);
    result["position_after_rollback"] = pos;
    if (pos != rollback_position - 1) {
        result["status"] = "rollback_position_mismatch";
        return result;
    }
    const auto start_save = Clock::now();
    const size_t size = llama_state_seq_get_size(rolled.get(), 0);
    require(size > 0, "Empty pending-rollback sequence snapshot");
    std::vector<uint8_t> state(size);
    require(llama_state_seq_get_data(rolled.get(), state.data(), state.size(), 0) == size,
            "Pending-rollback snapshot save failed");
    result["serialized_snapshot_bytes"] = size;
    result["snapshot_save_ms"] = elapsed_ms(start_save);
    const auto expected = replay(reference.get(), batch, replay_tokens, width, rollback_position, vocab_size);
    const auto expected_tail = replay(reference.get(), batch, tail, 1, rollback_position + width, vocab_size);
    const auto start_replay = Clock::now();
    const auto actual = replay(rolled.get(), batch, replay_tokens, width, rollback_position, vocab_size);
    result["rollback_replay_with_logit_copy_ms_not_decode_benchmark"] = elapsed_ms(start_replay);
    const auto actual_tail = replay(rolled.get(), batch, tail, 1, rollback_position + width, vocab_size);
    Errors direct;
    direct.add(expected, actual, rollback_position);
    direct.add(expected_tail, actual_tail, rollback_position + width);
    result["direct_rollback_replay_vs_fresh_reference"] = direct.json();
    reference.reset(); // At most two live contexts, apart from serialized host state.
    auto restored = make_context(model, threads);
    const auto start_load = Clock::now();
    const size_t loaded = llama_state_seq_set_data(restored.get(), state.data(), state.size(), 0);
    result["snapshot_load_ms"] = elapsed_ms(start_load);
    result["snapshot_load_consumed_exact_size"] = loaded == size;
    if (loaded != size) {
        result["status"] = "snapshot_load_failed";
        return result;
    }
    const auto restored_rows = replay(restored.get(), batch, replay_tokens, width, rollback_position, vocab_size);
    const auto restored_tail = replay(restored.get(), batch, tail, 1, rollback_position + width, vocab_size);
    Errors checkpoint;
    checkpoint.add(expected, restored_rows, rollback_position);
    checkpoint.add(expected_tail, restored_tail, rollback_position + width);
    result["serialized_pending_snapshot_replay_vs_fresh_reference"] = checkpoint.json();
    result["pass"] = anchor_errors.pass() && direct.pass(true) && checkpoint.pass(true);
    result["status"] = result["pass"].get<bool>() ? "passed" : "same_shape_rollback_or_checkpoint_mismatch";
    return result;
}
// JSON silently serializes NaN/Inf as null in some configurations; reject them
// explicitly, including metadata/timings added later, instead of hiding a fault.
void finite_json(const Json & value) {
    if (value.is_number_float()) require(std::isfinite(value.get<double>()), "Nonfinite JSON number");
    else if (value.is_structured()) for (const auto & child : value) finite_json(child);
}
void emit(const Json & report, const std::string & path) {
    finite_json(report);
    const std::string text = report.dump(2) + '\n';
    if (!path.empty()) {
        std::ofstream file(path, std::ios::binary | std::ios::trunc);
        require(bool(file), "Cannot open JSON output: " + path);
        file << text;
        file.flush();
        require(bool(file), "Cannot write JSON output: " + path);
    }
    std::cout << text;
    require(bool(std::cout), "Cannot write JSON to stdout");
}
} // namespace

int main(int argc, char ** argv) {
    Json report = {{"schema", 1}, {"benchmark", "bonsai-draftless-speculation-prerequisites-v1"},
        {"timing_protocol", "immediate-same-shape-warmup-v2"},
        {"status", "incomplete"}, {"prerequisites_probe_only", true},
        {"speculative_sampler_implemented", false}, {"end_to_end_speedup_claimed", false},
        {"sampling_distribution_equivalence_tested", false}, {"a55_measurement_claimed", false},
        {"numeric_gate_passed", false}, {"rollback_gate_passed", false}, {"prerequisites_passed", false}};
    std::string output;
    try {
        require(argc >= 2 && argc <= 7,
            "Usage: bonsai-speculation-probe model.gguf [threads=4] [steps=32, multiple of 8, 16..128] "
            "[trials=3, 1..10] [output.json|-] [extra_bufts=1, 0|1]");
        const int threads = argc > 2 ? integer(argv[2], 1, 16, "threads") : 4;
        const int steps = argc > 3 ? integer(argv[3], 16, 128, "steps") : 32;
        const int trials = argc > 4 ? integer(argv[4], 1, 10, "trials") : 3;
        require(steps % snapshots == 0, "steps must be divisible by 8");
        // Do not commit an output target until it is validated: the catch path
        // also emits JSON and must never truncate the model on an input error.
        const std::filesystem::path model_path(argv[1]);
        require(std::filesystem::is_regular_file(model_path), "Model path is not a regular file");
        std::string candidate_output;
        if (argc > 5 && std::string(argv[5]) != "-") candidate_output = argv[5];
        if (!candidate_output.empty()) {
            const std::filesystem::path target(candidate_output);
            const bool aliases_model = std::filesystem::exists(target) &&
                std::filesystem::equivalent(model_path, target);
            require(!aliases_model && std::filesystem::weakly_canonical(model_path) !=
                    std::filesystem::weakly_canonical(target), "Output must not overwrite model path");
        }
        const bool extra_bufts = argc > 6 ? integer(argv[6], 0, 1, "extra_bufts") != 0 : true;
        require(!BONSAI_PTQ_RECODE || extra_bufts, "PTQ recode build requires extra_bufts=1");
        output = candidate_output;
        report["model_path"] = argv[1];
        report["configuration"] = {{"device", "CPU"}, {"threads", threads}, {"batch_threads", threads},
            {"context", context_size}, {"batch", batch_size}, {"micro_batch", batch_size},
            {"n_seq_max", 1}, {"n_rs_seq_requested", snapshots}, {"prefix_tokens", prefix_size},
            {"steps", steps}, {"trials", trials}, {"kv_type", "f16"}, {"flash_attention", false},
            {"extra_bufts", extra_bufts}, {"ptq_recode", bool(BONSAI_PTQ_RECODE)},
            {"ptq_planar",bool(BONSAI_PTQ_PLANAR)},
            {"ptq_recode_column_tile",bool(BONSAI_RECODE_COLUMN_TILE)},
            {"ptq_recode_butterfly",bool(BONSAI_RECODE_BUTTERFLY)},
            {"ptq_recode_dot8",bool(BONSAI_RECODE_DOT8)},
            {"ptq_recode_avx2_only",bool(BONSAI_RECODE_AVX2_ONLY)},
            {"ptq_profile_compiled",bool(BONSAI_RECODE_PROFILE)},{"ptq_profile_enabled",false},
            {"app_cpu_wrappers", bool(BONSAI_FAST_TERNARY)}, {"persistent_cpu_pool", false}};
        report["tolerances"] = {{"abs", abs_tol}, {"rel", rel_tol},
            {"rule", "abs(actual-reference) <= max(abs_tol, rel_tol*max(abs(actual),abs(reference)))"},
            {"same_shape_rollback_abs", rollback_abs_tol}, {"require_same_top_token", true}};
        report["limitations"] = {
            "Fixed-token replay, no draft source, acceptance rate or stochastic rejection/resampling.",
            "No change to production penalties/top-k/top-p/temperature; matching argmax is not sampling equivalence.",
            "Logits row i predicts the token AFTER fixed input i; no speculative proposal alignment is implemented.",
            "Serial-vs-multirow numerical failures block prerequisites, not automatically a rollback defect.",
            "Rollback tests reject 1/2/4/8 draft tokens after an accepted anchor in each verify batch; partial draft acceptance is not tested.",
            "No n_rs_seq=0 timing baseline; snapshot allocation/update costs remain enabled in both paths.",
            "No multisequence, ubatch-split, context-shift, long-context or A55 validation.",
            "Sequence snapshot byte count is serialized payload, not total context allocation or process RSS.",
            "Verify timings exclude prefill, batch preparation, logit comparison, checkpoint and sampling overhead.",
            "Timing follows eight same-width warmup tokens without another prefill; runtime graph updates during measured passes remain included.",
            "Timing starts at position 72 after warmup; serial-vs-verify correctness separately starts at position 64.",
            "Fast verification alone is not an end-to-end speculative speedup; timings remain diagnostic if gates fail."};
        Backend backend;
#if BONSAI_PTQ_RECODE
        // Register BEFORE loading a model or first extra-buffer enumeration.
        bonsai_ptq_recode_register();
#endif
        ggml_backend_dev_t devices[] = {nullptr};
        auto mp = llama_model_default_params();
        mp.devices = devices;
        mp.n_gpu_layers = 0;
        mp.use_extra_bufts = extra_bufts;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        const auto start_load = Clock::now();
        Model model(llama_model_load_from_file(argv[1], mp), llama_model_free);
        require(bool(model), "Model load failed (no synthetic fallback)");
        report["load_ms"] = elapsed_ms(start_load);
        require(llama_model_is_hybrid(model.get()), "This probe requires a hybrid model; refusing to skip correctness");
        char arch[128] = {};
        const int arch_len = llama_model_meta_val_str(model.get(), "general.architecture", arch, sizeof(arch));
        require(arch_len > 0 && arch_len < static_cast<int>(sizeof(arch)), "Missing/oversized model architecture metadata");
        report["model_architecture"] = arch;
        report["model_is_hybrid"] = true;
        report["model_bytes"] = llama_model_size(model.get());
        report["model_parameters"] = llama_model_n_params(model.get());
        report["system_info"] = llama_print_system_info();
        const auto * vocab = llama_model_get_vocab(model.get());
        require(vocab != nullptr, "Model has no vocabulary");
        const int vocab_size = llama_vocab_n_tokens(vocab);
        require(vocab_size > 1, "Vocabulary too small");
        report["vocab_size"] = vocab_size;
        std::string text;
        for (int i = 0; i < 128; ++i)
            text += "Ein Wald speichert Wasser im Boden. Baeume nutzen Sonnenlicht und bilden neue Blaetter. ";
        const auto all = tokenize(vocab, text);
        require(all.size() >= static_cast<size_t>(prefix_size + steps), "Insufficient fixed tokens");
        for (auto token : all) require(token >= 0 && token < vocab_size, "Token outside vocabulary");
        const std::vector<llama_token> prompt(all.begin(), all.begin() + prefix_size);
        const std::vector<llama_token> fixed(all.begin() + prefix_size, all.begin() + prefix_size + steps);
        const auto alternate = tokenize(vocab, "Die andere Route fuehrt am Fluss entlang, ueber eine Bruecke bis zum Bahnhof.");
        std::vector<llama_token> replacements(snapshots);
        for (int i = 0; i < snapshots; ++i) {
            auto t = alternate[static_cast<size_t>(i) % alternate.size()];
            if (t == fixed[i+1]) t = static_cast<llama_token>((int64_t(t) + 1) % vocab_size);
            require(t >= 0 && t < vocab_size && t != fixed[i+1], "Invalid replacement token");
            replacements[i] = t;
        }
        report["prompt_token_ids"] = prompt;
        report["fixed_input_token_ids"] = fixed;
        bool numeric_pass = true;
        {
            auto ctx = make_context(model.get(), threads);
            report["configuration"]["n_rs_seq_actual"] = llama_n_rs_seq(ctx.get());
            Batch batch;
            prefill(ctx.get(), batch, prompt);
            const auto serial = replay(ctx.get(), batch, fixed, 1, prefix_size, vocab_size);
            report["serial_vs_verify_correctness"] = Json::array();
            for (int width : widths) {
                prefill(ctx.get(), batch, prompt);
                const auto actual = replay(ctx.get(), batch, fixed, width, prefix_size, vocab_size);
                Errors errors;
                errors.add(serial, actual, prefix_size);
                numeric_pass = numeric_pass && errors.pass();
                report["serial_vs_verify_correctness"].push_back({{"verify_width", width},
                    {"all_vocab_coordinates_compared", true}, {"errors", errors.json()}});
            }
            report["numeric_gate_passed"] = numeric_pass;
            // Separate performance phase: no full-vector scans/copies in timed passes.
            report["timing_warmup"] = {{"passes_per_width", "8/width"}, {"tokens_per_width", snapshots},
                {"each_width_warmed_before_each_timed_run", true}, {"samples_discarded", true},
                {"no_prefill_between_warmup_and_timing", true}, {"timing_start_position", prefix_size + snapshots}};
            report["timing_runs"] = Json::array();
            std::array<std::vector<double>, 4> totals;
            std::array<std::vector<double>, 4> rates;
            int ordinal = 0;
            for (int trial = 0; trial < trials; ++trial) {
                // Serial flanks each sweep. Alternate 2/4/8 and 8/4/2, retain
                // ALL samples rather than choosing a favorable ratio/run.
                const std::array<int, 5> order = trial % 2 == 0 ?
                    std::array<int, 5>{{1, 2, 4, 8, 1}} : std::array<int, 5>{{1, 8, 4, 2, 1}};
                for (int width : order) {
                    auto sample = timed_replay(ctx.get(), batch, prompt, fixed, width);
                    sample["trial"] = trial;
                    sample["ordinal"] = ordinal++;
                    sample["diagnostic_only_if_any_gate_fails"] = true;
                    const size_t index = static_cast<size_t>(std::find(widths.begin(), widths.end(), width) - widths.begin());
                    totals[index].push_back(sample["decode_ms"].get<double>());
                    rates[index].push_back(sample["tokens_per_second"].get<double>());
                    report["timing_runs"].push_back(std::move(sample));
                }
            }
            report["timing_summary"] = Json::array();
            for (size_t i = 0; i < widths.size(); ++i) {
                report["timing_summary"].push_back({{"verify_width", widths[i]}, {"samples", totals[i].size()},
                    {"median_decode_ms", median(totals[i])}, {"min_decode_ms", *std::min_element(totals[i].begin(), totals[i].end())},
                    {"max_decode_ms", *std::max_element(totals[i].begin(), totals[i].end())},
                    {"median_tokens_per_second", median(rates[i])}});
            }
        } // Free timing context before the paired rollback correctness tests.
        bool rollback_pass = true;
        report["rollback_tests"] = Json::array();
        for (int width : widths) {
            auto result = rollback_case(model.get(), threads, width, vocab_size, prompt, fixed, replacements);
            rollback_pass = rollback_pass && result["pass"].get<bool>();
            report["rollback_tests"].push_back(std::move(result));
        }
        report["rollback_gate_passed"] = rollback_pass;
        report["prerequisites_passed"] = numeric_pass && rollback_pass;
        report["status"] = numeric_pass && rollback_pass ? "passed_prerequisites_only" : "failed_prerequisites";
        report["failure_interpretation"] = !rollback_pass ?
            "Same-shape rollback/checkpoint path failed; inspect rollback_tests independently of multirow drift." :
            (!numeric_pass ? "Rollback matched, but serial-vs-verify numerics/top tokens failed. Do not accept speculation prerequisites." :
                             "Only tested fixed-token prerequisites passed; no sampler correctness or speedup established.");
#if BONSAI_PTQ_RECODE
        report["ptq_recode_graph_calls"] = bonsai_ptq_recode_graph_calls();
        report["ptq_recode_kernel"] = bonsai_ptq_recode_kernel_name();
#endif
        emit(report, output);
        return numeric_pass && rollback_pass ? 0 : 2;
    } catch (const std::exception & error) {
        report["status"] = "error";
        report["prerequisites_passed"] = false;
        report["error"] = error.what();
        std::cerr << "speculation_probe: " << error.what() << '\n';
        try { emit(report, output); }
        catch (const std::exception & output_error) {
            std::cerr << "speculation_probe: report output failed: " << output_error.what() << '\n';
            try { emit(report, ""); } catch (...) {}
        }
        return 1;
    }
}
