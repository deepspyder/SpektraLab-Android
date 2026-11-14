# 全尺寸 RAW 导入与导出性能记录

验证日期：2026-11-11。本次保持导出分辨率、RAW 解码参数、浮点精度、胶片与相纸模型、颗粒、光晕、暗角、色彩空间、文件格式和 JPEG 质量设置不变。

## 确认的问题与修改

导出链路包含 RAW 解码、镜头校正、引擎输入、胶片冲印与扫描、编码保存、编辑会话恢复。耗时与卡死并非都来自编码器。

- `RawDecoder` 原先对本地文件也先完整复制到缓存。现在本地路径与可寻址的普通文件描述符直接供 LibRaw 同步读取；管道或不能直接读取的来源仍安全暂存，并在结束时清理。描述符覆盖整个解码生命周期，管道不会重复请求同一个云端文件。
- 首次使用自定义相机白平衡时，只读取文件头中的相机矩阵，避免为了矩阵先完整解码一次；缺少矩阵的格式继续使用原有回退路径。
- 全尺寸 RGB16 转浮点时去除无意义的四邻域取样，保持原来的 double 除法及 float 舍入。缩放路径不变。
- RAW 解码及缓存暂存支持取消，过期的照片选择不再继续完整解码。
- Vulkan 缓冲池不再将数百 MB 的空闲图像缓冲借给很小的请求；在分配边界回收超过保留预算的无引用临时缓冲。当前活跃缓冲、持久输入和渲染结果不参与回收。
- 中性调色的 TIFF 按 32 行直接写出原始 RGB16；JPEG/PNG 使用等价的整数 8 位量化。非中性调色与暗角仍使用原有逐像素算法。
- TIFF/DI 直接编码到尚未公开的 MediaStore 项，避免先写完整临时 TIFF 再复制一次。只有编码完成且未取消才公开；失败时删除未完成文件。JPEG/PNG 保留元数据所需的临时文件流程。
- 大预览转换为 Bitmap 时使用有限行缓冲，JNI 逐行复制 RGBA16，减少全帧托管数组副本。
- 回归时复现了**文件已经保存，但恢复编辑预览再次渲染导致未捕获 OOM 并退出进程**。现在重新打开预览会话，保留已经显示的画面与直方图，下次编辑再按需重建负片。单张、批量和快捷导出均覆盖恢复失败处理。

对应实现：[`RawDecoder.kt`](../../app/src/main/java/com/spektralab/android/engine/RawDecoder.kt)、[`raw_decode.cpp`](../../app/src/main/cpp/raw_decode.cpp)、[`ColorPipeline.kt`](../../app/src/main/java/com/spektralab/android/engine/ColorPipeline.kt)、[`TiffWriter.kt`](../../app/src/main/java/com/spektralab/android/data/export/TiffWriter.kt)、[`ExportPublisher.kt`](../../app/src/main/java/com/spektralab/android/data/export/ExportPublisher.kt)、[`EditorViewModel.kt`](../../app/src/main/java/com/spektralab/android/feature/editor/EditorViewModel.kt)。

## 测试环境与耗时

Android API 37 x86_64 模拟器，Goldfish/GFXStream，主机 RTX 5060，ART 堆上限 192 MiB。没有连接小米 17 Pro 真机。性能对比使用各版本实际 Release 构建中的 native `.so`，装入相同调试签名的可测试 APK；避免将未优化 Debug native 与 Release native 混合比较。交付 APK 是项目原签名的 Release 构建。

24MP Nikon Z6II NEF，全尺寸 **6064×4040**，实际应用链路（含启动、导入、导出与恢复）：

| 输出 | 修改前 | 修改后 |
| --- | ---: | ---: |
| TIFF | 17.315 秒 | 14.440 秒 |
| JPEG | 15.243 秒 | 14.301 秒 |

这些是单次模拟器采样，未控制缓存与温度，不能作为手机加速比例承诺。修改后的 TIFF 各阶段采样为 RAW 解码 2669 ms、镜头校正 1752 ms、引擎输入 1000 ms、渲染 1191 ms、编码发布 649 ms；剩余时间包含启动、导入预览、调度和恢复。当前样本解码与镜头校正仍占较大比例。

50MP Xiaomi DNG（文件扩展名为 `.tiff`，按 DNG 识别），全尺寸 **6144×8192**：冷启动模拟器后 TIFF 用例 16.918 秒、JPEG 用例 15.401 秒，均成功保存。JPEG 采样中 RAW 解码 5723 ms、引擎输入 1167 ms、渲染 1866 ms、编码发布 1217 ms。

连续回归后的首次 50MP TIFF 测试曾在渲染阶段超过 240 秒，随后模拟器系统服务失去响应；当时尚未进入编码保存。保留了失败日志，冷启动后同一安装包通过 TIFF 与 JPEG。该现象没有稳定复现，不能据此认定真机驱动有问题，也不能将本轮结果描述为所有设备的卡死均已解决。

## 内存口径与限制

24MP 无镜头校正的直接引擎测试，渲染完成时的引擎缓冲账本由 **2,764,961,464 字节降至 923,519,084 字节**。这是该时间点的保留量，**不是活跃内存峰值**。

同一样本的临时缓冲活跃高水位仍为 **2,057,879,232 字节**；50MP 的对应数值为 **4,227,858,672 字节**。手机采用共享内存，不能把这些模拟器账本值直接当成手机独立显存或进程 PSS。PSS 也不能覆盖全部主机 GPU 驱动分配。

64 MiB 是分配边界的空闲池保留目标，不是所有 GPU 分配的硬上限。全局光晕、模糊等阶段仍需活跃的整图缓冲。本轮没有通过降低精度、缩图、关闭效果或改变胶片模型绕过这个限制。

另测现有引擎的 256/512 行条带执行，输出相同，但活跃高水位不降，渲染比整图慢，因此没有启用该选项。进一步降低活跃峰值需要针对全局阶段设计真正有界的执行与中间存储策略，并重新验证效果一致性。

Android 缓冲池适配由 [`AndroidVulkanMemory.cmake`](../../app/src/main/cpp/cmake/AndroidVulkanMemory.cmake) 生成共享 Vulkan 源码的本地构建副本，策略在 [`vulkan_memory_policy.hpp`](../../app/src/main/cpp/vulkan_memory_policy.hpp)。未修改桌面引擎检出目录。上游替换锚点缺失或不唯一时构建直接失败，要求人工核对兼容性。

## 画质与功能回归

- 24MP 原始版本与最终版本的完整 TIFF 均为 146,991,552 字节，SHA-256 完全一致：`aca76211e0e72a4a83266d54b765135c54a8b578f7774ee3b9043e36dade004f`。整图、256 行、512 行执行均匹配同一参考。
- 穷举 65,536 个 16 位样本，确认中性 TIFF 保留原值，8 位量化与原算法一致；验证最后不足 32 行的条带。
- sRGB/Display P3、中性/非中性调色及暗角的预览像素和直方图与原转换一致。
- 文件、可寻址描述符与管道 RAW 输入的像素和元数据一致；验证临时文件清理、取消、白平衡以及快速切换照片。
- 13 组专项与集成回归通过：`export-actions`、`export`、`batch-selection`、`editing-feedback`、`raw-input-access`、`raw-cancellation`、`export-encoding`、`nef-import`、`export-memory`、`native`、`effect-baselines`、`import-selection`、`smart-develop`。包含 149 项 native 检查、4 项效果检查、6 份 Z6II NEF、12 种格式/元数据组合、HD 预览后保存与继续编辑。
- Debug、测试 APK、签名 Release 构建通过；包边界与 diff 空白检查通过。arm64-v8a 完成编译，运行验证来自 x86_64 模拟器。

专项入口见 [`FullResolutionExportAudit.kt`](../../app/src/androidTest/java/com/spektralab/android/FullResolutionExportAudit.kt)、[`ExportEncodingAudit.kt`](../../app/src/androidTest/java/com/spektralab/android/ExportEncodingAudit.kt)、[`RawInputAccessAudit.kt`](../../app/src/androidTest/java/com/spektralab/android/RawInputAccessAudit.kt)、[`RawCancellationAudit.kt`](../../app/src/androidTest/java/com/spektralab/android/RawCancellationAudit.kt)。测试期间修正了测试管道 Provider 的独立进程依赖和测试读取整份 TIFF 的大数组分配，没有删除相应断言。相机原片与详细日志保持本地，不加入应用包或 Git。

## 复测

将 24MP 样本放到应用 external files 的 `functional-audit/perf-z6ii.NEF`；将上述参考哈希写入同目录 `full-export-reference.sha256`，再运行：

```powershell
adb shell am instrument -w -e audit full-export-perf com.spektralab.android.test/com.spektralab.android.FunctionalAuditInstrumentation
adb shell am instrument -w -e audit large-export -e input perf-50mp.tiff -e format JPEG com.spektralab.android.test/com.spektralab.android.FunctionalAuditInstrumentation
```

第二条需要先将相应 50MP 原片放到同一目录。`-e format TIFF` 测试 TIFF。测试 APK 不随正式版发布。

真机问题需要保留导出前到卡住时的日志：

```powershell
adb logcat -v threadtime -s SpkExport SpkRaw SpektraLab-Raw AndroidRuntime libc
```

[`ExportDiagnostics.kt`](../../app/src/main/java/com/spektralab/android/data/export/ExportDiagnostics.kt) 记录阶段耗时与托管/native 堆，不记录照片路径、像素或 EXIF/GPS。这些日志可以区分解码、渲染、编码和预览恢复；GPU 设备丢失还需结合对应设备的系统日志定位。
