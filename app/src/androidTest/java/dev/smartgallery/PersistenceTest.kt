package dev.smartgallery

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.FaceEmbedder
import dev.smartgallery.data.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @Test
    fun legacyNamedGroupsAreUpgradedOnceWithoutPinningLaterAutomaticAdditions() = runBlocking {
        val key = "legacy-${UUID.randomUUID()}"
        val isolated =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    super.getSharedPreferences("$key-$name", mode)
            }
        val db = Room.inMemoryDatabaseBuilder(isolated, GalleryDatabase::class.java).build()
        try {
            var repo = GalleryRepository(isolated, db)
            fixture(repo, "a")
            val id = repo.dao.membershipById("fa")!!.personId
            // Simulate an earlier build that changed the name without confirming memberships.
            repo.dao.person(repo.dao.personById(id)!!.copy(name = "Legacy fixture name"))
            assertFalse(repo.dao.membershipById("fa")!!.manual)
            repo.preserveLegacyPeopleDecisions()
            assertTrue(repo.dao.membershipById("fa")!!.manual)
            assertEquals(
                GalleryRepository.anchor(repo.dao.faceById("fa")!!),
                repo.dao.membershipById("fa")!!.anchor,
            )
            fixture(repo, "later")
            assertEquals(id, repo.dao.membershipById("flater")!!.personId)
            assertFalse(repo.dao.membershipById("flater")!!.manual)
            repo = GalleryRepository(isolated, db)
            repo.preserveLegacyPeopleDecisions()
            assertFalse(repo.dao.membershipById("flater")!!.manual)
            repo.purgeDerived("a")
            val regenerated = face("new-legacy-face", "a", model = "future-embedding-version")
            repo.dao.face(regenerated)
            repo.group(regenerated)
            assertEquals(id, repo.dao.membershipById(regenerated.id)!!.personId)
            assertEquals("Legacy fixture name", repo.dao.personById(id)!!.name)
        } finally {
            db.close()
            context.deleteSharedPreferences("$key-settings")
        }
    }

    @Test
    fun namingAndPromotionPreserveMembershipsAcrossNewFaceIdsAndEmbeddingVersions() = runBlocking {
        for (promote in listOf(false, true)) {
            val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
            try {
                val repo = GalleryRepository(context, db)
                fixture(repo, "a")
                val id = repo.dao.membershipById("fa")!!.personId
                val before = repo.dao.personById(id)!!
                suspend fun confirm() {
                    if (promote) repo.promote(id) else repo.rename(id, "Pinned fixture person")
                }
                confirm()
                assertTrue(repo.dao.membershipById("fa")!!.manual)
                repo.undo()
                assertFalse(repo.dao.membershipById("fa")!!.manual)
                assertEquals(before, repo.dao.personById(id))
                confirm()
                repo.purgeDerived("a")
                repo.dao.media(media("moved", hash = "a"))
                val regenerated =
                    face("new-face", "moved", hash = "a", model = "future-embedding-version")
                repo.dao.face(regenerated)
                repo.group(regenerated)
                assertEquals(id, repo.dao.membershipById(regenerated.id)!!.personId)
                assertTrue(repo.dao.membershipById(regenerated.id)!!.manual)
                if (promote) assertTrue(repo.dao.personById(id)!!.promoted)
                else assertEquals("Pinned fixture person", repo.dao.personById(id)!!.name)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun onePhotoCanBelongToTwoPeopleAndCategoriesWithoutDuplicateAlbumItems() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "shared")
            val first = repo.dao.membershipById("fshared")!!.personId
            val secondFace = face("second-face", "shared").copy(embedding = vector(0f, 1f))
            repo.dao.face(secondFace)
            repo.group(secondFace)
            val second = repo.dao.membershipById(secondFace.id)!!.personId
            assertNotEquals(first, second)
            val repeated = face("same-person-again", "shared")
            repo.dao.face(repeated)
            repo.group(repeated)
            assertEquals(first, repo.dao.membershipById(repeated.id)!!.personId)
            repo.promote(first)
            repo.promote(second)
            repo.label("shared", "Dog", true)
            repo.label("shared", "Beach", true)
            val cards = repo.dao.peopleCards().first()
            assertEquals(2, cards.size)
            assertTrue(cards.all { it.promoted && it.support == 1 && it.count == 1 })
            for ((person, category) in listOf(first to "Dog", second to "Beach")) {
                val page =
                    repo
                        .pageSource(GalleryFilter(person = person, category = category))
                        .load(PagingSource.LoadParams.Refresh(null, 20, false))
                assertTrue(page is PagingSource.LoadResult.Page)
                assertEquals(
                    listOf("shared"),
                    (page as PagingSource.LoadResult.Page).data.map { it.id },
                )
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun rejectedSuggestionStaysHiddenWhenFaceIdChangesAndUndoRestoresIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "a")
            val target = repo.dao.membershipById("fa")!!.personId
            repo.dao.media(media("b"))
            val original = face("fb", "b")
            repo.dao.face(original)
            assertTrue(repo.suggestions(original).any { it.first.id == target })
            repo.reject(original.id, target)
            repo.purgeDerived("b")
            // Same content and face location rediscovered under a new MediaStore/face ID.
            repo.dao.media(media("moved", hash = "b"))
            val rediscovered = face("regenerated", "moved", hash = "b")
            repo.dao.face(rediscovered)
            repo.group(rediscovered)
            assertNotEquals(target, repo.dao.membershipById(rediscovered.id)!!.personId)
            assertTrue(repo.suggestions(rediscovered).none { it.first.id == target })
            repo.undo()
            assertTrue(repo.suggestions(rediscovered).any { it.first.id == target })
        } finally {
            db.close()
        }
    }

    @Test
    fun personCoverMustBeAUsableFaceEvenWhenAnExcludedCropIsSharper() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "a")
            val person = repo.dao.membershipById("fa")!!.personId
            repo.dao.face(
                face("excluded", "a").copy(usable = false, quality = 9999.0, crop = "excluded.jpg")
            )
            repo.dao.membership(Membership("excluded", person, confident = true))
            assertEquals("fa.jpg", repo.dao.peopleCards().first().single().cover)
        } finally {
            db.close()
        }
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun vector(x: Float = 1f, y: Float = 0f) =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(x).putFloat(y).array()

    private fun media(id: String, hash: String = id) =
        Media(
            id,
            "content://synthetic/$id",
            "$id.jpg",
            "Fixtures/",
            "image/jpeg",
            1792627200000L,
            1,
            100,
            hash = hash,
        )

    private fun face(
        id: String,
        media: String,
        hash: String = media,
        model: String = FaceEmbedder.VERSION,
    ) = Face(id, media, hash, 0, vector(), model, 100.0, "$id.jpg")

    private suspend fun fixture(repo: GalleryRepository, id: String, hash: String = id) {
        repo.dao.media(media(id, hash))
        repo.dao.face(face("f$id", id, hash))
        repo.group(repo.dao.faceById("f$id")!!)
    }

    @Test
    fun duplicateSupportCorrectionsAndUndoSurviveDatabaseReopen() = runBlocking {
        val name = "test-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()
        try {
            var repo = GalleryRepository(context, db)
            fixture(repo, "a", "duplicate")
            fixture(repo, "b", "duplicate")
            assertEquals(1, repo.dao.peopleCards().first().single().support)
            val person = repo.dao.membershipById("fa")!!.personId
            repo.rename(person, "Test Person")
            repo.promote(person)
            repo.different(person, "other")
            repo.label("a", "Dog", false)
            db.close()
            db = Room.databaseBuilder(context, GalleryDatabase::class.java, name).build()
            repo = GalleryRepository(context, db)
            assertEquals("Test Person", repo.dao.personById(person)!!.name)
            assertTrue(repo.dao.personById(person)!!.promoted)
            assertTrue(repo.dao.membershipById("fa")!!.confident)
            assertTrue(repo.dao.negatives().isNotEmpty())
            assertFalse(repo.dao.overrides("a").single().included)
            repo.reindex()
            assertEquals(person, repo.dao.membershipById("fa")!!.personId)
            repo.undo()
            assertTrue(repo.dao.overrides("a").isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun singletonManualPromotionMultiplePeopleAndSplitAreDurable() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "a")
            fixture(repo, "b")
            val person = repo.dao.membershipById("fa")!!.personId
            assertEquals(person, repo.dao.membershipById("fb")!!.personId)
            assertEquals(2, repo.dao.peopleCards().first().single().support)
            repo.split(listOf("fb"), person)
            val split = repo.dao.membershipById("fb")!!.personId
            assertNotEquals(person, split)
            assertTrue(repo.dao.membershipById("fb")!!.manual)
            repo.group(repo.dao.faceById("fb")!!)
            assertEquals(split, repo.dao.membershipById("fb")!!.personId)
            repo.assign("fa", null, "Second person in same photo")
            assertEquals(2, repo.dao.peopleCards().first().size)
            repo.undo()
            assertEquals(person, repo.dao.membershipById("fa")!!.personId)
        } finally {
            db.close()
        }
    }

    @Test
    fun modelVersionMismatchCannotCreateAnAutomaticMatch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "a")
            repo.dao.media(media("b"))
            val f = face("fb", "b", model = "future-incompatible-model")
            repo.dao.face(f)
            repo.group(f)
            assertNotEquals(
                repo.dao.membershipById("fa")!!.personId,
                repo.dao.membershipById("fb")!!.personId,
            )
            val confirmed = repo.dao.membershipById("fa")!!
            repo.dao.membership(confirmed.copy(manual = true))
            repo.reindex()
            assertTrue(repo.dao.membershipById("fa")!!.manual)
        } finally {
            db.close()
        }
    }

    @Test
    fun stageUpsertRetainsSingleCheckpointAndExclusionPurgesOnlyDerivedData() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "a")
            val before = repo.dao.membershipById("fa")!!
            repo.dao.stage(Stage("a", "1video", checkpoint = 7))
            repo.dao.stage(Stage("a", "1video", checkpoint = 7))
            assertEquals(7, repo.dao.nextStage()!!.checkpoint)
            repo.dao.label(Label("a", "Dog"))
            repo.dao.ocr(Ocr("a", 0, "synthetic private text"))
            repo.exclude("folder", "Fixtures/")
            assertTrue(repo.dao.mediaById("a")!!.excluded)
            assertNull(repo.dao.faceById("fa"))
            assertTrue(repo.dao.labels("a").isEmpty())
            assertTrue(repo.dao.ocrForMedia("a").isEmpty())
            assertNull(repo.dao.nextStage())
            assertEquals(before.personId, repo.dao.membershipById("fa")!!.personId)
            assertEquals("content://synthetic/a", repo.dao.mediaById("a")!!.uri)
        } finally {
            db.close()
        }
    }

    @Test
    fun rejectedSuggestionRemainsRejectedAfterReindex() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "a")
            val person = repo.dao.membershipById("fa")!!.personId
            repo.dao.media(media("b"))
            val f = face("fb", "b")
            repo.dao.face(f)
            repo.reject(f.id, person)
            repo.group(f)
            repo.reindex()
            assertNotEquals(person, repo.dao.membershipById("fb")!!.personId)
            assertTrue(repo.suggestions(f).none { it.first.id == person })
        } finally {
            db.close()
        }
    }
}
