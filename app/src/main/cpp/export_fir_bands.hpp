#pragma once

#include "export_execution.hpp"
#include "image.hpp"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <string>
#include <utility>

namespace theia::export_execution {

struct FirBands {
    std::size_t calls = 0;
    std::size_t bands = 0;
    std::size_t largest_work_bytes = 0;
};

inline thread_local FirBands fir_bands;
inline thread_local bool inside_fir_band = false;

inline std::uint32_t fir_output_rows(const spk::Image& image) {
    constexpr std::size_t work_bytes = 16u * 1024u * 1024u;
    const std::size_t row_bytes = std::size_t(image.w) * image.c * sizeof(float);
    return row_bytes ? std::uint32_t(std::clamp(work_bytes / row_bytes, std::size_t(1), std::size_t(256))) : 0;
}

inline bool use_fir_bands(const spk::Image& image, std::size_t radius) {
    const auto rows = fir_output_rows(image);
    // Local savings alone did not lower the whole pipeline peak in the first
    // benchmark. Keep this path explicitly opt-in until that trade-off improves.
    return one_shot && experimental_fir_bands && !inside_fir_band && image.c == 3 && rows &&
        image.bytes() >= 64u * 1024u * 1024u && radius < rows &&
        std::size_t(image.h) > std::size_t(rows) + 2 * radius;
}

class FirBandScope {
public:
    FirBandScope() : previous_(inside_fir_band) { inside_fir_band = true; }
    ~FirBandScope() { inside_fir_band = previous_; }
    FirBandScope(const FirBandScope&) = delete;
    FirBandScope& operator=(const FirBandScope&) = delete;
private:
    bool previous_;
};

// The vertical FIR reads radius rows on each side. Only the final central
// rows are copied back; reflection is reached at the real image edges only.
// Horizontal pass, tap order, accumulator and Float operations stay unchanged.
template <class Filter>
bool run_fir_bands(spk::gpu::Gpu* gpu, const spk::Image& image, const spk::Image* accumulator,
                   std::size_t radius, std::uint32_t rows, spk::Image& output,
                   std::string& error, Filter filter) {
    if (!image.valid() || image.c != 3 || !rows ||
        (accumulator && (!accumulator->valid() || accumulator->w != image.w ||
                         accumulator->h != image.h || accumulator->c != image.c))) {
        error = "invalid export FIR band geometry";
        return false;
    }
    FirBandScope scope;
    spk::Image assembled;
    assembled.w = image.w; assembled.h = image.h; assembled.c = image.c;
    assembled.buf = gpu->alloc(image.bytes(), error);
    if (!assembled.buf) return false;
    const std::size_t row_bytes = std::size_t(image.w) * image.c * sizeof(float);
    ++fir_bands.calls;
    for (std::uint32_t y = 0; y < image.h;) {
        if (cancellation && cancellation->load(std::memory_order_relaxed)) {
            error = "render cancelled";
            return false;
        }
        const auto count = std::min(rows, image.h - y);
        const auto first = y > radius ? std::uint32_t(y - radius) : 0u;
        const auto last = std::uint32_t(std::min(std::size_t(image.h), std::size_t(y) + count + radius));
        spk::Image band, acc_band, result;
        band.w = image.w; band.h = last - first; band.c = image.c;
        band.buf = gpu->alloc(band.bytes(), error);
        if (!band.buf || !gpu->copy(band.buf.get(), 0, image.buf.get(),
                                    std::size_t(first) * row_bytes, band.bytes(), error)) return false;
        if (accumulator) {
            acc_band.w = band.w; acc_band.h = band.h; acc_band.c = band.c;
            acc_band.buf = gpu->alloc(acc_band.bytes(), error);
            if (!acc_band.buf || !gpu->copy(acc_band.buf.get(), 0, accumulator->buf.get(),
                                            std::size_t(first) * row_bytes, acc_band.bytes(), error)) return false;
        }
        if (!filter(band, accumulator ? &acc_band : nullptr, result) || !gpu->flush(error)) return false;
        if (!result.valid() || result.w != band.w || result.h != band.h || result.c != band.c) {
            error = "export FIR band changed shape";
            return false;
        }
        if (!gpu->copy(assembled.buf.get(), std::size_t(y) * row_bytes, result.buf.get(),
                       std::size_t(y - first) * row_bytes, std::size_t(count) * row_bytes, error)) return false;
        ++fir_bands.bands;
        fir_bands.largest_work_bytes = std::max(fir_bands.largest_work_bytes, band.bytes());
        y += count;
    }
    output = std::move(assembled);
    return true;
}

}  // namespace theia::export_execution
