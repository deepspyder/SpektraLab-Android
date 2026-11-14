# Theia 文档索引

文档按用途分组，开发进度和实施方案保留在 `docs` 根目录，方便工具脚本稳定读取。

## 目录

- `design/`：界面、交互和布局规范，以及导出设置的视觉记录。
- `features/`：功能开发记录，包括调色、图库、预设、观景台、导出和 CineStill。
- `engineering/`：架构、渲染基础、调度、缓存和引擎接入说明。
- `performance/`：全尺寸导出、渲染、内存、放大机和图像保真度测试报告。
- `compatibility/`：上游原型可行性、RAW 格式和相机兼容性记录。
- `project/`：字体、品牌改名和项目级资源说明。
- `releases/`：版本说明、签名和发布验证记录。
- `images/`：README 和文档使用的截图素材。

## 根目录文档

- `upstream-2.3.2-android-implementation-plan.md`：功能实施方案和验收门槛。
- `development-progress.json`：开发进度事实源，由检查脚本读取。
- `development-progress.md`：由进度事实源生成的可读版本。
- `parameter-and-feature-audit.md`：参数和功能总体验收索引。

运行 `python tools/check_development_progress.py` 可检查进度文档是否与事实源一致。

## 本地文件

仓库根目录的 `.tmp/`、`.impeccable/`、`build/`、`.gradle/`、`.idea/`、`.kotlin/` 和崩溃日志属于本地构建或设计工具产物，已通过 `.gitignore` 排除，不应提交到仓库。
