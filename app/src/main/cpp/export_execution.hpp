#pragma once

#include <android/log.h>
#include <sys/system_properties.h>
#include <cstddef>
#include <atomic>
#include <utility>

namespace theia::export_execution {

// Only nativeRenderExportOnce opts in. The scope is confined to the thread
// holding the engine transaction and restores itself even when rendering fails.
inline thread_local bool one_shot = false;
inline thread_local bool detailed = false;
inline thread_local bool experimental_fir_bands = false;
inline thread_local const std::atomic<bool>* cancellation = nullptr;

// The engine owns the export negative; the pipeline normally only borrows it.
// Register that one local owner for the duration of run_print, so the one-shot
// stage chain can take it after print setup has finished. Exact object identity
// prevents consuming any unrelated alias or an ordinary interactive input.
template <typename Image>
class PrintInputScope {
public:
    explicit PrintInputScope(Image& input) : previous_(input_) {
        input_ = one_shot ? &input : nullptr;
    }
    ~PrintInputScope() { input_ = previous_; }
    PrintInputScope(const PrintInputScope&) = delete;
    PrintInputScope& operator=(const PrintInputScope&) = delete;

    static Image take(const Image& input) {
        if (one_shot && input_ == &input) return std::exchange(*input_, Image{});
        return input;
    }

private:
    inline static thread_local Image* input_ = nullptr;
    Image* previous_;
};

class Scope {
public:
    Scope() : previous_once_(one_shot), previous_detailed_(detailed),
              previous_fir_(experimental_fir_bands), previous_cancellation_(cancellation) {
        one_shot = true;
        char value[PROP_VALUE_MAX]{};
        detailed = __system_property_get("debug.theia.export_timings", value) > 0 && value[0] == '1';
        value[0] = 0;
        experimental_fir_bands = __system_property_get("debug.theia.export_fir_bands", value) > 0 && value[0] == '1';
    }
    ~Scope() {
        one_shot = previous_once_;
        detailed = previous_detailed_;
        experimental_fir_bands = previous_fir_;
        cancellation = previous_cancellation_;
    }
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;

private:
    bool previous_once_;
    bool previous_detailed_;
    bool previous_fir_;
    const std::atomic<bool>* previous_cancellation_;
};

inline void released_before_readback(std::size_t source, std::size_t negative) {
    __android_log_print(ANDROID_LOG_INFO, "SpkExport",
        "one_shot released_source_bytes=%zu released_negative_bytes=%zu", source, negative);
}

// Sample only when explicitly requested. The node's existing flush still owns
// synchronization; observing its counters must not add work to normal exports.
template <typename Gpu>
inline void trace_memory(Gpu* gpu, const char* phase) {
    if (!one_shot || !detailed) return;
    const auto stats = gpu->pool_stats();
    __android_log_print(ANDROID_LOG_INFO, "SpkExport",
        "memory phase=%s live_bytes=%zu persistent_bytes=%zu pool_bytes=%zu high_water_bytes=%zu",
        phase, stats.live_bytes, stats.persistent_bytes, stats.total_bytes,
        stats.frame_high_water_bytes);
}

}  // namespace theia::export_execution
