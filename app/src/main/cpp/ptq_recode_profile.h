#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <map>
#include <mutex>
#include <stdexcept>
#include <string>
#include <tuple>
#include <vector>

// Header-only C++17 collector; callers supply durations, not timestamps.
// All *_ns totals are summed worker work, NOT additive wall-clock components.
// Enabled collection adds mutex, key-copy and allocation overhead; that overhead
// is not timed here. Disabled record() is an atomic check and otherwise a no-op.
namespace bonsai_ptq_profile {

struct Sample {
    std::string name;
    int width, rows, columns, threads;
    int thread_index;
    uint64_t quant_ns, barrier_ns, dot_ns;
};

struct Entry {
    std::string name;
    int width, rows, columns, threads;
    uint64_t thread_samples, quant_ns, barrier_ns, dot_ns;
};

namespace detail {
inline std::atomic<bool> active{false};
inline std::mutex mutex;
using Key = std::tuple<std::string, int, int, int, int>;
inline std::map<Key, Entry> entries;
}  // namespace detail

inline void reset() {
    const std::lock_guard<std::mutex> lock(detail::mutex);
    detail::active.store(false, std::memory_order_relaxed);
    detail::entries.clear();
}

inline void enable(bool value) {
    const std::lock_guard<std::mutex> lock(detail::mutex);
    detail::active.store(value, std::memory_order_relaxed);
}

inline bool enabled() {
    return detail::active.load(std::memory_order_relaxed);
}

// Nonempty name, positive shape and valid worker index are required when enabled.
// Invalid enabled input throws std::invalid_argument without changing statistics.
inline void record(const Sample& sample) {
    if (!enabled()) return;
    const std::lock_guard<std::mutex> lock(detail::mutex);
    if (!enabled()) return;
    if (sample.name.empty()) {
        throw std::invalid_argument("bonsai_ptq_profile::record: name must not be empty");
    }
    if (sample.width <= 0 || sample.rows <= 0 || sample.columns <= 0) {
        throw std::invalid_argument("bonsai_ptq_profile::record: width, rows and columns must be positive");
    }
    if (sample.threads < 1 || sample.thread_index < 0 || sample.thread_index >= sample.threads) {
        throw std::invalid_argument("bonsai_ptq_profile::record: threads >= 1 and 0 <= thread_index < threads required");
    }
    const detail::Key key{sample.name, sample.width, sample.rows, sample.columns, sample.threads};
    auto result = detail::entries.try_emplace(key, Entry{sample.name, sample.width,
        sample.rows, sample.columns, sample.threads, 0, 0, 0, 0});
    Entry& entry = result.first->second;
    ++entry.thread_samples;
    entry.quant_ns += sample.quant_ns;
    entry.barrier_ns += sample.barrier_ns;
    entry.dot_ns += sample.dot_ns;
}

inline std::vector<Entry> snapshot() {
    const std::lock_guard<std::mutex> lock(detail::mutex);
    std::vector<Entry> result;
    result.reserve(detail::entries.size());
    for (const auto& item : detail::entries) result.push_back(item.second);
    // The map supplies ascending name/width/rows/columns/threads tie order.
    std::stable_sort(result.begin(), result.end(), [](const Entry& left, const Entry& right) {
        return left.dot_ns > right.dot_ns;
    });
    return result;
}

}  // namespace bonsai_ptq_profile
