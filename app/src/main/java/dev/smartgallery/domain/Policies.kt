package dev.smartgallery.domain

import kotlin.math.abs
import kotlin.math.sqrt

object IdentityPolicy {
    const val CONFIG = "complete-link-v1-provisional"
    const val MATCH = 0.86f
    const val REVIEW = 0.55f
    const val MARGIN = 0.08f

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty())
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        a.indices.forEach {
            dot += a[it] * b[it]
            aa += a[it] * a[it]
            bb += b[it] * b[it]
        }
        return if (aa <= 0 || bb <= 0) -1f else (dot / sqrt(aa * bb)).toFloat().coerceIn(-1f, 1f)
    }

    /** Minimum similarity to every member, never a union-find/transitive edge. */
    fun score(face: FloatArray, members: List<FloatArray>): Float =
        members.minOfOrNull { cosine(face, it) } ?: -1f

    fun choose(scores: List<Pair<String, Float>>): String? {
        val sorted = scores.sortedByDescending { it.second }
        val best = sorted.firstOrNull() ?: return null
        return best.first.takeIf {
            best.second >= MATCH && (sorted.size == 1 || best.second - sorted[1].second >= MARGIN)
        }
    }

    fun visible(manualPromotion: Boolean, evidence: List<Evidence>): Boolean =
        manualPromotion ||
            evidence
                .filter { it.eligible && it.confident }
                .map { it.contentHash.ifBlank { it.mediaId } }
                .distinct()
                .size >= 2

    data class Evidence(
        val mediaId: String,
        val contentHash: String,
        val eligible: Boolean = true,
        val confident: Boolean = true,
    )
}

object QualityPolicy {
    // Pixel size is measured on the real decoded source, before alignment/upscaling.
    fun usable(
        width: Int,
        height: Int,
        sharpness: Double,
        yaw: Float,
        pitch: Float,
        landmarks: Int,
    ): Boolean =
        width >= 80 &&
            height >= 80 &&
            sharpness >= 45 &&
            abs(yaw) <= 38 &&
            abs(pitch) <= 30 &&
            landmarks == 5
}

object VideoPlan {
    const val VERSION = "uniform-v1"

    /** At most 120 normal / 600 thorough frames, distributed across the entire duration. */
    fun timestamps(durationMs: Long, thorough: Boolean): List<Long> {
        if (durationMs <= 0) return listOf(0)
        val step = if (thorough) 2_000L else 10_000L
        val cap = if (thorough) 600 else 120
        val count = ((durationMs / step) + 2).coerceIn(2, cap.toLong()).toInt()
        return List(count) { i -> i * (durationMs - 1) / (count - 1) }.distinct()
    }
}

object DocumentPolicy {
    /** OCR alone never declares an ID or receipt. Visual paper/document evidence is required. */
    fun classify(
        labels: Set<String>,
        text: String,
        screenshot: Boolean,
        lineCount: Int,
        paperRegion: Boolean = false,
    ): Set<String> {
        val out = mutableSetOf<String>()
        if (screenshot) out += "Screenshot"
        val visual =
            paperRegion ||
                labels.any {
                    it.lowercase() in
                        setOf(
                            "paper",
                            "document",
                            "receipt",
                            "identity document",
                            "font",
                            "handwriting",
                        )
                }
        val t = text.lowercase()
        val substantial = lineCount >= 4 && text.count { it.isLetterOrDigit() } >= 35
        if (visual && substantial) {
            out += "Document"
            val money = Regex("(?:php|usd|₱|\\$)\\s*\\d|\\d+[.,]\\d{2}").containsMatchIn(t)
            if (
                !screenshot &&
                    money &&
                    listOf("total", "subtotal", "cash", "tax", "receipt").count { it in t } >= 3
            )
                out += "Receipt"
            if (
                !screenshot &&
                    listOf(
                            "date of birth",
                            "nationality",
                            "identity",
                            "passport",
                            "license",
                            "identification",
                            "republic of",
                        )
                        .count { it in t } >= 2
            )
                out += "ID / document"
        }
        return out
    }
}

data class SearchSpec(val tokens: List<String>) {
    companion object {
        fun parse(query: String) =
            SearchSpec(
                Regex("\"([^\"]+)\"|(\\S+)")
                    .findAll(query.trim())
                    .map { it.groupValues[1].ifBlank { it.groupValues[2] }.lowercase() }
                    .take(12)
                    .toList()
            )
    }
}
