/*  jni_bridge.cpp -- JNI 桥接：把 spk_engine.h 的 C ABI 暴露给 Kotlin。
 *
 *  这是移植模板的「前端 ↔ 引擎」接缝。它不对引擎做任何抽象，只是：
 *    - 把 Java 的 String/FloatArray 转成 C 的 const char* / float*；
 *    - 把引擎返回的 rgba16 像素拷贝进 Java 的 ShortArray；
 *    - 把 spk_status 失败转成 Java 异常 SpkException（带上 spk_last_error）。
 *
 *  命名约定：`Java_<全限定类名>_<方法名>`，与 EngineClient.kt 里的
 *  `external fun` 一一对应。实例方法第二参是 jobject，companion 静态方法
 *  第二参是 jclass。
 */

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstring>

#include <spektrafilm/spk_engine.h>

#define LOG_TAG "SpektraLab-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kSpkResultClass   = "com/spektralab/android/engine/SpkResult";
constexpr const char* kSpkExceptionClass = "com/spektralab/android/engine/SpkException";

inline spk_engine*  asEngine(jlong p)  { return reinterpret_cast<spk_engine*>(p); }
inline spk_session* asSession(jlong p) { return reinterpret_cast<spk_session*>(p); }

// 引擎拥有的字符串（不要 free）→ Java String。
jstring toJString(JNIEnv* env, const char* s) {
    return env->NewStringUTF(s ? s : "");
}

// 调用方负责释放的字符串（out_json）→ 拷贝成 Java String 后立刻 free。
jstring takeString(JNIEnv* env, char* s) {
    if (!s) return env->NewStringUTF("");
    jstring r = env->NewStringUTF(s);
    spk_string_free(s);
    return r;
}

// 失败时抛 SpkException，消息优先取引擎的 spk_last_error。
void throwEngineError(JNIEnv* env, spk_engine* e, spk_status st) {
    (void) st;
    const char* msg = spk_last_error(e);
    if (!msg || !*msg) msg = "engine failed";
    jclass cls = env->FindClass(kSpkExceptionClass);
    if (cls) env->ThrowNew(cls, msg);
}

// 把一个填好的 spk_result 包装成 Java 的 SpkResult 对象。
// rgba16 是紧致排列的 width*height*4 个 uint16（行内跳过 row_stride_px 的填充）。
jobject wrapResult(JNIEnv* env, const spk_result& r) {
    jclass cls = env->FindClass(kSpkResultClass);
    if (!cls) return nullptr;
    jmethodID ctor = env->GetMethodID(
        cls, "<init>", "([SIIIDZZLjava/lang/String;)V");
    if (!ctor) return nullptr;

    const jsize total = static_cast<jsize>(r.width) * r.height * 4;
    jshortArray arr = env->NewShortArray(total);
    if (!arr) return nullptr;

    jshort* dst = env->GetShortArrayElements(arr, nullptr);
    for (uint32_t y = 0; y < r.height; ++y) {
        const uint16_t* src = r.rgba16 + static_cast<size_t>(y) * r.row_stride_px * 4;
        jshort* row = dst + static_cast<size_t>(y) * r.width * 4;
        for (uint32_t x = 0; x < r.width * 4; ++x) {
            // uint16 -> int16 保留原始位；Kotlin 侧用 `toInt() and 0xFFFF` 还原。
            row[x] = static_cast<jshort>(static_cast<int16_t>(src[x]));
        }
    }
    env->ReleaseShortArrayElements(arr, dst, 0);

    jstring progress = env->NewStringUTF(r.progress_id);

    jobject obj = env->NewObject(
        cls, ctor, arr,
        static_cast<jint>(r.width),
        static_cast<jint>(r.height),
        static_cast<jint>(r.row_stride_px),
        static_cast<jdouble>(r.elapsed_ms),
        static_cast<jboolean>(r.reprint != 0),
        static_cast<jboolean>(r.negative_was_cached != 0),
        progress);

    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(progress);
    return obj;
}

}  // namespace

extern "C" {

// ---- 引擎生命周期 ---------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeEngineCreate(
    JNIEnv* env, jclass, jstring resourcesDir) {
    const char* dir = env->GetStringUTFChars(resourcesDir, nullptr);
    spk_engine* e = spk_engine_create(dir, nullptr /* device */);
    env->ReleaseStringUTFChars(resourcesDir, dir);
    if (!e) {
        jclass cls = env->FindClass(kSpkExceptionClass);
        if (cls) env->ThrowNew(cls, "spk_engine_create failed");
        return 0;
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeEngineDestroy(
    JNIEnv*, jobject, jlong enginePtr) {
    spk_engine_destroy(asEngine(enginePtr));
}

// ---- 引擎自省（引擎拥有、返回 String） ------------------------------------

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeCapabilities(
    JNIEnv* env, jobject, jlong enginePtr) {
    return toJString(env, spk_capabilities(asEngine(enginePtr)));
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeParamsSchema(
    JNIEnv* env, jobject, jlong enginePtr) {
    return toJString(env, spk_params_schema(asEngine(enginePtr)));
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeMemoryReport(
    JNIEnv* env, jobject, jlong enginePtr) {
    return toJString(env, spk_memory_report(asEngine(enginePtr)));
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativePrintLutCatalog(
    JNIEnv* env, jobject, jlong enginePtr) {
    return toJString(env, spk_print_lut_catalog(asEngine(enginePtr)));
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeLastError(
    JNIEnv* env, jobject, jlong enginePtr) {
    return toJString(env, spk_last_error(asEngine(enginePtr)));
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeBuildInfo(
    JNIEnv* env, jclass) {
    return toJString(env, spk_build_info());
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeWarmUp(
    JNIEnv* env, jobject, jlong enginePtr, jstring filmStock, jstring printStock) {
    const char* f = env->GetStringUTFChars(filmStock, nullptr);
    const char* p = env->GetStringUTFChars(printStock, nullptr);
    char* out = nullptr;
    spk_status st = spk_warm_up(asEngine(enginePtr), f, p, &out);
    env->ReleaseStringUTFChars(filmStock, f);
    env->ReleaseStringUTFChars(printStock, p);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    return takeString(env, out);
}

// ---- 会话生命周期 ---------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeOpen(
    JNIEnv* env, jobject, jlong enginePtr,
    jfloatArray data, jint width, jint height, jint channels, jstring paramsJson) {
    spk_engine* e = asEngine(enginePtr);
    jfloat* px = env->GetFloatArrayElements(data, nullptr);

    spk_image img;
    img.data = px;
    img.width = static_cast<uint32_t>(width);
    img.height = static_cast<uint32_t>(height);
    img.channels = static_cast<uint32_t>(channels);

    const char* pj = nullptr;
    if (paramsJson) pj = env->GetStringUTFChars(paramsJson, nullptr);

    char* out = nullptr;
    spk_session* s = spk_open(e, &img, pj, &out);

    env->ReleaseFloatArrayElements(data, px, JNI_ABORT);
    if (pj) env->ReleaseStringUTFChars(paramsJson, pj);
    if (out) {
        LOGI("spk_open reply: %s", out);
        spk_string_free(out);
    }
    if (!s) {
        throwEngineError(env, e, SPK_ERR_INTERNAL);
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeSessionRelease(
    JNIEnv*, jobject, jlong sessionPtr) {
    spk_session_release(asSession(sessionPtr));
}

// ---- 参数与求解 -----------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeSetParams(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr, jstring json) {
    const char* j = env->GetStringUTFChars(json, nullptr);
    char* out = nullptr;
    spk_status st = spk_set_params(asSession(sessionPtr), j, &out);
    env->ReleaseStringUTFChars(json, j);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    return takeString(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeGetParams(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr) {
    char* out = nullptr;
    spk_status st = spk_get_params(asSession(sessionPtr), &out);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    return takeString(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeSolve(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr, jstring target) {
    const char* t = env->GetStringUTFChars(target, nullptr);
    char* out = nullptr;
    spk_status st = spk_solve(asSession(sessionPtr), t, &out);
    env->ReleaseStringUTFChars(target, t);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    return takeString(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeSceneLatitude(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr, jstring requestJson) {
    const char* req = nullptr;
    if (requestJson) req = env->GetStringUTFChars(requestJson, nullptr);
    char* out = nullptr;
    spk_status st = spk_scene_latitude(asSession(sessionPtr), req, &out);
    if (req) env->ReleaseStringUTFChars(requestJson, req);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    return takeString(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeOverscanGeometry(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr) {
    char* out = nullptr;
    spk_status st = spk_overscan_geometry(asSession(sessionPtr), &out);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    return takeString(env, out);
}

// ---- 渲染 -----------------------------------------------------------------

JNIEXPORT jobject JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeRender(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr, jstring tier) {
    const char* t = env->GetStringUTFChars(tier, nullptr);
    spk_result r{};
    spk_status st = spk_reprint(asSession(sessionPtr), t, &r);
    env->ReleaseStringUTFChars(tier, t);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    jobject obj = wrapResult(env, r);
    spk_result_free(&r);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeRenderFull(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr, jstring tier) {
    const char* t = env->GetStringUTFChars(tier, nullptr);
    spk_result r{};
    spk_status st = spk_render(asSession(sessionPtr), t, &r);
    env->ReleaseStringUTFChars(tier, t);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    jobject obj = wrapResult(env, r);
    spk_result_free(&r);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_com_spektralab_android_engine_EngineClient_nativePreviewStockLut(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr,
    jstring printStock, jstring tier) {
    const char* p = env->GetStringUTFChars(printStock, nullptr);
    const char* t = env->GetStringUTFChars(tier, nullptr);
    spk_result r{};
    char* out = nullptr;
    spk_status st = spk_preview_stock_lut(asSession(sessionPtr), p, t, &r, &out);
    env->ReleaseStringUTFChars(printStock, p);
    env->ReleaseStringUTFChars(tier, t);
    if (out) spk_string_free(out);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    jobject obj = wrapResult(env, r);
    spk_result_free(&r);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeExportDi(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr, jstring printStock) {
    const char* p = nullptr;
    if (printStock) p = env->GetStringUTFChars(printStock, nullptr);
    spk_result r{};
    char* out = nullptr;
    spk_status st = spk_export_di(asSession(sessionPtr), p, &r, &out);
    if (p) env->ReleaseStringUTFChars(printStock, p);
    if (out) spk_string_free(out);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    jobject obj = wrapResult(env, r);
    spk_result_free(&r);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeRenderDigitalIntermediate(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr) {
    spk_result r{};
    char* out = nullptr;
    spk_status st = spk_render_digital_intermediate(asSession(sessionPtr), &r, &out);
    if (out) spk_string_free(out);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    jobject obj = wrapResult(env, r);
    spk_result_free(&r);
    return obj;
}

// ---- 控制 -----------------------------------------------------------------

JNIEXPORT void JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeCancel(
    JNIEnv* env, jobject, jlong sessionPtr, jstring progressId) {
    const char* id = progressId ? env->GetStringUTFChars(progressId, nullptr) : nullptr;
    spk_cancel(asSession(sessionPtr), id);
    if (id) env->ReleaseStringUTFChars(progressId, id);
}

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeProgress(
    JNIEnv* env, jobject, jlong enginePtr, jlong sessionPtr, jstring progressId) {
    const char* id = progressId ? env->GetStringUTFChars(progressId, nullptr) : nullptr;
    char* out = nullptr;
    spk_status st = spk_progress(asSession(sessionPtr), id, &out);
    if (id) env->ReleaseStringUTFChars(progressId, id);
    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    return takeString(env, out);
}

// ---- 色彩 -----------------------------------------------------------------

JNIEXPORT jstring JNICALL
Java_com_spektralab_android_engine_EngineClient_nativeOutputTransform(
    JNIEnv* env, jobject, jlong enginePtr,
    jstring srcCs, jstring dstCs, jstring gamutCompressJson) {
    const char* s = env->GetStringUTFChars(srcCs, nullptr);
    const char* d = env->GetStringUTFChars(dstCs, nullptr);
    const char* g = nullptr;
    if (gamutCompressJson) g = env->GetStringUTFChars(gamutCompressJson, nullptr);

    char* out = nullptr;
    const float* cmax = nullptr;
    uint32_t cmaxCount = 0;
    spk_status st = spk_output_transform(asEngine(enginePtr), s, d, g, &out, &cmax, &cmaxCount);

    env->ReleaseStringUTFChars(srcCs, s);
    env->ReleaseStringUTFChars(dstCs, d);
    if (g) env->ReleaseStringUTFChars(gamutCompressJson, g);
    (void) cmax; (void) cmaxCount; // C_max 表骨架阶段暂不导出到 Kotlin。

    if (st != SPK_OK) {
        throwEngineError(env, asEngine(enginePtr), st);
        return nullptr;
    }
    return takeString(env, out);
}

}  // extern "C"
