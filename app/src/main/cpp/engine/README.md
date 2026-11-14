# Android 引擎接入

`CMakeLists.txt` 默认编译真实 C++20 引擎，`SPEKTRA_USE_STUB_ENGINE=OFF`。本目录只保存接入说明，实际源码来自构建时的 `SPEKTRA_ENGINE_DIR`，默认指向工作区同级的 `SpektraLab-Windows/engine`。

引擎编译进入 Android 的 `libspektralab.so`，在应用自身进程内执行。手机不访问电脑上的源码、桌面应用或网络服务；共用源码目录本身不增加运行时传输或会话争用。

## Android 一次性导出路径

单张和批量成片导出使用 `EngineClient.renderExportOnce()`，保持 full 层级、计算节点及像素精度：

1. DIR 最后一次读取后释放不再使用的曝光中间图，避免与颗粒阶段叠加驻留。
2. 不为随即关闭的导出会话额外复制完整浮点负片。
3. 保存源图尺寸并完成测光、前处理后，释放一次性导出的源图和测光层级引用；实际负片交给冲印阶段链，最后读取后释放。共享同一缓冲的前处理结果仍由计数句柄保护。
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

F03 第三批的 24MP RAW 测量中，冲印前持久图由 315,103,004 字节降到 672,284 字节，约释放 299.9 MiB。临时池高水位仍为 1401.8 MiB，首次达到该值的节点是 DIR 耦合；这次生命周期改进没有消除更早的全程峰值。完整验证见 `docs/features/f03-batch-03-source-lifetime.md`。

F03 第四批继续在一次性导出中消费 DIR 的两个输入：校正图完成后释放 CMY 输入，合成曝光图后释放校正图和原曝光图。颗粒的密度偏移完成后，也释放末次模糊已不再读取的原颗粒图。所有数学操作仍来自原节点，额外保留释放前的 flush。24MP 关闭颗粒和眩光时临时池高水位降至 1121.5 MiB；默认效果的峰值转移到冲印眩光，仍约 1401.8 MiB。不能将局部节点收益说成默认导出的整体峰值下降。详见 `docs/features/f03-batch-04-dir-lifetimes.md`。

F03 第五批将一次性导出的负片所有权交给冲印阶段链。常量和对比度遮罩准备完成后，`PrintInputScope<Image>` 只允许消费引擎登记的同一个局部对象；别名和未登记的借用输入保持有效。阶段链完成光谱转换后释放负片，使眩光计算不再与已用完的全尺寸负片重叠。作用域按线程隔离，取消及失败恢复登记；普通重印缓存和条带执行保持原路径。24MP 默认效果的临时池高水位由 1401.8 MiB 降到 1121.5 MiB，约减少 20%；这不是进程总峰值，50MP 与真机验收仍保留。详见 `docs/features/f03-batch-05-print-ownership.md`。

F03 第六批在一次性导出中转移光晕输入所有权，避免散射结束后仍保留最初输入；节点体从上游提取，数值与随机语义不变。模糊混合仅登记内部私有累加器，FIR 最后一次逐像素累加和混合 FIR/IIR 的最终线性组合可写回该累加器；邻域输入、外部累加图及 IIR 递推保持原路径。没有更换 shader 或修改材料资源。每个分量原有 flush 保留，其后检查取消，取消不发布部分结果。

24MP 默认效果中，光晕结束时累计临时高水位从约 1121.5 MiB 降到 841.1 MiB，但后续 DIR 扩散仍使全程高水位回到 1121.5 MiB。局部混合测试下降不代表全程或进程峰值下降。下一步需核对 DIR 扩散中的 IIR 工作集及总体峰值；详见 `docs/features/f03-batch-06-halation-mixture.md`。

F03 第七批进一步提前源图释放时机。完整尺寸先交给 pipeline、测光结果先缓存，再清除源图和层级图的多余持有者；`FilmInputScope<Image>` 只在前处理最后读者完成后退休登记的同一局部对象。前处理结果若与源图共享缓冲，仍由自己的计数句柄维持有效。保留 TierState 对象以写回曝光和片边元数据，普通编辑、已有负片缓存和条带路径保持语义。

24MP 默认效果的 DIR 扩散阶段，持久缓冲计数由上批 315,103,004 字节降到 672,284 字节，减少约 299.9 MiB；临时池高水位仍为 1121.5 MiB。这减少了源图与大工作集的重叠，不能把持久缓冲减少量说成临时池或进程总峰值减少量。IIR 递推及全局有界执行仍待完成。详见 `docs/features/f03-batch-07-film-source.md`。

有限核高斯FIR已增加精确分带实现，保留真实图边缘反射、完整邻域、垂直再水平顺序和末次累加。局部工作集减少，但第一轮实际24MP测量中全链路峰值未下降且耗时增加，因此**默认关闭**，仅供研发验证：

    adb shell setprop debug.theia.export_fir_bands 1

完成后恢复为0。生产界面不提供此实验开关；不能将独立模糊节点的内存收益说成完整流水线收益。IIR递推、全局统计和颗粒仍按原语义运行。

独立Float对照程序不会打进APK。构建x86_64 Release后执行：

    python tools/build_export_fir_audit.py --output .tmp/fir-native-audit

将生成程序、同目录原生库和相同Vulkan着色器放到测试设备，运行程序并传入着色器目录。程序覆盖小图、单行图、窄图、不同带高、累加、混合FIR/IIR和取消；要求每个Float逐位相等。实际发布仍必须通过材料与全尺寸输出回归。

DIR 与颗粒的独立验证复用构建入口：

    python tools/build_export_fir_audit.py --audit dir --output .tmp/dir-native-audit

运行 `export-dir-audit` 时传入完整引擎资源目录。它用相同 Vulkan 后端比较原路径和消费路径的完整 Float 负片，覆盖不同材料、FIR/IIR、无扩散、简单及分层颗粒、染料云、微结构与颗粒强度混合。测试编译副本额外注入三个 DIR 内部取消检查点，核对取消后无部分输出或活跃临时图；故障注入只存在于独立程序，不进入 APK。应用公开进度在运行中统一返回 `rendering`，不能通过匹配内部节点名做定点取消。

冲印链的独立验证使用同一入口的 `--audit print`，生成 `export-print-audit`，同样传入完整引擎资源目录。比较量化前的完整 Float 成片，覆盖不同材料、眩光关闭及不同模糊范围、直扫和扫描模糊、未登记的借用输入；另有四个冲印阶段取消检查点、对象地址复用、别名隔离及嵌套作用域恢复检查。测试注入不进入 APK。

混合模糊验证使用 `--audit mixture`，生成 `export-mixture-audit`，传入 Vulkan 着色器目录。覆盖 FIR、IIR、混合通道、零权重、身份分量、边缘、负值、微小值、不同截断范围；直接比较完整 Float，验证原图和公开累加图不变、私有作用域与别名隔离，并注入三个分量末尾取消检查点。光晕完整负片对照复用 `--audit dir` 生成程序，运行时在资源目录后追加 `halation`，覆盖散射、背反射、归一化、镜头模糊及颗粒组合。所有故障注入均不进入 APK。

`--audit dir` 的消费路径同时登记并核对前处理后的源图退休，包含前处理共享同一缓冲的情形；取消要求源图、部分结果和活跃临时图均清除，并检查嵌套作用域与同一对象地址重用。应用 `audit=export-once` 补充自动曝光关闭及裁剪场景，共 38 组完整 U16 对照。

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
