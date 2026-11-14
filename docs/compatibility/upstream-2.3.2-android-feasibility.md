# SpektraLab 2.3.2 新原型：Android 功能可行性分析

分析日期：2026-11-11。基于本地源码静态分析，没有在本轮重新编译或运行 Mac/Android 应用。

参照仓库：`E:\Code\SpektraLab\SpektraLab`，HEAD `1952c63`，2026-11-10，2.3.2。
对照安卓仓库：`E:\Code\SpektraLab\SpektraLab-Android`（Theia），HEAD `2a6adef`；包含当前未提交修改。
安卓实际依赖的共享引擎：`E:\Code\SpektraLab\SpektraLab-Windows\engine`，也有本地修改。

## 判断

多数照片处理、胶片模拟、图库和排版功能可以在 Android 本地实现，不需要因为移植而引入云端渲染。现有 Theia 已经完成相当一部分基础能力，适合继续增量完善。

需要重做的是 Apple 专属界面、解码、GPU 和文件编码接入；需要重点验证的是手机内存、GPU 驱动、RAW 色彩以及 HDR 文件和显示兼容性。不能把“功能可实现”当成“当前安卓已经实现”或“与 Mac 输出一致”。

这个仓库同时保留历史资料、Windows 移植和现行 Mac 应用。现行应用位于 `app/SpektraLab`，采用 Mac Catalyst / SwiftUI / UIKit；`modern_UI` 的 1.x 描述不能作为当前功能事实。

## 功能与安卓现状

| 功能 | 新原型的能力 | 当前安卓源码状态 | 安卓实施判断 |
| --- | --- | --- | --- |
| 胶片与相纸物理模拟 | 光谱曝光、染料密度、印放和扫描，彩色负片/反转片/黑白 | 已有 C++20/Vulkan/JNI 路径 | 继续复用物理模型；新增材质需连同资源、兼容规则和测试同步 |
| RAW 与普通图片 | Core Image 解码、白平衡、镜头校正 | 已有 LibRaw、URI 输入、白平衡及镜头校正接口 | 可以实现；Core Image 与 LibRaw 的解码结果不能默认一致，相机支持须逐型号验证 |
| 颗粒、光晕、散射、眩光、DIR | 物理尺度的胶片效果 | 已有大部分效果 | 继续扩展；最新 CineStill 高光余量等参数尚未与安卓逐项对齐 |
| 场景宽容度 | 引擎有相关能力；当前 Mac 页面改用 Decode 曲线 | 安卓已有测量及映射参数接入 | 现有功能无需重做，避免照旧文档误判为缺失 |
| Decode 曲线 | 在进入胶片之前修改场景曲线 | 未见 `scene_curve` 的安卓参数接入 | 值得新增；必须放在胶片曝光之前，不能用冲印后的曲线代替 |
| 基础成片调色 | 曝光、亮度、反差、影调、色温、暗角 | 已有基础滑杆及三段色彩平衡 | 继续完善现有成片处理层 |
| 完整成片编辑 | 曲线、八色 HSL、通道混合、色轮、自然饱和度 | 未见完整对应工具 | 优先补齐；把算法移植到统一处理层，再接 Compose 控件 |
| 两级裁剪 | 冲洗前裁剪底片、冲洗后裁剪成片 | 已有几何/裁剪；开片边时当前入口限制裁剪 | 可实现；要区分两个裁剪状态，解决片边与构图的配合 |
| 胶片片边与日期后背 | 多画幅、齿孔、片边字、日期曝光 | 安卓已有参数、界面和 Vulkan 内核接入 | 属于已有能力的完善工作，不是从零新增；不要降为普通贴纸 |
| 半格拼接 | 两张原片、独立曝光/白平衡/构图/印放，作为图库项目编辑导出 | 未见安卓完整的 pair 模型和编辑流程 | 可实现，复杂度中高；观景台靠边吸附不等于半格拼接 |
| 图库 | 相册、评级、色标、变体、筛选、目录/会话、批量同步 | 有多图会话、每图 sidecar、设置复制；未见完整目录数据库 | 推荐新增手机图库模型，用 Room/SQLite + URI 身份 + 应用私有存储承载 |
| 观景台 | 按毫米放置胶片、布局管理、高分辨率读取与排版输出 | 已有拖放、缩放、吸附、透光、灯光和放大镜 | 优先补持久布局、独立旋转、海报/接触印样导出；具体差异需在实现时逐项验收 |
| 导出与配方 | JPEG/HEIF/PNG/16 位 TIFF，多输出色域、批量和元数据 | 已有 JPEG/PNG/TIFF、DI TIFF、批量、配方、EXIF/GPS；当前普通 JPEG/PNG 固定 sRGB，TIFF 提供 sRGB/P3 | 扩充 Adobe RGB/ProPhoto、软打样和预览/导出一致性；色域变换不能只换 ICC 标签 |
| HEIF | ImageIO 编码 | 未见安卓对应格式入口 | 可以实现，需适配 Android 编码 API 并检测设备编码能力 |
| HDR 显示与增益图文件 | HDR 扫描、会话增益曲线、gain map 和多种容器声明 | 未见完整安卓 HDR 接入；现有 EDR 参数不等于 Ultra HDR | 可以实现，工作量高；按系统和屏幕能力开放，保持 SDR 路径 |
| 全分辨率细节 | 低分辨率预览、按需完整渲染、瓦片和缓存共享 | 已有预览/全分辨率导出及内存策略，未见对等的完整瓦片体系 | 优先级高；属于后续大图、拼接和观景台导出的基础 |
| 对比度遮罩 | 暗房物理过程中的 contrast mask；不是局部画笔蒙版 | 共享 Vulkan 实现仍明确拒绝该能力 | 可以移植，但需要补 GPU/管线实现，不能仅增加一个 UI 开关 |
| Capture One 读取 | 只读快照目录，应用自己的镜像保存编辑 | 未见安卓对应接入 | 理论可行，优先级低；需重做目录授权、路径映射和目录解析；不能使用仓库里的 macOS helper 二进制 |
| CLI/MCP | 驱动运行中的 Mac 应用 | 未见安卓对应入口 | 自动化操作可以设计为 Intent/受控应用接口；桌面命令行进程模型不宜直接搬来 |
| 多语言 | 英文、简中、繁中、日文 | 当前界面仍有大量中文直接文案 | 可以实现，先资源化字符串再补翻译 |
| 后台完成导出 | Mac 的 `backgroundFinish` 明确为 false | 安卓未声明对应前台服务 | 属于需要新增的能力；Android 可评估前台服务/长任务方案，不能承诺任意时长无条件运行 |

## 不能直接替换共享引擎

当前安卓 CMake 默认引用兄弟目录 `SpektraLab-Windows/engine`，并明确编译了 `view_process.cpp`、`overscan.cpp`、`imprint_raster.cpp` 及对应 Vulkan 着色器。

新仓库 `engine/src/pipeline/engine.cpp:342` 开始的非 Apple 能力清单，仍列出未移植的 `digital_intermediate`、`scene_latitude_mapping`、`contrast_mask`、`overscan`、`date_imprint`、`scan_hdr`。这与当前安卓使用的、已经补过部分能力的共享引擎不同。

因此，直接把 `SPEKTRA_ENGINE_DIR` 改向新仓库，会存在丢失既有能力、缺文件、接口路径改变、资源不同步及着色器不匹配的风险。新仓库的公开头文件目录也已使用 `spektralab`，而旧接入仍有 `spektrafilm` 路径。

推荐以现有安卓引擎为可运行基线，逐项移植新增核心逻辑、参数、资源和 Vulkan 内核。每项分别确认 schema、shoot/print 失效规则、资源版本和输出；不要一次合并全部管线变化。

另一个需要分开决策的变化是 minilab：新 Mac 默认的 Portra 400 / Portra Endura 使用特定组合的校准表，其他组合保留光学印放；不能将其理解为所有胶片/相纸都已有相同校准支持。引入它可能改变默认成片，需单独做视觉与数值比较。

## 建议的实现顺序

1. **补成片编辑能力。** 曲线、HSL、自然饱和度、通道混合和色轮，沿用现有撤销、每图保存和批量复制机制。预览、1:1 细节和导出共享同一算法顺序，避免只在预览有效。新功能优先使用 GPU/原生处理，不能把大图交互建立在逐像素 Kotlin 循环上。
2. **补观景台成片输出。** 保存布局、毫米坐标、旋转、海报与接触印样输出；导出时重新取得所需分辨率的照片，不能直接截屏当成品。
3. **做完整半格项目。** 独立保存两张原片引用及各自编辑，处理原片丢失、重定位、交换左右、单张与整条胶片作用范围；预览和导出使用同一布局。
4. **补图库。** 持久会话、相册、评级、色标、筛选和虚拟变体。变体优先复用原片而只复制编辑参数，避免复制大 RAW。
5. **升级解码/渲染基础。** Decode 曲线、完整细节瓦片、缓存和调度；再逐项移植最新光晕、minilab、输出色域和软打样。
6. **专项处理 HDR/HEIF。** 先做好 SDR 与色彩链，再增加系统能力判断、HDR 增益图、预览和文件验证。
7. **后置桌面集成。** Capture One、MCP/CLI 不应先于手机拍照与修图主流程。

## 安卓边界与验证重点

- **内存。** 45 MP 的 RGB float32 单缓冲约为 540 MB（约 515 MiB），尚未计 RAW 解码、输出、纹理和临时数据。半格/批量不能同时保留多张全尺寸缓冲；应按需解码、串行处理、限制预览尺寸，并在空间操作分块时保留足够 halo。这是数据量计算，不是本轮测得的峰值。
- **图像一致性。** 固定输入、参数和色彩空间验证；确定性比较关闭随机效果或固定种子。随机颗粒要检查统计特性。不得为了让安卓图像“看起来一致”而改写实测材质数据。
- **RAW。** “支持 RAW”不等于所有相机压缩格式均可解码；LibRaw 与 Core Image 的白平衡、去马赛克和高光策略须分别核对。
- **HDR。** Android 14 起的平台 Ultra HDR 支持给出实现入口；是否有 HDR 观感还取决于设备显示能力。HDR 文件、屏幕显示、胶片 HDR 扫描与现有 EDR 参数是不同层次，不能混用名称。
- **后台。** Android 官方说明长时间 Worker 可以使用前台服务，但 Android 16 起仍可能消耗任务配额；按实际导出任务选机制，并实现取消、进度和恢复。
- **文件。** Android 的共享存储不能按桌面绝对路径模型处理。目录连接使用用户授权的 SAF/URI，并处理权限失效；Android 11 起部分根目录和 `Android/data`、`Android/obb` 等目录受选择限制。
- **完成标准。** 控件有实际管线效果、每图持久化和撤销可用、预览/导出一致、原图不被覆盖、失败能恢复，再做实际手机上的耗时、内存和显示验证。此次分析没有重新运行此前文档记录的验收测试。

## 核心源码依据

- [新原型入口与两层编辑](E:/Code/SpektraLab/SpektraLab/ARCHITECTURE.md:328)：当前 Mac 产品结构、处理顺序、两级裁剪、色彩链和半格模型。
- [原型功能开关](E:/Code/SpektraLab/SpektraLab/app/SpektraLab/Sources/Model/FeatureFlags.swift:12)：片边、日期、半格已开放；后台完成未实现。
- [原型调色模型](E:/Code/SpektraLab/SpektraLab/app/SpektraLab/Sources/Model/Adjustments.swift:133)：HSL、通道与色彩模型。
- [原型导出配方](E:/Code/SpektraLab/SpektraLab/app/SpektraLab/Sources/Model/ExportRecipe.swift:26)：格式、输出色域、尺寸和 HDR 布局。
- [原型图库数据说明](E:/Code/SpektraLab/SpektraLab/app/SpektraLab/MAC-LIBRARY-DATA.md:65)：相册、评级、筛选、变体与目录来源。
- [新引擎非 Apple 能力边界](E:/Code/SpektraLab/SpektraLab/engine/src/pipeline/engine.cpp:342)。
- [安卓引擎实际接线](E:/Code/SpektraLab/SpektraLab-Android/app/src/main/cpp/CMakeLists.txt:9)。
- [安卓现有编辑参数](E:/Code/SpektraLab/SpektraLab-Android/app/src/main/java/com/spektralab/android/core/model/EditorParams.kt:384)。
- [安卓基础调色页面](E:/Code/SpektraLab/SpektraLab-Android/app/src/main/java/com/spektralab/android/feature/editor/ui/panels/GradePanel.kt:30)。
- [安卓片边与日期入口](E:/Code/SpektraLab/SpektraLab-Android/app/src/main/java/com/spektralab/android/feature/editor/ui/panels/PrintPanel.kt:46)。
- [安卓导出页面](E:/Code/SpektraLab/SpektraLab-Android/app/src/main/java/com/spektralab/android/feature/export/ExportScreen.kt:69)。
- [安卓每图编辑持久化](E:/Code/SpektraLab/SpektraLab-Android/app/src/main/java/com/spektralab/android/data/local/EditStore.kt:12)。

平台资料参考入口如下。本轮联网读取没有成功，平台版本条款未完成在线复核；实施时应结合目标系统和设备再次确认：

- Ultra HDR：`https://developer.android.com/media/platform/hdr-image-format`
- HEIF 编码接口：`https://developer.android.com/reference/androidx/heifwriter/HeifWriter`
- 长时间 Worker：`https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running`
- SAF 目录访问：`https://developer.android.com/training/data-storage/shared/documents-files`
