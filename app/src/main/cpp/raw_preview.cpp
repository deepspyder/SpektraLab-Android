// Display-only RAW originals. Never used as film input, render cache, or export pixels.
#include <jni.h>
#include <libraw/libraw.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace {
constexpr unsigned kMaxPreviewBytes = 16u * 1024u * 1024u;

struct Cancellation {
    JNIEnv* env;
    jobject callback;
    jmethodID method;

    Cancellation(JNIEnv* e, jobject value) : env(e), callback(value), method(nullptr) {
        if (!value) return;
        jclass cls = env->GetObjectClass(value);
        if (cls) {
            method = env->GetMethodID(cls, "isCancelled", "()Z");
            env->DeleteLocalRef(cls);
        }
    }
    bool requested() const {
        return env->ExceptionCheck() || (callback && method && env->CallBooleanMethod(callback, method));
    }
};

int progress(void* value, LibRaw_progress, int, int) {
    return static_cast<Cancellation*>(value)->requested() ? 1 : 0;
}

std::string path_string(JNIEnv* env, jstring path) {
    const char* value = env->GetStringUTFChars(path, nullptr);
    if (!value) return {};
    std::string result(value);
    env->ReleaseStringUTFChars(path, value);
    return result;
}

void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("com/spektralab/android/engine/SpkException"), message);
}
}  // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_spektralab_android_engine_RawDecoder_nativeCameraPreview(
    JNIEnv* env, jobject, jstring path, jobject callback) {
    try {
        Cancellation cancelled(env, callback);
        const auto file = path_string(env, path);
        if (cancelled.requested()) { fail(env, "RAW preview cancelled"); return nullptr; }
        LibRaw raw(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
        raw.set_progress_handler(progress, &cancelled);
        if (raw.open_file(file.c_str()) != LIBRAW_SUCCESS) return nullptr;
        const auto& list = raw.imgdata.thumbs_list;
        int best = -1;
        uint64_t area = 0;
        for (int i = 0; i < std::min(list.thumbcount, LIBRAW_THUMBNAIL_MAXCOUNT); ++i) {
            const auto& item = list.thumblist[i];
            const uint64_t pixels = uint64_t(item.twidth) * item.theight;
            if (item.tformat == LIBRAW_INTERNAL_THUMBNAIL_JPEG && item.tlength > 0 &&
                item.tlength <= kMaxPreviewBytes && pixels > area) {
                best = i;
                area = pixels;
            }
        }
        if (best < 0 && raw.imgdata.thumbnail.tlength > kMaxPreviewBytes) return nullptr;
        const int status = best >= 0 ? raw.unpack_thumb_ex(best) : raw.unpack_thumb();
        if (cancelled.requested()) { fail(env, "RAW preview cancelled"); return nullptr; }
        if (status != LIBRAW_SUCCESS || raw.imgdata.thumbnail.tformat != LIBRAW_THUMBNAIL_JPEG ||
            raw.imgdata.thumbnail.tlength < 8 || raw.imgdata.thumbnail.tlength > kMaxPreviewBytes) return nullptr;
        int error = LIBRAW_SUCCESS;
        std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(
            raw.dcraw_make_mem_thumb(&error), &LibRaw::dcraw_clear_mem);
        if (error || !image || image->type != LIBRAW_IMAGE_JPEG || image->data_size > kMaxPreviewBytes) return nullptr;
        // LibRaw retains an existing EXIF orientation or inserts the camera's orientation into JPEG.
        auto result = env->NewByteArray(jsize(image->data_size));
        if (result) env->SetByteArrayRegion(result, 0, jsize(image->data_size), reinterpret_cast<const jbyte*>(image->data));
        return result;
    } catch (const std::bad_alloc&) {
        // This optional reference must not turn a readable RAW into an import failure.
        return nullptr;
    } catch (const std::exception&) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_spektralab_android_engine_RawDecoder_nativeDisplayPreview(
    JNIEnv* env, jobject, jstring path, jint edge, jobject callback) {
    try {
        if (edge <= 0 || edge > 2048) { fail(env, "Invalid RAW preview size"); return nullptr; }
        Cancellation cancelled(env, callback);
        const auto file = path_string(env, path);
        if (cancelled.requested()) { fail(env, "RAW preview cancelled"); return nullptr; }
        LibRaw raw(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
        raw.set_progress_handler(progress, &cancelled);
        if (raw.open_file(file.c_str()) != LIBRAW_SUCCESS) return nullptr;
        auto& params = raw.imgdata.params;
        params.half_size = 1;
        params.user_qual = 3;  // AHD remains the decoder; this is a display rendition, not the film decode.
        params.use_camera_wb = 1;
        params.output_color = 1;
        params.output_bps = 8;
        params.gamm[0] = 1.0 / 2.4;
        params.gamm[1] = 12.92;
        params.no_auto_bright = 0;
        if (raw.unpack() != LIBRAW_SUCCESS || raw.dcraw_process() != LIBRAW_SUCCESS) return nullptr;
        if (cancelled.requested()) { fail(env, "RAW preview cancelled"); return nullptr; }
        int error = LIBRAW_SUCCESS;
        std::unique_ptr<libraw_processed_image_t, decltype(&LibRaw::dcraw_clear_mem)> image(
            raw.dcraw_make_mem_image(&error), &LibRaw::dcraw_clear_mem);
        if (error || !image || image->type != LIBRAW_IMAGE_BITMAP || image->bits != 8 || image->colors != 3 ||
            !image->width || !image->height || uint64_t(image->width) * image->height * 3 != image->data_size) return nullptr;
        const double scale = std::min(1.0, double(edge) / std::max(image->width, image->height));
        const int w = std::max(1, int(std::lround(image->width * scale)));
        const int h = std::max(1, int(std::lround(image->height * scale)));
        std::vector<jint> pixels(size_t(w) * h);
        for (int y = 0; y < h; ++y) {
            if (cancelled.requested()) { fail(env, "RAW preview cancelled"); return nullptr; }
            const double sy = std::clamp((y + .5) / scale - .5, 0.0, double(image->height - 1));
            const int y0 = int(sy), y1 = std::min(y0 + 1, int(image->height) - 1);
            const double fy = sy - y0;
            for (int x = 0; x < w; ++x) {
                const double sx = std::clamp((x + .5) / scale - .5, 0.0, double(image->width - 1));
                const int x0 = int(sx), x1 = std::min(x0 + 1, int(image->width) - 1);
                const double fx = sx - x0;
                uint32_t pixel = 0xff000000u;
                for (int c = 0; c < 3; ++c) {
                    const auto sample = [&](int xx, int yy) { return image->data[(size_t(yy) * image->width + xx) * 3 + c]; };
                    const double top = sample(x0, y0) * (1 - fx) + sample(x1, y0) * fx;
                    const double bottom = sample(x0, y1) * (1 - fx) + sample(x1, y1) * fx;
                    pixel |= uint32_t(std::lround(top * (1 - fy) + bottom * fy)) << (16 - c * 8);
                }
                pixels[size_t(y) * w + x] = jint(pixel);
            }
        }
        jclass bitmap = env->FindClass("android/graphics/Bitmap");
        jclass config = env->FindClass("android/graphics/Bitmap$Config");
        if (!bitmap || !config) return nullptr;
        const auto field = env->GetStaticFieldID(config, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        const auto create = env->GetStaticMethodID(bitmap, "createBitmap", "([IIILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        if (!field || !create) return nullptr;
        auto argb = env->NewIntArray(w * h);
        if (!argb) return nullptr;
        env->SetIntArrayRegion(argb, 0, w * h, pixels.data());
        return env->CallStaticObjectMethod(bitmap, create, argb, w, h, env->GetStaticObjectField(config, field));
    } catch (const std::bad_alloc&) {
        return nullptr;
    } catch (const std::exception&) {
        return nullptr;
    }
}
