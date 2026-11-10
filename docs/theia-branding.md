# Theia 品牌改名说明

2026-11-10 起，项目应用名由 SpektraLab Android 改为 **Theia**。

## 已统一的名称

- Android 桌面和系统应用列表中的显示名。
- 系统启动主题、应用内启动画面、首页、编辑器默认标题和关于页面。
- 项目在 Android Studio 中的显示名、主题资源与应用类名称。
- 新导出文件：`Theia_时间戳.jpg/png/tiff`，DI 文件为 `Theia_DI_时间戳.tiff`。
- 新导出的系统相册目录：`Pictures/Theia/`；观片分享文件使用 `Theia-viewing-` 前缀。
- 项目 README、当前设计说明和新安装包名称。

Theia 基于 [spektrafilm](https://github.com/andreavolpato/spektrafilm) 开发。关于页面和 README 明确保留上游署名。

## 升级与历史兼容

本次是品牌改名。应用 ID / Kotlin namespace 仍为 `com.spektralab.android`，保持原签名、FileProvider authority、逐图编辑 sidecar、配方、偏好目录及 JNI 调用约定，支持覆盖安装并恢复已有数据。

既有 `Pictures/SpektraLab/` 文件不移动或改名，仍可通过照片选择器导入。改名前的发布标签、安装包名称、校验记录及截图属于历史资料，保留其真实名称；当前界面以 Theia 为准。

现有 GitHub 仓库地址与本地 `SpektraLab-Android`、共享引擎目录 `SpektraLab-Windows/engine` 仍为有效技术路径，不凭空替换成尚不存在的目录或链接。原生库 `libspektralab.so` 及共享引擎的 LUT、配置、第三方许可名称保留，以维持加载兼容性和作者署名。

## 验证

构建并检查签名 Release 的应用显示名、应用 ID 和架构；模拟器覆盖安装前后核对已有编辑与偏好文件。品牌专项实际验证首页、启动组件、关于页面、上游署名、旧编辑恢复和 JPEG / PNG / Display P3 TIFF / DI TIFF 的新命名与相册路径。

屏幕截图来自 Android 模拟器。功能和数据兼容检查不代表所有真机 GPU 或大图导出的性能保证。

## English

The application is now **Theia**, formerly SpektraLab Android. Launcher, splash, Home, About, project display identity and newly exported files use the new name. Theia is built on **spektrafilm**, with upstream attribution retained.

The existing application ID, signing identity, private edit/preferences storage and JNI ABI remain stable for in-place upgrades. Previously exported files and historical releases keep their original names. The existing repository address and shared engine checkout paths remain valid technical identifiers.
