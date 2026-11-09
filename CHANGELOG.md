# 更新日志 / Changelog

## v0.1.3 — 2026-11-09

### 新增

- 智能显影：自然耐看、电影浓郁、轻柔复古三种可选风格，自动记住选择。导入后逐张分析亮暗、反差和色彩，匹配胶片、相纸与调色参数；支持关闭自动处理、手动应用和撤销，已有编辑优先恢复。
- 首页增加观景台快捷入口，与导入按钮并列；空会话也可直接进入并单独导入照片。
- 观景台增加近边吸附拼接、接缝预告和柔和贴齐动画。照片叠放时，仅上层交叠区域透光；拼接后仍可逐张拖开。
- 编辑器顶部常驻多照片缩略条；批量导出可勾选照片，每张沿用自己的编辑参数。
- 裁剪增加 2.39:1、1.85:1 和自定义比例，支持拖动选区；调色增加暗角强度、范围和羽化。
- 导出可分别选择是否保留拍摄信息和位置信息，JPEG、PNG、TIFF 按选项写入可读取的源信息。

### 修复与调整

- 修复拖动放大镜时底下照片跟随移动、触发吸附的问题；修复沉浸查看中的放大镜坐标偏移和裁切。
- 首页与编辑器共用观景台摆放、叠放顺序及视口状态，切换入口保留画布布局；从观景台编辑照片时进入正确的编辑页。
- 修复导出页高级设置与快捷操作区过于贴近的问题；增加分区、内边距和间隔，保留滚动及固定导出按钮。
- 胶片库和相纸使用紧凑横向选择布局、透明 WebP 标签素材与统一圆润字体；适配手机、大字体和平板。
- 修复大尺寸 RAW、DNG 和 TIFF 导出路径的内存及会话恢复问题，保留原生像素缓冲和分段写入。
- 导出帮助增加大尺寸照片建议：优先选择长边 2048、2560 或 3840px，按需再尝试原始尺寸全尺寸导出。
- 智能显影使用小预览串行分析，补齐未打开照片的批量导出方案，支持受限 RAW 预览；自动建议只保存在逐图编辑记录中，不改写原文件。

### 说明

- 智能显影为本地像素统计与规则建议，不是语义识别模型，也不保证每张照片都达到主观最佳效果。
- 保留全局圆润字体、完整胶片/相纸素材及 arm64-v8a、x86_64 双架构。此前试验的代码裁剪和启动缓存优化已撤销，不包含在本版本中。
- 观景台拼接属于画布排放，不会合并原文件或自动生成拼接导出图片。
- 验证覆盖 Android 模拟器中的手机、大字体、平板和横屏；不视为所有真机的性能保证。详细验证见 `docs/releases/v0.1.3.md`。

### English

- Added three remembered Smart Develop styles with per-photo local image analysis, compatible film/paper selection, manual application and undo, while preserving existing edits.
- Added direct Home access to the viewing stand, shared canvas placement across Home/editor entry, magnetic edge joining and translucent overlapping regions.
- Added a persistent editor photo strip, selected-photo batch export, movable cinematic crop ratios, vignette controls and independent capture/GPS metadata options.
- Fixed loupe gesture ownership and immersive-viewer positioning; separated advanced export settings from quick actions.
- Added guidance to prefer a bounded output size for very large photos. Originals stay untouched; canvas joining does not merge source files.
- Retained rounded fonts, existing artwork and both supported ABIs. The reverted APK/startup optimization experiment is excluded.
