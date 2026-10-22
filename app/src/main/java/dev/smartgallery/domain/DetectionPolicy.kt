package dev.smartgallery.domain

import kotlin.math.hypot

/** Suppress repeated detector hypotheses at the same physical landmarks, not similar identities. */
object DetectionPolicy {
    data class Point(val x: Float, val y: Float)

    data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val area: Float
            get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
    }

    fun sameDetection(a: Region, pointsA: List<Point>, b: Region, pointsB: List<Point>): Boolean {
        if (pointsA.size != 5 || pointsB.size != 5 || minOf(a.area, b.area) <= 0f) return false
        val intersection =
            (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
                (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        if (intersection / minOf(a.area, b.area) < .9f) return false
        fun distance(p: Point, q: Point) = hypot(p.x - q.x, p.y - q.y)
        val scale = minOf(distance(pointsA[0], pointsA[1]), distance(pointsB[0], pointsB[1]))
        if (!scale.isFinite() || scale <= 0f) return false
        return pointsA.indices.all { distance(pointsA[it], pointsB[it]) <= scale * .06f }
    }
}
