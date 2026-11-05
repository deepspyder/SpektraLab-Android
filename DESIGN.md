---
name: SpektraLab Android
description: 照片优先的数字暗房工作台
colors:
  background: "#151615"
  surface: "#1B1D1A"
  surface-low: "#191A18"
  surface-mid: "#242622"
  surface-high: "#2D2F2A"
  paper: "#F2EEE6"
  paper-muted: "#B8B2A8"
  amber: "#E5AA55"
  amber-deep: "#4A351D"
  line: "#41433D"
  navigation: "#20231E"
  navigation-selected: "#3B301E"
typography:
  title-medium:
    fontFamily: "sans-serif"
    fontSize: "16sp"
    fontWeight: 600
    lineHeight: "22sp"
    letterSpacing: "0sp"
  title-small:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "20sp"
    letterSpacing: "0sp"
  body-medium:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "20sp"
    letterSpacing: "0sp"
  label-medium:
    fontFamily: "sans-serif"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: "16sp"
    letterSpacing: "0sp"
rounded:
  extra-small: "4dp"
  small: "6dp"
  medium: "8dp"
  extra-large: "10dp"
spacing:
  small: "8dp"
  medium: "12dp"
  large: "16dp"
components:
  action-primary:
    backgroundColor: "{colors.amber}"
    rounded: "{rounded.small}"
    height: "48dp"
  choice-selected:
    backgroundColor: "{colors.surface-high}"
    textColor: "{colors.amber}"
    rounded: "{rounded.small}"
    height: "48dp"
  film-row-selected:
    backgroundColor: "{colors.surface-high}"
    rounded: "{rounded.medium}"
    height: "72dp"
---

# Design System: SpektraLab Android

## Overview

**Creative North Star: "数字暗房"**

以 Darkroom、Lightroom 与胶片美学为方向：深炭灰工作环境、暖纸色文字和克制的琥珀强调色让照片成为视觉主体。界面专业、安静，服务反复调整和比较。

基于 Kotlin、Jetpack Compose 与 Material 3。此文记录已实现的 Android UI；dp 和 sp 是源代码单位，随设备密度和字体缩放解释。

**Key Characteristics:**
- 照片优先，控制区紧凑而可触达。
- 色阶区分工作区域，琥珀标记选择和关键操作。
- 手机与宽屏使用适合可用空间的导航和参数布局。

## Colors

### Primary
- **琥珀**：主要操作、选中状态、参数值及章节强调；深琥珀用于主色容器。

### Neutral
- **炭灰背景与表面**：背景、低、中、高表面依次为工作区提供层次。
- **暖纸与柔纸**：分别用于主要内容与次要说明；线色用于分隔和边界。
- **导航与导航选中底色**：紧凑底栏及其选中图标背景使用独立的运行时颜色。

sidecar 中合成的色阶仅供文档可视化，不是 Android 运行时令牌；实现以主题和 DarkroomChrome 中已定义的颜色为准。

## Typography

使用 Android 系统默认无衬线字体；主题未指定自定义字体。标题、正文及标签使用 frontmatter 的实际字号与行高，未覆盖的角色沿用 Material 3 默认值。允许系统字体缩放，长内容按组件既有规则换行或省略。

## Layout

手机首页、照片编辑器、胶片库、分组参数与导出构成工作流。紧凑屏幕用底部导航；宽度至少 840dp，或宽度至少 600dp 且横向时，首页展开布局、编辑器使用导航 rail 和左右双栏。宽屏参数栏为可用宽度的 44%，限制在 280–440dp。手机竖屏完整参数页让 Fit 照片预览占据参数面板以外的空间。

胶片库在宽度至少 600dp 且高度小于 500dp 时，将列表与 260dp 右侧应用区并列；其他情况应用操作位于底部。常用间距为 8、12、16dp，按实际内容调整。

## Elevation & Depth

主要通过表面色阶、细分隔线及选中边框区分层次。导航栏、胶片库底部及相关表面显式使用零 tonal elevation；其他 Material 组件保留其自身默认行为。

## Shapes

主题圆角为 4、6、8、8、10dp；常见操作按钮为 6dp，胶片行和搜索框为 8dp。缩略图使用裁切与小圆角，选中状态可用 1dp 琥珀边框。

## Components

- **操作按钮**：主操作使用主题主色，次操作使用描边；通用操作最小高度 48dp，胶片应用按钮使用 8dp 圆角。
- **分段选择**：低表面底色与 8dp 外框；选项至少 48dp，选中项使用高表面和琥珀文字，并公开 selected 语义。
- **暗房分段选择**：外框使用导航底色和 6dp 圆角；按钮最小高度 48dp，内部选中背景最小高度 40dp，外留 4dp 内边距。处理期间禁用操作，仍保留选中背景。
- **参数组与滑杆**：可展开组标题至少 64dp，显示摘要与展开状态；滑杆触控区至少 48dp，轨道 3dp、圆形滑块 16dp，值以琥珀显示。
- **选择行和开关**：选择行外层至少 64dp，内部行至少 56dp；含标签、当前值和展开图标，长值省略。选择行公开按钮角色和“展开选项”动作，以 contentDescription 和 stateDescription 分别提供标签与当前值；OptionsDropdown 菜单项公开 selected 语义。开关行至少 56dp，整行可操作。
- **选项显示**：OptionsDropdown 的 optionLabel 将测光、镜头滤镜、片边和日期枚举显示为中文；选择回调仍使用原始 wire 值，序列化不变。
- **胶片库**：搜索框、横向筛选及可滚动胶片行；每行至少 72dp，选中行使用高表面与琥珀边框。空结果显示提示，应用时可选择兼容相纸。
- **首页顶栏**：纯文字品牌前置 3dp 琥珀竖线，单个设置图标打开更多操作。
- **首页照片与会话**：紧凑布局横向内边距 20dp；照片标题右侧显示数字暗房序号，主照片按 1.4 宽高比裁切。下方显示胶片、相纸和可用拍摄时间，以及来源格式、原始尺寸（缺失时标明预览尺寸）和可用拍摄参数；导入操作附系统照片选择器提示。当前会话标题行显示数量和添加操作；横向照片项为 112×84dp、2dp 圆角的照片填充区域，序号和格式叠加在图像内。当前项使用 1dp 琥珀边框与“编辑中”标记，此状态依据队列选择，而非修改状态；当前项的格式另置于图像右上角。选择变化时按需滚动到当前项。
- **编辑器顶栏**：最小高度 52dp。大图页提供返回、可打开更多操作的文件名、撤销、重做和对比；使用原稿同名的 Lucide `arrow-left`、`undo-2`、`redo-2`、`columns-2` 资源，以 20dp 显示圆头细线矢量。对比为完整外框加中央分隔线，使用琥珀色。SVG 来源、校验及许可证保存在 `app/src/main/design/lucide/`。参数页显示返回、暗房调整和重置；导出页显示返回、导出照片和帮助。
- **照片参数信息**：编辑器大图信息行显示可用的焦距、光圈、快门和 ISO；这些参数均缺失时显示可用的原始尺寸，右侧显示来源格式。首页与编辑器共用拍摄参数格式化；来源格式通过文件名与媒体类型识别，未知时显示“照片”，不取决于导出格式。
- **导航**：紧凑底栏基准高度 64dp，字体放大时按 fontScale 增高；使用 19dp 描边图标、48×28dp 选中背景和 11sp 标签，公开 Tab 角色与选中状态。展开布局使用 rail。
- **手机调整工作区**：照片按完整构图 Fit 显示；底部参数面板默认最多占可用高度的 50%，内容可滚动并自然收缩。56dp 工具行提供收起或展开参数、可上下拖动的比例控制和完成；面板比例默认 0.5，可调范围为 0.3–0.72，预览最小高度为 192dp 与可用高度 35% 的较小值，面板至少为 240dp 与可用高度 50% 的较小值。拖动控制公开 progress 语义。底部主导航切换拍摄、放印和调色；切换时展开参数，保留各模式滚动位置与参数组展开状态。胶片与相纸信息行至少 48dp。
- **预览操作**：照片上叠加 48dp 前后对比和全屏按钮。全屏为同窗口内的独立查看状态，遵守主窗口的状态栏与导航栏安全区；复用同一照片画布和现有位图，支持原有前后对比，隐藏会话标记，关闭或系统返回后恢复原控件状态。布局切换不新增解码、渲染或全尺寸缓存；这属于实现范围说明，不代表真机性能基准。
- **照片展示台**：编辑队列以低表面底色的底部面板呈现，顶部圆角为 16dp，拖动柄行高 20dp。标题、照片数量、添加和关闭操作位于顶部；横向照片条使用 88×104dp、6dp 圆角的照片框及序号。当前照片以 2dp 琥珀边框、勾选标记和“当前”文字共同标识，并在选择变化时按需滚动到可见位置。分隔线下方显示省略长文件名和当前位置，配有上一张、下一张和照片操作图标；菜单提供照片信息和从展示台移除。空队列显示添加提示，单张时前后导航禁用。照片优先复用已显影预览，再使用缩略图；选择项公开单选和当前状态语义。
- **导出**：独立页面最大宽度 600dp，沿用背景色；56×64dp 缩略图和照片信息之后依次为单张/批量及格式分段、分辨率与色彩空间行、JPEG 质量及高级设置。三列快捷操作分别执行 DI TIFF、软打样和高清预览；底部固定导出按钮，导出时显示内联进度与取消操作。
- **导出密度**：照片信息区上、下内边距分别为 12dp、8dp，单张/批量分段后间隔 12dp，高级设置行最小 48dp。局部显式字号/行高组合为 9/12、10/14、11/16、12/16、14/20sp。底部上下内边距 4dp、项目间距 6dp；按需出现的进度区内边距 8dp、内容间距 4dp，进度和取消来自实际导出状态。

## Do's and Don'ts

### Do:
- **Do** 让照片预览保持主要视觉权重。
- **Do** 使用表面色阶和琥珀选中状态表达层级。
- **Do** 保持控件的触控尺寸与语义，支持系统字体缩放。

### Don't:
- **Don't** 用大面积强调色压过照片和参数。
- **Don't** 将参数、选中状态或导航语义仅交给颜色传达。
