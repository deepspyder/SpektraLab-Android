package com.spektralab.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class WallFrame(val key: String, val bounds: Rect)
internal data class WallJoin(
    val key: String, val neighbor: String, val position: Offset,
    val seamStart: Offset, val seamEnd: Offset,
)
internal data class WallJoinMotion(val join: WallJoin, val from: Offset)

/** Thresholds are supplied in table units after converting a constant screen-distance budget. */
internal fun findWallJoin(moving: WallFrame, others: List<WallFrame>, threshold: Float): WallJoin? {
    if (threshold <= 0f || moving.bounds.isEmpty) return null
    var best: WallJoin? = null
    var bestDistance = Float.POSITIVE_INFINITY
    val a = moving.bounds
    for ((key, b) in others) {
        if (key == moving.key || b.isEmpty) continue
        val intersection = a.intersect(b)
        // A deliberate stack stays a stack; only shallow edge approaches are magnetic.
        if (!intersection.isEmpty && intersection.width * intersection.height >
            min(a.width*a.height, b.width*b.height) * .26f) continue
        fun alignment(value: Float, choices: List<Float>): Float {
            val closest = choices.minBy { abs(it-value) }
            return if (abs(closest-value) <= threshold) closest else value
        }
        fun offer(position: Offset, vertical: Boolean, seam: Float) {
            val snapped = Rect(position, a.size)
            val start = if (vertical) max(snapped.top,b.top) else max(snapped.left,b.left)
            val end = if (vertical) min(snapped.bottom,b.bottom) else min(snapped.right,b.right)
            val shortest = if (vertical) min(a.height,b.height) else min(a.width,b.width)
            if (end-start < shortest*.3f) return
            val distance=(position-a.topLeft).getDistance()
            if (distance >= bestDistance) return
            bestDistance=distance
            best=WallJoin(moving.key,key,position,
                if(vertical) Offset(seam,start) else Offset(start,seam),
                if(vertical) Offset(seam,end) else Offset(end,seam))
        }
        val y=alignment(a.top,listOf(b.top,b.center.y-a.height/2,b.bottom-a.height))
        if(abs(a.left-b.right)<=threshold) offer(Offset(b.right,y),true,b.right)
        if(abs(a.right-b.left)<=threshold) offer(Offset(b.left-a.width,y),true,b.left)
        val x=alignment(a.left,listOf(b.left,b.center.x-a.width/2,b.right-a.width))
        if(abs(a.top-b.bottom)<=threshold) offer(Offset(x,b.bottom),false,b.bottom)
        if(abs(a.bottom-b.top)<=threshold) offer(Offset(x,b.top-a.height),false,b.top)
    }
    return best
}

/** Only intersecting upper areas transmit light; the rest of a photograph stays opaque. */
internal fun wallOverlaps(bounds: Rect, lower: List<Rect>): List<Rect> =
    lower.mapNotNull { other -> bounds.intersect(other).takeUnless { it.isEmpty } }
