package com.spektralab.android.feature.editor.ui


/** Display names only. Callbacks and saved edits continue to use the engine's wire values. */
private val editorOptionNames = mapOf(
    "balanced" to "均衡测光", "center" to "中央重点",
    "protect_highlights" to "保护高光", "protect_shadows" to "保护阴影",
    "w8" to "Wratten 8 号", "w11" to "Wratten 11 号", "w12" to "Wratten 12 号",
    "w15" to "Wratten 15 号", "w21" to "Wratten 21 号", "w25" to "Wratten 25 号",
    "w47" to "Wratten 47 号", "w58" to "Wratten 58 号",
    "135" to "135 · 全画幅", "135_half" to "135 · 半画幅", "135_xpan" to "135 · 宽画幅",
    "120_645" to "120 · 6×4.5", "120_6x6" to "120 · 6×6", "120_6x7" to "120 · 6×7",
    "120_6x8" to "120 · 6×8", "120_6x9" to "120 · 6×9", "120_6x12" to "120 · 6×12",
    "120_6x17" to "120 · 6×17",
    "strip" to "整条胶片", "filed" to "扩展片门",
    "auto" to "自动", "square" to "直角", "rounded" to "圆角",
    "eared" to "耳形", "shouldered" to "肩形", "kicked" to "斜角",
    "white" to "白色", "black" to "黑色", "none" to "无", "open" to "开放式",
    "lcd" to "液晶数字", "dots" to "点阵数字", "data" to "拍摄数据",
    "frame" to "照片内", "rebate" to "胶片边缘",
    "br" to "右下角", "bl" to "左下角", "tr" to "右上角", "tl" to "左上角",
)

internal fun editorOptionLabel(value: String): String = editorOptionNames[value] ?: value
