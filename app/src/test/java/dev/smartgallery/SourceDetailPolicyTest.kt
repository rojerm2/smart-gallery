package dev.smartgallery

import dev.smartgallery.domain.DetectionPolicy.Region
import dev.smartgallery.domain.SourceDetailPolicy
import org.junit.Assert.*
import org.junit.Test

class SourceDetailPolicyTest {
    @Test
    fun mapsRoundedNonSquareCropBackToTheFullImage() {
        val mapped =
            SourceDetailPolicy.toSource(
                Region(250f, 200f, 750f, 800f),
                Region(103f, 51f, 703f, 491f),
                1003,
                703,
            )
        assertEquals(253f * 1000 / 1003, mapped.left, .001f)
        assertEquals(139f * 1000 / 703, mapped.top, .001f)
        assertEquals(553f * 1000 / 1003, mapped.right, .001f)
        assertEquals(403f * 1000 / 703, mapped.bottom, .001f)
    }

    @Test
    fun acceptsSmallDetectionJitterButRejectsNeighborsAndAmbiguousOverlaps() {
        val target = Region(250f, 250f, 500f, 700f)
        val same = Region(260f, 260f, 490f, 690f)
        val neighbor = Region(510f, 350f, 620f, 550f)
        assertEquals(1, SourceDetailPolicy.correspondingIndex(target, listOf(neighbor, same)))
        assertNull(SourceDetailPolicy.correspondingIndex(target, listOf(neighbor)))
        assertNull(SourceDetailPolicy.correspondingIndex(target, listOf(same, target)))
        // A tiny face contained within a coarse box is insufficient correspondence.
        assertNull(
            SourceDetailPolicy.correspondingIndex(target, listOf(Region(330f, 400f, 370f, 450f)))
        )
        assertNull(SourceDetailPolicy.correspondingIndex(target, emptyList()))
    }

    @Test
    fun malformedOrOutOfBoundsRegionsNeverBecomeCandidates() {
        for (key in listOf("", "0-0-0-20", "990-0-20-20", "0-NaN-20-20", "0-a-0-20-20")) assertNull(
            SourceDetailPolicy.region(key)
        )
        assertEquals(Region(0f, 0f, 1000f, 1000f), SourceDetailPolicy.region("0-0-1000-1000"))
    }
}
