# 放大机、调色性能与图库批量移除专项

本轮工作始于 2026-11-13，最终核对于 2026-11-14。

## 结论与边界

本轮解决应用显示阶段的主要耗时，新增图库多选批量移除。没有降低预览分辨率、修改光谱模型或材料参数，也没有降低 RAW 解码、导出尺寸、位深或编码质量。

性能数据来自同一 Android 模拟器，1600×1200 输入、相同参数和发布原生库。每项热身后测量三次，表中为中位数；不代表 ARM 真机帧率、长时间温控或严谨的尾延迟。性能宿主为 debug Kotlin 加 release native 的测试包。最后一次布局修订只涉及两个图库 UI 文件，六项原生库与该测量宿主逐字节相同。

## 原因与实现

1. 放大机纯原生重印基线约 40–42 毫秒，但之后的显示调色、像素转换和直方图统计继续串行消耗约 80–270 毫秒。基础调色在 Kotlin 中逐像素执行，复杂调色还需将 RGB 浮点条带从 JNI 搬回，再遍历量化。
2. sRGB 显示改为原生分块中一次完成调色、ARGB 量化和直方图。沿用原操作次序；高光权重保留原标量分组，禁止融合乘加。NaN 和越界量化保持安全，避免非法直方图下标。Display P3 继续使用既有颜色连接器，16 位 TIFF 保留原浮点路径。
3. 大于等于一百万像素的非中性显示最多使用一个额外后台任务，应用全局限额为一；没有额外名额或只有一个可用处理器时在调用线程顺序处理。条带最多 32 行，并受一百万像素上限约束。工作线程不写 Bitmap，取消时先收完当前小块。
4. 亮度标量查表改为按需创建，避免新显示路径额外生成未使用的表。工作台缩略图等短暂停顿 120 毫秒再更新，主画面仍发布每个完成帧；导出数据不经过该缩略图。
5. 保留已有请求合并、最新参数最终发布、照片代次和导出互斥逻辑，没有改成连续输入期间不出帧的尾部防抖。

## 全部后期参数

每项包含相同输入上的完整像素摘要对照，52 项全部一致。耗时包括 Bitmap 分配、条带调色、量化、直方图和写入。

| 参数 | 基线 ms | 本轮 ms | 耗时减少 |
|---|---:|---:|---:|
| `neutral` | 42.88 | 15.91 | 62.9% |
| `temperature` | 134.07 | 42.95 | 68.0% |
| `tint` | 115.44 | 35.69 | 69.1% |
| `gradeExposure` | 113.64 | 32.61 | 71.3% |
| `contrast` | 117.72 | 34.31 | 70.9% |
| `brightness` | 112.20 | 31.01 | 72.4% |
| `highlights` | 137.35 | 35.03 | 74.5% |
| `shadows` | 126.75 | 34.57 | 72.7% |
| `saturation` | 117.00 | 29.72 | 74.6% |
| `blackPoint` | 110.09 | 28.89 | 73.8% |
| `whitePoint` | 109.17 | 28.77 | 73.6% |
| `cbShadowTemp` | 140.66 | 29.21 | 79.2% |
| `cbShadowTint` | 133.25 | 28.52 | 78.6% |
| `cbMidTemp` | 135.16 | 28.41 | 79.0% |
| `cbMidTint` | 133.42 | 26.68 | 80.0% |
| `cbHighTemp` | 134.88 | 29.16 | 78.4% |
| `cbHighTint` | 134.52 | 25.42 | 81.1% |
| `vignetteAmount` | 99.58 | 23.58 | 76.3% |
| `vignetteSize` | 98.04 | 24.70 | 74.8% |
| `vignetteFeather` | 102.13 | 28.10 | 72.5% |
| `vibrance` | 90.53 | 47.84 | 47.2% |
| `curve.rgb` | 78.94 | 39.08 | 50.5% |
| `curve.luma` | 79.46 | 39.75 | 50.0% |
| `curve.red` | 82.63 | 38.09 | 53.9% |
| `curve.green` | 80.52 | 40.63 | 49.5% |
| `curve.blue` | 79.38 | 40.63 | 48.8% |
| `hsl.RED.hue` | 159.12 | 90.34 | 43.2% |
| `hsl.RED.saturation` | 157.55 | 91.48 | 41.9% |
| `hsl.RED.luminance` | 156.30 | 98.75 | 36.8% |
| `hsl.ORANGE.hue` | 157.15 | 110.19 | 29.9% |
| `hsl.ORANGE.saturation` | 155.90 | 89.11 | 42.8% |
| `hsl.ORANGE.luminance` | 157.34 | 103.41 | 34.3% |
| `hsl.YELLOW.hue` | 156.36 | 119.50 | 23.6% |
| `hsl.YELLOW.saturation` | 155.56 | 92.57 | 40.5% |
| `hsl.YELLOW.luminance` | 156.79 | 117.86 | 24.8% |
| `hsl.GREEN.hue` | 154.60 | 92.23 | 40.3% |
| `hsl.GREEN.saturation` | 157.29 | 83.73 | 46.8% |
| `hsl.GREEN.luminance` | 158.58 | 82.76 | 47.8% |
| `hsl.AQUA.hue` | 167.41 | 92.58 | 44.7% |
| `hsl.AQUA.saturation` | 157.20 | 85.01 | 45.9% |
| `hsl.AQUA.luminance` | 155.29 | 86.17 | 44.5% |
| `hsl.BLUE.hue` | 169.64 | 96.27 | 43.2% |
| `hsl.BLUE.saturation` | 156.41 | 81.32 | 48.0% |
| `hsl.BLUE.luminance` | 156.47 | 82.13 | 47.5% |
| `hsl.PURPLE.hue` | 157.11 | 83.31 | 47.0% |
| `hsl.PURPLE.saturation` | 157.41 | 83.86 | 46.7% |
| `hsl.PURPLE.luminance` | 156.21 | 82.26 | 47.3% |
| `hsl.MAGENTA.hue` | 157.21 | 83.50 | 46.9% |
| `hsl.MAGENTA.saturation` | 158.55 | 83.88 | 47.1% |
| `hsl.MAGENTA.luminance` | 159.12 | 80.90 | 49.2% |
| `combined.legacy` | 179.07 | 42.26 | 76.4% |
| `combined.curve-color` | 268.84 | 150.41 | 44.1% |

## 连续拖动

每项连续输入 30 次，间隔 32 毫秒。后面的用例保留前面累积的调色，放大机项因此同时带有对比度、亮度与曲线。每项最终画面与独立的原逐像素实现完全一致，最终参数与保存记录一致。

| 参数 | 首帧 基线→本轮 ms | 拖动中帧数 基线→本轮 | 松手完成 基线→本轮 ms |
|---|---:|---:|---:|
| `contrast` | 217.6 → 125.9 | 7 → 18 | 112.8 → 58.2 |
| `brightness` | 177.9 → 86.2 | 6 → 17 | 228.4 → 18.4 |
| `curve` | 180.1 → 111.2 | 6 → 10 | 283.2 → 122.3 |
| `printExposure` | 206.4 → 174.8 | 4 → 5 | 362.3 → 194.2 |
| `exposureEv` | 310.9 → 240.5 | 3 → 3 | 664.6 → 288.2 |

跨轮次的放大机图像摘要可能不同：原引擎 `Pipeline::print_prefix` 为每次重印的随机光晕调用 `fresh_seed`，更快的调度会完成不同数量的重印。本轮未为对齐摘要关闭光晕或修改随机合同；显示精确性对照使用同一原生底图，材料与确定性重印另有完整 U16 基准。

## 原生参数与材料耗时

以下 88 项是参数同步加原生渲染的独立记录，不包含显示后期；原生引擎模型本轮未修改。采样波动保留，不能把这些数字解读为每一个底层参数都提速。

| 参数 | 基线 ms | 本轮 ms | 缓存负片重印 |
|---|---:|---:|---|
| `film.fujifilm_c200` | 151.91 | 173.42 | 否 |
| `film.fujifilm_neopan_acros_100_ii` | 146.45 | 151.89 | 否 |
| `film.fujifilm_pro_400h` | 171.93 | 152.15 | 否 |
| `film.fujifilm_provia_100f` | 129.25 | 132.08 | 否 |
| `film.fujifilm_velvia_100` | 134.03 | 141.11 | 否 |
| `film.fujifilm_xtra_400` | 138.70 | 151.70 | 否 |
| `film.ilford_hp5_plus_400` | 138.72 | 146.83 | 否 |
| `film.kodak_ektachrome_100` | 128.21 | 145.14 | 否 |
| `film.kodak_ektar_100` | 144.86 | 178.97 | 否 |
| `film.kodak_gold_200` | 149.60 | 153.98 | 否 |
| `film.kodak_kodachrome_64` | 139.51 | 142.73 | 否 |
| `film.kodak_portra_160` | 140.44 | 159.27 | 否 |
| `film.kodak_portra_400` | 41.13 | 42.99 | 是 |
| `film.kodak_portra_800` | 152.58 | 155.42 | 否 |
| `film.kodak_portra_800_push1` | 151.10 | 153.60 | 否 |
| `film.kodak_portra_800_push2` | 147.60 | 152.18 | 否 |
| `film.kodak_tmax_100` | 140.57 | 146.03 | 否 |
| `film.kodak_tri_x_400` | 138.28 | 143.91 | 否 |
| `film.kodak_ultramax_400` | 146.44 | 151.68 | 否 |
| `film.kodak_verita_200d` | 151.27 | 153.97 | 否 |
| `film.kodak_vision3_200t` | 147.33 | 154.47 | 否 |
| `film.kodak_vision3_250d` | 147.61 | 147.79 | 否 |
| `film.kodak_vision3_500t` | 142.05 | 172.16 | 否 |
| `film.kodak_vision3_50d` | 144.57 | 148.85 | 否 |
| `paper.fujifilm_crystal_archive_typeii` | 70.80 | 75.44 | 是 |
| `paper.ilford_multigrade_iv_rc` | 144.14 | 151.13 | 否 |
| `paper.kodak_ektacolor_edge` | 71.25 | 76.78 | 是 |
| `paper.kodak_endura_premier` | 72.18 | 79.81 | 是 |
| `paper.kodak_portra_endura` | 41.35 | 43.11 | 是 |
| `paper.kodak_supra_endura` | 72.66 | 72.13 | 是 |
| `paper.kodak_ultra_endura` | 72.75 | 74.03 | 是 |
| `paper.kodak_2383` | 70.95 | 73.79 | 是 |
| `paper.kodak_2393` | 67.22 | 72.50 | 是 |
| `exposureEv` | 129.04 | 140.79 | 否 |
| `autoExposure` | 127.76 | 136.10 | 否 |
| `autoExposureMethod` | 133.21 | 147.82 | 否 |
| `filmFormatMm` | 138.02 | 141.89 | 否 |
| `cameraFilter` | 144.04 | 145.05 | 否 |
| `printExposure` | 42.10 | 42.92 | 是 |
| `yFilterShift` | 41.93 | 42.26 | 是 |
| `mFilterShift` | 40.35 | 42.84 | 是 |
| `grainActive` | 125.51 | 128.14 | 否 |
| `grainAmount` | 140.91 | 143.61 | 否 |
| `grainLayered` | 136.38 | 139.99 | 否 |
| `halationActive` | 107.94 | 110.73 | 否 |
| `halationAmount` | 136.32 | 140.24 | 否 |
| `halationScatterAmount` | 136.98 | 139.52 | 否 |
| `halationBoostEv` | 137.80 | 141.89 | 否 |
| `antihalationRemoved` | 141.56 | 143.02 | 否 |
| `dirCouplersActive` | 106.13 | 118.92 | 否 |
| `dirCouplersAmount` | 140.88 | 141.73 | 否 |
| `glareActive` | 61.69 | 62.35 | 是 |
| `glareAmount` | 64.22 | 65.60 | 是 |
| `preflashExposure` | 40.79 | 41.22 | 是 |
| `extendedDynamicRange` | 65.27 | 67.80 | 是 |
| `quarterTurns` | 283.06 | 291.60 | 否 |
| `flipH` | 269.51 | 311.53 | 否 |
| `flipV` | 270.17 | 313.98 | 否 |
| `rotationDeg` | 301.70 | 311.39 | 否 |
| `crop` | 232.83 | 247.79 | 否 |
| `overscanActive` | 337.30 | 353.70 | 否 |
| `overscanFormat` | 220.83 | 213.87 | 否 |
| `overscanMode` | 304.55 | 304.60 | 否 |
| `overscanGate` | 338.77 | 353.73 | 否 |
| `overscanHoles` | 329.90 | 348.34 | 否 |
| `overscanCarrier` | 335.61 | 370.77 | 否 |
| `overscanFog` | 331.58 | 380.90 | 否 |
| `overscanLeaks` | 329.30 | 403.88 | 否 |
| `overscanFNumber` | 327.25 | 395.10 | 否 |
| `overscanFrameNumber` | 336.20 | 365.25 | 否 |
| `overscanCameraSeed` | 328.45 | 343.02 | 否 |
| `overscanFrameSeed` | 325.26 | 377.96 | 否 |
| `dateImprintActive` | 157.80 | 166.53 | 否 |
| `dateImprintStyle` | 161.33 | 161.75 | 否 |
| `dateImprintPlacement` | 330.75 | 353.68 | 否 |
| `dateImprintCorner` | 160.37 | 163.85 | 否 |
| `dateImprintSize` | 161.73 | 171.04 | 否 |
| `dateImprintEv` | 163.51 | 165.12 | 否 |
| `dateImprintText` | 162.62 | 157.86 | 否 |
| `dateImprintDataText` | 332.59 | 352.70 | 否 |
| `sceneLatitude` | 149.65 | 168.55 | 否 |
| `sceneLatitudeHighlightKnee` | 159.67 | 182.01 | 否 |
| `sceneLatitudeHighlightRoom` | 155.15 | 161.25 | 否 |
| `sceneLatitudeShadowKnee` | 159.29 | 162.80 | 否 |
| `sceneLatitudeShadowRoom` | 153.51 | 162.97 | 否 |
| `sceneLatitudeRolloff` | 149.39 | 164.11 | 否 |
| `sceneLatitudeMaxLift` | 157.87 | 176.57 | 否 |
| `sceneLatitudeNorm` | 159.72 | 163.09 | 否 |

## RAW 调整耗时

| 参数 | 本轮端到端中位数 ms |
|---|---:|
| `cameraWbCustom` | 1753.5 |
| `cameraWbKelvin` | 1791.1 |
| `cameraWbTint` | 1792.4 |
| `lensCorrection` | 1380.9 |

RAW 相机白平衡仍需解码与原生处理，没有伪装成即时后期；真实设备、持续操作与 50MP 容量验收仍保留。

## 图库多选与安全移除

- 顶部“选择”和照片长按均可进入多选。轻点切换选择，全选覆盖当前筛选下未加载的页面；单次最多 5000 张，超过时提示先筛选。系统返回先退出多选。
- 底部显示明确数量，提交前确认仅从应用图库隐藏。原片、编辑、虚拟版本、相册归属和工作台保持；单张菜单复用同一入口。
- DB v4 升为 v5，只增加 `hidden_photos`。移除在一个事务中核对资产、变体、URI 和修订，任何过期项均整批拒绝。列表、筛选数量和可见相册数量排除隐藏记录。
- 普通自动保存不恢复可见性。只有明确的图片选择器导入会恢复同 URI 的记录；导航、工作台恢复和迟到保存不恢复。已有版本与源图路由仍可读取隐藏记录。
- 130 条隔离记录覆盖迁移、分页、组合筛选、过期批次、写入故障回滚、重开和重新导入；原文件逐字节保留。66 条真实界面记录覆盖跨页全选、取消确认、移除、空态、重新导入、无障碍长按和系统返回。

## 画质与回归证据

- 核心 25 组、40 条结构化记录全部通过，明细见下表。
- 分块精确对照 10,853,790 个通道样本，另有标量 10,223,616 个样本和亮度 1,572,864 个样本精确对照；直方图、非整条带、35003 像素宽全景、取消、非法直方图长度和 NaN 安全通过。
- 24 种胶片、9 种相纸的 31 个兼容组合确定性 U16 基准全部通过，没有跳过配置。94 项引擎资源与任务前安装包逐字节相同。
- 6064×4040 RAW 的 16 位 TIFF 保持 146,992,152 字节。调色摘要 `62f1d58c709a4bc694ca93f8d552954320f921260fc64dd4c013bf4461926f3f`，中性摘要 `3c7e8eee7848fa80cafd64faad1b3c2dd6d90b9de6ecee316d079f0da85fc8d3` 均保持。
- 手机、大字体手机、平板和横屏使用原生生产图库截取相同五种状态；初次复核指出大字体工具栏与长文件名断行，后续只修改该处并重新验证。布局修订后的5组10条图库记录全部通过；4种配置的完整流程及20张截图全部重新验证。独立复核对两项既定修订给出 resolved，限定范围结论 ship；详见 `.tmp/render-library-performance/verification.json` 和 `ui-finish-verdict.md`。

| 核心测试组 | 记录数 | 结果 |
|---|---:|---|
| `batch-selection` | 1 | PASS |
| `changed-params` | 1 | PASS |
| `display-strip` | 1 | PASS |
| `edit-versions-storage` | 1 | PASS |
| `editing-boundaries` | 8 | PASS |
| `editing-feedback` | 1 | PASS |
| `enlarger` | 3 | PASS |
| `export-actions` | 1 | PASS |
| `export-cache-raw` | 1 | PASS |
| `export-encoding` | 1 | PASS |
| `export` | 1 | PASS |
| `image-quality-materials` | 1 | PASS |
| `image-quality` | 1 | PASS |
| `import-selection` | 1 | PASS |
| `mixer` | 2 | PASS |
| `photo-organization-storage` | 1 | PASS |
| `photo-selection` | 2 | PASS |
| `render-scheduling` | 1 | PASS |
| `saved-storage` | 1 | PASS |
| `source-recovery-storage` | 1 | PASS |
| `stock-comparison` | 1 | PASS |
| `stock-interaction` | 1 | PASS |
| `table-output` | 4 | PASS |
| `versions-raw` | 1 | PASS |
| `wheels` | 2 | PASS |

## 产物与后续

最终签名发行构建保持 0.1.5，versionCode 6；原更新证书及 V1、V2、V3 均验证通过。APK SHA-256：`486182a620d0bb1f611d5692ca47a89032bce5c5fd3d7a49edc3b1191edb52aa`。本轮没有提交、推送或发布新版本。

原始报告位于 `.tmp/render-library-performance/`，核心回归位于 `.tmp/enlarger-audit/render-library-final/`，原生截图位于 `.impeccable/review/library-removal/`。这些是本地证据，不包含进度之外的发布承诺。

F10 未在本轮推进。F07 的批量评分色标、相册排序、统一源新鲜度、长期授权与恢复边界，以及 ARM 真机、温控、50MP 容量和底层 IIR 等既有剩余项继续保留。
