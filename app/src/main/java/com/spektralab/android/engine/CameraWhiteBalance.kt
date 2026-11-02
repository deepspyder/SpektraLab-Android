package com.spektralab.android.engine

import kotlin.math.sqrt

/**
 * 相机白平衡（Adobe DNG 的温度/色调定义）：Kelvin + tint → 相机乘数。
 *
 * 与 SpektraLab-Windows 的 white_balance.cpp 一致：Robertson(1968) 等温线表
 * 把 Kelvin 映射到 CIE 1960 uv，tint 是沿等温线的带符号距离（×-3000，正=品红）。
 * 乘数 = 相机 cam_xyz 矩阵作用于该光源的 XYZ 后的倒数。
 */
object CameraWhiteBalance {

    private data class Ruvt(val r: Double, val u: Double, val v: Double, val t: Double)

    // Robertson (1968)，DNG SDK 表：mired, u, v, 等温线斜率。
    private val table = arrayOf(
        Ruvt(0.0, 0.18006, 0.26352, -0.24341), Ruvt(10.0, 0.18066, 0.26589, -0.25479),
        Ruvt(20.0, 0.18133, 0.26846, -0.26876), Ruvt(30.0, 0.18208, 0.27119, -0.28539),
        Ruvt(40.0, 0.18293, 0.27407, -0.30470), Ruvt(50.0, 0.18388, 0.27709, -0.32675),
        Ruvt(60.0, 0.18494, 0.28021, -0.35156), Ruvt(70.0, 0.18611, 0.28342, -0.37915),
        Ruvt(80.0, 0.18740, 0.28668, -0.40955), Ruvt(90.0, 0.18880, 0.28997, -0.44278),
        Ruvt(100.0, 0.19032, 0.29326, -0.47888), Ruvt(125.0, 0.19462, 0.30141, -0.58204),
        Ruvt(150.0, 0.19962, 0.30921, -0.70471), Ruvt(175.0, 0.20525, 0.31647, -0.84901),
        Ruvt(200.0, 0.21142, 0.32312, -1.0182), Ruvt(225.0, 0.21807, 0.32909, -1.2168),
        Ruvt(250.0, 0.22511, 0.33439, -1.4512), Ruvt(275.0, 0.23247, 0.33904, -1.7298),
        Ruvt(300.0, 0.24010, 0.34308, -2.0637), Ruvt(325.0, 0.24702, 0.34655, -2.4681),
        Ruvt(350.0, 0.25591, 0.34951, -2.9641), Ruvt(375.0, 0.26400, 0.35200, -3.5814),
        Ruvt(400.0, 0.27218, 0.35407, -4.3633), Ruvt(425.0, 0.28039, 0.35577, -5.3762),
        Ruvt(450.0, 0.28863, 0.35714, -6.7262), Ruvt(475.0, 0.29685, 0.35823, -8.5955),
        Ruvt(500.0, 0.30505, 0.35907, -11.324), Ruvt(525.0, 0.31320, 0.35968, -15.628),
        Ruvt(550.0, 0.32129, 0.36011, -23.325), Ruvt(575.0, 0.32931, 0.36038, -40.770),
        Ruvt(600.0, 0.33724, 0.36051, -116.45),
    )
    private const val TINT_SCALE = -3000.0

    /** Kelvin + tint → CIE xy。结果写进 [out][0..1]。 */
    fun temperatureTintToXy(temperature: Double, tint: Double, out: DoubleArray) {
        val r = 1.0e6 / temperature.coerceIn(1700.0, 50000.0)
        val offset = tint * (1.0 / TINT_SCALE)
        for (i in 0 until 30) {
            if (r < table[i + 1].r || i == 29) {
                val f = (table[i + 1].r - r) / (table[i + 1].r - table[i].r)
                var u = table[i].u * f + table[i + 1].u * (1.0 - f)
                var v = table[i].v * f + table[i + 1].v * (1.0 - f)
                var uu1 = 1.0; var vv1 = table[i].t; var uu2 = 1.0; var vv2 = table[i + 1].t
                val len1 = sqrt(1.0 + vv1 * vv1); val len2 = sqrt(1.0 + vv2 * vv2)
                uu1 /= len1; vv1 /= len1; uu2 /= len2; vv2 /= len2
                var uu3 = uu1 * f + uu2 * (1.0 - f); var vv3 = vv1 * f + vv2 * (1.0 - f)
                val len3 = sqrt(uu3 * uu3 + vv3 * vv3)
                uu3 /= len3; vv3 /= len3
                u += uu3 * offset
                v += vv3 * offset
                out[0] = 1.5 * u / (u - 4.0 * v + 2.0)
                out[1] = v / (u - 4.0 * v + 2.0)
                return
            }
        }
    }

    /**
     * 相机 cam_xyz 矩阵（3x3 行主序）+ Kelvin/tint → 相机白平衡乘数 (R, G, B, G2)。
     * 无法中性化（该相机不支持这个光源）时返回 false。
     */
    fun multipliersFor(camXyz: FloatArray, temperature: Double, tint: Double, out: FloatArray): Boolean {
        val xy = DoubleArray(2)
        temperatureTintToXy(temperature, tint, xy)
        val xyz = doubleArrayOf(xy[0] / xy[1], 1.0, (1.0 - xy[0] - xy[1]) / xy[1])
        val n = DoubleArray(3)
        for (row in 0..2) {
            n[row] = camXyz[row * 3] * xyz[0] + camXyz[row * 3 + 1] * xyz[1] + camXyz[row * 3 + 2] * xyz[2]
        }
        if (!(n[0] > 0 && n[1] > 0 && n[2] > 0)) return false
        out[0] = (n[1] / n[0]).toFloat()
        out[1] = 1f
        out[2] = (n[1] / n[2]).toFloat()
        out[3] = 1f
        return true
    }
}
