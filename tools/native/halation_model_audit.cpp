// Standalone Vulkan verification. No test entry points are added to the APK.
#include "gpu.hpp"
#include "blur.hpp"
#include "halation/halation_psf.hpp"
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

using spk::gpu::Gpu;
using spk::gpu::Arg;
static void require(bool value, const std::string& message) {
    if (!value) throw std::runtime_error(message);
}
static float smooth(float t) {
    t = std::clamp(t, 0.0f, 1.0f);
    return t*t*(3.0f-2.0f*t);
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    try {
        std::string error;
        std::unique_ptr<Gpu> gpu(Gpu::create_vulkan(argv[1], error));
        require(bool(gpu), error);
        std::size_t samples=0, unchanged=0, cases=0;
        double max_abs=0, max_relative=0;
        const auto start=std::chrono::steady_clock::now();
        constexpr uint32_t count=65539; // Non-workgroup-aligned tail.
        for (float clip : {0.00001f, .1f, 1.0f, 16.0f, 256.0f}) {
            std::vector<float> input(count*3), actual(input.size());
            for (uint32_t i=0;i<count;++i) {
                const float v=clip*std::exp2(-12.0f+12.0f*float(i%4096)/4095.0f);
                input[i*3]=v;input[i*3+1]=v*.3f;input[i*3+2]=v*.1f;
                if (i%103==0) {input[i*3]=-v;input[i*3+1]=-v*.5f;input[i*3+2]=0;}
            }
            for (float headroom : {0.0f, 1.0f, 4.0f, 8.0f}) {
                // Independent scalar evaluation of the upstream Metal formula.
                const float p[7]={1.0f/clip,-1.0f,1.0f/.75f,headroom,float(1.0/.184),1.5f,1.0f};
                const uint32_t n[1]={count};
                auto in=gpu->upload(input.data(),input.size()*sizeof(float),error);
                auto uniforms=gpu->upload(p,sizeof(p),error);
                auto out=gpu->alloc(input.size()*sizeof(float),error);
                require(bool(in)&&bool(uniforms)&&bool(out),error);
                require(gpu->dispatch("spk_highlight_headroom",{Arg::buf(in),Arg::buf(uniforms),
                    Arg::inline_bytes(n,1),Arg::buf(out)},count,error),error);
                require(gpu->flush(error)&&gpu->read(out.get(),0,actual.data(),actual.size()*sizeof(float),error),error);
                for(uint32_t i=0;i<count;++i) {
                    const float m=std::max({input[i*3],input[i*3+1],input[i*3+2]});
                    float gain=1.0f;
                    if(m>0) {
                        const float lm=std::log2(m);
                        gain=std::exp2(p[3]*smooth((lm+std::log2(p[0])-p[1])*p[2])*
                            smooth((lm+std::log2(p[4])-p[5])*p[6]));
                    }
                    for(int c=0;c<3;++c) {
                        const float expected=input[i*3+c]*gain;
                        const double delta=std::abs(double(actual[i*3+c])-expected);
                        const double relative=delta/std::max(1.0,std::abs(double(expected)));
                        max_abs=std::max(max_abs,delta);max_relative=std::max(max_relative,relative);
                        require(std::isfinite(actual[i*3+c])&&relative<=3e-5,"headroom changed the upstream formula");
                        if (gain==1.0f) {
                            require(std::memcmp(&actual[i*3+c],&input[i*3+c],sizeof(float))==0,"shadow or disabled headroom changed");
                            ++unchanged;
                        }
                        ++samples;
                    }
                }
                ++cases;
            }
        }
        // A full-size photo exceeds Vulkan's 65,535 X-workgroup limit. Check
        // both sides of the folded row and its partial tail without retaining
        // a second full-size host buffer.
        {
            constexpr uint32_t row = 65535u * 256u;
            constexpr uint32_t pixels = row + 17u;
            constexpr float clip = 16.0f;
            std::vector<float> input(std::size_t(pixels) * 3u);
            auto value = [](uint32_t i, int c) {
                return float(i % 97u + 1u) / 97.0f * clip * (c == 0 ? 1.0f : c == 1 ? .3f : .1f);
            };
            for (uint32_t i = 0; i < pixels; ++i)
                for (int c = 0; c < 3; ++c) input[std::size_t(i) * 3u + c] = value(i, c);
            auto in = gpu->upload(input.data(), input.size() * sizeof(float), error);
            require(bool(in), error);
            std::vector<float>().swap(input);
            const float p[7] = {1.0f / clip, -1.0f, 1.0f / .75f, 4.0f, float(1.0 / .184), 1.5f, 1.0f};
            const uint32_t n[1] = {pixels};
            auto uniforms = gpu->upload(p, sizeof(p), error);
            auto out = gpu->alloc(std::size_t(pixels) * 3u * sizeof(float), error);
            require(bool(uniforms) && bool(out), error);
            require(gpu->dispatch("spk_highlight_headroom", {Arg::buf(in), Arg::buf(uniforms),
                Arg::inline_bytes(n, 1), Arg::buf(out)}, pixels, error) && gpu->flush(error), error);
            for (uint32_t i : {0u, 255u, 256u, row - 1u, row, row + 1u, pixels - 1u}) {
                std::array<float, 3> actual{};
                require(gpu->read(out.get(), std::size_t(i) * 3u * sizeof(float), actual.data(), sizeof(actual), error), error);
                const float lm = std::log2(value(i, 0));
                const float gain = std::exp2(p[3] * smooth((lm + std::log2(p[0]) - p[1]) * p[2]) *
                    smooth((lm + std::log2(p[4]) - p[5]) * p[6]));
                for (int c = 0; c < 3; ++c) {
                    const float expected = value(i, c) * gain;
                    require(std::isfinite(actual[c]) && std::abs(actual[c] - expected) /
                        std::max(1.0f, std::abs(expected)) <= 3e-5f, "folded dispatch skipped full-size pixels");
                    ++samples;
                }
            }
            ++cases;
        }
        // Signed fits must remain normalized and nonnegative as a point spread function.
        double mass_error=0,min_psf=1;
        constexpr double pi=3.14159265358979323846;
        for(int c=0;c<3;++c) {
            double mass=0;
            for(int k=0;k<spk::kBackReflectionComponents;++k) mass+=spk::kBackReflectionWeight[k][c];
            mass_error=std::max(mass_error,std::abs(mass-1));
            require(std::abs(mass-1)<=2e-6,"PSF mass changed");
            for(int r=0;r<=50000;++r) {
                const double radius=r/1000.0;double value=0;
                for(int k=0;k<spk::kBackReflectionComponents;++k) {
                    const double s=spk::kBackReflectionSigmaOverBase[k][c];
                    value+=spk::kBackReflectionWeight[k][c]*std::exp(-radius*radius/(2*s*s))/(2*pi*s*s);
                }
                min_psf=std::min(min_psf,value);
                require(value>=-1e-8,"PSF became negative");
            }
        }
        // Exercise the real Vulkan signed-mixture implementation on both FIR and IIR scales.
        spk::Blur blur(gpu.get());
        for(double pitch : {.4,3.0,12.0}) {
            spk::Image image;image.w=131;image.h=137;image.c=3;
            std::vector<float> uniform(image.elements(),.25f),actual(uniform.size());
            image.buf=gpu->upload(uniform.data(),image.bytes(),error);require(bool(image.buf),error);
            std::vector<spk::Blur::Component> components;
            for(int k=0;k<spk::kBackReflectionComponents;++k) {
                spk::Blur::Component part{};
                for(int c=0;c<3;++c) {part.weight[c]=spk::kBackReflectionWeight[k][c];part.sigma[c]=spk::kBackReflectionSigmaOverBase[k][c]*pitch;}
                components.push_back(part);
            }
            spk::Image result;require(blur.mixture(image,components,result,error),error);
            require(gpu->flush(error)&&gpu->read(result.buf.get(),0,actual.data(),result.bytes(),error),error);
            for(float value:actual) require(std::isfinite(value)&&std::abs(value-.25f)<3e-5f,"signed GPU mixture changed a uniform exposure");
            ++cases;
        }
        const double elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
        std::cout<<"{\"success\":true,\"cases\":"<<cases<<",\"samples\":"<<samples
            <<",\"unchanged_samples\":"<<unchanged<<",\"max_absolute_error\":"<<max_abs
            <<",\"max_relative_error\":"<<max_relative<<",\"psf_mass_error\":"<<mass_error
            <<",\"min_psf\":"<<min_psf<<",\"elapsed_ms\":"<<elapsed<<"}\n";
    } catch(const std::exception& failure) {std::cerr<<failure.what()<<'\n';return 1;}
}
