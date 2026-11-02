#pragma once

#include <cstdint>
#include <vector>

// Shared only by the Android JNI adapters. spk_open copies input synchronously.
struct NativeRawPixels {
    std::uint32_t width = 0, height = 0;
    std::vector<float> rgb;
};
