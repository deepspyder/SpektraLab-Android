package com.spektralab.android.engine

/** Output curves and colour adjustments share bounded native strips; neutral output keeps its original path. */
internal object NativeCurveGrade {
    init {
        System.loadLibrary("spektralab")
    }

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
        output: FloatArray,
    )
}
