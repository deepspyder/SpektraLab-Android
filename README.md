<div align="center">

# Theia

**让光，慢慢显影。**

一间装进口袋的数字暗房。用胶片与相纸的色彩，重新看见照片里的光。

**简体中文** · [English](README.en.md)

[下载应用](https://github.com/deepspyder/theia/releases) · [功能](#功能) · [从源码构建](#从源码构建) · [开源协议](#开源协议)

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-E5AC55)](LICENSE)

</div>

## 关于 Theia

Theia 是一款免费开源的 Android 胶片与暗房模拟应用，基于 **Andrea Volpato 的 [spektrafilm](https://github.com/andreavolpato/spektrafilm)** 开发。将照片导入工作台，搭配胶片与相纸、调整显影参数，再把喜欢的色彩带回相册。

支持 RAW 编辑、逐张智能显影、多照片工作台、自由观景台和批量导出。你可以从一种自动风格开始，也可以亲手调整每一个细节。

## 界面预览

<table>
  <tr><th>照片工作台</th><th>胶片显影</th><th>前后对比</th><th>观景台</th><th>导出照片</th></tr>
  <tr>
    <td><img src="docs/images/home.jpg" width="160" alt="照片工作台：预览、智能显影与当前会话"></td>
    <td><img src="docs/images/darkroom.jpg" width="160" alt="胶片显影：RAW 照片、胶片相纸搭配与曝光调整"></td>
    <td><img src="docs/images/compare.jpg" width="160" alt="暗房调整：拖动分割线对比原图与冲印效果"></td>
    <td><img src="docs/images/viewstand.jpg" width="160" alt="观景台：胶片照片展示、灯光和可移动放大镜"></td>
    <td><img src="docs/images/export.jpg" width="160" alt="导出照片：格式、分辨率和元数据选项"></td>
  </tr>
</table>

<sub>使用本次提供的实机截图。部分截图仍显示项目原名 SpektraLab，应用现已更名为 Theia。</sub>

## 功能

| 从导入到成片 | 你可以做什么 |
| --- | --- |
| **胶片 × 相纸** | 选择彩色负片、反转片或黑白胶片，搭配相纸与冲印参数，探索不同的色彩与反差。 |
| **逐张智能显影** | 自然耐看、电影浓郁、轻柔复古三种风格，根据每张照片的情况推荐胶片、相纸与参数，支持继续手动微调。 |
| **RAW 与多图工作台** | 导入 JPEG、PNG、WebP 和 RAW；在顶部缩略图之间切换，独立保存每张照片的编辑参数。 |
| **暗房调整** | 调整曝光、白平衡、颗粒、光晕、暗角与色彩，通过前后对比查看变化，支持撤销和重做。 |
| **构图与裁剪** | 移动裁剪选区，调整旋转、翻转与比例；包含 **2.39:1**、**1.85:1** 电影画幅。 |
| **自由观景台** | 自由拖放照片、平移和缩放画布；靠边吸附、重叠透光，配合灯光与可移动放大镜观察细节。 |
| **按需导出** | JPEG、PNG、16 位 TIFF 与 DI TIFF；可勾选照片批量导出，独立选择是否保留拍摄信息与位置信息。 |

## 开始使用

1. 从 [GitHub Releases](https://github.com/deepspyder/theia/releases) 下载 APK，安装到支持 Vulkan 的 64 位 Android 设备上。
2. 导入照片，选择智能显影风格，或手动搭配胶片与相纸。
3. 在暗房调整画面，通过观景台查看多张照片，再按需要导出。

> **大图导出提示**：照片尺寸较大时，建议优先选择较小的导出分辨率。全尺寸导出会占用更多内存，处理时间也更长。

应用要求 Android 8.0（API 26）及以上，构建目标为 `arm64-v8a` 和 `x86_64`。项目原名为 SpektraLab Android；为兼容覆盖安装与已有编辑数据，应用 ID 仍为 `com.spektralab.android`。详见 [Theia 改名说明](docs/theia-branding.md)。

## 从源码构建

### 构建环境

| 工具 | 版本 / 要求 |
| --- | --- |
| Android Studio | 安装 Android SDK Platform 36 |
| JDK | 21，本地构建已验证 |
| Gradle / Android Gradle Plugin | 8.13 / 8.11.2 |
| Kotlin | 2.1.0 |
| Android NDK / CMake | 27.0.12077973 / 3.22.1 |
| Python / glslang | Python 3.10+；`glslangValidator` 或 `glslang` 在 PATH 中 |

### 准备共享引擎

Android 端通过 JNI 复用共享 C++20 / Vulkan 引擎。完整构建还需要共享引擎、资源与 LibRaw 源码；仅下载本仓库并不足以运行真实渲染。

默认目录布局：

```text
workspace/
├── SpektraLab-Android/          # 本仓库，应用名为 Theia
├── SpektraLab-Windows/
│   └── engine/                 # 共享引擎、配置与着色器
└── deps/
    └── LibRaw-0.22.2/          # RAW 解码依赖
```

保留上述实际目录名。若更换引擎位置，需要同步调整 CMake 的 `SPEKTRA_ENGINE_DIR`、资源同步参数和着色器脚本的输入目录；LibRaw 路径由 `SPEKTRALAB_LIBRAW_SOURCE` 控制。准备 LibRaw 可使用共享引擎中的 `setup-libraw.ps1`。

GitHub 仓库现名为 `theia`。按上述本地布局克隆时，可执行 `git clone https://github.com/deepspyder/theia.git SpektraLab-Android`。

### 同步资源并构建

在本仓库根目录执行：

```powershell
python tools/sync_resources.py
python tools/compile_shaders.py
gradle :app:assembleDebug
```

也可以使用 Android Studio 打开项目，完成 SDK 配置后构建。若已生成 Gradle Wrapper，可用 `./gradlew`（Windows 为 `.\gradlew.bat`）代替 `gradle`。

生成的引擎资源和 SPIR-V 文件位于 `app/src/main/assets/engine/resources/`，默认不提交 Git。Release 构建还需在本地准备签名与 `keystore.properties`，请勿提交私钥或密码。

### 代码结构

```text
app/src/main/
├── java/com/spektralab/android/  # Compose 界面、编辑状态与导出流程
│   └── engine/                  # Kotlin 引擎与色彩处理封装
├── cpp/                         # JNI 桥接、Vulkan 引擎接入和 RAW 解码
└── res/                         # 应用主题、字体与界面资源

docs/images/                     # 本 README 使用的实机截图
tools/                           # 引擎资源同步与着色器编译脚本
```

## 来源与致谢

- **[spektrafilm](https://github.com/andreavolpato/spektrafilm)** — Andrea Volpato 的开源胶片模拟项目，是 Theia 的重要基础。
- **[LibRaw](https://www.libraw.org/)** — RAW 照片解码。
- **[Resource Han Rounded](https://github.com/CyanoHao/Resource-Han-Rounded)** — 源柔黑，为界面提供圆润的中英文字体。
- **[Lucide](https://lucide.dev/)**、**[volk](https://github.com/zeux/volk)**、**[stb](https://github.com/nothings/stb)** — 图标与底层开源组件。

感谢上游作者、贡献者，以及提供测试与反馈的使用者。

## 开源协议

Theia 应用代码沿用 **GPL-3.0-or-later**，完整协议见 [LICENSE](LICENSE)。

spektrafilm 的原始胶片配置、LUT 及其直接衍生数据遵循 **CC BY-SA 4.0**，原作者为 **Andrea Volpato**，来源为 [spektrafilm](https://github.com/andreavolpato/spektrafilm)。完整原始声明见 [SPEKTRAFILM_LICENSE.txt](licenses/SPEKTRAFILM_LICENSE.txt)。

第三方库、字体、图标与其他素材保留各自的版权及许可。来源、协议文件和资源说明汇总在 [NOTICE.md](NOTICE.md)。
