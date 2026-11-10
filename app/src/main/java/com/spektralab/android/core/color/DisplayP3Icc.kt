package com.spektralab.android.core.color

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Display P3 ICC v2.1：D65 原色经 Bradford 适应到 D50 PCS；sRGB TRC 使用 v2 sampled curve。
 */
object DisplayP3Icc {

    fun generate(): ByteArray {
        // P3 原色（CIE xy）
        val primaries = arrayOf(
            doubleArrayOf(0.680, 0.320),  // R
            doubleArrayOf(0.265, 0.690),  // G
            doubleArrayOf(0.150, 0.060),  // B
        )
        val white = doubleArrayOf(0.3127, 0.3290)  // D65

        fun xyz(xy: DoubleArray): DoubleArray {
            val (x, y) = xy
            return doubleArrayOf(x / y, 1.0, (1.0 - x - y) / y)
        }
        val r = xyz(primaries[0])
        val g = xyz(primaries[1])
        val b = xyz(primaries[2])
        val w = xyz(white)

        val s = solve3x3(r, g, b, w)
        val d50 = doubleArrayOf(0.9642, 1.0, 0.8249)
        val bradford = arrayOf(doubleArrayOf(.8951,.2664,-.1614),doubleArrayOf(-.7502,1.7135,.0367),doubleArrayOf(.0389,-.0685,1.0296))
        val inverse = arrayOf(doubleArrayOf(.9869929,-.1470543,.1599627),doubleArrayOf(.4323053,.5183603,.0492912),doubleArrayOf(-.0085287,.0400428,.9684867))
        fun mul(m:Array<DoubleArray>,v:DoubleArray)=DoubleArray(3){i->(0..2).sumOf{j->m[i][j]*v[j]}}
        val srcCone=mul(bradford,w);val dstCone=mul(bradford,d50)
        fun adapt(v:DoubleArray):DoubleArray {val cone=mul(bradford,v);return mul(inverse,DoubleArray(3){cone[it]*dstCone[it]/srcCone[it]})}
        val mr = adapt(r.map { it * s[0] }.toDoubleArray())
        val mg = adapt(g.map { it * s[1] }.toDoubleArray())
        val mb = adapt(b.map { it * s[2] }.toDoubleArray())

        // sRGB TRC（ICC para type 0 参数）
        val trc = doubleArrayOf(
            2.4,                      // g
            1.0 / 1.055,              // a
            0.055 / 1.055,            // b
            1.0 / 12.92,              // c
            0.04045,                  // d
            0.0,                      // e
            0.0,                      // f
        )

        // 构建 tag 数据
        val desc = descTag("Display P3")
        val cprt = textTag("Theia Display P3")
        val wtpt = xyzTag(d50)
        val rxyz = xyzTag(mr)
        val gxyz = xyzTag(mg)
        val bxyz = xyzTag(mb)
        val trcTag = paraTag(trc)

        val tags = listOf(
            "desc" to desc,
            "cprt" to cprt,
            "wtpt" to wtpt,
            "rXYZ" to rxyz,
            "gXYZ" to gxyz,
            "bXYZ" to bxyz,
            "rTRC" to trcTag,
            "gTRC" to trcTag,
            "bTRC" to trcTag,
        )

        val headerSize = 128
        val tagTableSize = 4 + tags.size * 12
        val dataStart = headerSize + tagTableSize
        // 计算每个 tag 的偏移（数据顺序 = tags 顺序）
        val offsets = IntArray(tags.size)
        var off = dataStart
        for (i in tags.indices) {
            offsets[i] = off
            off = (off + tags[i].second.size + 3) and -4
        }
        val totalSize = off

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // ---- Header ----
        buf.putInt(totalSize)                    // profile size
        buf.putInt(0)                            // CMM type
        buf.putInt(0x02100000)                   // version 2.1.0
        buf.putInt(sig("mntr"))                  // device class = display
        buf.putInt(sig("RGB "))                  // color space
        buf.putInt(sig("XYZ "))                  // PCS
        listOf(2026, 10, 30, 0, 0, 0).forEach { buf.putShort(it.toShort()) }
        buf.putInt(sig("acsp"))                  // signature
        buf.putInt(0)                            // platform
        buf.putInt(0)                            // flags
        buf.putInt(0)                            // manufacturer
        buf.putInt(0)                            // model
        buf.put(ByteArray(8))                    // attributes
        buf.putInt(0)                            // rendering intent = perceptual
        buf.putInt(s15(0.9642)).putInt(s15(1.0)).putInt(s15(0.8249))  // D50 PCS illuminant
        buf.putInt(0)                            // creator
        buf.put(ByteArray(44))                   // reserved

        // ---- Tag table ----
        buf.putInt(tags.size)
        for (i in tags.indices) {
            buf.putInt(sig(tags[i].first))
            buf.putInt(offsets[i])
            buf.putInt(tags[i].second.size)
        }

        // ---- Tag data ----
        tags.forEachIndexed { i, (_, data) -> buf.position(offsets[i]); buf.put(data) }

        return buf.array()
    }

    // 4 字符签名 → 32 位整数
    private fun sig(s: String): Int {
        var v = 0
        for (c in s) v = (v shl 8) or c.code
        return v
    }

    // s15Fixed16：double × 65536
    private fun s15(v: Double): Int = (v * 65536).roundToInt()

    // ICC v2 的 desc 类型：type + reserved + ASCII 长度 + ASCII
    private fun descTag(text: String): ByteArray {
        val ascii = text.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        // The Unicode and ScriptCode fields are mandatory even when unused.
        val buf = ByteBuffer.allocate((12 + ascii.size + 78 + 3) and -4).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(sig("desc"))
        buf.putInt(0)             // reserved
        buf.putInt(ascii.size)    // ASCII 长度（含结尾 null）
        buf.put(ascii)
        return buf.array()
    }

    // text 类型：type + reserved + ASCII（无长度字段）
    private fun textTag(text: String): ByteArray {
        val ascii = text.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val buf = ByteBuffer.allocate(8 + ascii.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(sig("text"))
        buf.putInt(0)             // reserved
        buf.put(ascii)
        return buf.array()
    }

    // XYZ tag（三个 s15Fixed16）
    private fun xyzTag(xyz: DoubleArray): ByteArray {
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(sig("XYZ "))
        buf.putInt(0)
        buf.putInt(s15(xyz[0])).putInt(s15(xyz[1])).putInt(s15(xyz[2]))
        return buf.array()
    }

    // ICC v2 uses curveType. The old para function-0 meant pure gamma,
    // ignored the sRGB linear segment and also declared the wrong parameter count.
    private fun paraTag(p: DoubleArray): ByteArray {
        val buf = ByteBuffer.allocate(12 + 4096 * 2).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(sig("curv"))
        buf.putInt(0)
        buf.putInt(4096)
        repeat(4096) { i ->
            val x = i / 4095.0
            val linear = if (x < p[4]) p[3] * x else (p[1] * x + p[2]).pow(p[0])
            buf.putShort((linear * 65535).roundToInt().toShort())
        }
        return buf.array()
    }

    // 解 3×3 线性方程组 M·x = b，M 的列是 r/g/b，返回 x
    private fun solve3x3(r: DoubleArray, g: DoubleArray, b: DoubleArray, w: DoubleArray): DoubleArray {
        fun det(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double, h: Double, i: Double, j: Double) =
            a * (e * j - f * i) - b * (d * j - f * h) + c * (d * i - e * h)

        val m = arrayOf(
            doubleArrayOf(r[0], g[0], b[0]),
            doubleArrayOf(r[1], g[1], b[1]),
            doubleArrayOf(r[2], g[2], b[2]),
        )
        val d = det(m[0][0], m[0][1], m[0][2], m[1][0], m[1][1], m[1][2], m[2][0], m[2][1], m[2][2])
        val d0 = det(w[0], m[0][1], m[0][2], w[1], m[1][1], m[1][2], w[2], m[2][1], m[2][2])
        val d1 = det(m[0][0], w[0], m[0][2], m[1][0], w[1], m[1][2], m[2][0], w[2], m[2][2])
        val d2 = det(m[0][0], m[0][1], w[0], m[1][0], m[1][1], w[1], m[2][0], m[2][1], w[2])
        return doubleArrayOf(d0 / d, d1 / d, d2 / d)
    }
}
