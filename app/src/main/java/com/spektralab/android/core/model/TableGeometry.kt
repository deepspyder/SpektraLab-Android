package com.spektralab.android.core.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class TablePoint(val x: Float, val y: Float) {
    operator fun plus(other: TablePoint) = TablePoint(x + other.x, y + other.y)
    operator fun minus(other: TablePoint) = TablePoint(x - other.x, y - other.y)
    operator fun times(value: Float) = TablePoint(x * value, y * value)
    fun dot(other: TablePoint) = x * other.x + y * other.y
    fun cross(other: TablePoint) = x * other.y - y * other.x
    fun length() = sqrt(dot(this))
}

/** One transform for display, picking, overlap, edge joining, and future scene export. */
internal object TableGeometry {
    fun rotate(point: TablePoint, center: TablePoint, degrees: Float): TablePoint {
        if (degrees == 0f) return point
        val angle = Math.toRadians(degrees.toDouble())
        val c = cos(angle).toFloat(); val s = sin(angle).toFloat()
        val p = point - center
        return center + TablePoint(p.x * c - p.y * s, p.x * s + p.y * c)
    }

    fun corners(x: Float, y: Float, width: Float, height: Float, rotation: Float): List<TablePoint> {
        val center = TablePoint(x + width / 2, y + height / 2)
        return listOf(TablePoint(x, y), TablePoint(x + width, y), TablePoint(x + width, y + height), TablePoint(x, y + height))
            .map { rotate(it, center, rotation) }
    }

    fun contains(polygon: List<TablePoint>, point: TablePoint): Boolean = polygon.size >= 3 && polygon.indices.all { i ->
        (polygon[(i + 1) % polygon.size] - polygon[i]).cross(point - polygon[i]) >= -.001f
    }

    fun intersect(subject: List<TablePoint>, clip: List<TablePoint>): List<TablePoint> {
        var result = subject
        clip.indices.forEach { index ->
            val a = clip[index]; val edge = clip[(index + 1) % clip.size] - a
            val input = result
            if (input.isEmpty()) return emptyList()
            result = buildList {
                var previous = input.last()
                var prevDistance = edge.cross(previous - a)
                input.forEach { point ->
                    val distance = edge.cross(point - a)
                    if ((distance >= 0) != (prevDistance >= 0)) {
                        val fraction = prevDistance / (prevDistance - distance)
                        add(previous + (point - previous) * fraction)
                    }
                    if (distance >= 0) add(point)
                    previous = point; prevDistance = distance
                }
            }
        }
        return result
    }

    fun area(polygon: List<TablePoint>): Float = if (polygon.size < 3) 0f else
        abs(polygon.indices.sumOf { i -> polygon[i].cross(polygon[(i + 1) % polygon.size]).toDouble() }.toFloat()) / 2f

    data class Join(val delta: TablePoint, val start: TablePoint, val end: TablePoint)

    /** Only opposing, parallel edges attract; intentional deep stacks stay independent. */
    fun join(moving: List<TablePoint>, other: List<TablePoint>, threshold: Float): Join? {
        if (threshold <= 0 || area(intersect(moving, other)) > minOf(area(moving), area(other)) * .26f) return null
        var best: Join? = null
        var distance = Float.POSITIVE_INFINITY
        for (i in moving.indices) for (j in other.indices) {
            val a = moving[i]; val ae = moving[(i + 1) % moving.size] - a
            val b = other[j]; val be = other[(j + 1) % other.size] - b
            val al = ae.length(); val bl = be.length()
            if (al < .001f || bl < .001f) continue
            val axis = be * (1f / bl)
            if ((ae * (1f / al)).dot(axis) > -.9999f) continue
            val normal = TablePoint(axis.y, -axis.x)
            val gap = (a - b).dot(normal)
            if (abs(gap) > threshold || abs(gap) >= distance) continue
            val start = maxOf(0f, minOf((a - b).dot(axis), (a + ae - b).dot(axis)))
            val end = minOf(bl, maxOf((a - b).dot(axis), (a + ae - b).dot(axis)))
            if (end - start < minOf(al, bl) * .3f) continue
            distance = abs(gap)
            best = Join(normal * -gap, b + axis * start, b + axis * end)
        }
        return best
    }
}
