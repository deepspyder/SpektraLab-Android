#pragma once

#include "export_execution.hpp"
#include "image.hpp"

namespace theia::export_execution {

struct MixtureReuseStats {
    std::size_t fir = 0;
    std::size_t lincomb = 0;
};
inline thread_local MixtureReuseStats mixture_reuses;

// Only Blur::mixture registers its private accumulator. Public gaussian and
// lincomb calls continue to borrow their inputs, even during one-shot exports.
class MixtureAccumulatorScope {
public:
    explicit MixtureAccumulatorScope(const spk::Image& accumulator) : previous_(owned_) {
        owned_ = one_shot ? &accumulator : nullptr;
    }
    ~MixtureAccumulatorScope() { owned_ = previous_; }
    MixtureAccumulatorScope(const MixtureAccumulatorScope&) = delete;
    MixtureAccumulatorScope& operator=(const MixtureAccumulatorScope&) = delete;

    static bool can_reuse(const spk::Image* accumulator, const spk::Image& source) {
        return one_shot && accumulator && owned_ == accumulator && accumulator->valid() &&
            source.valid() && accumulator->h == source.h && accumulator->w == source.w &&
            accumulator->c == source.c && accumulator->buf.get() != source.buf.get();
    }

private:
    inline static thread_local const spk::Image* owned_ = nullptr;
    const spk::Image* previous_;
};

}  // namespace theia::export_execution
