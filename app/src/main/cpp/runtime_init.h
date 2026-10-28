#pragma once
#include "llama.h"
#include "ggml-backend.h"
#include "ptq_recode.h"
#include <mutex>

inline void bonsai_runtime_init() {
    static std::once_flag init;
    std::call_once(init, [] {
        ggml_backend_load_all(); llama_backend_init();
        #if BONSAI_PTQ_RECODE
        bonsai_ptq_recode_register();
        #endif
    });
}
