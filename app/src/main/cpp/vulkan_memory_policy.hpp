#pragma once

#include <algorithm>
#include <cstddef>
#include <vector>

namespace theia::vulkan_memory {

// Reusing a 300 MB image plane for a small uniform or band keeps all 300 MB
// live. Bound that slack without changing a shader's requested storage.
inline bool can_reuse(std::size_t capacity, std::size_t requested) {
    if (capacity < requested) return false;
    const auto slack = std::max(requested / 4, std::size_t(64 * 1024));
    return capacity - requested <= slack;
}

// Vulkan submissions in this backend are synchronous. Only unreferenced
// scratch buffers may be reclaimed; persistent sources/results stay owned.
template <class Buffer, class Destroy>
void trim_idle(std::vector<Buffer*>& buffers, Destroy destroy) {
    constexpr std::size_t keep_bytes = 64 * 1024 * 1024;
    std::size_t idle_bytes = 0;
    std::vector<Buffer*> idle;
    for (Buffer* buffer : buffers) {
        if (buffer->persistent || buffer->refs != 0) continue;
        idle_bytes += buffer->bytes;
        idle.push_back(buffer);
    }
    if (idle_bytes <= keep_bytes) return;
    std::sort(idle.begin(), idle.end(), [](Buffer* a, Buffer* b) { return a->bytes > b->bytes; });
    for (Buffer* buffer : idle) {
        if (idle_bytes <= keep_bytes) break;
        idle_bytes -= buffer->bytes;
        buffers.erase(std::remove(buffers.begin(), buffers.end(), buffer), buffers.end());
        destroy(buffer);
        delete buffer;
    }
}

}  // namespace theia::vulkan_memory
