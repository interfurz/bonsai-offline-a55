#pragma once
#include "llama.h"
#include "ggml-cpu.h"
#include <algorithm>
#include <memory>
#include <stdexcept>

// Single-worker ownership: finish/free the context before destroying this pool.
// Declare the pool BEFORE its context so reverse member destruction is safe.
class BonsaiCpuThreadpool {
    using Pool = std::unique_ptr<ggml_threadpool, decltype(&ggml_threadpool_free)>;
    Pool pool_{nullptr, ggml_threadpool_free};
    int capacity_ = 0; // The pinned runtime declares a getter but does not implement it.
public:
    BonsaiCpuThreadpool() = default;
    BonsaiCpuThreadpool(BonsaiCpuThreadpool &&) noexcept = default;
    BonsaiCpuThreadpool & operator=(BonsaiCpuThreadpool &&) noexcept = default;
    BonsaiCpuThreadpool(const BonsaiCpuThreadpool &) = delete;
    BonsaiCpuThreadpool & operator=(const BonsaiCpuThreadpool &) = delete;

    int capacity() const { return pool_ ? capacity_ : 0; }
    void attach(llama_context * context, int threads, int batch_threads) {
        if (!context || threads < 1 || batch_threads < 1 ||
            threads > GGML_MAX_N_THREADS || batch_threads > GGML_MAX_N_THREADS)
            throw std::invalid_argument("Invalid CPU threadpool configuration");
        const int count = std::max(threads, batch_threads);
        // Allocate first: a failed resize must not detach or change the live pool.
        Pool previous(nullptr, ggml_threadpool_free);
        if (capacity() != count) {
            auto params = ggml_threadpool_params_default(count);
            params.paused = true;
            // Sampling/streaming runs between graphs. Do not spin worker cores
            // through that serial work (default poll=50 can burn millions of cycles).
            params.poll = 0;
            Pool replacement(ggml_threadpool_new(&params), ggml_threadpool_free);
            if (!replacement) throw std::runtime_error("CPU threadpool allocation failed");
            llama_synchronize(context);
            llama_detach_threadpool(context);
            previous = std::move(pool_);
            pool_ = std::move(replacement);
            capacity_ = count;
        }
        llama_set_n_threads(context, threads, batch_threads);
        // One pool of max(decode,batch) capacity, not two competing worker sets.
        llama_attach_threadpool(context, pool_.get(), nullptr);
    }
    void pause() { if (pool_) ggml_threadpool_pause(pool_.get()); }
    void reset() { pool_.reset(); capacity_ = 0; }
};

// Pause on normal return and exceptions, without discarding worker threads.
// GGML resumes a paused pool automatically when the next graph starts.
class BonsaiCpuIdle {
    BonsaiCpuThreadpool & pool_;
public:
    explicit BonsaiCpuIdle(BonsaiCpuThreadpool & pool) : pool_(pool) {}
    ~BonsaiCpuIdle() { pool_.pause(); }
    BonsaiCpuIdle(const BonsaiCpuIdle &) = delete;
    BonsaiCpuIdle & operator=(const BonsaiCpuIdle &) = delete;
};
