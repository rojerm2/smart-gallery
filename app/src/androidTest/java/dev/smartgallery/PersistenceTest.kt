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
    fun earlierUndoJournalAlsoReleasesAliasesCreatedAfterTheCorrection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "a")
            repo.assign("fa", null, "Earlier saved correction")
            val assigned = repo.dao.membershipById("fa")!!.personId
            val entry = repo.dao.lastUndo()!!
            val legacy = org.json.JSONObject(entry.payload).apply { remove("anchorStates") }
            repo.dao.deleteUndo(entry.id)
            repo.dao.undo(entry.copy(id = 0, payload = legacy.toString()))
            repo.purgeDerived("a")
            val rediscovered = face("new-face", "a", model = "new-version")
            repo.dao.face(rediscovered)
            repo.group(rediscovered)
            assertEquals(assigned, repo.dao.membershipById(rediscovered.id)!!.personId)
            repo.undo()
            assertFalse(repo.dao.membershipById(rediscovered.id)!!.manual)
            assertNotEquals(assigned, repo.dao.membershipById(rediscovered.id)!!.personId)
            assertNull(repo.dao.personById(assigned))
        } finally {
            db.close()
        }
    }

    @Test
    fun missingGeometryCannotTransferAConfirmedIdentityToAnotherFaceInThePhoto() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            repo.dao.media(media("shared"))
            val first = face("first", "shared").copy(location = "")
            repo.dao.face(first)
            repo.group(first)
            val person = repo.dao.membershipById(first.id)!!.personId
            repo.promote(person)
            val second = face("second", "shared").copy(location = "", embedding = vector(0f, 1f))
            repo.dao.face(second)
            repo.group(second)
            assertNotEquals(person, repo.dao.membershipById(second.id)!!.personId)
            assertFalse(repo.dao.membershipById(second.id)!!.manual)
            assertNotEquals(GalleryRepository.anchor(first), GalleryRepository.anchor(second))
        } finally {
            db.close()
        }
    }

    @Test
    fun correctingRediscoveredFaceUpdatesOldAliasesAndUndoAlsoRestoresFutureAliases() =
        runBlocking {
            val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
            try {
                val repo = GalleryRepository(context, db)
                fixture(repo, "original")
                repo.assign("foriginal", null, "First choice")
                val first = repo.dao.membershipById("foriginal")!!.personId
                suspend fun rediscover(id: String): Face {
                    repo.purgeDerived("original")
                    val f = face(id, "original", model = "version-$id")
                    repo.dao.face(f)
                    repo.group(f)
                    return f
                }
                val alias = rediscover("alias")
                assertEquals(first, repo.dao.membershipById(alias.id)!!.personId)
                repo.assign(alias.id, null, "Corrected choice")
                val corrected = repo.dao.membershipById(alias.id)!!.personId
                assertNotEquals(first, corrected)
                assertEquals(corrected, repo.dao.membershipById("foriginal")!!.personId)
                val future = rediscover("future")
                assertEquals(corrected, repo.dao.membershipById(future.id)!!.personId)
                repo.undo()
                assertEquals(first, repo.dao.membershipById(future.id)!!.personId)
                assertEquals(first, repo.dao.membershipById("foriginal")!!.personId)
                assertEquals(first, repo.dao.membershipById(rediscover("after-undo").id)!!.personId)
                assertNull(repo.dao.personById(corrected))
                repo.undo()
                assertTrue(repo.dao.memberships().none { it.manual })
                assertNull(repo.dao.personById(first))
            } finally {
                db.close()
            }
        }

    @Test
    fun contradictoryLegacyAliasesStayInReviewUntilExplicitlyResolved() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "original")
            val f = repo.dao.faceById("foriginal")!!
            val anchor = GalleryRepository.anchor(f)
            for (id in listOf("first", "second")) {
                repo.dao.person(Person(id))
                repo.dao.membership(
                    Membership("old-$id", id, manual = true, confident = true, anchor = anchor)
                )
            }
            val rediscovered = f.copy(id = "rediscovered", model = "new-version")
            repo.dao.face(rediscovered)
            repo.group(rediscovered)
            val unresolved = repo.dao.membershipById(rediscovered.id)!!
            assertFalse(unresolved.manual)
            assertFalse(unresolved.confident)
            assertTrue(unresolved.personId !in listOf("first", "second"))
            repo.assign(rediscovered.id, "first")
            assertTrue(
                repo.dao.membershipsByAnchor(anchor).all { it.personId == "first" && it.manual }
            )
            val future = rediscovered.copy(id = "future")
            repo.dao.face(future)
            repo.group(future)
            assertEquals("first", repo.dao.membershipById(future.id)!!.personId)
            repo.undo()
            assertEquals("first", repo.dao.membershipById("old-first")!!.personId)
            assertEquals("second", repo.dao.membershipById("old-second")!!.personId)
            assertFalse(repo.dao.membershipById(future.id)!!.manual)
            assertFalse(repo.dao.membershipById(future.id)!!.confident)
        } finally {
            db.close()
        }
    }

    @Test
    fun rejectedTargetIdentitySurvivesRegenerationAndUndoReleasesConfirmation() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            fixture(repo, "target")
            val person = repo.dao.membershipById("ftarget")!!.personId
            repo.dao.media(media("source"))
            val source = face("source-face", "source")
            repo.dao.face(source)
            repo.reject(source.id, person)
            assertTrue(repo.dao.membershipById("ftarget")!!.manual)
            repo.undo()
            assertFalse(repo.dao.membershipById("ftarget")!!.manual)
            repo.reject(source.id, person)
            repo.purgeDerived("target")
            val target = face("new-target-id", "target", model = "new-version")
            repo.dao.face(target)
            repo.group(target)
            assertEquals(person, repo.dao.membershipById(target.id)!!.personId)
            val updatedSource = source.copy(model = "new-version")
            repo.dao.face(updatedSource)
            assertTrue(repo.suggestions(updatedSource).none { it.first.id == person })
            repo.reindex()
            repo.group(updatedSource)
            assertNotEquals(person, repo.dao.membershipById(source.id)!!.personId)
        } finally {
            db.close()
        }
    }

    @Test
    fun differentAndSplitKeepBothReferencedIdentitiesAcrossNewFaceIds() = runBlocking {
        for (split in listOf(false, true)) {
            val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
            try {
                val repo = GalleryRepository(context, db)
                fixture(repo, "a")
                fixture(repo, "b")
                val first = repo.dao.membershipById("fa")!!.personId
                if (split) repo.split(listOf("fb"), first)
                else {
                    val other = Person("other")
                    repo.dao.person(other)
                    repo.dao.membership(repo.dao.membershipById("fb")!!.copy(personId = other.id))
                    repo.different(first, other.id)
                }
                val second = repo.dao.membershipById("fb")!!.personId
                assertNotEquals(first, second)
                for ((media, expected) in listOf("a" to first, "b" to second)) {
                    repo.purgeDerived(media)
                    val regenerated = face("new-$media", media, model = "new-version")
                    repo.dao.face(regenerated)
                    repo.group(regenerated)
                    assertEquals(expected, repo.dao.membershipById(regenerated.id)!!.personId)
                }
                assertTrue(
                    repo.suggestions(repo.dao.faceById("new-a")!!).none { it.first.id == second }
                )
                repo.undo()
                assertTrue(repo.dao.negatives().isEmpty())
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun legacyNegativeTargetIsPinnedEvenAfterTheEarlierNameUpgradeAlreadyRan() = runBlocking {
        val key = "legacy-negative-${UUID.randomUUID()}"
        val isolated =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    super.getSharedPreferences("$key-$name", mode)
            }
        val db = Room.inMemoryDatabaseBuilder(isolated, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(isolated, db)
            fixture(repo, "a")
            val target = repo.dao.membershipById("fa")!!.personId
            repo.settings.legacyPeopleConfirmed = true
            repo.dao.negative(GalleryRepository.negative("a:old-source", "p:$target"))
            repo.preserveLegacyPeopleDecisions()
            assertTrue(repo.dao.membershipById("fa")!!.manual)
            fixture(repo, "later")
            assertFalse(repo.dao.membershipById("flater")!!.manual)
            repo.preserveLegacyPeopleDecisions()
            assertFalse(repo.dao.membershipById("flater")!!.manual)
            repo.purgeDerived("a")
            val regenerated = face("new-a", "a", model = "new-version")
            repo.dao.face(regenerated)
            repo.group(regenerated)
            assertEquals(target, repo.dao.membershipById(regenerated.id)!!.personId)
        } finally {
            db.close()
            context.deleteSharedPreferences("$key-settings")
        }
    }

    @Test
    fun longVideoFacesStayTogetherAndAnotherPhotoProvidesSecondSupport() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            repo.dao.media(media("video").copy(mime = "video/mp4", duration = 600_000))
            repeat(130) { frame ->
                val f = face("frame-$frame", "video").copy(timestamp = frame * 4000L)
                repo.dao.face(f)
                repo.group(f)
            }
            val card = repo.dao.peopleCards().first().single()
            assertEquals(1, card.support)
            assertEquals(1, card.count)
            assertFalse(card.promoted)
            fixture(repo, "another-photo")
            val supported = repo.dao.peopleCards().first().single()
            assertEquals(card.id, supported.id)
            assertEquals(2, supported.support)
            assertEquals(2, supported.count)
            repo.reindex()
            assertTrue(repo.dao.memberships().isEmpty())
            for (item in listOf("video", "another-photo")) {
                for (f in repo.dao.facesForMedia(item)) repo.group(f)
            }
            assertEquals(2, repo.dao.peopleCards().first().single().support)
        } finally {
            db.close()
        }
    }

    @Test
    fun completeLinkAndAmbiguityIncludeMembersBeyondTheFirstDatabasePage() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            repo.dao.media(media("many"))
            repo.dao.person(Person("large-group"))
            repeat(65) { index ->
                val f = face("a-%03d".format(index), "many")
                repo.dao.face(f)
                repo.dao.membership(Membership(f.id, "large-group", confident = true))
            }
            val last = face("z-last", "many").copy(embedding = vector(0f, 1f))
            repo.dao.face(last)
            repo.dao.membership(Membership(last.id, "large-group", confident = true))
            repo.dao.media(media("new"))
            val candidate = face("candidate", "new")
            repo.dao.face(candidate)
            repo.group(candidate)
            assertNotEquals("large-group", repo.dao.membershipById(candidate.id)!!.personId)
            assertTrue(repo.suggestions(candidate).none { it.first.id == "large-group" })

            // A lower score on a later page must also contribute to the runner-up margin.
            repo.dao.face(last.copy(embedding = vector(.89f, .4559605f)))
            repo.dao.person(Person("rival"))
            val rival = face("rival-face", "many").copy(embedding = vector(.82f, .5723635f))
            repo.dao.face(rival)
            repo.dao.membership(Membership(rival.id, "rival", confident = true))
            repo.dao.deleteMembership(candidate.id)
            repo.group(candidate)
            val assigned = repo.dao.membershipById(candidate.id)!!.personId
            assertNotEquals("large-group", assigned)
            assertNotEquals("rival", assigned)
        } finally {
            db.close()
        }
    }

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
            val secondFace =
                face("second-face", "shared")
                    .copy(embedding = vector(0f, 1f), location = "55-10-30-40")
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
    ) = Face(id, media, hash, 0, vector(), model, 100.0, "$id.jpg", location = "10-10-30-40")

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
            repo.group(f)
            assertNotEquals(person, repo.dao.membershipById("fb")!!.personId)
            assertTrue(repo.suggestions(f).none { it.first.id == person })
        } finally {
            db.close()
        }
    }
}
