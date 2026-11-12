#pragma once

#include <android/log.h>
#include <sys/system_properties.h>
#include <cstddef>

namespace theia::export_execution {

// Only nativeRenderExportOnce opts in. The scope is confined to the thread
// holding the engine transaction and restores itself even when rendering fails.
inline thread_local bool one_shot = false;
inline thread_local bool detailed = false;

class Scope {
public:
    Scope() : previous_once_(one_shot), previous_detailed_(detailed) {
        one_shot = true;
        char value[PROP_VALUE_MAX]{};
        detailed = __system_property_get("debug.theia.export_timings", value) > 0 && value[0] == '1';
    }
    ~Scope() {
        one_shot = previous_once_;
        detailed = previous_detailed_;
    }
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;

private:
    bool previous_once_;
    bool previous_detailed_;
};

inline void released_before_readback(std::size_t source, std::size_t negative) {
    __android_log_print(ANDROID_LOG_INFO, "SpkExport",
        "one_shot released_source_bytes=%zu released_negative_bytes=%zu", source, negative);
}

}  // namespace theia::export_execution
