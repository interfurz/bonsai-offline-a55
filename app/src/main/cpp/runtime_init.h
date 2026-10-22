#pragma once
#include "llama.h"
#include "ggml-backend.h"
#include <mutex>

inline void bonsai_runtime_init() {
    static std::once_flag init;
    std::call_once(init, [] { ggml_backend_load_all(); llama_backend_init(); });
}
