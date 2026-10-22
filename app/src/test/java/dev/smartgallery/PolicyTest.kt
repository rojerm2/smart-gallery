package dev.smartgallery

import dev.smartgallery.domain.*
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test
    fun nestedDetectorHypothesesRequireAgreementAtEveryPhysicalLandmark() {
        val outer = DetectionPolicy.Region(0f, 0f, 200f, 200f)
        val inner = DetectionPolicy.Region(20f, 40f, 180f, 150f)
        val points =
            listOf(
                DetectionPolicy.Point(60f, 70f),
                DetectionPolicy.Point(140f, 70f),
                DetectionPolicy.Point(100f, 100f),
                DetectionPolicy.Point(65f, 130f),
                DetectionPolicy.Point(135f, 130f),
            )
        assertTrue(
            DetectionPolicy.sameDetection(
                outer,
                points,
                inner,
                points.map { it.copy(x = it.x + 1f, y = it.y - 1f) },
            )
        )
        // Another face inside an overlapping box, or a scaled portrait in a collage, must survive.
        assertFalse(
            DetectionPolicy.sameDetection(
                outer,
                points,
                inner,
                points.map { it.copy(x = it.x + 20f) },
            )
        )
        assertFalse(
            DetectionPolicy.sameDetection(
                outer,
                points,
                inner,
                points.map { it.copy(x = it.x * .8f, y = it.y * .8f) },
            )
        )
        assertFalse(DetectionPolicy.sameDetection(outer, points, inner, points.dropLast(1)))
        assertFalse(
            DetectionPolicy.sameDetection(
                outer,
                points,
                DetectionPolicy.Region(250f, 0f, 450f, 200f),
                points,
            )
        )
        assertFalse(
            DetectionPolicy.sameDetection(
                outer,
                points,
                inner,
                points.mapIndexed { i, p -> if (i == 4) p.copy(y = p.y + 15f) else p },
            )
        )
    }

    private fun v(degrees: Double) =
        doubleArrayOf(cos(Math.toRadians(degrees)), sin(Math.toRadians(degrees)))
            .map { it.toFloat() }
            .toFloatArray()

    @Test
    fun singletonAndRepeatedVideoFramesStayInReview() {
        val frames = List(30) { IdentityPolicy.Evidence("one-video", "same-video-hash") }
        assertFalse(IdentityPolicy.visible(false, frames))
        assertTrue(IdentityPolicy.visible(true, frames))
    }

    @Test
    fun byteIdenticalCopiesDoNotInflateSupport() {
        assertFalse(
            IdentityPolicy.visible(
                false,
                listOf(
                    IdentityPolicy.Evidence("copy1", "hash"),
                    IdentityPolicy.Evidence("copy2", "hash"),
                ),
            )
        )
        assertTrue(
            IdentityPolicy.visible(
                false,
                listOf(
                    IdentityPolicy.Evidence("one", "hash1"),
                    IdentityPolicy.Evidence("two", "hash2"),
                ),
            )
        )
    }

    @Test
    fun unusableAndUncertainEvidenceDoesNotPromotePeople() {
        val evidence =
            listOf(
                IdentityPolicy.Evidence("one", "1"),
                IdentityPolicy.Evidence("two", "2", eligible = false),
                IdentityPolicy.Evidence("three", "3", confident = false),
            )
        assertFalse(IdentityPolicy.visible(false, evidence))
    }

    @Test
    fun aWeakBridgeCannotCollapseACluster() {
        val a = v(0.0)
        val bridge = v(25.0)
        val c = v(50.0)
        assertTrue(IdentityPolicy.cosine(a, bridge) > IdentityPolicy.MATCH)
        assertTrue(IdentityPolicy.cosine(bridge, c) > IdentityPolicy.MATCH)
        assertTrue(IdentityPolicy.score(c, listOf(a, bridge)) < IdentityPolicy.MATCH)
        assertNull(
            IdentityPolicy.choose(listOf("group" to IdentityPolicy.score(c, listOf(a, bridge))))
        )
    }

    @Test
    fun ambiguityStaysUnassigned() {
        assertNull(IdentityPolicy.choose(listOf("one" to .94f, "two" to .92f)))
        assertEquals("one", IdentityPolicy.choose(listOf("one" to .96f, "two" to .72f)))
    }

    @Test
    fun malformedOrZeroEmbeddingsAreNotMatches() {
        assertEquals(-1f, IdentityPolicy.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)))
    }

    @Test
    fun qualityRequiresActualDetailAndUsablePose() {
        assertFalse(QualityPolicy.usable(30, 30, 1000.0, 0f, 0f, 5))
        assertFalse(QualityPolicy.usable(180, 180, 2.0, 0f, 0f, 5))
        assertFalse(QualityPolicy.usable(180, 180, 1000.0, 75f, 0f, 5))
        assertFalse(QualityPolicy.usable(180, 180, 1000.0, 0f, 0f, 3))
        assertTrue(QualityPolicy.usable(180, 180, 1000.0, 0f, 0f, 5))
    }

    @Test
    fun videoSamplesCoverBeyondOpeningAndRemainBounded() {
        val normal = VideoPlan.timestamps(6 * 60 * 60 * 1000L, false)
        val thorough = VideoPlan.timestamps(6 * 60 * 60 * 1000L, true)
        assertEquals(120, normal.size)
        assertEquals(600, thorough.size)
        assertEquals(0L, normal.first())
        assertEquals(6 * 60 * 60 * 1000L - 1, normal.last())
        assertEquals(normal.size, normal.distinct().size)
        assertTrue(VideoPlan.timestamps(0, false) == listOf(0L))
    }

    @Test
    fun screenshotOfTextIsNeitherReceiptNorId() {
        val text = "RECEIPT\nREPUBLIC OF TEST\nIDENTITY\nTOTAL PHP 125.00\nCASH 125.00\nTAX 0.00"
        val categories = DocumentPolicy.classify(setOf("Document"), text, true, 6)
        assertTrue("Screenshot" in categories)
        assertFalse("Receipt" in categories)
        assertFalse("ID / document" in categories)
    }

    @Test
    fun textAloneCannotCreateDocumentCategories() {
        val text = "TOTAL PHP 125.00 CASH 125.00 TAX 0.00 RECEIPT NATIONALITY DATE OF BIRTH"
        assertTrue(DocumentPolicy.classify(setOf("Dog", "Outdoor"), text, false, 6).isEmpty())
    }

    @Test
    fun receiptNeedsVisualAndMonetaryEvidence() {
        val text =
            "SMART GALLERY STORE\nRECEIPT\n2026-10-22\nTOTAL PHP 125.00\nCASH 125.00\nTAX 0.00"
        assertTrue("Receipt" in DocumentPolicy.classify(setOf("Paper"), text, false, 6))
        assertFalse(
            "Receipt" in
                DocumentPolicy.classify(
                    setOf("Paper"),
                    "A note to buy milk\nThen walk home\nRemember the dog\nMake some tea",
                    false,
                    4,
                )
        )
    }

    @Test
    fun broadIdsNeedMoreThanOneKeyword() {
        assertTrue(
            "ID / document" in
                DocumentPolicy.classify(
                    setOf("Document"),
                    "REPUBLIC OF EXAMPLE\nIDENTIFICATION\nDATE OF BIRTH 2000\nNATIONALITY TEST",
                    false,
                    4,
                )
        )
        assertFalse(
            "ID / document" in
                DocumentPolicy.classify(
                    setOf("Paper"),
                    "My passport story is long\nand detailed\nwith several lines\nabout a holiday",
                    false,
                    4,
                )
        )
    }

    @Test
    fun combinedSearchPreservesQuotedPhrasesAndLiteralWildcards() {
        assertEquals(
            listOf("alice", "2026-10", "total php", "50%"),
            SearchSpec.parse(" Alice 2026-10 \"total PHP\" 50% ").tokens,
        )
    }
}
