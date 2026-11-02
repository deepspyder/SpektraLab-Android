/*  spk_stub_engine.cpp -- 最小 stub 引擎，让骨架工程能 build + link + 运行。
 *
 *  它实现 spk_engine.h 的完整 C ABI，但：
 *    - 自省类调用（capabilities / params_schema / build_info / print_lut_catalog /
 *      memory_report / last_error）返回合法 JSON 或文本；
 *    - 渲染类调用（render / reprint / preview_stock_lut / export_di /
 *      render_digital_intermediate）返回 SPK_ERR_UNSUPPORTED。
 *
 *  这样整套链路（Compose UI → EngineClient → JNI → C ABI → 返回）都能跑通，
 *  而不会让人误以为「已经能渲染」。里程碑 M0 时用真实引擎替换它（见 engine/README.md）。
 */

#include <spektrafilm/spk_engine.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {

struct EngineImpl {
    char last_error[512];
    char resources_dir[1024];
};

struct SessionImpl {
    EngineImpl* engine; // 便于渲染函数写回 spk_last_error
};

void setError(EngineImpl* e, const char* msg) {
    if (!e) return;
    std::snprintf(e->last_error, sizeof(e->last_error), "%s", msg);
}

// 把 out_json 写成调用方负责释放（spk_string_free）的字符串。
char* dup(const char* s) {
    if (!s) return nullptr;
    char* p = static_cast<char*>(std::malloc(std::strlen(s) + 1));
    if (p) std::strcpy(p, s);
    return p;
}

}  // namespace

extern "C" {

spk_engine* spk_engine_create(const char* resources_dir, void* device) {
    (void) device;
    auto* e = new EngineImpl();
    e->last_error[0] = '\0';
    std::snprintf(e->resources_dir, sizeof(e->resources_dir), "%s",
                  resources_dir ? resources_dir : "");
    return reinterpret_cast<spk_engine*>(e);
}

void spk_engine_destroy(spk_engine* engine) {
    delete reinterpret_cast<EngineImpl*>(engine);
}

const char* spk_capabilities(spk_engine*) {
    static const char* s =
        "{\"transport_version\":1,\"schema_version\":1,"
        "\"backend\":{\"render_core\":\"stub\",\"gpu\":\"none\","
        "\"concurrent\":false,\"host\":\"stub\"},"
        "\"stub\":true,\"note\":\"link the real engine for full capabilities\"}";
    return s;
}

const char* spk_params_schema(spk_engine*) {
    static const char* s =
        "{\"stub\":true,\"note\":\"link the real engine for the full wire schema\"}";
    return s;
}

const char* spk_memory_report(spk_engine*) {
    static const char* s = "{\"stub\":true,\"total_bytes\":0}";
    return s;
}

const char* spk_print_lut_catalog(spk_engine*) {
    return "{}";
}

const char* spk_last_error(spk_engine* engine) {
    auto* e = reinterpret_cast<EngineImpl*>(engine);
    return e ? e->last_error : "";
}

const char* spk_build_info(void) {
    return "stub-engine (SpektraLab Android skeleton)";
}

spk_status spk_warm_up(spk_engine* engine, const char* film_stock,
                       const char* print_stock, char** out_json) {
    (void) film_stock; (void) print_stock;
    *out_json = dup("{\"stub\":true,\"warm_up\":true}");
    return SPK_OK;
}

spk_session* spk_open(spk_engine* engine, const spk_image* input,
                      const char* params_delta_json, char** out_json) {
    (void) input; (void) params_delta_json;
    if (out_json) *out_json = dup("{\"stub\":true,\"open\":true}");
    auto* s = new SessionImpl();
    s->engine = reinterpret_cast<EngineImpl*>(engine);
    return reinterpret_cast<spk_session*>(s);
}

spk_session* spk_open_device(spk_engine* engine, const spk_device_image* input,
                             const char* params_delta_json, char** out_json) {
    // Vulkan 后端不支持 device image 入口，与真实引擎行为一致。
    setError(reinterpret_cast<EngineImpl*>(engine),
             "spk_open_device is not supported on the Vulkan backend");
    return nullptr;
}

void spk_session_release(spk_session* session) {
    delete reinterpret_cast<SessionImpl*>(session);
}

spk_status spk_set_params(spk_session* session, const char* params_delta_json,
                          char** out_json) {
    (void) params_delta_json;
    if (out_json) *out_json = dup("{\"stub\":true,\"invalidated\":[],\"params\":{}}");
    return SPK_OK;
}

spk_status spk_get_params(spk_session* session, char** out_json) {
    *out_json = dup("{\"stub\":true}");
    return SPK_OK;
}

spk_status spk_solve(spk_session* session, const char* target, char** out_json) {
    (void) target;
    *out_json = dup("{\"stub\":true,\"solved_params\":{}}");
    return SPK_OK;
}

spk_status spk_contrast_mask_field(spk_session* session, const char* tier,
                                   const float** out_delta,
                                   uint32_t* out_grid_w, uint32_t* out_grid_h) {
    (void) tier;
    *out_delta = nullptr;
    *out_grid_w = 0;
    *out_grid_h = 0;
    return SPK_OK;
}

spk_status spk_scene_latitude(spk_session* session, const char* request_json,
                              char** out_json) {
    (void) request_json;
    *out_json = dup("{\"stub\":true,\"medium\":{},\"scene\":{},\"suggested\":{},\"fit\":{}}");
    return SPK_OK;
}

spk_status spk_overscan_geometry(spk_session* session, char** out_json) {
    *out_json = dup("{\"valid\":false}");
    return SPK_OK;
}

static const char* kNotImplemented =
    "stub engine: render not implemented — link the real engine (SPEKTRA_USE_STUB_ENGINE=OFF)";

spk_status spk_reprint(spk_session* session, const char* tier, spk_result* out) {
    (void) tier; (void) out;
    setError(reinterpret_cast<SessionImpl*>(session)->engine, kNotImplemented);
    return SPK_ERR_UNSUPPORTED;
}

spk_status spk_render(spk_session* session, const char* tier, spk_result* out) {
    (void) tier; (void) out;
    setError(reinterpret_cast<SessionImpl*>(session)->engine, kNotImplemented);
    return SPK_ERR_UNSUPPORTED;
}

spk_status spk_preview_stock_lut(spk_session* session, const char* print_stock,
                                 const char* tier, spk_result* out, char** out_json) {
    (void) print_stock; (void) tier; (void) out;
    if (out_json) *out_json = dup("{\"stub\":true}");
    setError(reinterpret_cast<SessionImpl*>(session)->engine, kNotImplemented);
    return SPK_ERR_UNSUPPORTED;
}

spk_status spk_export_di(spk_session* session, const char* print_stock,
                         spk_result* out, char** out_json) {
    (void) print_stock; (void) out;
    if (out_json) *out_json = dup("{\"stub\":true}");
    setError(reinterpret_cast<SessionImpl*>(session)->engine, kNotImplemented);
    return SPK_ERR_UNSUPPORTED;
}

spk_status spk_render_digital_intermediate(spk_session* session, spk_result* out,
                                           char** out_json) {
    (void) out;
    if (out_json) *out_json = dup("{\"stub\":true}");
    setError(reinterpret_cast<SessionImpl*>(session)->engine, kNotImplemented);
    return SPK_ERR_UNSUPPORTED;
}

spk_status spk_print_lut_table(spk_engine* engine, const char* print_stock,
                               const float** out_table, uint32_t* out_size) {
    (void) engine; (void) print_stock;
    *out_table = nullptr;
    *out_size = 0;
    return SPK_ERR_UNSUPPORTED;
}

spk_status spk_output_transform(spk_engine* engine, const char* src_cs,
                                const char* dst_cs, const char* gamut_compress_json,
                                char** out_json, const float** out_cmax,
                                uint32_t* out_cmax_count) {
    (void) src_cs; (void) dst_cs; (void) gamut_compress_json;
    *out_json = dup("{\"stub\":true}");
    *out_cmax = nullptr;
    *out_cmax_count = 0;
    return SPK_OK;
}

void spk_result_free(spk_result* result) {
    (void) result; // stub 不产生结果；真实引擎里这里释放像素/纹理。
}

void spk_cancel(spk_session* session, const char* progress_id) {
    (void) session; (void) progress_id;
}

spk_status spk_progress(spk_session* session, const char* progress_id, char** out_json) {
    (void) session; (void) progress_id;
    *out_json = dup("{\"stub\":true}");
    return SPK_OK;
}

void spk_string_free(char* s) {
    std::free(s);
}

}  // extern "C"
