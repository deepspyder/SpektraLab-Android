# 接入真实引擎（里程碑 M0）

`CMakeLists.txt` 默认编译 `stub/spk_stub_engine.cpp`（能 build+link+run，不能渲染）。
下面说明如何切换到真实引擎，以及需要哪些外部依赖。

## 切换

在 `app/build.gradle.kts` 的 `externalNativeBuild.cmake.arguments` 里加：

```kotlin
arguments += listOf("-DSPEKTRA_USE_STUB_ENGINE=OFF")
```

（或直接在 CMake 配置时传 `-DSPEKTRA_USE_STUB_ENGINE=OFF`。）

## 依赖清单（已核实）

| 依赖 | 状态 | 说明 |
|---|---|---|
| C++ 引擎源码 | ✅ 已在 `SpektraLab-Windows/engine/src` | core/pipeline/gpu/vulkan 全部由 CMake 引用 |
| Vulkan 头文件 | ✅ **NDK 自带** | `<ndk>/sysroot/usr/include/vulkan/`（NDK 27 已含完整 Khronos 头，无需单独下载） |
| volk | ✅ `engine/third_party/volk` | 运行时 `dlopen` 加载 `libvulkan.so`（Android 已提供） |
| stb_truetype | ✅ `engine/third_party/stb` | 片边文字光栅化 |
| 片边字体 | ✅ `engine/resources_product/fonts` | CMake 里 `file(READ ... HEX)` 嵌入二进制 |
| **glslangValidator** | ❌ **需单独安装** | 把 47 个 `.comp` 编译成 SPIR-V。没有它 `.so` 能编译，但运行时加载不到 SPIR-V 无法渲染 |
| LibRaw | ⏸️ 可选（M0 暂不做） | RAW 解码。M0 用 Android 侧解码线性像素喂给 `spk_open`；后续再做 LibRaw NDK |

## 编译 SPIR-V（运行前必须）

`.so` 里没有着色器；着色器是 `resources_dir/vulkan/*.spv` 运行时加载的资源。两条路：

1. **装 glslangValidator**（Vulkan SDK 或 [glslang 独立发布](https://github.com/KhronosGroup/glslang/releases)），
   然后 CMake 里 `find_program` 自动找到它，`spk_vulkan_shaders` 目标会编译出 `build/vulkan/*.spv`。
2. **用 Windows/Linux 版先编译好**（SPIR-V 跨平台通用）：在 `SpektraLab-Windows/engine` 里
   `build.sh`/`build-windows.ps1` 生成的 `.spv`，直接拷到 `assets/engine/resources/vulkan/`。

## 资源打包（运行前必须）

`spk_engine_create(resources_dir, NULL)` 需要 `resources_dir` 下这些文件：

```
engine/resources/                     # 基础资源（28 profiles、constants.bin、print_luts.json、sRGB.icc）
engine/resources_product/profiles/    # 5 个 B&W profiles 覆盖到 profiles/
engine/resources_product/paper_grades.json
engine/resources_product/neutral_print_filters.json  → 改名为 product-neutral-print-filters.json
engine/resources_product/fonts/LICENSE                → Imprint-fonts-LICENSE.txt
engine/resources_product/NOTICE.md                    → Product-profiles-NOTICE.md
engine/third_party/stb/LICENSE                       → stb-LICENSE.txt
vulkan/*.spv                                        # 上一步编译出的 SPIR-V
```

把这些放到 `app/src/main/assets/engine/resources/`。`EngineClient.create()` 会在首次启动时
把它们解压到 `filesDir/engine/` 再传给引擎。（后期可用 Gradle 自动化这一步。）

## Android 特有注意点

- **Vulkan 后端用 `spk_open`，不用 `spk_open_device`**：`spk_open_device` 在 Vulkan 后端被拒绝。
- **`spk_engine_create` 的 `device` 参数传 `NULL`**（Vulkan 后端自建 instance/device；`EngineClient` 已经这么做）。
- **内存**：45MP 全分辨率渲染在手机内存会爆。引擎有 `spk_memory_report` / `trim_pool`，
  生产版要在 Kotlin 侧限制最大帧尺寸 + 用 live/preview tier 交互、后台 full 渲染。
- **线程**：引擎 C ABI 里 `spk_cancel` 可跨线程；渲染本身单线程单飞。Kotlin 侧用 `Dispatchers.IO` + 单飞调度。

## 对比度遮罩（contrast mask）

Windows/Vulkan 后端**尚未实现** contrast mask（`engine/src/pipeline/windows_unported.cpp` 明确拒绝）。
Android 走的是同一条非 Apple 分支，所以该控件在 M0 阶段应隐藏/置灰，与 Windows 版行为一致。
