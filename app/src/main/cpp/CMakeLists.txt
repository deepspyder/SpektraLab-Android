cmake_minimum_required(VERSION 3.22.1)
project(spektralab_android LANGUAGES C CXX)

# ---------------------------------------------------------------------------
# 引擎 checkout 路径（持有真正的 spk_engine.h 与全部引擎源码）。
# 默认按仓库布局指向兄弟目录 SpektraLab-Windows/engine，可被覆盖：
#   -DSPEKTRA_ENGINE_DIR=/path/to/SpektraLab-Windows/engine
# ---------------------------------------------------------------------------
set(SPEKTRA_ENGINE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../SpektraLab-Windows/engine"
    CACHE PATH "Path to the SpektraLab engine checkout (SpektraLab-Windows/engine)")

# 真实引擎已接入并验证（SPIR-V + 资源已就绪），默认编译真实引擎。
# 想回到纯骨架（stub，不渲染）调试链路时，可临时设回 ON。
option(SPEKTRA_USE_STUB_ENGINE "Build the stub engine instead of the real one" OFF)

# 真实 C ABI 头文件（单一事实来源），JNI 桥接与引擎都对着它编译。
set(SPK_INCLUDE_DIRS ${SPEKTRA_ENGINE_DIR}/include)

set(SPK_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.cpp
)

if(SPEKTRA_USE_STUB_ENGINE)
    # ---- stub 引擎 -----------------------------------------------------
    list(APPEND SPK_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/stub/spk_stub_engine.cpp)
    add_definitions(-DSPEKTRA_STUB_ENGINE=1)

else()
    # ---- 真实引擎（C++20 + Vulkan 后端） -------------------------------
    #
    # 依赖：Vulkan 头文件由 Android NDK 自带（<ndk>/sysroot/usr/include/vulkan/），
    # 无需单独下载 Khronos Vulkan-Headers。着色器（SPIR-V）编译需要 glslangValidator，
    # 缺它时 .so 仍可编译，只是运行时加载不到 SPIR-V（见 engine/README.md）。

    set(SPK_CORE_SOURCES
        ${SPEKTRA_ENGINE_DIR}/src/core/blob.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/cam16.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/colour.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/curves.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/digital_intermediate.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/hanatos.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/json.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/latitude_fit.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/numeric.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/params.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/print_lut.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/printing.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/profile.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/setup_cache.cpp
        ${SPEKTRA_ENGINE_DIR}/src/core/spectral.cpp
    )

    # 非 Apple 分支（Android 走这里）：windows_unported 是「显式边界」占位，
    # view_process / overscan / imprint_raster 是片边/日期后背的 Vulkan 路径。
    set(SPK_PIPELINE_SOURCES
        ${SPEKTRA_ENGINE_DIR}/src/pipeline/blur.cpp
        ${SPEKTRA_ENGINE_DIR}/src/pipeline/engine.cpp
        ${SPEKTRA_ENGINE_DIR}/src/pipeline/pipeline.cpp
        ${SPEKTRA_ENGINE_DIR}/src/pipeline/scene_latitude.cpp
        ${SPEKTRA_ENGINE_DIR}/src/pipeline/windows_unported.cpp
        ${SPEKTRA_ENGINE_DIR}/src/pipeline/view_process.cpp
        ${SPEKTRA_ENGINE_DIR}/src/pipeline/overscan.cpp
        ${SPEKTRA_ENGINE_DIR}/src/pipeline/imprint_raster.cpp
    )

    set(SPK_GPU_SOURCES
        ${SPEKTRA_ENGINE_DIR}/src/gpu/vulkan_gpu.cpp
    )

    set(SPK_VOLK_SOURCES
        ${SPEKTRA_ENGINE_DIR}/third_party/volk/volk.c
    )

    list(APPEND SPK_SOURCES ${SPK_CORE_SOURCES} ${SPK_PIPELINE_SOURCES} ${SPK_GPU_SOURCES})

    list(APPEND SPK_INCLUDE_DIRS
        ${SPEKTRA_ENGINE_DIR}/src
        ${SPEKTRA_ENGINE_DIR}/src/core
        ${SPEKTRA_ENGINE_DIR}/third_party/volk
        ${SPEKTRA_ENGINE_DIR}/third_party/stb
        ${CMAKE_CURRENT_BINARY_DIR}/generated
    )

    # ---- 嵌入片边字体（等价于 engine/cmake/ImprintFonts.cmake） ------------
    set(imprint_font_dir "${SPEKTRA_ENGINE_DIR}/resources_product/fonts")
    file(READ "${imprint_font_dir}/LiberationSans-Regular.ttf" imprint_regular HEX)
    file(READ "${imprint_font_dir}/LiberationSans-Bold.ttf" imprint_bold HEX)
    string(REGEX REPLACE "(..)" "0x\\1," imprint_regular "${imprint_regular}")
    string(REGEX REPLACE "(..)" "0x\\1," imprint_bold "${imprint_bold}")
    file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/generated")
    file(GENERATE OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/generated/spk_imprint_fonts.hpp"
        CONTENT "#pragma once\nnamespace spk::imprint::font_data {\nconst unsigned char regular[] = {${imprint_regular}};\nconst unsigned char bold[] = {${imprint_bold}};\n}\n")

    # ---- volk：单独编译成 C 静态库（与上游一致） ---------------------------
    add_library(spk_volk STATIC ${SPK_VOLK_SOURCES})
    set_target_properties(spk_volk PROPERTIES LINKER_LANGUAGE C)
    target_include_directories(spk_volk PUBLIC ${SPEKTRA_ENGINE_DIR}/third_party/volk)

    # ---- LibRaw：RAW 解码（Android 单线程构建，不启用 OpenMP）-------------
    # 依赖源码由 SpektraLab-Windows/engine/setup-libraw.ps1 下载到 deps/LibRaw-0.22.2。
    # NDK clang 不传 -fopenmp，LibRaw 的 libraw_types.h 就不会定义 LIBRAW_USE_OPENMP，
    # 自然退化为单线程，无需链接 OpenMP。
    set(SPEKTRALAB_LIBRAW_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../deps/LibRaw-0.22.2"
        CACHE PATH "Extracted official LibRaw 0.22.2 source")
    set(SPEKTRALAB_RAW_AVAILABLE OFF)
    if(EXISTS "${SPEKTRALAB_LIBRAW_SOURCE}/libraw/libraw.h")
        file(GLOB_RECURSE spk_libraw_sources CONFIGURE_DEPENDS "${SPEKTRALAB_LIBRAW_SOURCE}/src/*.cpp")
        # *_ph.cpp 是无后处理构建的占位实现，不是额外实现，必须排除。
        list(FILTER spk_libraw_sources EXCLUDE REGEX "/[^/]*_ph\\.cpp$")
        add_library(spk_libraw STATIC ${spk_libraw_sources})
        target_include_directories(spk_libraw PUBLIC "${SPEKTRALAB_LIBRAW_SOURCE}")
        target_compile_definitions(spk_libraw PUBLIC LIBRAW_NODLL)
        target_compile_features(spk_libraw PRIVATE cxx_std_11)
        set_target_properties(spk_libraw PROPERTIES POSITION_INDEPENDENT_CODE ON)
        if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
            target_compile_options(spk_libraw PRIVATE -w)
        endif()
        set(SPEKTRALAB_RAW_AVAILABLE ON)
        list(APPEND SPK_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/raw_decode.cpp)
        list(APPEND SPK_INCLUDE_DIRS "${SPEKTRALAB_LIBRAW_SOURCE}")
    else()
        message(WARNING "LibRaw 0.22.2 not found at ${SPEKTRALAB_LIBRAW_SOURCE} — RAW decode disabled. "
                        "Run SpektraLab-Windows/engine/setup-libraw.ps1 to fetch it.")
    endif()

    # ---- 可选：编译 SPIR-V（需要 glslangValidator） ----------------------
    find_program(SPEKTRALAB_GLSLANG NAMES glslangValidator glslang)
    if(SPEKTRALAB_GLSLANG)
        set(vulkan_shader_dir "${CMAKE_BINARY_DIR}/vulkan")
        set(SPEKTRALAB_VULKAN_KERNELS
            spk_math_probe spk_split_probe spk_take_rgb spk_matmul3 spk_stride_sample
            spk_affine3 spk_tc_b spk_lut2d_cubic spk_sep_fir_acc spk_iir_df_acc
            spk_lincomb3 spk_log10_guarded spk_curves
            spk_couplers_correction spk_spectral_epilogue
            spk_print_exposure spk_cam16ucs_compress
            spk_cctf_encode_matrix spk_to_rgba16
            spk_view_grade spk_view_grade_di spk_view_geometry spk_view_pack spk_view_cam16 spk_view_pair
            spk_grain_layers spk_grain_layer_one spk_grain_simple
            spk_lognormal_field spk_rng_probe spk_mul spk_glare_add
            spk_reduce_max spk_boost spk_bw_correct spk_edr spk_cctf_decode
            spk_zoom_bilinear_mirror spk_geometry_resample_df
            spk_lut3d_trilinear spk_di_normalise spk_di_encode spk_scene_latitude
            spk_overscan_canvas spk_overscan_add_mask spk_overscan_film_present spk_overscan_light)
        set(vulkan_spirv)
        foreach(kernel ${SPEKTRALAB_VULKAN_KERNELS})
            set(shader "${SPEKTRA_ENGINE_DIR}/src/shaders/vulkan/${kernel}.comp")
            set(spirv "${vulkan_shader_dir}/${kernel}.spv")
            add_custom_command(OUTPUT "${spirv}"
                COMMAND "${CMAKE_COMMAND}" -E make_directory "${vulkan_shader_dir}"
                COMMAND "${SPEKTRALAB_GLSLANG}" -V --target-env vulkan1.0
                        "-I${SPEKTRA_ENGINE_DIR}/src/shaders/vulkan"
                        -o "${spirv}" "${shader}"
                DEPENDS "${shader}"
                VERBATIM)
            list(APPEND vulkan_spirv "${spirv}")
        endforeach()
        add_custom_target(spk_vulkan_shaders DEPENDS ${vulkan_spirv})
        message(STATUS "SPIR-V shader compilation enabled (glslang: ${SPEKTRALAB_GLSLANG})")
    else()
        message(STATUS "glslangValidator not found — SPIR-V shaders will NOT be built. "
                       "Install it (Vulkan SDK) and copy the .spv files into assets/engine/resources/vulkan/ "
                       "to actually render. See cpp/engine/README.md.")
    endif()
endif()

# ---- 主库：JNI 桥接 +（stub 或 真实引擎） ---------------------------------
add_library(spektralab SHARED ${SPK_SOURCES})
target_compile_features(spektralab PRIVATE cxx_std_20)
target_include_directories(spektralab PRIVATE ${SPK_INCLUDE_DIRS})

find_library(log-lib log)

target_link_libraries(spektralab ${log-lib} jnigraphics)
if(NOT SPEKTRA_USE_STUB_ENGINE)
    target_link_libraries(spektralab spk_volk)
    if(SPEKTRALAB_RAW_AVAILABLE)
        target_link_libraries(spektralab spk_libraw)
    endif()
    # dlopen 用于 volk 加载 libvulkan；Android (bionic) 的 dl* 在 libc 里，
    # 显式链接 dl 是为了与 Linux 一致、且无副作用。
    find_library(dl-lib dl)
    if(dl-lib)
        target_link_libraries(spektralab ${dl-lib})
    endif()
endif()
