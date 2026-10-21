package dev.smartgallery

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.analysis.FaceEmbedder
import dev.smartgallery.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchIntegrationTest {
    @Test
    fun personCategoryFolderDateOcrAndLiteralWildcardsCombineInRealSqlitePaging() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).build()
        try {
            val repo = GalleryRepository(context, db)
            val a =
                Media(
                    "a",
                    "content://synthetic/a",
                    "50% discount.jpg",
                    "Camera/",
                    "image/jpeg",
                    1792627200000L,
                    1,
                    100,
                    hash = "a",
                )
            val b =
                a.copy(
                    id = "b",
                    uri = "content://synthetic/b",
                    name = "500 discount.jpg",
                    hash = "b",
                )
            repo.dao.media(a)
            repo.dao.media(b)
            repo.dao.label(Label("a", "Dog"))
            repo.dao.label(Label("b", "Dog"))
            repo.dao.ocr(Ocr("a", 0, "TOTAL PHP 125.00"))
            repo.dao.person(Person("person", "Alice", true))
            for (key in listOf("face1", "face2")) {
                repo.dao.face(
                    Face(
                        key,
                        "a",
                        "a",
                        0,
                        byteArrayOf(),
                        FaceEmbedder.VERSION,
                        100.0,
                        "fixture.jpg",
                    )
                )
                repo.dao.membership(Membership(key, "person", true, confident = true))
            }
            repo.refreshSearch("a")
            repo.refreshSearch("b")
            suspend fun ids(filter: GalleryFilter): List<String> {
                val page =
                    repo.pageSource(filter).load(PagingSource.LoadParams.Refresh(null, 90, false))
                assertTrue(
                    "Paging SQL must load successfully: $page",
                    page is PagingSource.LoadResult.Page,
                )
                return (page as PagingSource.LoadResult.Page).data.map { it.id }
            }
            assertEquals(
                listOf("a"),
                ids(
                    GalleryFilter(
                        query = "Alice Dog 2026-10 \"total PHP\"",
                        folder = "Camera/",
                        person = "person",
                        category = "Dog",
                    )
                ),
            )
            assertEquals(listOf("a"), ids(GalleryFilter(query = "50%")))
            assertEquals(listOf("a"), ids(GalleryFilter(person = "person")))
            repo.label("a", "  Dog  ", false)
            assertTrue(ids(GalleryFilter(person = "person", category = "Dog")).isEmpty())
            repo.undo()
            assertEquals(listOf("a"), ids(GalleryFilter(person = "person", category = "Dog")))
            repo.exclude("media", "a")
            assertTrue(ids(GalleryFilter(query = "Alice")).isEmpty())
            assertEquals(listOf("a"), ids(GalleryFilter(query = "50%")))
        } finally {
            db.close()
        }
    }
}
