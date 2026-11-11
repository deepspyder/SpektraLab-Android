package com.spektralab.android.engine

/** Tight RGBA16 rows, preserving all sixteen bits; storage may be native or file-backed. */
interface Rgba16Source {
    val width: Int
    val height: Int

    fun readRows(y: Int, rows: Int, destination: ShortArray)
}
