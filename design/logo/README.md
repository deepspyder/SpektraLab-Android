# Theia 立体卷片图标

用户明确选择直接精修本次提供的立体卷片参考图并适配应用，保留参考图主体。本版使用参考图原始像素，不是 AI 重新生成或原创重绘；卷曲、齿孔、胶片材质和光谱反光均来自该参考图。底板按用户要求改为圆角纯黑色 `#000000`，去除原参考图背景的磨砂纹理、边缘反光和描边。

## 设计文件

- `source-reference.png`：用户提供的原始参考图，1254 × 1254。
- `theia-logo.png`：纯黑圆角底板加立体卷片，1024 × 1024，圆角外透明。
- `theia-logo-foreground.png`：沿参考图实际外轮廓制作的透明卷片前景，768 × 768。
- `prepare-logo.cjs`：裁切、轻微锐化、前景蒙版和 Android 资源生成脚本，需要 Node.js 与 `sharp`，运行 `node design/logo/prepare-logo.cjs`。
- `provenance.json`：参考图 SHA-256、处理步骤和运行时资源清单。PNG 和 WebP 的 XMP 同时保留来源说明。

1024 版本通过裁切和缩放获得，不代表凭空恢复出参考图中不存在的细节。已弃用上一版平面 SVG。

## Android 接入

- `drawable-nodpi/theia_logo_artwork.webp`：512 × 512 完整图标，用于 Compose 启动过渡页。
- `drawable-nodpi/theia_logo_foreground.webp`：512 × 512 透明卷片，用于自适应桌面图标。
- `drawable/ic_theia_mark.xml`：系统启动图使用透明卷片并保留 20% 内边距，防止 Android 的启动图圆形掩模截断主体。Compose 直接引用完整 WebP。
- `drawable/ic_theia_launcher_foreground.xml`：前景保留 24% 内边距，配合 `theia_logo_background` 纯色背景，避免圆形裁切截断卷片或出现双层圆角底板。

两张运行时资源使用无损 WebP；512 × 512 ARGB 解码后每张约 1 MiB，不进入照片渲染或导出链路。设计源、母版和脚本均不打入 APK。原应用主题、名字和包名保持。
