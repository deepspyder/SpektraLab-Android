package com.spektralab.android.core.color

import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

data class CurvePoint(val x: Float, val y: Float)

enum class CurveChannel(val key: String) {
    RGB("rgb"),
    LUMA("luma"),
    RED("red"),
    GREEN("green"),
    BLUE("blue"),
}

/** Immutable monotone-Hermite control points. X is strictly ordered; endpoints stay at x=0/1. */
class ToneCurve private constructor(points: List<CurvePoint>) {
    val points: List<CurvePoint> = java.util.Collections.unmodifiableList(points.toList())
    val isIdentity: Boolean
        get() = points.all { it.x == it.y }

    private val tangents: DoubleArray by lazy {
        val d =
            DoubleArray(points.size - 1) { i ->
                (points[i + 1].y.toDouble() - points[i].y) /
                    (points[i + 1].x.toDouble() - points[i].x)
            }
        val m = DoubleArray(points.size)
        m[0] = d.first()
        m[m.lastIndex] = d.last()
        for (i in 1 until m.lastIndex) m[i] =
            if (d[i - 1] * d[i] <= 0) 0.0 else (d[i - 1] + d[i]) / 2
        for (i in d.indices) {
            if (d[i] == 0.0) {
                m[i] = 0.0
                m[i + 1] = 0.0
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val sum = a * a + b * b
            if (sum > 9) {
                val scale = 3 / sqrt(sum)
                m[i] = scale * a * d[i]
                m[i + 1] = scale * b * d[i]
            }
        }
        m
    }

    fun move(index: Int, x: Float, y: Float): ToneCurve {
        require(index in points.indices && x.isFinite() && y.isFinite())
        val nextX =
            when (index) {
                0 -> 0f
                points.lastIndex -> 1f
                else -> x.coerceIn(points[index - 1].x + MIN_GAP, points[index + 1].x - MIN_GAP)
            }
        return of(
            points.mapIndexed { i, p ->
                if (i == index) CurvePoint(nextX, y.coerceIn(0f, 1f)) else p
            }
        )
    }

    fun add(x: Float, y: Float): ToneCurve {
        require(x.isFinite() && y.isFinite())
        if (
            points.size >= MAX_POINTS ||
                x !in MIN_GAP..(1f - MIN_GAP) ||
                points.any { kotlin.math.abs(it.x - x) < MIN_GAP }
        )
            return this
        return of((points + CurvePoint(x, y.coerceIn(0f, 1f))).sortedBy { it.x })
    }

    fun remove(index: Int): ToneCurve =
        if (index in 1 until points.lastIndex) of(points.filterIndexed { i, _ -> i != index })
        else this

    /** Fritsch-Carlson tangent limiter, evaluated in double then stored as float table entries. */
    fun evaluate(x: Float): Float {
        if (isIdentity) return x.coerceIn(0f, 1f)
        if (x <= 0f) return points.first().y
        if (x >= 1f) return points.last().y
        val m = tangents
        val i = (0 until points.lastIndex).first { x <= points[it + 1].x }
        val h = points[i + 1].x.toDouble() - points[i].x
        val t = (x - points[i].x) / h
        val t2 = t * t
        val t3 = t2 * t
        return ((2 * t3 - 3 * t2 + 1) * points[i].y +
                (t3 - 2 * t2 + t) * h * m[i] +
                (-2 * t3 + 3 * t2) * points[i + 1].y +
                (t3 - t2) * h * m[i + 1])
            .coerceIn(0.0, 1.0)
            .toFloat()
    }

    override fun equals(other: Any?): Boolean = other is ToneCurve && points == other.points

    override fun hashCode(): Int = points.hashCode()

    companion object {
        const val MIN_GAP = .01f
        const val MAX_POINTS = 12
        val IDENTITY = ToneCurve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f)))

        fun of(points: List<CurvePoint>): ToneCurve {
            require(points.size in 2..MAX_POINTS)
            require(points.first().x == 0f && points.last().x == 1f)
            require(
                points.all {
                    it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f
                }
            )
            require(points.zipWithNext().all { (a, b) -> b.x - a.x >= MIN_GAP - .000001f })
            return ToneCurve(points.toList())
        }
    }
}

/** Versioned curves, applied after legacy colour balance and before the existing vignette. */
class ToneCurves private constructor(private val rows: List<ToneCurve>) {
    operator fun get(channel: CurveChannel): ToneCurve = rows[channel.ordinal]

    val isIdentity: Boolean
        get() = rows.all { it.isIdentity }

    val isDefault: Boolean
        get() = rows.all { it == ToneCurve.IDENTITY }

    fun with(channel: CurveChannel, curve: ToneCurve): ToneCurves =
        ToneCurves(rows.mapIndexed { i, old -> if (i == channel.ordinal) curve else old })

    fun table(): FloatArray =
        FloatArray(TABLE_SIZE * 5) { i ->
            rows[i / TABLE_SIZE].evaluate((i % TABLE_SIZE) / (TABLE_SIZE - 1f))
        }

    fun toJson(): JSONObject =
        JSONObject().put("version", 1).apply {
            CurveChannel.entries.forEach { channel ->
                put(
                    channel.key,
                    JSONArray().apply {
                        this@ToneCurves[channel].points.forEach {
                            put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble()))
                        }
                    },
                )
            }
        }

    override fun equals(other: Any?): Boolean = other is ToneCurves && rows == other.rows

    override fun hashCode(): Int = rows.hashCode()

    companion object {
        const val TABLE_SIZE = 1025
        val IDENTITY = ToneCurves(List(5) { ToneCurve.IDENTITY })

        fun fromJson(json: JSONObject?): ToneCurves {
            if (json == null) return IDENTITY
            require(json.getInt("version") == 1) { "Unsupported curve version" }
            return ToneCurves(
                CurveChannel.entries.map { channel ->
                    val points = json.optJSONArray(channel.key) ?: return@map ToneCurve.IDENTITY
                    ToneCurve.of(
                        (0 until points.length()).map { i ->
                            points.getJSONArray(i).let {
                                CurvePoint(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())
                            }
                        }
                    )
                }
            )
        }
    }
}
