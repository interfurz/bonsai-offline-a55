#pragma once

#include <atomic>

// One engine task runs at a time; GGML's worker threads read this selection.
extern std::atomic<bool> bonsai_reference_cpu;
