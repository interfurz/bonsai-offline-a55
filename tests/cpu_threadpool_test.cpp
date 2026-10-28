#include "cpu_threadpool.h"
#include <iostream>
#include <string>
#include <type_traits>
#include <vector>

// Deterministic API stubs test ownership/failure ordering. Real GGML workers and
// model outputs are exercised separately by bonsai-decode-bench.
struct ggml_threadpool { int threads; bool paused; };
struct llama_context { ggml_threadpool * pool = nullptr; int threads = 1, batch = 1, cached = 42; };
static std::vector<std::string> events;
static bool fail_allocation = false;
static int allocations = 0, frees = 0, syncs = 0, pauses = 0;
static void check(bool value, const char * message) {
    if (!value) throw std::runtime_error(message);
}
extern "C" ggml_threadpool_params ggml_threadpool_params_default(int threads) {
    ggml_threadpool_params params{};
    params.n_threads = threads;
    return params;
}
extern "C" ggml_threadpool * ggml_threadpool_new(ggml_threadpool_params * params) {
    check(params->paused, "New workers must start paused");
    check(params->poll == 0, "Idle workers must sleep during serial sampling/streaming");
    events.push_back("allocate");
    if (fail_allocation) return nullptr;
    ++allocations;
    return new ggml_threadpool{params->n_threads, params->paused};
}
extern "C" void ggml_threadpool_free(ggml_threadpool * pool) {
    events.push_back("free-pool");
    ++frees;
    delete pool;
}

extern "C" void ggml_threadpool_pause(ggml_threadpool * pool) { ++pauses; pool->paused = true; }
extern "C" void llama_synchronize(llama_context *) { ++syncs; events.push_back("sync"); }
extern "C" void llama_detach_threadpool(llama_context * context) { context->pool = nullptr; events.push_back("detach"); }
extern "C" void llama_set_n_threads(llama_context * context, int32_t threads, int32_t batch) {
    context->threads = threads; context->batch = batch;
}
extern "C" void llama_attach_threadpool(llama_context * context, ggml_threadpool_t pool, ggml_threadpool_t batch) {
    check(batch == nullptr, "One shared capacity pool is sufficient");
    check(pool->threads >= context->threads && pool->threads >= context->batch, "Worker capacity was clamped");
    context->pool = pool;
    events.push_back("attach");
}
static void test_reuse_and_resize() {
    llama_context context;
    BonsaiCpuThreadpool pool;
    check(pool.capacity() == 0, "Empty pool capacity");
    const int initial = allocations;
    pool.attach(&context, 4, 8);
    check(pool.capacity() == 8 && context.threads == 4 && context.batch == 8, "Batch capacity omitted");
    auto * first = context.pool;
    pool.attach(&context, 8, 4);
    check(context.pool == first && allocations == initial + 1, "Same capacity must reuse workers");
    pool.attach(&context, 2, 2);
    check(pool.capacity() == 2 && context.cached == 42, "Thread resize must preserve KV state");
    check(events[events.size() - 2] == "attach" && events.back() == "free-pool", "Old pool freed before replacement attached");
    const int before_sync = syncs;
    auto * previous = context.pool;
    fail_allocation = true;
    try { pool.attach(&context, 2, 6); throw std::logic_error("Expected allocation failure"); }
    catch (const std::runtime_error & error) {
        check(std::string(error.what()) == "CPU threadpool allocation failed", "Unexpected allocation failure");
    }
    fail_allocation = false;
    check(context.pool == previous && context.threads == 2 && context.batch == 2 && syncs == before_sync,
          "Failed resize changed live context");
    const int before_pause = pauses;
    try { BonsaiCpuIdle idle(pool); throw std::runtime_error("cancel"); }
    catch (const std::runtime_error &) {}
    check(pauses == before_pause + 1 && context.pool->paused, "Idle guard must pause on exceptions");
    llama_detach_threadpool(&context);
    pool.reset();
    check(pool.capacity() == 0, "Pool reset");
}
static void test_validation_and_destruction() {
    llama_context context;
    BonsaiCpuThreadpool pool;
    for (auto counts : {std::pair<int,int>{0,4}, {-1,4}, {4,0}, {4,GGML_MAX_N_THREADS+1}}) {
        bool rejected = false;
        try { pool.attach(&context, counts.first, counts.second); }
        catch (const std::invalid_argument &) { rejected = true; }
        check(rejected && pool.capacity() == 0, "Invalid counts allocated workers");
    }
    bool rejected = false;
    try { pool.attach(nullptr, 1, 1); }
    catch (const std::invalid_argument &) { rejected = true; }
    check(rejected, "Null context accepted");
    auto free_context = [](llama_context * value) { events.push_back("free-context"); delete value; };
    {
        BonsaiCpuThreadpool owner;
        std::unique_ptr<llama_context, decltype(free_context)> owned(new llama_context, free_context);
        owner.attach(owned.get(), 1, 1);
    }
    check(events[events.size() - 2] == "free-context" && events.back() == "free-pool", "Context must die before pool");
    check(allocations == frees, "Worker pool leak");
}
int main() {
    static_assert(!std::is_copy_constructible_v<BonsaiCpuThreadpool>);
    static_assert(std::is_nothrow_move_constructible_v<BonsaiCpuThreadpool>);
    try {
        test_reuse_and_resize();
        test_validation_and_destruction();
        std::cout << "CPU pool capacity/reuse, atomic allocation failure, idle pause and destruction order passed\n";
    } catch (const std::exception & error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
