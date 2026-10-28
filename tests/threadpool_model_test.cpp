#include "cpu_threadpool.h"
#include "ggml-backend.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

using Model = std::unique_ptr<llama_model, decltype(&llama_model_free)>;
using Context = std::unique_ptr<llama_context, decltype(&llama_free)>;
static void decode(llama_context * context, llama_token * tokens, int count) {
    if (llama_decode(context, llama_batch_get_one(tokens, count))) throw std::runtime_error("Decode failed");
}
static double compare(llama_context * context, const std::vector<float> & expected) {
    const float * actual = llama_get_logits_ith(context, -1);
    if (!actual) throw std::runtime_error("Missing logits");
    double maximum = 0;
    int best_a = 0, best_b = 0;
    for (size_t i = 0; i < expected.size(); ++i) {
        if (!std::isfinite(actual[i])) throw std::runtime_error("Nonfinite logits");
        maximum = std::max(maximum, double(std::abs(actual[i] - expected[i])));
        if (actual[i] > actual[best_a]) best_a = static_cast<int>(i);
        if (expected[i] > expected[best_b]) best_b = static_cast<int>(i);
        if (std::abs(actual[i] - expected[i]) > 0.002 + 0.0002 * std::abs(expected[i]))
            throw std::runtime_error("Resized pool changed model logits");
    }
    if (best_a != best_b) throw std::runtime_error("Resized pool changed top token");
    return maximum;
}
int main(int argc, char ** argv) {
    try {
        if (argc != 2) throw std::runtime_error("Usage: bonsai-threadpool-model-test model.gguf");
        ggml_backend_load_all();
        llama_backend_init();
        ggml_backend_dev_t devices[] = {nullptr};
        auto mp = llama_model_default_params();
        mp.devices = devices; mp.n_gpu_layers = 0; mp.use_extra_bufts = false;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        Model model(llama_model_load_from_file(argv[1], mp), llama_model_free);
        if (!model) throw std::runtime_error("Model load failed");
        auto cp = llama_context_default_params();
        cp.n_ctx = 1024; cp.n_batch = cp.n_ubatch = 128; cp.n_seq_max = 1;
        cp.n_threads = cp.n_threads_batch = 4;
        cp.type_k = cp.type_v = GGML_TYPE_F16; cp.offload_kqv = cp.op_offload = false;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        Context reference(llama_init_from_model(model.get(), cp), llama_free);
        if (!reference) throw std::runtime_error("Reference context failed");
        BonsaiCpuThreadpool pool;
        bool abort = false;
        cp.abort_callback = [](void * value) { return *static_cast<bool *>(value); };
        cp.abort_callback_data = &abort;
        Context candidate(llama_init_from_model(model.get(), cp), llama_free);
        if (!candidate) throw std::runtime_error("Candidate context failed");
        pool.attach(candidate.get(), 4, 4);
        std::string text;
        for (int i = 0; i < 8; ++i) text += "Ein Wald speichert Wasser im Boden. Baeume nutzen Sonnenlicht. ";
        const auto * vocab = llama_model_get_vocab(model.get());
        const int vocab_size = llama_vocab_n_tokens(vocab);
        int count = llama_tokenize(vocab, text.data(), static_cast<int>(text.size()), nullptr, 0, true, true);
        if (count >= 0) throw std::runtime_error("Token sizing failed");
        std::vector<llama_token> tokens(-count);
        count = llama_tokenize(vocab, text.data(), static_cast<int>(text.size()), tokens.data(), static_cast<int>(tokens.size()), true, true);
        if (count < 64) throw std::runtime_error("Insufficient prompt");
        tokens.resize(64);
        decode(reference.get(), tokens.data(), 64);
        decode(candidate.get(), tokens.data(), 64);
        const std::array<std::pair<int,int>, 8> schedules = {{{1,4}, {4,1}, {2,8}, {8,2}, {1,1}, {4,4}, {2,2}, {4,8}}};
        double maximum_error = 0;
        std::vector<float> first_logits;
        for (size_t step = 0; step < schedules.size(); ++step) {
            auto token = tokens[(step + 7) % tokens.size()];
            decode(reference.get(), &token, 1);
            const float * logits = llama_get_logits_ith(reference.get(), -1);
            if (!logits) throw std::runtime_error("Missing reference logits");
            std::vector<float> expected(logits, logits + vocab_size);
            if (step == 0) first_logits = expected;
            const auto position = llama_memory_seq_pos_max(llama_get_memory(candidate.get()), 0);
            pool.attach(candidate.get(), schedules[step].first, schedules[step].second);
            if (position != llama_memory_seq_pos_max(llama_get_memory(candidate.get()), 0))
                throw std::runtime_error("Pool resize discarded KV positions");
            if (llama_n_threads(candidate.get()) != schedules[step].first ||
                llama_n_threads_batch(candidate.get()) != schedules[step].second)
                throw std::runtime_error("Requested thread counts not applied");
            BonsaiCpuIdle idle(pool);
            decode(candidate.get(), &token, 1);
            maximum_error = std::max(maximum_error, compare(candidate.get(), expected));
        }
        // An aborted graph must not poison reused workers or the next request.
        pool.attach(candidate.get(), 4, 4);
        abort = true;
        auto token = tokens[7];
        const int status = llama_decode(candidate.get(), llama_batch_get_one(&token, 1));
        abort = false;
        pool.pause();
        if (status == 0) throw std::runtime_error("Abort callback did not stop graph");
        llama_memory_clear(llama_get_memory(candidate.get()), true);
        decode(candidate.get(), tokens.data(), 64);
        decode(candidate.get(), &token, 1);
        maximum_error = std::max(maximum_error, compare(candidate.get(), first_logits));
        pool.pause();
        std::cout << "Real-model persistent pool resize/pause/abort/recovery passed; max_logit_error=" << maximum_error << '\n';
    } catch (const std::exception & error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
