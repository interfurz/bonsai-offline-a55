// Synthetic test fixtures only: no model runs or performance measurements.
#include "../app/src/main/cpp/ptq_recode_profile.h"

#include <atomic>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

namespace profile = bonsai_ptq_profile;

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

static void test_enable_and_reset_state() {
    require(!profile::enabled(), "profiling must start disabled");
    profile::enable(true);
    require(profile::enabled(), "enable(true) must enable profiling");
    profile::enable(false);
    require(!profile::enabled(), "enable(false) must disable profiling");
    profile::enable(true);
    profile::reset();
    require(!profile::enabled(), "reset must disable profiling");
}

static void test_aggregation_disabled_reset_and_snapshot_copy() {
    profile::reset();
    const profile::Sample test_data{"test.projection", 64, 3, 5, 2, 0, 11, 13, 17};
    profile::record(test_data);
    require(profile::snapshot().empty(), "disabled record must not create entries");
    profile::enable(true);
    profile::record(test_data);
    auto other_thread = test_data;
    other_thread.thread_index = 1;
    other_thread.quant_ns = 19;
    other_thread.barrier_ns = 23;
    other_thread.dot_ns = 29;
    profile::record(other_thread);
    const auto entries = profile::snapshot();
    require(entries.size() == 1, "thread indices must aggregate into one entry");
    const profile::Entry& entry = entries.front();
    require(entry.name == test_data.name && entry.width == 64 && entry.rows == 3 &&
                entry.columns == 5 && entry.threads == 2, "entry must preserve its key");
    require(entry.thread_samples == 2 && entry.quant_ns == 30 &&
                entry.barrier_ns == 36 && entry.dot_ns == 46, "exact worker sums required");
    profile::enable(false);
    profile::record(test_data);
    require(profile::snapshot().front().thread_samples == 2,
            "disabled record must not update an existing entry");
    auto detached = profile::snapshot();
    detached.front().name = "modified copy";
    require(profile::snapshot().front().name == test_data.name,
            "snapshot must return an independent copy");
    profile::reset();
    require(!profile::enabled() && profile::snapshot().empty(), "reset must clear and disable");
    require(entries.front().thread_samples == 2, "reset must not mutate old snapshots");
    profile::enable(true);
    require(profile::snapshot().empty(), "reenabling must not restore cleared entries");
    profile::reset();
}

static void test_keys_and_deterministic_sort() {
    profile::reset();
    profile::enable(true);
    // Synthetic fixtures: expected order is dot descending, then the complete key ascending.
    const profile::Sample test_data[] = {
        {"test.z", 8, 2, 3, 2, 0, 0, 0, 100},
        {"test.a", 8, 2, 3, 2, 0, 0, 0, 10},
        {"test.a", 8, 2, 3, 3, 2, 0, 0, 10},
        {"test.a", 8, 2, 4, 2, 0, 0, 0, 10},
        {"test.a", 8, 3, 3, 2, 0, 0, 0, 10},
        {"test.a", 9, 2, 3, 2, 0, 0, 0, 10},
        {"test.b", 8, 2, 3, 2, 0, 0, 0, 10},
        {"test.zero", 1, 1, 1, 1, 0, 0, 0, 0},
    };
    // Deliberately reverse insertion order to avoid accidental insertion-order sorting.
    for (int index = 7; index >= 0; --index) profile::record(test_data[index]);
    const auto entries = profile::snapshot();
    require(entries.size() == 8, "every name/shape/thread-count key must be distinct");
    for (std::size_t index = 0; index < entries.size(); ++index) {
        const auto& entry = entries[index];
        const auto& expected = test_data[index];
        require(entry.name == expected.name && entry.width == expected.width &&
                    entry.rows == expected.rows && entry.columns == expected.columns &&
                    entry.threads == expected.threads && entry.dot_ns == expected.dot_ns &&
                    entry.thread_samples == 1, "snapshot sort or key identity mismatch");
    }
    profile::reset();
}

static void test_invalid_samples_throw_without_mutation() {
    profile::reset();
    profile::enable(true);
    const profile::Sample valid_test_data{"test.valid", 8, 2, 3, 2, 1, 1, 2, 3};
    profile::record(valid_test_data);
    for (int field = 0; field < 10; ++field) {
        auto invalid_test_data = valid_test_data;
        switch (field) {
            case 0: invalid_test_data.name.clear(); break;
            case 1: invalid_test_data.width = 0; break;
            case 2: invalid_test_data.width = -1; break;
            case 3: invalid_test_data.rows = 0; break;
            case 4: invalid_test_data.rows = -1; break;
            case 5: invalid_test_data.columns = 0; break;
            case 6: invalid_test_data.columns = -1; break;
            case 7: invalid_test_data.threads = 0; break;
            case 8: invalid_test_data.thread_index = -1; break;
            case 9: invalid_test_data.thread_index = valid_test_data.threads; break;
        }
        bool rejected = false;
        try {
            profile::record(invalid_test_data);
        } catch (const std::invalid_argument& error) {
            rejected = error.what()[0] != '\0';
        }
        require(rejected, "invalid sample must throw a descriptive invalid_argument");
        const auto entries = profile::snapshot();
        require(entries.size() == 1 && entries.front().thread_samples == 1 &&
                    entries.front().quant_ns == 1 && entries.front().barrier_ns == 2 &&
                    entries.front().dot_ns == 3, "invalid input must not mutate statistics");
    }
    auto invalid_test_data = valid_test_data;
    invalid_test_data.threads = -1;
    profile::enable(false);
    profile::record(invalid_test_data);  // Disabled collection ignores even invalid samples.
    require(profile::snapshot().front().thread_samples == 1, "disabled invalid input is a no-op");
    profile::enable(true);
    bool rejected = false;
    try { profile::record(invalid_test_data); }
    catch (const std::invalid_argument&) { rejected = true; }
    require(rejected, "negative thread count must be rejected when enabled");
    profile::reset();
}

static void test_concurrent_record_and_snapshot_exact_sums() {
    profile::reset();
    profile::enable(true);
    constexpr int worker_count = 8;
    constexpr int samples_per_worker = 1000;
    constexpr uint64_t test_quant_ns = uint64_t{1} << 33;  // Deliberately exceeds 32 bits.
    std::atomic<int> ready{0};
    std::atomic<bool> start{false};
    std::atomic<bool> coherent{true};
    std::vector<std::thread> workers;
    for (int index = 0; index < worker_count; ++index) {
        workers.emplace_back([&, index] {
            const profile::Sample shared_test_data{"test.shared", 64, 3, 5, worker_count,
                index, test_quant_ns, 3 * test_quant_ns, 7 * test_quant_ns};
            auto private_test_data = shared_test_data;
            private_test_data.name = "test.worker." + std::to_string(index);
            ++ready;
            while (!start.load()) std::this_thread::yield();
            for (int iteration = 0; iteration < samples_per_worker; ++iteration) {
                profile::record(shared_test_data);
                profile::record(private_test_data);
            }
        });
    }
    std::thread observer([&] {
        ++ready;
        while (!start.load()) std::this_thread::yield();
        for (int iteration = 0; iteration < 200; ++iteration) {
            for (const auto& entry : profile::snapshot()) {
                if (entry.quant_ns != entry.thread_samples * test_quant_ns ||
                    entry.barrier_ns != entry.thread_samples * 3 * test_quant_ns ||
                    entry.dot_ns != entry.thread_samples * 7 * test_quant_ns) {
                    coherent.store(false);
                }
            }
            std::this_thread::yield();
        }
    });
    while (ready.load() != worker_count + 1) std::this_thread::yield();
    start.store(true);
    for (auto& worker : workers) worker.join();
    observer.join();
    require(coherent.load(), "concurrent snapshots must contain complete sample updates");
    const auto entries = profile::snapshot();
    require(entries.size() == worker_count + 1, "all concurrent keys must survive");
    require(entries.front().name == "test.shared", "shared sum must sort first");
    for (const auto& entry : entries) {
        const uint64_t expected_samples = entry.name == "test.shared"
            ? uint64_t{worker_count} * samples_per_worker : samples_per_worker;
        require(entry.thread_samples == expected_samples &&
                    entry.quant_ns == expected_samples * test_quant_ns &&
                    entry.barrier_ns == expected_samples * 3 * test_quant_ns &&
                    entry.dot_ns == expected_samples * 7 * test_quant_ns,
                "concurrent recording must preserve exact 64-bit worker sums");
    }
    profile::reset();
}

static void test_concurrent_reset_and_enable() {
    profile::reset();
    std::atomic<bool> start{false};
    std::atomic<int> ready{0};
    std::vector<std::thread> workers;
    for (int index = 0; index < 4; ++index) {
        workers.emplace_back([&, index] {
            const profile::Sample test_data{"test.reset.race", 8, 1, 1, 4, index, 1, 3, 7};
            ++ready;
            while (!start.load()) std::this_thread::yield();
            for (int iteration = 0; iteration < 2000; ++iteration) {
                profile::record(test_data);
                (void)profile::enabled();
                if (iteration % 31 == 0) (void)profile::snapshot();
            }
        });
    }
    while (ready.load() != 4) std::this_thread::yield();
    start.store(true);
    bool consistent = true;
    for (int iteration = 0; iteration < 200; ++iteration) {
        profile::enable(true);
        std::this_thread::yield();
        profile::enable(false);
        const auto frozen = profile::snapshot();
        const auto frozen_again = profile::snapshot();
        if (frozen.size() != frozen_again.size() ||
            (!frozen.empty() && frozen.front().thread_samples != frozen_again.front().thread_samples)) {
            consistent = false;
        }
        profile::reset();
        if (profile::enabled() || !profile::snapshot().empty()) consistent = false;
    }
    for (auto& worker : workers) worker.join();
    require(consistent, "disable/reset must serialize against in-flight record calls");
    require(!profile::enabled() && profile::snapshot().empty(), "final reset must stay empty");
}

int main() {
    try {
        test_enable_and_reset_state();
        test_aggregation_disabled_reset_and_snapshot_copy();
        test_keys_and_deterministic_sort();
        test_invalid_samples_throw_without_mutation();
        test_concurrent_record_and_snapshot_exact_sums();
        test_concurrent_reset_and_enable();
        std::cout << "PASS: PTQ profile tests (synthetic test data only)\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
