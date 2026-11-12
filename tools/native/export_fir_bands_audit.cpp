// Standalone Android executable. Links the build's generated blur source and
// Release GPU implementation; no test entry point is added to the application.
#include "blur.hpp"
#include "export_fir_bands.hpp"

#include <array>
#include <chrono>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

using spk::Image;
using spk::gpu::Gpu;

static void require(bool ok, const std::string& error) {
    if (!ok) throw std::runtime_error(error);
}

static std::vector<float> read(Gpu* gpu, const Image& image) {
    std::string error;
    std::vector<float> pixels(image.elements());
    require(gpu->flush(error) && gpu->read(image.buf.get(), 0, pixels.data(), image.bytes(), error), error);
    return pixels;
}

static Image fixture(Gpu* gpu, std::uint32_t width, std::uint32_t height, int salt) {
    Image image;
    image.w = width; image.h = height; image.c = 3;
    std::vector<float> pixels(image.elements());
    for (std::size_t i = 0; i < pixels.size(); ++i) {
        // Edge impulses, negative values, nonuniform colour and partial bands.
        const auto x = i / 3 % width, y = i / 3 / width;
        pixels[i] = float(int((i * 373 + salt * 17) % 65536) - 1024) / 32768.f;
        if (!x || !y || x + 1 == width || y + 1 == height) pixels[i] *= 3.f;
    }
    std::string error;
    image.buf = gpu->upload_persistent(pixels.data(), image.bytes(), error);
    require(bool(image.buf), error);
    return image;
}

int main(int argc, char** argv) {
    if (argc != 2) {
        std::cerr << "Pass the Vulkan shader directory\n";
        return 2;
    }
    try {
        std::string error;
        std::unique_ptr<Gpu> gpu(Gpu::create_vulkan(argv[1], error));
        require(bool(gpu), error);
        spk::Blur blur(gpu.get());
        std::size_t cases = 0, samples = 0, bands = 0;
        const std::array<std::array<double, 3>, 4> sigma_sets{{
            {{0., 0., 0.}}, {{.4, .7, 1.2}}, {{2.9, 0., 1.7}}, {{2.9, 2.9, 2.9}}
        }};
        for (const auto shape : {std::array<unsigned, 2>{37, 1}, {17, 3}, {1, 37}, {7, 41}, {63, 517}, {385, 1031}, {1025, 513}}) {
            auto source = fixture(gpu.get(), shape[0], shape[1], 1);
            auto accumulator = fixture(gpu.get(), shape[0], shape[1], 11);
            for (const auto sigma : sigma_sets) for (bool accumulating : {false, true}) {
                const double weight[3] = {.31, -.17, 1.04};
                const Image* acc = accumulating ? &accumulator : nullptr;
                Image reference;
                theia::export_execution::one_shot = false;
                require(blur.gaussian(source, sigma.data(), reference, error, 3., acc, weight), error);
                const auto expected = read(gpu.get(), reference);
                reference = {};
                const auto radius = spk::Blur::demand(sigma.data(), 3.).halo;
                const std::vector<unsigned> row_cases = shape[1] < 100
                    ? std::vector<unsigned>{1, 7, 16, 63}
                    : std::vector<unsigned>{31, 127, 256};
                for (const unsigned rows : row_cases) {
                    Image actual;
                    theia::export_execution::one_shot = true;
                    theia::export_execution::fir_bands = {};
                    require(theia::export_execution::run_fir_bands(gpu.get(), source, acc, radius, rows,
                        actual, error, [&](const Image& band, const Image* band_acc, Image& result) {
                            return blur.gaussian(band, sigma.data(), result, error, 3., band_acc, weight);
                        }), error);
                    const auto pixels = read(gpu.get(), actual);
                    if (pixels.size() != expected.size() || std::memcmp(pixels.data(), expected.data(), source.bytes())) {
                        throw std::runtime_error("FIR Float mismatch: " + std::to_string(shape[0]) + "x" +
                            std::to_string(shape[1]) + " rows=" + std::to_string(rows) +
                            " acc=" + std::to_string(accumulating));
                    }
                    samples += pixels.size(); ++cases;
                    bands += theia::export_execution::fir_bands.bands;
                    require(!theia::export_execution::inside_fir_band, "Nested band scope leaked");
                }
            }
            gpu->trim_pool(0);
        }

        // Production gate: large enough to enter banding automatically.
        auto source = fixture(gpu.get(), 2400, 2401, 3);
        const double sigma[3] = {2.4, .9, 1.7};
        Image reference;
        gpu->trim_pool(0); gpu->begin_frame();
        theia::export_execution::one_shot = false;
        require(blur.gaussian(source, sigma, reference, error), error);
        const auto expected = read(gpu.get(), reference);
        const auto whole_peak = gpu->pool_stats().frame_high_water_bytes;
        reference = {};
        gpu->trim_pool(0); gpu->begin_frame();
        theia::export_execution::one_shot = true;
        theia::export_execution::experimental_fir_bands = true;
        theia::export_execution::fir_bands = {};
        Image actual;
        require(blur.gaussian(source, sigma, actual, error), error);
        const auto pixels = read(gpu.get(), actual);
        const auto band_peak = gpu->pool_stats().frame_high_water_bytes;
        require(pixels.size() == expected.size() && !std::memcmp(pixels.data(), expected.data(), source.bytes()),
                "Automatic FIR changed Float samples");
        require(theia::export_execution::fir_bands.bands > 1, "Production FIR band gate never ran");
        require(band_peak < whole_peak, "FIR working peak did not decrease");
        const auto automatic_bands = theia::export_execution::fir_bands.bands;
        actual = {};

        // Mixed FIR/IIR channels continue to preserve the whole-plane IIR pass.
        const double mixed[3] = {.9, 3., 5.1};
        theia::export_execution::one_shot = false;
        require(blur.gaussian(source, mixed, reference, error), error);
        const auto mixed_expected = read(gpu.get(), reference);
        reference = {};
        theia::export_execution::one_shot = true;
        require(blur.gaussian(source, mixed, actual, error), error);
        const auto mixed_actual = read(gpu.get(), actual);
        require(!std::memcmp(mixed_actual.data(), mixed_expected.data(), source.bytes()),
                "Mixed FIR/IIR changed Float samples");
        actual = {};

        // Cancellation must stop between bands without publishing partial output.
        std::atomic<bool> cancel{false};
        theia::export_execution::cancellation = &cancel;
        std::size_t visited = 0;
        Image canceled;
        const bool completed = theia::export_execution::run_fir_bands(gpu.get(), source, nullptr, 8, 64,
            canceled, error, [&](const Image& band, const Image*, Image& result) {
                const bool ok = blur.gaussian(band, sigma, result, error);
                if (++visited == 2) cancel = true;
                return ok;
            });
        require(!completed && error == "render cancelled" && visited == 2 && !canceled.valid(),
                "Band cancellation published or continued partial output");
        theia::export_execution::cancellation = nullptr;
        require(!theia::export_execution::inside_fir_band, "Canceled nested scope leaked");
        theia::export_execution::one_shot = false;
        theia::export_execution::fir_bands = {};
        require(blur.gaussian(source, sigma, actual, error), error);
        require(theia::export_execution::fir_bands.calls == 0, "Ordinary blur unexpectedly used export bands");
        std::cout << "{\"status\":\"PASS\",\"explicit_cases\":" << cases
                  << ",\"float_samples\":" << samples << ",\"explicit_bands\":" << bands
                  << ",\"automatic_bands\":" << automatic_bands
                  << ",\"whole_scratch_peak_bytes\":" << whole_peak
                  << ",\"band_scratch_peak_bytes\":" << band_peak
                  << ",\"mixed_iir_exact\":true,\"cancellation_between_bands\":true}\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n';
        return 1;
    }
}
