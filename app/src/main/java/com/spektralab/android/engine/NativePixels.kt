package com.spektralab.android.engine

import java.io.Closeable

/** Export pixels live outside the small ART heap; ownership ends deterministically with use(). */
class NativeRawImage internal constructor(
    internal var handle: Long,
    var width: Int,
    var height: Int,
    val make: String,
    val model: String,
    val lens: String,
    val focal: Float,
    val camXyz: FloatArray,
) : Closeable {
    fun resize(maxLongEdge: Int) {
        check(handle != 0L)
        val size = nativeResize(handle, maxLongEdge)
        width = size[0]; height = size[1]
    }

    fun correctLens(profile: LensProfile) {
        check(handle != 0L)
        nativeCorrectLens(handle, profile.distModel, profile.distTerms, profile.vigK, profile.tcaVr, profile.tcaVb)
    }

    override fun close() {
        if (handle != 0L) { nativeRelease(handle); handle = 0L }
    }

    private external fun nativeResize(handle: Long, maxLongEdge: Int): IntArray
    private external fun nativeCorrectLens(handle: Long, model: String, terms: FloatArray, vignette: FloatArray, vr: Float, vb: Float)
    private external fun nativeRelease(handle: Long)
}

/** Owns one native RGBA16 result. Only a small strip is copied into the managed heap. */
class NativeRenderResult internal constructor(
    private var handle: Long,
    val width: Int,
    val height: Int,
) : Closeable {
    fun readRows(y: Int, rows: Int, destination: ShortArray) {
        check(handle != 0L)
        require(y >= 0 && rows > 0 && y.toLong() + rows <= height && destination.size.toLong() >= width.toLong() * rows * 4)
        nativeReadRows(handle, y, rows, destination)
    }

    override fun close() {
        if (handle != 0L) { nativeRelease(handle); handle = 0L }
    }

    private external fun nativeReadRows(handle: Long, y: Int, rows: Int, destination: ShortArray)
    private external fun nativeRelease(handle: Long)
}
