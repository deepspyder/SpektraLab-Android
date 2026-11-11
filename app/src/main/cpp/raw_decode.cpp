/*  raw_decode.cpp -- JNI 侧的 RAW 解码（LibRaw）。
 *
 *  与 SpektraLab-Windows 的 spk::io::decode_raw_compatible 对齐的简化版：
 *    - output_color=4（ProPhoto RGB）、gamm=(1,1)（线性）、output_bps=16
 *    - user_qual=3（AHD 去马赛克）、use_camera_wb=1（相机/as-shot 白平衡）
 *    - 不启用 OpenMP（Android NDK 无 libomp），单线程解码
 *    - 返回线性 ProPhoto 的 float RGB（0..1，已应用相机白平衡）+ 元数据
 *
 *  Android 内容提供者（content://）不是文件系统路径，Kotlin 侧先把它拷到
 *  cacheDir 的临时文件再传入本函数；解完由 Kotlin 侧删除临时文件。
 */

#include <jni.h>
#include <android/log.h>

#include <libraw/libraw.h>
#include "native_pixels.hpp"

#include <cstdint>
#include <cstring>
#include <exception>
#include <string>
#include <vector>
#include <memory>
#include <algorithm>
#include <cmath>

#define LOG_TAG "SpektraLab-Raw"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

struct DecodedRaw : NativeRawPixels {
    float cam_mul[4] = {};       // 相机白平衡乘数（R, G, B, G2）
    float cam_xyz[9] = {};       // XYZ -> camera 矩阵（3x3，行主序）
    std::string make, model;
    std::string lens;            // 镜头型号（EXIF）
    float focal = 0.0f;          // 当前焦距 mm
};

// 镜头校正参数（自包含实现，不依赖 Lensfun/GLib）。
// 畸变用 Lensfun 数据库的原始系数，在 Hugin 坐标系（r=1 = 短边中点）下直接用。
struct LensCorrection {
    std::string dist_model;      // "ptlens" | "poly3"
    double terms[3] = {};        // ptlens: a,b,c；poly3: k1
    double vig_k[3] = {};        // 暗角 pa 模型 k1,k2,k3
    double tca_vr = 1.0, tca_vb = 1.0;
};

// 在已解码的线性 ProPhoto float RGB 上就地做畸变 + 暗角 + 简易 TCA 纠正。
// 返回 false 表示输入非法（太小/尺寸不符）。
bool apply_lens_correction(std::vector<float>& rgb, std::uint32_t w, std::uint32_t h,
                           const LensCorrection& lc, std::string& error) {
    (void) error;
    if (w < 8 || h < 8 || rgb.size() != std::size_t(w) * h * 3) return false;
    const double cx = (w - 1) * 0.5, cy = (h - 1) * 0.5;
    const double half_diag = std::hypot(cx, cy);   // 角落半径（像素），暗角 r=1

    // 1. 暗角纠正：gain = 1 / (1 + k1*r^2 + k2*r^4 + k3*r^6)
    if (lc.vig_k[0] != 0 || lc.vig_k[1] != 0 || lc.vig_k[2] != 0) {
        const double k1 = lc.vig_k[0], k2 = lc.vig_k[1], k3 = lc.vig_k[2];
        for (std::uint32_t y = 0; y < h; ++y) {
            const double dy = (y - cy) / half_diag;
            for (std::uint32_t x = 0; x < w; ++x) {
                const double dx = (x - cx) / half_diag;
                const double r2 = dx * dx + dy * dy, r4 = r2 * r2, r6 = r4 * r2;
                const double gain = 1.0 / (1.0 + k1 * r2 + k2 * r4 + k3 * r6);
                float* px = rgb.data() + (std::size_t(y) * w + x) * 3;
                px[0] *= float(gain); px[1] *= float(gain); px[2] *= float(gain);
            }
        }
    }

    // 2. 畸变纠正（Newton 求逆 + 双线性采样）
    const bool is_ptlens = (lc.dist_model == "ptlens");
    const double a = lc.terms[0], b = lc.terms[1], c = lc.terms[2];
    if (a == 0 && b == 0 && c == 0) return true;
    const double rscale = 2.0 / double(std::min(w, h));  // pixel -> Hugin r
    const std::vector<float> src = rgb;                  // 在源上采样

    auto sample = [&](double sx, double sy, float* out) {
        const double fx = std::clamp(sx, 0.0, double(w) - 1.001);
        const double fy = std::clamp(sy, 0.0, double(h) - 1.001);
        const int x0 = int(fx), y0 = int(fy);
        const double tx = fx - x0, ty = fy - y0;
        const int x1 = std::min(x0 + 1, int(w) - 1), y1 = std::min(y0 + 1, int(h) - 1);
        for (int ch = 0; ch < 3; ++ch) {
            const double v00 = src[(std::size_t(y0) * w + x0) * 3 + ch];
            const double v10 = src[(std::size_t(y0) * w + x1) * 3 + ch];
            const double v01 = src[(std::size_t(y1) * w + x0) * 3 + ch];
            const double v11 = src[(std::size_t(y1) * w + x1) * 3 + ch];
            out[ch] = float((v00 * (1 - tx) + v10 * tx) * (1 - ty) + (v01 * (1 - tx) + v11 * tx) * ty);
        }
    };

    for (std::uint32_t y = 0; y < h; ++y) {
        for (std::uint32_t x = 0; x < w; ++x) {
            const double dx = (x - cx) * rscale, dy = (y - cy) * rscale;
            const double rd = std::hypot(dx, dy);
            float* dst = rgb.data() + (std::size_t(y) * w + x) * 3;
            if (rd < 1e-9) continue;  // 中心不动
            double ru = rd;
            bool ok = false;
            for (int step = 0; step < 8; ++step) {
                double f, df;
                if (is_ptlens) {
                    f = ru * (a * ru * ru * ru + b * ru * ru + c * ru + 1) - rd;
                    df = 4 * a * ru * ru * ru + 3 * b * ru * ru + 2 * c * ru + 1;
                } else {
                    f = ru * (1 + a * ru * ru) - rd;
                    df = 1 + 3 * a * ru * ru;
                }
                if (std::abs(f) < 1e-7) { ok = true; break; }
                ru -= f / df;
                if (ru < 0) break;
            }
            if (!ok || ru < 0) continue;
            // 简易 TCA：红/蓝通道用不同半径（vr/vb 缩放）
            const double scale = ru / rd;
            const double sr = cx + (x - cx) * scale * lc.tca_vr;
            const double sg = cx + (x - cx) * scale;
            const double sb = cx + (x - cx) * scale * lc.tca_vb;
            const double yr = cy + (y - cy) * scale * lc.tca_vr;
            const double yg = cy + (y - cy) * scale;
            const double yb = cy + (y - cy) * scale * lc.tca_vb;
            float vr[3], vg[3], vb[3];
            sample(sr, yr, vr); sample(sg, yg, vg); sample(sb, yb, vb);
            dst[0] = vr[0]; dst[1] = vg[1]; dst[2] = vb[2];
        }
    }
    return true;
}

bool raw_stage_succeeded(int status, const char* stage, std::string& error) {
    if (status == LIBRAW_SUCCESS) return true;
    error = std::string("RAW ") + stage + ": " + LibRaw::strerror(status);
    return false;
}

void log_raw_data_warnings(LibRaw& raw) {
    const int count = raw.error_count();
    if (count == 0) return;

    // LibRaw documents error_count() as NON-FATAL unpack errors, including
    // out-of-range samples recovered by nikon_load_raw(). It is cumulative,
    // not the return status of unpack() or dcraw_process().
    LOGW("LibRaw recovered %d data warnings: camera=%s %s, process_warnings=%u",
         count, raw.imgdata.idata.make, raw.imgdata.idata.model,
         raw.imgdata.process_warnings);
}

struct RawCancellation {
    JNIEnv* env;
    jobject callback;
    jmethodID method = nullptr;

    RawCancellation(JNIEnv* environment, jobject value) : env(environment), callback(value) {
        if (!callback) return;
        jclass type = env->GetObjectClass(callback);
        if (type) {
            method = env->GetMethodID(type, "isCancelled", "()Z");
            env->DeleteLocalRef(type);
        }
    }

    bool requested() const {
        return env->ExceptionCheck() ||
               (callback && method && env->CallBooleanMethod(callback, method) == JNI_TRUE);
    }
};

int check_raw_progress(void* context, enum LibRaw_progress, int, int) {
    return static_cast<const RawCancellation*>(context)->requested() ? 1 : 0;
}

bool decode_raw(const std::string& path, bool half_size, const float* user_mul,
                DecodedRaw& out, std::string& error, int max_long_edge = 0,
                const RawCancellation* cancellation = nullptr) {
    try {
        if (cancellation && cancellation->requested()) {
            error = "RAW decode cancelled";
            return false;
        }
        LibRaw raw(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
        if (cancellation && cancellation->callback) {
            raw.set_progress_handler(check_raw_progress, const_cast<RawCancellation*>(cancellation));
        }
        libraw_output_params_t& p = raw.imgdata.params;
        p.user_qual = 3;                // AHD
        p.half_size = half_size ? 1 : 0;
        p.four_color_rgb = 0;
        p.output_color = 4;             // ProPhoto RGB
        p.output_bps = 16;
        p.gamm[0] = p.gamm[1] = 1.0;    // 线性
        p.no_auto_bright = 1;
        p.no_auto_scale = 0;
        p.adjust_maximum_thr = 0.0f;
        p.bright = 1.0f;
        p.use_camera_wb = user_mul ? 0 : 1;  // 有自定义乘数时替代相机 as-shot 白平衡
        p.use_auto_wb = 0;
        p.use_camera_matrix = 1;
        p.user_flip = -1;
        p.highlight = 0;
        if (user_mul) {
            std::copy_n(user_mul, 4, p.user_mul);
        }

        if (!raw_stage_succeeded(raw.open_file(path.c_str()), "open", error)) {
            return false;
        }
        // Use half demosaic only when it still contains every requested output pixel.
        if (max_long_edge > 0 &&
            std::max(raw.imgdata.sizes.width, raw.imgdata.sizes.height) / 2 >= max_long_edge) {
            p.half_size = 1;
        }
        if (!raw_stage_succeeded(raw.unpack(), "unpack", error)) {
            return false;
        }
        if (!raw_stage_succeeded(raw.dcraw_process(), "process", error)) {
            return false;
        }
        log_raw_data_warnings(raw);

        int image_status = LIBRAW_SUCCESS;
        std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(
            raw.dcraw_make_mem_image(&image_status), &LibRaw::dcraw_clear_mem);
        if (image_status != LIBRAW_SUCCESS || !image) {
            error = std::string("RAW output: ") + LibRaw::strerror(image_status);
            return false;
        }
        if (image->type != LIBRAW_IMAGE_BITMAP || image->bits != 16 || image->colors != 3 ||
            image->width == 0 || image->height == 0) {
            error = "RAW output: expected non-empty 16-bit three-channel bitmap";
            return false;
        }

        const std::uint32_t source_width = image->width;
        const std::uint32_t source_height = image->height;
        const std::uint64_t source_samples = std::uint64_t(source_width) * source_height * 3;
        if (source_samples * sizeof(std::uint16_t) != image->data_size) {
            error = "RAW output: inconsistent byte count";
            return false;
        }

        out.width = source_width;
        out.height = source_height;
        const auto& color = raw.imgdata.color;
        const auto& idata = raw.imgdata.idata;
        for (unsigned c = 0; c < 4; ++c) out.cam_mul[c] = color.cam_mul[c];
        for (unsigned row = 0; row < 3; ++row)
            for (unsigned col = 0; col < 3; ++col)
                out.cam_xyz[row * 3 + col] = color.cam_xyz[row][col];
        out.make.assign(idata.make, idata.make + strnlen(idata.make, sizeof(idata.make)));
        out.model.assign(idata.model, idata.model + strnlen(idata.model, sizeof(idata.model)));
        const auto& lensinfo = raw.imgdata.lens;
        out.lens.assign(lensinfo.Lens, lensinfo.Lens + strnlen(lensinfo.Lens, sizeof(lensinfo.Lens)));
        out.focal = lensinfo.makernotes.CurFocal > 0 ? lensinfo.makernotes.CurFocal
                    : (lensinfo.makernotes.MinFocal > 0 ? lensinfo.makernotes.MinFocal : 0.0f);
        // make_mem_image owns its copy. Release demosaic/RAW work planes before expanding RGB16 to RGB32.
        const std::uint32_t source_edge = std::max(source_width, source_height);
        if (max_long_edge > 0 && source_edge > std::uint32_t(max_long_edge)) {
            const double scale = double(max_long_edge) / source_edge;
            out.width = std::max(1u, std::uint32_t(source_width * scale));
            out.height = std::max(1u, std::uint32_t(source_height * scale));
        }
        const std::uint64_t samples = std::uint64_t(out.width) * out.height * 3;
        raw.recycle();
        out.rgb.resize(static_cast<std::size_t>(samples));
        if (out.width == source_width && out.height == source_height) {
            // At full size the bilinear coordinates are exactly integer pixels.
            // Keep the same double division/float rounding, without fetching
            // four identical neighbours for every output channel.
            for (std::size_t i = 0; i < samples; ++i) {
                if (cancellation && i % (1024 * 1024) == 0 && cancellation->requested()) {
                    error = "RAW decode cancelled";
                    return false;
                }
                std::uint16_t value;
                std::memcpy(&value, image->data + i * sizeof(value), sizeof(value));
                out.rgb[i] = float(double(value) / 65535.0);
            }
            return true;
        }
        const auto sample = [&](std::uint32_t x, std::uint32_t y, std::uint32_t channel) {
            std::uint16_t value;
            const std::uint64_t index = (std::uint64_t(y) * source_width + x) * 3 + channel;
            std::memcpy(&value, image->data + index * sizeof(value), sizeof(value));
            return double(value);
        };
        for (std::uint32_t y = 0; y < out.height; ++y) {
            if (cancellation && cancellation->requested()) {
                error = "RAW decode cancelled";
                return false;
            }
            const double sy = std::clamp((y + 0.5) * source_height / out.height - 0.5, 0.0, double(source_height - 1));
            const std::uint32_t y0 = std::uint32_t(sy), y1 = std::min(y0 + 1, source_height - 1);
            const double fy = sy - y0;
            for (std::uint32_t x = 0; x < out.width; ++x) {
                const double sx = std::clamp((x + 0.5) * source_width / out.width - 0.5, 0.0, double(source_width - 1));
                const std::uint32_t x0 = std::uint32_t(sx), x1 = std::min(x0 + 1, source_width - 1);
                const double fx = sx - x0;
                for (std::uint32_t c = 0; c < 3; ++c) {
                    const double top = sample(x0, y0, c) * (1.0 - fx) + sample(x1, y0, c) * fx;
                    const double bottom = sample(x0, y1, c) * (1.0 - fx) + sample(x1, y1, c) * fx;
                    out.rgb[(std::size_t(y) * out.width + x) * 3 + c] =
                        float((top * (1.0 - fy) + bottom * fy) / 65535.0);
                }
            }
        }
        return true;
    } catch (const std::exception& e) {
        error = std::string("RAW decode: ") + e.what();
        return false;
    } catch (...) {
        error = "RAW decode: unknown decoder error";
        return false;
    }
}

void throw_engine_error(JNIEnv* env, const std::string& msg) {
    jclass cls = env->FindClass("com/spektralab/android/engine/SpkException");
    if (cls) env->ThrowNew(cls, msg.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_spektralab_android_engine_RawDecoder_nativeReadCameraMatrix(
    JNIEnv* env, jobject, jstring path) {
    try {
        const char* text = env->GetStringUTFChars(path, nullptr);
        if (!text) return nullptr;
        const std::string file(text);
        env->ReleaseStringUTFChars(path, text);
        LibRaw raw(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
        std::string error;
        if (!raw_stage_succeeded(raw.open_file(file.c_str()), "metadata", error)) {
            throw_engine_error(env, error);
            return nullptr;
        }
        float matrix[9];
        bool nonzero = false;
        for (unsigned row = 0; row < 3; ++row) {
            for (unsigned column = 0; column < 3; ++column) {
                const float value = raw.imgdata.color.cam_xyz[row][column];
                if (!std::isfinite(value)) return nullptr;
                matrix[row * 3 + column] = value;
                nonzero |= value != 0.0f;
            }
        }
        // Some formats populate the matrix only during processing: retain the old fallback there.
        if (!nonzero) return nullptr;
        jfloatArray result = env->NewFloatArray(9);
        if (result) env->SetFloatArrayRegion(result, 0, 9, matrix);
        return result;
    } catch (const std::exception& error) {
        throw_engine_error(env, error.what());
        return nullptr;
    }
}

JNIEXPORT jobject JNICALL
Java_com_spektralab_android_engine_RawDecoder_nativeDecodeForExport(
    JNIEnv* env, jobject, jstring path, jint maxLongEdge, jfloatArray userMul, jobject cancellation) {
    try {
        const RawCancellation cancelled(env, cancellation);
        if (env->ExceptionCheck()) return nullptr;
        const char* text = env->GetStringUTFChars(path, nullptr);
        if (!text) return nullptr;
        const std::string file(text);
        env->ReleaseStringUTFChars(path, text);
        float multipliers[4]{};
        if (userMul) {
            if (env->GetArrayLength(userMul) != 4) { throw_engine_error(env, "Invalid RAW white balance"); return nullptr; }
            env->GetFloatArrayRegion(userMul, 0, 4, multipliers);
            if (env->ExceptionCheck()) return nullptr;
        }
        DecodedRaw raw;
        std::string error;
        if (!decode_raw(file, false, userMul ? multipliers : nullptr, raw, error, maxLongEdge, &cancelled)) {
            if (env->ExceptionCheck()) return nullptr;
            throw_engine_error(env, error); return nullptr;
        }
        auto pixels = std::make_unique<NativeRawPixels>();
        pixels->width = raw.width; pixels->height = raw.height; pixels->rgb = std::move(raw.rgb);
        jclass cls = env->FindClass("com/spektralab/android/engine/NativeRawImage");
        jmethodID ctor = cls ? env->GetMethodID(cls, "<init>", "(JIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;F[F)V") : nullptr;
        if (!ctor) return nullptr;
        jfloatArray xyz = env->NewFloatArray(9);
        if (!xyz) return nullptr;
        env->SetFloatArrayRegion(xyz, 0, 9, raw.cam_xyz);
        jstring make = env->NewStringUTF(raw.make.c_str());
        jstring model = env->NewStringUTF(raw.model.c_str());
        jstring lens = env->NewStringUTF(raw.lens.c_str());
        if (env->ExceptionCheck()) return nullptr;
        jobject obj = env->NewObject(cls, ctor, reinterpret_cast<jlong>(pixels.get()), jint(raw.width), jint(raw.height), make, model, lens, jfloat(raw.focal), xyz);
        if (obj) pixels.release();
        return obj;
    } catch (const std::exception& ex) { throw_engine_error(env, ex.what()); return nullptr; }
}

JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_NativeRawImage_nativeRelease(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<NativeRawPixels*>(handle);
}

JNIEXPORT jfloatArray JNICALL
Java_com_spektralab_android_engine_NativeRawImage_nativeCopySmallLinearRgb(JNIEnv* env, jobject, jlong handle) {
    const auto* raw = reinterpret_cast<NativeRawPixels*>(handle);
    if (!raw || raw->rgb.size() > 512u * 512u * 3u) {
        throw_engine_error(env, "Analysis preview exceeds its size budget");
        return nullptr;
    }
    auto result = env->NewFloatArray(static_cast<jsize>(raw->rgb.size()));
    if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(raw->rgb.size()), raw->rgb.data());
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_spektralab_android_engine_NativeRawImage_nativeResize(JNIEnv* env, jobject, jlong handle, jint maxLongEdge) {
    auto* raw = reinterpret_cast<NativeRawPixels*>(handle);
    if (!raw) return nullptr;
    try {
        const uint32_t edge = std::max(raw->width, raw->height);
        if (maxLongEdge > 0 && edge > uint32_t(maxLongEdge)) {
            const double scale = double(maxLongEdge) / edge;
            const uint32_t w = std::max(1u, uint32_t(std::lround(raw->width * scale)));
            const uint32_t h = std::max(1u, uint32_t(std::lround(raw->height * scale)));
            std::vector<float> resized(size_t(w) * h * 3);
            for (uint32_t y = 0; y < h; ++y) {
                const double sy = std::clamp((y + .5) * raw->height / h - .5, 0., double(raw->height - 1));
                const uint32_t y0 = uint32_t(sy), y1 = std::min(y0 + 1, raw->height - 1);
                const double fy = sy - y0;
                for (uint32_t x = 0; x < w; ++x) {
                    const double sx = std::clamp((x + .5) * raw->width / w - .5, 0., double(raw->width - 1));
                    const uint32_t x0 = uint32_t(sx), x1 = std::min(x0 + 1, raw->width - 1);
                    const double fx = sx - x0;
                    for (int c = 0; c < 3; ++c) {
                        const auto sample = [&](uint32_t xx, uint32_t yy) { return raw->rgb[(size_t(yy) * raw->width + xx) * 3 + c]; };
                        resized[(size_t(y) * w + x) * 3 + c] = float(
                            (sample(x0,y0)*(1-fx)+sample(x1,y0)*fx)*(1-fy) +
                            (sample(x0,y1)*(1-fx)+sample(x1,y1)*fx)*fy);
                    }
                }
            }
            raw->rgb.swap(resized); raw->width = w; raw->height = h;
        }
        jintArray size = env->NewIntArray(2);
        const jint dimensions[] = {jint(raw->width), jint(raw->height)};
        if (size) env->SetIntArrayRegion(size, 0, 2, dimensions);
        return size;
    } catch (const std::exception& ex) { throw_engine_error(env, ex.what()); return nullptr; }
}

JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_NativeRawImage_nativeCorrectLens(
    JNIEnv* env, jobject, jlong handle, jstring model, jfloatArray terms, jfloatArray vignette, jfloat vr, jfloat vb) {
    auto* raw = reinterpret_cast<NativeRawPixels*>(handle);
    if (!raw) return;
    try {
        LensCorrection correction;
        const char* text = env->GetStringUTFChars(model, nullptr);
        if (!text) return;
        correction.dist_model = text; env->ReleaseStringUTFChars(model, text);
        float t[3]{}, v[3]{};
        env->GetFloatArrayRegion(terms, 0, 3, t);
        if (env->ExceptionCheck()) return;
        env->GetFloatArrayRegion(vignette, 0, 3, v);
        if (env->ExceptionCheck()) return;
        for (int i = 0; i < 3; ++i) { correction.terms[i] = t[i]; correction.vig_k[i] = v[i]; }
        correction.tca_vr = vr; correction.tca_vb = vb;
        std::string error;
        if (!apply_lens_correction(raw->rgb, raw->width, raw->height, correction, error))
            throw_engine_error(env, error.empty() ? "RAW lens correction failed" : error);
    } catch (const std::exception& ex) { throw_engine_error(env, ex.what()); }
}

JNIEXPORT jobject JNICALL
Java_com_spektralab_android_engine_RawDecoder_nativeDecodeRaw(
    JNIEnv* env, jobject, jstring path, jboolean halfSize, jfloatArray userMul, jobject cancellation) {
    const RawCancellation cancelled(env, cancellation);
    if (env->ExceptionCheck()) return nullptr;
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return nullptr;
    const std::string file(p);
    env->ReleaseStringUTFChars(path, p);

    float user_mul_storage[4] = {};
    const float* user_mul = nullptr;
    if (userMul) {
        env->GetFloatArrayRegion(userMul, 0, 4, user_mul_storage);
        user_mul = user_mul_storage;
    }

    DecodedRaw decoded;
    std::string error;
    // The editor needs a responsive preview, not a half-resolution copy of a 50 MP sensor frame.
    if (!decode_raw(file, halfSize == JNI_TRUE, user_mul, decoded, error,
                    halfSize == JNI_TRUE ? 2048 : 0, &cancelled)) {
        if (env->ExceptionCheck()) return nullptr;
        LOGE("%s", error.c_str());
        throw_engine_error(env, error);
        return nullptr;
    }

    const jsize samples = static_cast<jsize>(decoded.rgb.size());
    jfloatArray jpx = env->NewFloatArray(samples);
    if (!jpx) return nullptr;
    env->SetFloatArrayRegion(jpx, 0, samples, decoded.rgb.data());

    jfloatArray jMul = env->NewFloatArray(4);
    jfloatArray jXyz = env->NewFloatArray(9);
    env->SetFloatArrayRegion(jMul, 0, 4, decoded.cam_mul);
    env->SetFloatArrayRegion(jXyz, 0, 9, decoded.cam_xyz);

    jclass cls = env->FindClass("com/spektralab/android/engine/RawDecodeResult");
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(cls, "<init>",
        "(II[FLjava/lang/String;Ljava/lang/String;Ljava/lang/String;F[F[F)V");
    if (!ctor) return nullptr;

    jstring jMake = env->NewStringUTF(decoded.make.c_str());
    jstring jModel = env->NewStringUTF(decoded.model.c_str());
    jstring jLens = env->NewStringUTF(decoded.lens.c_str());
    jobject obj = env->NewObject(cls, ctor,
        static_cast<jint>(decoded.width),
        static_cast<jint>(decoded.height),
        jpx, jMake, jModel, jLens, static_cast<jfloat>(decoded.focal),
        jMul, jXyz);
    env->DeleteLocalRef(jpx);
    env->DeleteLocalRef(jMake);
    env->DeleteLocalRef(jModel);
    env->DeleteLocalRef(jLens);
    env->DeleteLocalRef(jMul);
    env->DeleteLocalRef(jXyz);
    return obj;
}

// 就地做镜头校正（畸变 + 暗角 + 简易 TCA）。返回是否成功。
JNIEXPORT jboolean JNICALL
Java_com_spektralab_android_engine_RawDecoder_nativeApplyLensCorrection(
    JNIEnv* env, jobject, jfloatArray rgb, jint width, jint height,
    jstring distModel, jfloatArray terms, jfloatArray vigK, jfloat tcaVr, jfloat tcaVb) {
    if (!rgb || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize n = static_cast<jsize>(width) * height * 3;
    std::vector<float> data(static_cast<std::size_t>(n));
    env->GetFloatArrayRegion(rgb, 0, n, data.data());

    LensCorrection lc;
    const char* dm = env->GetStringUTFChars(distModel, nullptr);
    lc.dist_model = dm ? dm : "";
    if (dm) env->ReleaseStringUTFChars(distModel, dm);
    jfloat terms_f[3] = {}, vig_f[3] = {};
    if (terms) env->GetFloatArrayRegion(terms, 0, 3, terms_f);
    if (vigK) env->GetFloatArrayRegion(vigK, 0, 3, vig_f);
    lc.terms[0] = terms_f[0]; lc.terms[1] = terms_f[1]; lc.terms[2] = terms_f[2];
    lc.vig_k[0] = vig_f[0]; lc.vig_k[1] = vig_f[1]; lc.vig_k[2] = vig_f[2];
    lc.tca_vr = tcaVr;
    lc.tca_vb = tcaVb;

    std::string error;
    const bool ok = apply_lens_correction(data, static_cast<std::uint32_t>(width),
                                          static_cast<std::uint32_t>(height), lc, error);
    if (ok) env->SetFloatArrayRegion(rgb, 0, n, data.data());
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
