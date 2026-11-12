// Compare developed Float negatives before quantisation, with original and
// consuming execution in the same generated pipeline and the same Vulkan GPU.
#include "pipeline.hpp"
#include "export_execution.hpp"

#include <array>
#include <cstring>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdexcept>

thread_local int theia_dir_audit_cancel_checkpoint = 0;

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
    if (argc != 2 && argc != 3) {
        std::cerr << "Pass the complete engine resources directory and optional 'halation' matrix\n";
        return 2;
    }
    try {
        const std::string resources = argv[1];
        const bool halation_audit = argc == 3 && std::string(argv[2]) == "halation";
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
        std::size_t cases = 0, samples = 0;
        std::size_t original_peak = 0, consumed_peak = 0;
        const std::array<std::array<const char*, 2>, 4> stocks{{
            {{"kodak_portra_400", "kodak_portra_endura"}},
            {{"fujifilm_velvia_100", "kodak_portra_endura"}},
            {{"kodak_vision3_500t", "kodak_2383"}},
            {{"ilford_hp5_plus_400", "ilford_multigrade_iv_rc"}},
        }};
        for (const auto& stock : stocks) {
            for (const auto shape : {std::array<unsigned, 2>{1, 37}, {63, 41}, {385, 257}}) {
                for (int mode = 0; mode < (halation_audit ? 10 : 8); ++mode) {
                    spk::Params params;
                    require(spk::init_params(resources, stock[0], stock[1], filters, params, error), error);
                    params.camera.auto_exposure = false;
                    params.film_render.grain.active = mode >= 5;
                    if (mode == 6) {
                        params.film_render.grain.sublayers_active = false;
                        params.film_render.grain.blur = 1.2;
                    }
                    if (mode == 7) {
                        params.film_render.grain.amount = .35;
                        params.film_render.grain.blur_dye_clouds_um = 12.;
                        params.film_render.grain.micro_structure[1] = 10000.;
                    }
                    params.film_render.halation.active = false;
                    auto& dir = params.film_render.dir_couplers;
                    dir.active = mode != 0;
                    dir.diffusion_size_um = mode == 1 ? 0. : (mode == 2 ? 2. : (mode == 3 ? 800. : 20.));
                    if (mode == 3) dir.diffusion_tail_um = 2000.;
                    dir.diffusion_tail_weight = mode == 2 ? 0. : .06;
                    if (mode == 4) dir.amount = 0.;
                    if (halation_audit) {
                        params.film_render.grain.active = mode == 9;
                        auto& hal = params.film_render.halation;
                        hal.active = mode != 0;
                        hal.scatter_amount = mode == 2 ? 0. : (mode == 4 ? .35 : 1.);
                        hal.halation_amount = mode == 1 ? 0. : (mode == 8 ? 2.3 : 1.);
                        hal.halation_renormalize = mode != 5;
                        if (mode == 3) {
                            hal.scatter_amount = 0.;
                            for (double& strength : hal.halation_strength) strength = 0.;
                        }
                        if (mode == 6) {
                            hal.halation_first_sigma_um[0] = 1.;
                            hal.halation_first_sigma_um[1] = 350.;
                            hal.halation_first_sigma_um[2] = 0.;
                        }
                        if (mode == 7) params.camera.lens_blur_um = 50.;
                        if (mode == 8) hal.scatter_spatial_scale = 4.;
                    }
                    spk::Image source;
                    source.w = shape[0]; source.h = shape[1]; source.c = 3;
                    std::vector<float> input(source.elements());
                    for (std::size_t i = 0; i < input.size(); ++i) {
                        // Nonuniform channels, highlights, negatives and edge impulses.
                        input[i] = float(int(i * 373 % 65536) - 1024) / 32768.f;
                        if (i / 3 % source.w == 0) input[i] *= 3.f;
                    }
                    source.buf = gpu->upload_persistent(input.data(), source.bytes(), error);
                    require(bool(source.buf), error);
                    std::vector<float> expected;
                    for (bool consuming : {false, true}) {
                        spk::Pipeline pipeline(gpu.get(), &colour, &blob, &cache);
                        require(pipeline.build(params, error), error);
                        pipeline.set_source_shape(source.w, source.h, source.w, source.h);
                        theia::export_execution::one_shot = consuming;
                        gpu->begin_frame();
                        spk::Image negative;
                        {
                            theia::export_execution::FilmInputScope ownership(source);
                            require(pipeline.run_film(source, negative, nullptr, error), error);
                        }
                        require(consuming ? !source.valid() : source.valid(), "Wrong source ownership after film prefix");
                        const auto actual = read(gpu.get(), negative);
                        const auto peak = gpu->pool_stats().frame_high_water_bytes;
                        if (!consuming) {
                            expected = actual;
                            original_peak = std::max(original_peak, peak);
                        } else {
                            require(actual.size() == expected.size() &&
                                std::memcmp(actual.data(), expected.data(), actual.size() * sizeof(float)) == 0,
                                "DIR changed Float samples: " + std::string(stock[0]) + " mode=" + std::to_string(mode));
                            consumed_peak = std::max(consumed_peak, peak);
                            samples += actual.size();
                            ++cases;
                        }
                        gpu->end_frame();
                        theia::export_execution::one_shot = false;
                    }
                }
            }
        }
        std::size_t cancellations = 0;
        for (int checkpoint = 1; checkpoint <= 3; ++checkpoint) {
            spk::Params params;
            require(spk::init_params(resources, "kodak_portra_400", "kodak_portra_endura", filters, params, error), error);
            params.camera.auto_exposure = false;
            params.film_render.grain.active = false;
            params.film_render.halation.active = false;
            spk::Pipeline pipeline(gpu.get(), &colour, &blob, &cache);
            require(pipeline.build(params, error), error);
            spk::Image source;
            source.w = 385; source.h = 257; source.c = 3;
            const std::vector<float> pixels(source.elements(), .18f);
            source.buf = gpu->upload_persistent(pixels.data(), source.bytes(), error);
            require(bool(source.buf), error);
            pipeline.set_source_shape(source.w, source.h, source.w, source.h);
            spk::Progress progress;
            spk::Image output;
            theia_dir_audit_cancel_checkpoint = checkpoint;
            theia::export_execution::one_shot = true;
            gpu->begin_frame();
            bool ok;
            {
                theia::export_execution::FilmInputScope ownership(source);
                ok = pipeline.run_film(source, output, &progress, error);
            }
            require(!ok && progress.cancelled && error == "render cancelled", "DIR checkpoint failed to cancel");
            require(!source.valid() && !output.valid() && gpu->pool_stats().live_bytes == 0,
                    "Cancelled DIR retained its source or partial images");
            source = {};
            source.w = 13; source.h = 9; source.c = 3;
            {
                theia::export_execution::FilmInputScope outer(source);
                auto alias = source;
                {
                    theia::export_execution::FilmInputScope inner(alias);
                    theia::export_execution::FilmInputScope<spk::Image>::retire(source);
                    require(source.w == 13, "Nested film scope retired an unrelated owner");
                    theia::export_execution::FilmInputScope<spk::Image>::retire(alias);
                    require(alias.w == 0, "Nested film scope failed to retire its owner");
                }
                theia::export_execution::FilmInputScope<spk::Image>::retire(source);
                require(source.w == 0, "Nested film scope did not restore outer owner");
            }
            source.w = 13;
            theia::export_execution::FilmInputScope<spk::Image>::retire(source);
            require(source.w == 13, "Retired film scope escaped into reused source object");
            gpu->end_frame();
            theia_dir_audit_cancel_checkpoint = 0;
            theia::export_execution::one_shot = false;
            error.clear();
            ++cancellations;
        }
        std::cout << "{\"status\":\"PASS\",\"cases\":" << cases
                  << ",\"float_samples\":" << samples
                  << ",\"original_scratch_peak_bytes\":" << original_peak
                  << ",\"consumed_scratch_peak_bytes\":" << consumed_peak
                  << ",\"cancelled_checkpoints\":" << cancellations << "}\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
