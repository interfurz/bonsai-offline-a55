#include "llama.h"
#include "ggml-backend.h"
#include "cpu_threadpool.h"
#include "ptq_recode.h"
#if BONSAI_RECODE_PROFILE
#include "ptq_recode_profile.h"
#endif
#include "nlohmann/json.hpp"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

// Fixed-token replay: no EOS, answer length, sampling or validation work in the
// decode timer. Host results must not be presented as Galaxy A55 measurements.
using Clock = std::chrono::steady_clock;
using Json = nlohmann::ordered_json;
static double milliseconds(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
static double median(std::vector<double> values) {
    std::sort(values.begin(), values.end());
    const size_t middle = values.size() / 2;
    return values.size() % 2 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
}
int main(int argc, char ** argv) {
    try {
        if (argc < 2) throw std::runtime_error(
            "Usage: bonsai-decode-bench model.gguf [threads=4] [steps=32] [trials=3] [prompt_tokens=192] [output.json] [logits.f32] [persistent_pool=0|1] [profile=0|1]");
        const int threads = argc > 2 ? std::stoi(argv[2]) : 4;
        const int steps = argc > 3 ? std::stoi(argv[3]) : 32;
        const int trials = argc > 4 ? std::stoi(argv[4]) : 3;
        const int prompt_count = argc > 5 ? std::stoi(argv[5]) : 192;
        const bool persistent_pool = argc > 8 && std::string(argv[8]) == "1";
        const bool profile_run = argc > 9 && std::string(argv[9]) == "1";
        if ((argc > 8 && std::string(argv[8]) != "0" && std::string(argv[8]) != "1") ||
            (argc > 9 && std::string(argv[9]) != "0" && std::string(argv[9]) != "1") ||
            argc > 10 || threads < 1 || threads > 16 || steps < 4 || steps > 128 ||
            trials < 1 || trials > 20 || prompt_count < 8 || prompt_count + steps > 4096)
            throw std::runtime_error("Invalid benchmark parameters");
        if (profile_run && !BONSAI_RECODE_PROFILE)
            throw std::runtime_error("Profiling was not compiled; enable BONSAI_RECODE_PROFILE");
        #if BONSAI_RECODE_PROFILE
        bonsai_ptq_profile::reset();
        #endif
        ggml_backend_load_all();
        llama_backend_init();
        if (BONSAI_PTQ_RECODE) bonsai_ptq_recode_register();
        ggml_backend_dev_t devices[] = {nullptr};
        auto mp = llama_model_default_params();
        mp.devices = devices;
        mp.n_gpu_layers = 0;
        mp.use_extra_bufts = true;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        auto load_start = Clock::now();
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(argv[1], mp), llama_model_free);
        if (!model) throw std::runtime_error("Model load failed");
        const double load_ms = milliseconds(load_start);
        auto cp = llama_context_default_params();
        cp.n_ctx = 4096;
        cp.n_batch = cp.n_ubatch = 512;
        cp.n_seq_max = 1;
        cp.n_threads = cp.n_threads_batch = threads;
        cp.type_k = cp.type_v = GGML_TYPE_F16;
        cp.offload_kqv = cp.op_offload = false;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        cp.no_perf = false;
        BonsaiCpuThreadpool cpu_pool;
        std::unique_ptr<llama_context, decltype(&llama_free)> context(
            llama_init_from_model(model.get(), cp), llama_free);
        if (!context) throw std::runtime_error("Context allocation failed");
        if (persistent_pool) cpu_pool.attach(context.get(), threads, threads);
        const auto * vocab = llama_model_get_vocab(model.get());
        const int vocab_size = llama_vocab_n_tokens(vocab);
        std::string text;
        for (int i = 0; i < prompt_count; ++i)
            text += "Ein Wald speichert Wasser im Boden. Baeume nutzen Sonnenlicht und bilden neue Blaetter. ";
        int count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
        if (count >= 0) throw std::runtime_error("Token sizing failed");
        std::vector<llama_token> tokens(static_cast<size_t>(-count));
        count = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                               static_cast<int32_t>(tokens.size()), true, true);
        if (count < prompt_count) throw std::runtime_error("Insufficient prompt tokens");
        tokens.resize(prompt_count);
        std::ofstream logits_file;
        if (argc > 7) {
            logits_file.open(argv[7], std::ios::binary);
            if (!logits_file) throw std::runtime_error("Cannot create logits file");
        }
        Json runs = Json::array();
        std::vector<double> rates;
        uint64_t profile_calls = 0;
        for (int trial = -1; trial < trials; ++trial) {
            BonsaiCpuIdle idle(cpu_pool);
            llama_memory_clear(llama_get_memory(context.get()), true);
            auto start = Clock::now();
            for (size_t pos = 0; pos < tokens.size(); pos += 512) {
                auto batch = llama_batch_get_one(tokens.data() + pos,
                    static_cast<int32_t>(std::min<size_t>(512, tokens.size() - pos)));
                if (llama_decode(context.get(), batch)) throw std::runtime_error("Prefill failed");
            }
            const double prefill_ms = milliseconds(start);
            std::vector<double> latency;
            Json top_tokens = Json::array(), signature = Json::array();
            const int replay_steps = trial < 0 ? 4 : steps;
            #if BONSAI_RECODE_PROFILE
            bonsai_ptq_profile::enable(profile_run && trial >= 0);
            #endif
            const auto calls_before = bonsai_ptq_recode_graph_calls();
            for (int step = 0; step < replay_steps; ++step) {
                auto token = tokens[(step + 7) % tokens.size()];
                auto batch = llama_batch_get_one(&token, 1);
                start = Clock::now();
                if (llama_decode(context.get(), batch)) throw std::runtime_error("Decode failed");
                llama_synchronize(context.get());
                latency.push_back(milliseconds(start));
                const float * logits = llama_get_logits_ith(context.get(), -1);
                if (!logits) throw std::runtime_error("Missing logits");
                int best = 0;
                for (int j = 0; j < vocab_size; ++j) {
                    if (!std::isfinite(logits[j])) throw std::runtime_error("Nonfinite logits");
                    if (logits[j] > logits[best]) best = j;
                }
                top_tokens.push_back(best);
                for (int j = 0; j < 64; ++j) signature.push_back(logits[int64_t(j) * vocab_size / 64]);
                if (trial == 0 && logits_file.is_open())
                    logits_file.write(reinterpret_cast<const char *>(logits), sizeof(float) * vocab_size);
            }
            #if BONSAI_RECODE_PROFILE
            bonsai_ptq_profile::enable(false);
            #endif
            if (profile_run && trial >= 0)
                profile_calls += bonsai_ptq_recode_graph_calls()-calls_before;
            if (trial < 0) continue;
            double total_ms = 0;
            for (double ms : latency) total_ms += ms;
            const double rate = steps * 1000.0 / total_ms;
            rates.push_back(rate);
            runs.push_back({{"prefill_ms", prefill_ms}, {"decode_ms", total_ms},
                {"tokens_per_second", rate}, {"latency_ms", latency},
                {"top_tokens", top_tokens}, {"signature", signature}});
        }
        if (logits_file.is_open()) {
            logits_file.flush();
            if (!logits_file) throw std::runtime_error("Logits write failed");
        }
        Json profile_entries = Json::array();
        #if BONSAI_RECODE_PROFILE
        for (const auto & entry : bonsai_ptq_profile::snapshot()) {
            profile_entries.push_back({{"name",entry.name},{"width",entry.width},{"rows",entry.rows},
                {"columns",entry.columns},{"threads",entry.threads},{"thread_samples",entry.thread_samples},
                {"quant_ns",entry.quant_ns},{"barrier_ns",entry.barrier_ns},{"dot_ns",entry.dot_ns}});
        }
        #endif
        Json report = {{"schema", 1}, {"benchmark", "bonsai-fixed-decode-v1"},
            {"system_info", llama_print_system_info()}, {"model_path", argv[1]},
            {"model_bytes", llama_model_size(model.get())}, {"model_parameters", llama_model_n_params(model.get())},
            {"context", 4096}, {"threads", threads}, {"batch_threads", threads},
            {"micro_batch", 512}, {"flash_attention", false}, {"kv_type", "f16"},
            {"repack", true}, {"app_cpu_wrappers", bool(BONSAI_FAST_TERNARY)},
            {"persistent_cpu_pool", persistent_pool},
            {"cpu_pool_poll", persistent_pool ? Json(0) : Json(nullptr)},
            {"ptq_recode",bool(BONSAI_PTQ_RECODE)}, {"ptq_recode_graph_calls",bonsai_ptq_recode_graph_calls()},
            {"ptq_planar",bool(BONSAI_PTQ_PLANAR)},
            {"ptq_recode_kernel",BONSAI_PTQ_RECODE ? bonsai_ptq_recode_kernel_name() : "disabled"},
            {"ptq_recode_column_tile",bool(BONSAI_RECODE_COLUMN_TILE)},
            {"ptq_recode_butterfly",bool(BONSAI_RECODE_BUTTERFLY)},
            {"ptq_recode_dot8",bool(BONSAI_RECODE_DOT8)},{"ptq_recode_dot8_batches",bonsai_ptq_recode_dot8_batches()},
            {"ptq_recode_avx2_only",bool(BONSAI_RECODE_AVX2_ONLY)},
            {"ptq_profile_compiled",bool(BONSAI_RECODE_PROFILE)},{"ptq_profile_enabled",profile_run},
            {"ptq_profile",{{"measurement","summed-worker-nanoseconds; not wall-time shares"},
                {"decode_only",true},{"graph_calls",profile_calls},{"entries",profile_entries}}},
            {"prompt_tokens", tokens}, {"decode_tokens", steps},
            {"vocab_size", vocab_size}, {"warmup_steps", 4}, {"load_ms", load_ms},
            {"median_tokens_per_second", median(rates)}, {"runs", runs}};
        if (argc > 6) {
            std::ofstream output(argv[6]);
            if (!output || !(output << report.dump(2) << '\n')) throw std::runtime_error("Report write failed");
        }
        std::cout << report.dump(2) << '\n';
    } catch (const std::exception & error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
