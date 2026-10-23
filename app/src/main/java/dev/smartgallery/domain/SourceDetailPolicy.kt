package dev.smartgallery.domain

import dev.smartgallery.domain.DetectionPolicy.Region
import kotlin.math.abs

/** Spatial correspondence only; this does not establish a person's identity. */
object SourceDetailPolicy {
    fun region(key: String): Region? {
        val parts = key.split('-').map { it.toFloatOrNull() ?: return null }
        if (parts.size != 4 || parts.any { !it.isFinite() }) return null
        val (x, y, w, h) = parts
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > 1000 || y + h > 1000) return null
        return Region(x, y, x + w, y + h)
    }

    /** Map crop-normalized detections through the actual rounded decoder output geometry. */
    fun toSource(candidate: Region, crop: Region, canvasWidth: Int, canvasHeight: Int): Region {
        require(canvasWidth > 0 && canvasHeight > 0)
        fun x(value: Float) =
            (crop.left + value * (crop.right - crop.left) / 1000) * 1000 / canvasWidth
        fun y(value: Float) =
            (crop.top + value * (crop.bottom - crop.top) / 1000) * 1000 / canvasHeight
        return Region(x(candidate.left), y(candidate.top), x(candidate.right), y(candidate.bottom))
    }

    /** Require a unique overlapping detection with a consistent center, regardless of quality. */
    fun correspondingIndex(target: Region, candidates: List<Region>): Int? =
        candidates.indices
            .filter { index ->
                val other = candidates[index]
                val intersection =
                    (minOf(target.right, other.right) - maxOf(target.left, other.left))
                        .coerceAtLeast(0f) *
                        (minOf(target.bottom, other.bottom) - maxOf(target.top, other.top))
                            .coerceAtLeast(0f)
                val union = target.area + other.area - intersection
                union > 0 &&
                    intersection / union >= .5f &&
                    abs(target.left + target.right - other.left - other.right) <=
                        (target.right - target.left) * .5f &&
                    abs(target.top + target.bottom - other.top - other.bottom) <=
                        (target.bottom - target.top) * .5f
            }
            .singleOrNull()
}
