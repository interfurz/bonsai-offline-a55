#pragma once
#include "llama.h"
#include <cstdint>
#include <stdexcept>

// Keep standalone ARM model checks on the same sampling path as the app.
inline llama_sampler * bonsai_chat_sampler(const llama_vocab * vocab,uint32_t seed,const char * grammar=nullptr) {
    auto * sampler=llama_sampler_chain_init(llama_sampler_chain_default_params());
    if(!sampler)return nullptr;
    if(grammar && grammar[0]) {
        auto * constraint=llama_sampler_init_grammar(vocab,grammar,"root");
        if(!constraint) {
            llama_sampler_free(sampler);
            throw std::runtime_error("GBNF-Grammatik konnte nicht geladen werden.");
        }
        llama_sampler_chain_add(sampler,constraint);
    }
    llama_sampler_chain_add(sampler,llama_sampler_init_penalties(llama_vocab_n_tokens(vocab),64,1.0f,0.0f,1.5f));
    llama_sampler_chain_add(sampler,llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler,llama_sampler_init_top_p(0.80f,1));
    llama_sampler_chain_add(sampler,llama_sampler_init_temp(0.70f));
    llama_sampler_chain_add(sampler,llama_sampler_init_dist(seed));
    return sampler;
}
