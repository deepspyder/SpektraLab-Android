// Compare every Float in blur mixtures; the app receives no test JNI surface.
#include "blur.hpp"
#include "export_mixture_accumulator.hpp"

#include <array>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

using spk::Image;
using spk::gpu::Gpu;
using Component = spk::Blur::Component;
using Owned = theia::export_execution::MixtureAccumulatorScope;

static thread_local std::atomic<bool>* audit_cancel = nullptr;
static thread_local unsigned cancel_after = 0, components_seen = 0;
void theia_mixture_audit_checkpoint() {
    if (audit_cancel && ++components_seen == cancel_after) audit_cancel->store(true);
}

static void require(bool ok, const std::string& error) {
    if (!ok) throw std::runtime_error(error);
}

static std::vector<float> read(Gpu* gpu, const Image& image) {
    std::string error;
    std::vector<float> pixels(image.elements());
    require(gpu->flush(error) && gpu->read(image.buf.get(), 0, pixels.data(), image.bytes(), error), error);
    return pixels;
}

static Image fixture(Gpu* gpu, unsigned w, unsigned h, unsigned salt) {
    Image image;
    image.w = w; image.h = h; image.c = 3;
    std::vector<float> pixels(image.elements());
    for (std::size_t i = 0; i < pixels.size(); ++i) {
        pixels[i] = float(int((i * 373 + salt * 17) % 65536) - 1024) / 32768.f;
        if (i / 3 % w == 0 || i / 3 / w == h - 1) pixels[i] *= 3.f;
        if (i % 127 == 0) pixels[i] = -0.f;
        if (i % 131 == 0) pixels[i] = 1e-32f;
    }
    std::string error;
    image.buf = gpu->upload_persistent(pixels.data(), image.bytes(), error);
    require(bool(image.buf), error);
    return image;
}

static void require_same_pixels(const std::vector<float>& expected, const std::vector<float>& actual, const std::string& label) {
    require(actual.size() == expected.size() &&
            std::memcmp(actual.data(), expected.data(), expected.size() * sizeof(float)) == 0, label);
}

int main(int argc, char** argv) {
    if (argc != 2) { std::cerr << "Pass the Vulkan shader directory\n"; return 2; }
    try {
        std::string error;
        std::unique_ptr<Gpu> gpu(Gpu::create_vulkan(argv[1], error));
        require(bool(gpu), error);
        spk::Blur blur(gpu.get());
        const Component identity{{.2, -.7, 1.1}, {0., 0., 0.}};
        const Component fir{{.3, .17, -.11}, {.4, 1.2, 2.9}};
        const Component iir{{.2, -.1, .9}, {3., 4.5, 8.1}};
        const Component mixed{{.2, .7, .1}, {.9, 3., 0.}};
        const Component zero{{0., 0., 0.}, {2.9, 1.1, .1}};
        const std::vector<std::vector<Component>> mixtures{
            {identity}, {fir}, {iir}, {mixed}, {fir, fir, fir},
            {identity, mixed, iir, fir}, {fir, iir, mixed, identity, zero}, {zero, fir, zero},
        };
        std::size_t cases = 0, samples = 0;
        theia::export_execution::mixture_reuses = {};
        for (const auto shape : {std::array<unsigned, 2>{37, 1}, {1, 37}, {17, 3}, {7, 41}, {63, 517}, {385, 257}, {1025, 513}}) {
            auto source = fixture(gpu.get(), shape[0], shape[1], 1);
            const auto source_pixels = read(gpu.get(), source);
            for (const auto& components : mixtures) for (double truncate : {2., 3., 4.}) {
                Image reference;
                theia::export_execution::one_shot = false;
                require(blur.mixture(source, components, reference, error, truncate), error);
                const auto expected = read(gpu.get(), reference);
                reference = {};
                Image actual;
                theia::export_execution::one_shot = true;
                require(blur.mixture(source, components, actual, error, truncate), error);
                require_same_pixels(expected, read(gpu.get(), actual), "Mixture Float mismatch at case " + std::to_string(cases));
                require_same_pixels(source_pixels, read(gpu.get(), source), "Mixture modified its input");
                cases++;
                samples += expected.size();
            }

            // Public accumulators remain borrowed and unchanged: no internal
            // registration is allowed to escape a completed mixture call.
            auto accumulator = fixture(gpu.get(), shape[0], shape[1], 11);
            const auto acc_pixels = read(gpu.get(), accumulator);
            for (const Component& comp : {fir, iir, mixed}) {
                Image expected_image, actual;
                theia::export_execution::one_shot = false;
                require(blur.gaussian(source, comp.sigma, expected_image, error, 3., &accumulator, comp.weight), error);
                const auto expected = read(gpu.get(), expected_image);
                theia::export_execution::one_shot = true;
                require(blur.gaussian(source, comp.sigma, actual, error, 3., &accumulator, comp.weight), error);
                require_same_pixels(expected, read(gpu.get(), actual), "Public accumulator Float mismatch");
                require_same_pixels(acc_pixels, read(gpu.get(), accumulator), "Public accumulator was modified");
                require(!Owned::can_reuse(&accumulator, source), "Mixture scope escaped public call");
                cases++;
                samples += expected.size();
            }
            gpu->trim_pool(0);
        }
        require(theia::export_execution::mixture_reuses.fir > 0 &&
                theia::export_execution::mixture_reuses.lincomb > 0, "Reuse paths never ran");
        const auto reuse_counts = theia::export_execution::mixture_reuses;

        auto source = fixture(gpu.get(), 2304, 1537, 5);
        const std::vector<Component> components{fir, fir, fir};
        Image reference, actual;
        gpu->trim_pool(0); gpu->begin_frame();
        theia::export_execution::one_shot = false;
        require(blur.mixture(source, components, reference, error), error);
        const auto expected = read(gpu.get(), reference);
        const auto original_peak = gpu->pool_stats().frame_high_water_bytes;
        reference = {};
        gpu->trim_pool(0); gpu->begin_frame();
        theia::export_execution::one_shot = true;
        require(blur.mixture(source, components, actual, error), error);
        require_same_pixels(expected, read(gpu.get(), actual), "Large mixture Float mismatch");
        const auto reused_peak = gpu->pool_stats().frame_high_water_bytes;
        require(reused_peak < original_peak, "Mixture scratch peak did not decrease");
        samples += expected.size(); cases++;
        actual = {};
        unsigned cancelled_checkpoints = 0;
        for (unsigned checkpoint : {1u, 2u, 3u}) {
            std::atomic<bool> cancel{false};
            audit_cancel = &cancel;
            cancel_after = checkpoint;
            components_seen = 0;
            theia::export_execution::cancellation = &cancel;
            Image cancelled;
            const bool ok = blur.mixture(source, components, cancelled, error);
            require(!ok && error == "render cancelled" && components_seen == checkpoint && !cancelled.valid(),
                    "Mixture cancellation continued or published a partial result");
            require(gpu->pool_stats().live_bytes == 0, "Cancelled mixture retained working images");
            audit_cancel = nullptr;
            theia::export_execution::cancellation = nullptr;
            error.clear();
            ++cancelled_checkpoints;
        }
        {
            auto accumulator = fixture(gpu.get(), 13, 9, 7);
            Owned scope(accumulator);
            require(!Owned::can_reuse(&accumulator, accumulator), "Neighbor input can alias its output");
            auto alias = accumulator;
            require(!Owned::can_reuse(&accumulator, alias), "Buffer alias escaped neighborhood guard");
            auto other = fixture(gpu.get(), 13, 9, 8);
            require(Owned::can_reuse(&accumulator, other), "Owned accumulator rejected distinct source");
            try {
                Owned nested(other);
                require(!Owned::can_reuse(&accumulator, other), "Nested owner was ignored");
                throw 1;
            } catch (int) {}
            require(Owned::can_reuse(&accumulator, other), "Nested failed scope did not restore owner");
        }
        theia::export_execution::one_shot = false;
        std::cout << "{\"status\":\"PASS\",\"cases\":" << cases
                  << ",\"float_samples\":" << samples
                  << ",\"fir_reuses\":" << reuse_counts.fir
                  << ",\"lincomb_reuses\":" << reuse_counts.lincomb
                  << ",\"cancelled_checkpoints\":" << cancelled_checkpoints
                  << ",\"original_scratch_peak_bytes\":" << original_peak
                  << ",\"reused_scratch_peak_bytes\":" << reused_peak << "}\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n'; return 1;
    }
}
