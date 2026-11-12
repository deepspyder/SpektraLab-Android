# Android 引擎接入

`CMakeLists.txt` 默认编译真实 C++20 引擎，`SPEKTRA_USE_STUB_ENGINE=OFF`。本目录只保存接入说明，实际源码来自构建时的 `SPEKTRA_ENGINE_DIR`，默认指向工作区同级的 `SpektraLab-Windows/engine`。

引擎编译进入 Android 的 `libspektralab.so`，在应用自身进程内执行。手机不访问电脑上的源码、桌面应用或网络服务；共用源码目录本身不增加运行时传输或会话争用。

## Android 一次性导出路径

单张和批量成片导出使用 `EngineClient.renderExportOnce()`，保持 full 层级、计算节点及像素精度：

1. DIR 最后一次读取后释放不再使用的曝光中间图，避免与颗粒阶段叠加驻留。
2. 不为随即关闭的导出会话额外复制完整浮点负片。
3. 测光、负片及片边布局完成后，在冲印开始前释放源图和层级引用；保留实际负片直到冲印结束，再于最终打包和 CPU 读回前释放。
4. 返回独立拥有的 CPU RGBA16 结果并关闭会话，之后编码仍按既有精度逐行读取。

后续F03批次进一步将film前处理图的所有权转移到阶段链，避免前处理结果一直持有到整个胶片计算结束。普通预览链仍沿用原路径，输出节点及运算顺序不变。

普通编辑预览和 `renderForExport()` 保留原先缓存语义。作用域按线程隔离并在失败时恢复，不能用于仍需继续交互重印的会话。软打样和 DI 沿用原有入口。

`cmake/AndroidExportExecution.cmake` 在 Android 构建目录生成专用引擎和流水线源文件；`cmake/AndroidVulkanMemory.cmake` 同样生成移动端分配适配。这些适配不会修改桌面引擎检出。上游锚点变化会让构建失败，必须重新审查资源生命周期。

这不是完整分块渲染：全局归约、模糊和颗粒仍可能需要多个全尺寸图面。GPU 临时池高水位不等于进程 PSS，仍需在手机上核对驱动和总体峰值。

## 分阶段诊断

`SpkExport` 记录解码、镜头校正、引擎输入、渲染和编码耗时。需要检查一次性导出的具体节点时可临时开启：

    adb shell setprop debug.theia.export_timings 1
    adb logcat -s SpkExport

完成后恢复为 0。诊断默认关闭；开启会获取同步节点计时，不能与正常运行的总耗时直接混作同一指标。

诊断同时在现有节点 flush 之后记录 `memory phase=...`，包含活跃临时图、持久图、临时池总容量和本帧临时池高水位。`before_source_release` 与 `after_source_release` 可核对冲印前释放的图面。高水位是累计值；第一次升高的节点才是新增峰值来源，不能把后续相同计数当成该节点自己的峰值。持久图与临时池分别记录，不代表完整进程或驱动内存。

F03 第三批的 24MP RAW 测量中，冲印前持久图由 315,103,004 字节降到 672,284 字节，约释放 299.9 MiB。临时池高水位仍为 1401.8 MiB，首次达到该值的节点是 DIR 耦合；这次生命周期改进没有消除更早的全程峰值。完整验证见 `docs/f03-batch-03-source-lifetime.md`。

有限核高斯FIR已增加精确分带实现，保留真实图边缘反射、完整邻域、垂直再水平顺序和末次累加。局部工作集减少，但第一轮实际24MP测量中全链路峰值未下降且耗时增加，因此**默认关闭**，仅供研发验证：

    adb shell setprop debug.theia.export_fir_bands 1

完成后恢复为0。生产界面不提供此实验开关；不能将独立模糊节点的内存收益说成完整流水线收益。IIR递推、全局统计和颗粒仍按原语义运行。

独立Float对照程序不会打进APK。构建x86_64 Release后执行：

    python tools/build_export_fir_audit.py --output .tmp/fir-native-audit

将生成程序、同目录原生库和相同Vulkan着色器放到测试设备，运行程序并传入着色器目录。程序覆盖小图、单行图、窄图、不同带高、累加、混合FIR/IIR和取消；要求每个Float逐位相等。实际发布仍必须通过材料与全尺寸输出回归。

测试入口 `audit=export-once` 核对全部胶片家族、兼容相纸、裁剪、片边、日期、相同初始随机状态颗粒、已有负片缓存的消费导出、实际取消和再次打开；`audit=engine-export` 使用真实 RAW 完成全尺寸分阶段计时及完整 TIFF 哈希。相机测试文件不进入应用或 Git。

## 依赖清单（已核实）

| 依赖 | 状态 | 说明 |
|---|---|---|
| C++ 引擎源码 | ✅ 已在 `SpektraLab-Windows/engine/src` | core/pipeline/gpu/vulkan 全部由 CMake 引用 |
| Vulkan 头文件 | ✅ **NDK 自带** | `<ndk>/sysroot/usr/include/vulkan/`（NDK 27 已含完整 Khronos 头，无需单独下载） |
| volk | ✅ `engine/third_party/volk` | 运行时 `dlopen` 加载 `libvulkan.so`（Android 已提供） |
| stb_truetype | ✅ `engine/third_party/stb` | 片边文字光栅化 |
| 片边字体 | ✅ `engine/resources_product/fonts` | CMake 里 `file(READ ... HEX)` 嵌入二进制 |
| **glslangValidator** | 由本地构建环境提供 | 编译 Vulkan 内核为 SPIR-V；运行包需要包含相应着色器 |
| LibRaw | 已接入 0.22.2 | 源码默认来自工作区 deps；当前 Android 构建未启用 OpenMP |

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
- **内存**：全分辨率的活跃图面、GPU 驱动和 CPU 编码都占用内存；保留 full 输出尺寸，先降低无用复制和生命周期重叠。设备能力必须实测，不能靠降低画质冒充优化。
- **线程**：引擎 C ABI 里 `spk_cancel` 可跨线程；渲染本身单线程单飞。Kotlin 侧用 `Dispatchers.IO` + 单飞调度。

## 对比度遮罩（contrast mask）

Windows/Vulkan 后端**尚未实现** contrast mask（`engine/src/pipeline/windows_unported.cpp` 明确拒绝）。
Android 走的是同一条非 Apple 分支，因此不能将尚不支持的内核作为已实现功能开放。
