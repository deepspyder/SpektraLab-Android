# 参数渲染与胶片切换性能复核

日期：2026-11-12。基线为前一批胶片/曲线修复后的工作树，基础提交 441305c；不是最初 v0.1.4 的慢版本。

本批解决连续拖动预览饥饿、整份参数使负片误失效和亮度重复幂运算。未减少预览或导出尺寸、位深、JPEG 质量，未关闭产品默认效果，未更换胶片/相纸模型和资源。

## 测量口径

- API 37 x86_64 模拟器 emulator-5554 / Medium Phone，Release 原生库，调试签名应用与 instrumentation；不是小米 17 Pro 真机数据。
- 后期/原生矩阵为1600×1200：52 个后期场景、88 个原生场景（含全部胶片/相纸）。另测4个 RAW 场景（原比例预览1600×1065）、五种连续输入和真实材料切换。普通条目预热后测三次取中位数。
- 后期表包含 Bitmap 和直方图生成；亮度及组合场景每次重建 Grade，包含精确缓存准备。哈希、fixture 重置和参考对照不计时。
- 引擎表包含参数应用、live render 和 JNI 像素传输，不包含后期显示转换及 UI 等待。几何和片边按原功能改变输出尺寸。
- 原生矩阵保留默认颗粒、光晕、DIR 和眩光。胶片交互四款×三轮的可重复对照关闭随机颗粒/眩光，产品默认值未改。
- 连续输入每 32ms 一次、共 30 次，记录预览发布次数及最后一次输入后的处理完成时间；组合场景保留前面已调效果。
- RAW 使用真实 Z6II NEF。自定义白平衡、Kelvin、tint 各三次；命中镜头校正测一次。
- 最终版与基线计时来自冷启动恢复后的同一 AVD/同一次启动（先最终版，后基线），没有并行构建。模拟器调度有波动，数字不能作为所有手机/图片的时延承诺，也不是屏幕 60fps 指标。

## 实现

1. 原来的 45/120ms 尾部去抖在连续滑动时一直取消、重启，导致拖动期间没有新画面。现在每条链路只保留一个工作者，完成当前帧，新输入合并成下一帧目标；胶片离散切换立即启动。
2. 编辑快照按引擎实际参数计算差量，打印曝光/滤色不再因未变的 film_stock、print_stock 和拍摄字段重载材料、重建负片。直接查询当前 native 状态，兼容 solve、换片和导出恢复；低层 setParams 语义保持。
3. 中性预览枚举全部 65,536 个 U16 的原 Float 量化结果；亮度也枚举全部 U16 通道输入，保持原 double pow 和运算次序。没有稀疏采样或插值近似，原生缓存为有界 direct buffer。
4. 常量移出像素循环；未启用的阴影/高光、色彩平衡跳过无效权重。保留代次、照片身份、取消、私有待转换底图和最后参数保存保护。
5. 单张、批量和导出动作在结束后按需续算被打断的预览，防止调完立即导出时最后一次编辑丢在旧画面上；已就绪的画面不无故重建。

## 后期预览转换：毫秒

| 参数/场景 | 基线 | 修复 |
| --- | ---: | ---: |
| neutral | 63.9 | 17.8 |
| temperature | 144.0 | 99.4 |
| tint | 128.7 | 96.8 |
| gradeExposure | 128.8 | 91.1 |
| contrast | 130.9 | 94.8 |
| brightness | 444.6 | 100.7 |
| highlights | 130.6 | 105.0 |
| shadows | 128.7 | 104.9 |
| saturation | 146.1 | 92.0 |
| blackPoint | 130.0 | 93.7 |
| whitePoint | 130.8 | 91.2 |
| cbShadowTemp | 133.6 | 116.6 |
| cbShadowTint | 127.2 | 116.9 |
| cbMidTemp | 139.5 | 117.1 |
| cbMidTint | 139.7 | 121.0 |
| cbHighTemp | 137.6 | 116.2 |
| cbHighTint | 132.7 | 114.2 |
| vignetteAmount | 85.6 | 82.0 |
| vignetteSize | 80.2 | 82.8 |
| vignetteFeather | 83.3 | 81.9 |
| vibrance | 74.9 | 79.1 |
| curve.rgb | 69.7 | 74.1 |
| curve.luma | 69.8 | 76.4 |
| curve.red | 70.6 | 72.8 |
| curve.green | 69.4 | 71.9 |
| curve.blue | 70.0 | 71.3 |
| hsl.RED.hue | 142.5 | 143.2 |
| hsl.RED.saturation | 143.1 | 143.5 |
| hsl.RED.luminance | 143.9 | 146.1 |
| hsl.ORANGE.hue | 141.9 | 146.3 |
| hsl.ORANGE.saturation | 141.8 | 144.2 |
| hsl.ORANGE.luminance | 155.0 | 142.5 |
| hsl.YELLOW.hue | 144.6 | 143.0 |
| hsl.YELLOW.saturation | 143.1 | 154.2 |
| hsl.YELLOW.luminance | 141.2 | 144.7 |
| hsl.GREEN.hue | 142.3 | 144.1 |
| hsl.GREEN.saturation | 143.8 | 144.9 |
| hsl.GREEN.luminance | 142.7 | 143.3 |
| hsl.AQUA.hue | 158.5 | 154.4 |
| hsl.AQUA.saturation | 144.0 | 144.1 |
| hsl.AQUA.luminance | 144.4 | 143.2 |
| hsl.BLUE.hue | 152.8 | 153.4 |
| hsl.BLUE.saturation | 142.2 | 145.7 |
| hsl.BLUE.luminance | 143.1 | 143.6 |
| hsl.PURPLE.hue | 149.1 | 142.5 |
| hsl.PURPLE.saturation | 151.0 | 143.3 |
| hsl.PURPLE.luminance | 142.6 | 143.4 |
| hsl.MAGENTA.hue | 149.4 | 142.3 |
| hsl.MAGENTA.saturation | 161.1 | 146.9 |
| hsl.MAGENTA.luminance | 143.1 | 144.7 |
| combined.legacy | 486.6 | 168.1 |
| combined.curve-color | 379.0 | 261.8 |

## 引擎参数及全部材料：毫秒

| 参数/场景 | 基线 | 修复 | 修复复用负片 | 输出尺寸 |
| --- | ---: | ---: | --- | --- |
| film.fujifilm_c200 | 140.7 | 124.6 | 否 | 1600×1200 |
| film.fujifilm_neopan_acros_100_ii | 133.0 | 124.1 | 否 | 1600×1200 |
| film.fujifilm_pro_400h | 132.0 | 120.8 | 否 | 1600×1200 |
| film.fujifilm_provia_100f | 120.6 | 115.4 | 否 | 1600×1200 |
| film.fujifilm_velvia_100 | 117.6 | 127.9 | 否 | 1600×1200 |
| film.fujifilm_xtra_400 | 127.4 | 128.0 | 否 | 1600×1200 |
| film.ilford_hp5_plus_400 | 129.7 | 120.1 | 否 | 1600×1200 |
| film.kodak_ektachrome_100 | 117.6 | 114.1 | 否 | 1600×1200 |
| film.kodak_ektar_100 | 129.1 | 128.7 | 否 | 1600×1200 |
| film.kodak_gold_200 | 131.3 | 129.8 | 否 | 1600×1200 |
| film.kodak_kodachrome_64 | 117.5 | 113.9 | 否 | 1600×1200 |
| film.kodak_portra_160 | 134.7 | 137.4 | 否 | 1600×1200 |
| film.kodak_portra_400 | 128.9 | 35.3 | 是 | 1600×1200 |
| film.kodak_portra_800 | 128.9 | 123.1 | 否 | 1600×1200 |
| film.kodak_portra_800_push1 | 126.2 | 127.3 | 否 | 1600×1200 |
| film.kodak_portra_800_push2 | 131.7 | 121.8 | 否 | 1600×1200 |
| film.kodak_tmax_100 | 130.0 | 119.6 | 否 | 1600×1200 |
| film.kodak_tri_x_400 | 128.3 | 120.8 | 否 | 1600×1200 |
| film.kodak_ultramax_400 | 130.3 | 121.2 | 否 | 1600×1200 |
| film.kodak_verita_200d | 134.6 | 129.4 | 否 | 1600×1200 |
| film.kodak_vision3_200t | 126.4 | 123.0 | 否 | 1600×1200 |
| film.kodak_vision3_250d | 132.5 | 122.8 | 否 | 1600×1200 |
| film.kodak_vision3_500t | 129.2 | 121.3 | 否 | 1600×1200 |
| film.kodak_vision3_50d | 130.5 | 123.0 | 否 | 1600×1200 |
| paper.fujifilm_crystal_archive_typeii | 127.7 | 60.7 | 是 | 1600×1200 |
| paper.ilford_multigrade_iv_rc | 124.3 | 128.9 | 否 | 1600×1200 |
| paper.kodak_ektacolor_edge | 130.0 | 60.0 | 是 | 1600×1200 |
| paper.kodak_endura_premier | 130.3 | 58.9 | 是 | 1600×1200 |
| paper.kodak_portra_endura | 125.8 | 34.4 | 是 | 1600×1200 |
| paper.kodak_supra_endura | 129.2 | 60.0 | 是 | 1600×1200 |
| paper.kodak_ultra_endura | 126.2 | 58.4 | 是 | 1600×1200 |
| paper.kodak_2383 | 129.6 | 61.4 | 是 | 1600×1200 |
| paper.kodak_2393 | 130.5 | 60.8 | 是 | 1600×1200 |
| exposureEv | 125.1 | 116.4 | 否 | 1600×1200 |
| autoExposure | 123.2 | 112.4 | 否 | 1600×1200 |
| autoExposureMethod | 125.3 | 117.7 | 否 | 1600×1200 |
| filmFormatMm | 135.3 | 113.0 | 否 | 1600×1200 |
| cameraFilter | 140.3 | 120.1 | 否 | 1600×1200 |
| printExposure | 131.2 | 36.4 | 是 | 1600×1200 |
| yFilterShift | 127.8 | 36.1 | 是 | 1600×1200 |
| mFilterShift | 126.8 | 35.9 | 是 | 1600×1200 |
| grainActive | 118.1 | 105.3 | 否 | 1600×1200 |
| grainAmount | 127.2 | 116.4 | 否 | 1600×1200 |
| grainLayered | 129.2 | 112.7 | 否 | 1600×1200 |
| halationActive | 103.2 | 93.9 | 否 | 1600×1200 |
| halationAmount | 125.3 | 117.4 | 否 | 1600×1200 |
| halationScatterAmount | 125.6 | 116.1 | 否 | 1600×1200 |
| halationBoostEv | 130.2 | 115.1 | 否 | 1600×1200 |
| antihalationRemoved | 126.4 | 116.5 | 否 | 1600×1200 |
| dirCouplersActive | 108.1 | 95.1 | 否 | 1600×1200 |
| dirCouplersAmount | 125.0 | 120.1 | 否 | 1600×1200 |
| glareActive | 124.3 | 48.1 | 是 | 1600×1200 |
| glareAmount | 126.8 | 52.4 | 是 | 1600×1200 |
| preflashExposure | 126.3 | 35.7 | 是 | 1600×1200 |
| extendedDynamicRange | 128.1 | 53.9 | 是 | 1600×1200 |
| quarterTurns | 287.1 | 252.2 | 否 | 1200×1600 |
| flipH | 255.2 | 250.4 | 否 | 1600×1200 |
| flipV | 252.5 | 244.6 | 否 | 1600×1200 |
| rotationDeg | 287.6 | 269.0 | 否 | 1600×1200 |
| crop | 212.7 | 207.1 | 否 | 1280×1080 |
| overscanActive | 314.1 | 304.8 | 否 | 1672×1545 |
| overscanFormat | 202.8 | 188.0 | 否 | 1640×862 |
| overscanMode | 278.6 | 270.1 | 否 | 1676×1170 |
| overscanGate | 323.5 | 297.2 | 否 | 1672×1545 |
| overscanHoles | 315.3 | 301.8 | 否 | 1672×1545 |
| overscanCarrier | 315.0 | 307.2 | 否 | 1672×1580 |
| overscanFog | 310.8 | 292.8 | 否 | 1672×1545 |
| overscanLeaks | 311.7 | 297.9 | 否 | 1672×1545 |
| overscanFNumber | 308.2 | 293.8 | 否 | 1672×1545 |
| overscanFrameNumber | 307.8 | 298.9 | 否 | 1672×1545 |
| overscanCameraSeed | 320.4 | 310.4 | 否 | 1680×1545 |
| overscanFrameSeed | 312.2 | 304.9 | 否 | 1672×1545 |
| dateImprintActive | 150.5 | 138.9 | 否 | 1600×1200 |
| dateImprintStyle | 145.0 | 154.9 | 否 | 1600×1200 |
| dateImprintPlacement | 310.7 | 303.6 | 否 | 1672×1545 |
| dateImprintCorner | 146.2 | 137.0 | 否 | 1600×1200 |
| dateImprintSize | 152.0 | 136.5 | 否 | 1600×1200 |
| dateImprintEv | 145.2 | 137.2 | 否 | 1600×1200 |
| dateImprintText | 142.8 | 136.9 | 否 | 1600×1200 |
| dateImprintDataText | 316.9 | 298.6 | 否 | 1672×1545 |
| sceneLatitude | 147.1 | 135.2 | 否 | 1600×1200 |
| sceneLatitudeHighlightKnee | 155.0 | 136.7 | 否 | 1600×1200 |
| sceneLatitudeHighlightRoom | 146.3 | 134.5 | 否 | 1600×1200 |
| sceneLatitudeShadowKnee | 145.8 | 134.1 | 否 | 1600×1200 |
| sceneLatitudeShadowRoom | 141.7 | 136.6 | 否 | 1600×1200 |
| sceneLatitudeRolloff | 142.3 | 135.9 | 否 | 1600×1200 |
| sceneLatitudeMaxLift | 143.5 | 137.5 | 否 | 1600×1200 |
| sceneLatitudeNorm | 148.5 | 135.1 | 否 | 1600×1200 |

## 连续拖动反馈

| 参数 | 基线更新数 | 修复更新数 | 修复首次反馈 ms | 基线松手后完成 ms | 修复松手后完成 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| contrast | 0 | 9 | 158.7 | 152.3 | 104.9 |
| brightness | 0 | 8 | 139.1 | 464.5 | 135.3 |
| curve | 0 | 7 | 173.7 | 255.9 | 102.7 |
| printExposure | 0 | 5 | 207.8 | 454.1 | 295.2 |
| exposureEv | 0 | 3 | 277.3 | 448.4 | 353.8 |

记录的是画面计算/发布完成时间；参考图计算及哈希不在计时内。

## RAW 参数：端到端毫秒

| 参数 | 基线 | 修复 |
| --- | ---: | ---: |
| cameraWbCustom | 2163.1 | 2160.4 |
| cameraWbKelvin | 2232.1 | 2177.5 |
| cameraWbTint | 2196.2 | 2216.4 |
| lensCorrection | 1690.0 | 1690.1 |

RAW 相机白平衡仍需重新解码、变换源图并重开会话；原有 300ms 解码去抖保持。未用普通后期色温替代，也未切换低质量解码，因此仍明显慢于普通后期参数。

## 实际胶片切换

| 项目 | 基线 ms | 修复 ms |
| --- | ---: | ---: |
| 四款胶片三轮中位数 | 321.8 | 176.8 |
| 三次快速切换后落地 | 383.3 | 237.2 |

## 画质与回归

- 52 个后期场景全图 SHA-256 与基线相同。10,223,616 个标量 Float 样本与冻结原 Grade 逐项相同，1,572,864 个原生 Float 样本与原逐像素 pow 路径相同。
- 六类打印/相纸修改的缓存重印与完整渲染 RGBA16 逐项相同；拍摄/胶片变化仍重建；重新打开、引擎外部修改、无效参数验证通过。
- 曲线/HSL、材料/后期竞态、撤销/重做、原图对比、导入/取消、JPEG/PNG/TIFF、元数据、批量、缓存与队列，以及立即导出后最新预览恢复，详见本地 verification.json。
- 24MP Z6II 全尺寸 6064×4040 TIFF 的中性、曲线、HSL 完整 SHA-256 保持既有基线。
- assets/engine 下 94 个资源与基线逐字节一致；.so 因精确亮度缓存新增 JNI 而变化，不能声称原生库字节未变。
- 早先连续回归中的24MP测试曾超时；随后基线测试也因系统ActivityManager服务缺失无法启动，模拟器记录系统进程崩溃。保留数据冷启动后，原基线24MP输出恢复（首次8575ms、缓存2400ms、哈希不变）；最终包重新跑全部28组。没有放宽180秒超时或缩图。

## 限制与复测

小米 17 Pro/其他 ARM GPU 真机未连接。F01/F02 成本模型与随机 seed 合同、F03 大图全局活跃峰值、F04 真机持续交互和超宽图边界仍需后续专项；T06 未开始。未自动提交、push 或发布 Release。

测试入口：ParameterPerformanceAudit、ChangedParamsAudit、StockInteractionAudit。原始三次采样、像素摘要、构建日志和最终回归在 .tmp/parameter-latency/（本地忽略目录）。本次新增测试源码保存在项目中。
