package com.spektralab.android.engine

import java.nio.ByteBuffer

/** Output curves and colour adjustments share bounded native strips; neutral output keeps its original path. */
internal object NativeCurveGrade {
    init {
        System.loadLibrary("spektralab")
    }

    external fun prepareBrightness(uniforms: FloatArray, channels: ByteBuffer)

    external fun apply(
        input: ShortArray,
        offset: Int,
        width: Int,
        height: Int,
        y: Int,
        rows: Int,
        uniforms: FloatArray,
        curves: FloatArray,
        colors: FloatArray,
        vignette: FloatArray?,
        brightness: ByteBuffer?,
        output: FloatArray,
    )
}
