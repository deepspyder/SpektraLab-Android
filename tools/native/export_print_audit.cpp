// Full Float print comparison, before RGBA16 packing. Cancellation injection
// exists only in the standalone pipeline copy built by the audit tool.
#include "pipeline.hpp"
#include "export_execution.hpp"

#include <array>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>

thread_local int theia_print_audit_cancel_checkpoint = 0;

static void require(bool ok, const std::string& error) {
    if (!ok) throw std::runtime_error(error);
}

static std::vector<float> read(spk::gpu::Gpu* gpu, const spk::Image& image) {
    std::string error;
    std::vector<float> pixels(image.elements());
    require(gpu->flush(error) && gpu->read(image.buf.get(), 0, pixels.data(), image.bytes(), error), error);
    return pixels;
}

int main(int argc, char** argv) {
    if (argc != 2) { std::cerr << "Pass the complete engine resources directory\n"; return 2; }
    try {
        const std::string resources = argv[1];
        std::string error;
        spk::Blob blob;
        spk::Colour colour;
        spk::SetupCache cache;
        require(blob.open(resources + "/spektrafilm_constants.bin", error), error);
        require(colour.init(blob, error), error);
        std::ifstream filters_file(resources + "/neutral_print_filters.json");
        const std::string filters_text{std::istreambuf_iterator<char>(filters_file), {}};
        spk::Json filters;
        require(spk::Json::parse(filters_text, filters, error), error);
        std::unique_ptr<spk::gpu::Gpu> gpu(spk::gpu::Gpu::create_vulkan(resources + "/vulkan", error));
        require(bool(gpu), error);
        std::size_t cases = 0, samples = 0, cancellations = 0;
        const std::array<std::array<const char*, 2>, 4> stocks{{
            {{"kodak_portra_400", "kodak_portra_endura"}},
            {{"fujifilm_velvia_100", "kodak_portra_endura"}},
            {{"kodak_vision3_500t", "kodak_2383"}},
            {{"ilford_hp5_plus_400", "ilford_multigrade_iv_rc"}},
        }};
        using InputScope = theia::export_execution::PrintInputScope<spk::Image>;
        for (const auto& stock : stocks) {
            for (const auto shape : {std::array<unsigned, 2>{1, 37}, {63, 41}, {385, 257}}) {
                for (int mode = 0; mode < 7; ++mode) {
                    spk::Params params;
                    require(spk::init_params(resources, stock[0], stock[1], filters, params, error), error);
                    params.camera.auto_exposure = false;
                    params.film_render.grain.active = false;
                    params.film_render.halation.active = false;
                    params.print_render.glare.active = mode != 0;
                    params.print_render.glare.blur = mode == 1 ? 0. : (mode == 2 ? 1.2 : 20.);
                    if (mode == 4) params.io.scan_film = true;
                    if (mode == 5) params.scanner.lens_blur = 1.2;
                    spk::Image source;
                    source.w = shape[0]; source.h = shape[1]; source.c = 3;
                    std::vector<float> input(source.elements());
                    for (std::size_t i = 0; i < input.size(); ++i)
                        input[i] = float(int(i * 373 % 65536) - 1024) / 32768.f;
                    source.buf = gpu->upload_persistent(input.data(), source.bytes(), error);
                    require(bool(source.buf), error);
                    std::vector<float> expected;
                    for (bool consuming : {false, true}) {
                        spk::Pipeline pipeline(gpu.get(), &colour, &blob, &cache);
                        require(pipeline.build(params, error), error);
                        pipeline.set_source_shape(source.w, source.h, source.w, source.h);
                        spk::Image negative, output;
                        theia::export_execution::one_shot = consuming;
                        require(pipeline.run_film(source, negative, nullptr, error), error);
                        gpu->begin_frame();
                        if (mode == 6) {
                            // Borrowed inputs have no registered owner to take.
                            require(pipeline.run_print(negative, output, nullptr, error), error);
                        } else {
                            InputScope ownership(negative);
                            require(pipeline.run_print(negative, output, nullptr, error), error);
                        }
                        require(consuming && mode != 6 ? !negative.valid() : negative.valid(),
                                "Wrong negative ownership after print");
                        const auto actual = read(gpu.get(), output);
                        if (!consuming) expected = actual;
                        else {
                            require(actual.size() == expected.size() &&
                                std::memcmp(actual.data(), expected.data(), actual.size() * sizeof(float)) == 0,
                                "Print Float mismatch: " + std::string(stock[0]) + " mode=" + std::to_string(mode));
                            samples += actual.size();
                            ++cases;
                        }
                        gpu->end_frame();
                        theia::export_execution::one_shot = false;
                    }
                }
            }
        }
        for (int checkpoint = 1; checkpoint <= 4; ++checkpoint) {
            spk::Params params;
            require(spk::init_params(resources, "kodak_portra_400", "kodak_portra_endura", filters, params, error), error);
            params.camera.auto_exposure = false;
            params.film_render.grain.active = false;
            spk::Pipeline pipeline(gpu.get(), &colour, &blob, &cache);
            require(pipeline.build(params, error), error);
            spk::Image source;
            source.w = 385; source.h = 257; source.c = 3;
            const std::vector<float> pixels(source.elements(), .18f);
            source.buf = gpu->upload_persistent(pixels.data(), source.bytes(), error);
            require(bool(source.buf), error);
            pipeline.set_source_shape(source.w, source.h, source.w, source.h);
            theia::export_execution::one_shot = true;
            spk::Image negative, output;
            require(pipeline.run_film(source, negative, nullptr, error), error);
            spk::Progress progress;
            theia_print_audit_cancel_checkpoint = checkpoint;
            {
                InputScope ownership(negative);
                const bool ok = pipeline.run_print(negative, output, &progress, error);
                require(!ok && progress.cancelled && error == "render cancelled", "Print checkpoint failed to cancel");
            }
            require(!negative.valid() && !output.valid() && gpu->pool_stats().live_bytes == 0,
                    "Cancelled print retained an image");
            // The scope must restore its pointer even on an unsuccessful print.
            negative = source;
            auto ordinary = InputScope::take(negative);
            require(negative.valid() && ordinary.valid(), "Print input scope escaped cancellation");
            negative = {};
            ordinary = {};
            {
                InputScope outer(source);
                auto alias = source;
                {
                    InputScope inner(alias);
                    auto borrowed = InputScope::take(source);
                    require(source.valid() && borrowed.valid(), "Nested scope consumed an unrelated alias");
                    auto consumed = InputScope::take(alias);
                    require(!alias.valid() && consumed.valid(), "Nested scope failed to take its own input");
                }
                auto consumed = InputScope::take(source);
                require(!source.valid() && consumed.valid(), "Nested scope failed to restore its outer owner");
            }
            theia_print_audit_cancel_checkpoint = 0;
            theia::export_execution::one_shot = false;
            error.clear();
            ++cancellations;
        }
        std::cout << "{\"status\":\"PASS\",\"cases\":" << cases
                  << ",\"float_samples\":" << samples
                  << ",\"cancelled_checkpoints\":" << cancellations << "}\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
