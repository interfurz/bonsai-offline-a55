#include "llama.h"
#include "ggml-backend.h"
#include "chat.h"
#include "reference_cpu.h"
#include "chat_sampler.h"
#include <algorithm>
#include <iostream>
#include <stdexcept>
#include <vector>

// Standalone real-model check, also runnable as a static ARM64 binary under QEMU.
int main(int argc, char ** argv) {
    try {
        if (argc < 3 || argc > 5 || (std::string(argv[2])!="scalar" && std::string(argv[2])!="optimized"))
            throw std::runtime_error("Usage: bonsai-model-test model.gguf scalar|optimized [context=4096] [threads=4]");
        const int context_size=argc>3?std::stoi(argv[3]):4096;
        const int threads=argc>4?std::stoi(argv[4]):4;
        if(context_size<512 || context_size>8192 || threads<1 || threads>8)
            throw std::runtime_error("Invalid context or thread count");
        bonsai_reference_cpu = std::string(argv[2]) == "scalar";
        ggml_backend_load_all();
        llama_backend_init();
        ggml_backend_dev_t devices[] = {nullptr};
        auto mp = llama_model_default_params();
        mp.devices = devices; mp.n_gpu_layers = 0; mp.use_extra_bufts = false;
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
        auto * model = llama_model_load_from_file(argv[1], mp);
        if (!model) throw std::runtime_error("Model load failed");
        auto cp = llama_context_default_params();
        cp.n_ctx = context_size; cp.n_batch = 256; cp.n_ubatch = bonsai_reference_cpu ? 1 : 32;
        cp.n_seq_max = 1; cp.n_threads = threads; cp.n_threads_batch = threads;
        cp.type_k = cp.type_v = GGML_TYPE_F16; cp.offload_kqv = cp.op_offload = false;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        auto * context = llama_init_from_model(model, cp);
        if (!context) throw std::runtime_error("Context allocation failed");
        auto templates = common_chat_templates_init(model, "");
        common_chat_templates_inputs input;
        input.enable_thinking = false; input.use_jinja = true; input.add_generation_prompt = true;
        common_chat_msg system, user;
        system.role = "system"; system.content = "Du bist ein hilfreicher Assistent. Antworte auf Deutsch, sofern die Person keine andere Sprache wünscht.";
        user.role = "user"; user.content = "Was ist 2 + 2? Antworte nur mit der Zahl.";
        input.messages = {system, user};
        auto prompt = common_chat_templates_apply(templates.get(), input).prompt;
        auto * vocab = llama_model_get_vocab(model);
        int count = llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        std::vector<llama_token> tokens(-count);
        count = llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), tokens.size(), true, true);
        if (count <= 0) throw std::runtime_error("Tokenization failed");
        std::cout << "Prompt tokens: " << count << ", scalar: " << bonsai_reference_cpu << std::endl;
        for (int i = 0; i < count; i += 256) {
            auto b = llama_batch_get_one(tokens.data() + i, std::min(256, count - i));
            if (llama_decode(context, b) != 0) throw std::runtime_error("Prefill failed");
        }
        auto * sampler = bonsai_chat_sampler(vocab,42);
        if(!sampler)throw std::runtime_error("Sampler allocation failed");
        std::string answer;
        for (int i = 0; i < 16; ++i) {
            auto token = llama_sampler_sample(sampler, context, -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            char text[256];
            int n = llama_token_to_piece(vocab, token, text, sizeof(text), 0, false);
            if (n < 0) throw std::runtime_error("Token buffer too small");
            answer.append(text, n);
            auto b = llama_batch_get_one(&token, 1);
            if (llama_decode(context, b) != 0) throw std::runtime_error("Decode failed");
        }
        std::cout << "Arithmetic answer: " << answer << std::endl;
        const auto first = answer.find_first_not_of(" \r\n\t"), last = answer.find_last_not_of(" \r\n\t");
        if (first == std::string::npos || answer.substr(first, last - first + 1) != "4")
            throw std::runtime_error("Expected exactly 4");
        llama_sampler_free(sampler); llama_free(context); templates.reset(); llama_model_free(model);
        std::cout << "Real model arithmetic passed" << std::endl;
    } catch (const std::exception & e) {
        std::cerr << e.what() << std::endl; return 1;
    }
}
