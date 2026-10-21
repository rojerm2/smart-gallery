package dev.smartgallery

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.data.GalleryDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            GalleryDatabase::class.java,
        )

    @Test
    fun migrationPreservesConfirmedAndNegativeDecisions() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO people(id,name,promoted) VALUES('person','Test Person',1)")
            execSQL(
                "INSERT INTO memberships(faceId,personId,manual,dismissed,confident) VALUES('face','person',1,0,1)"
            )
            execSQL("INSERT INTO negatives(a,b) VALUES('p:person','p:other')")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, GalleryDatabase.MIGRATION_1_2).apply {
            query("SELECT personId,manual,anchor FROM memberships WHERE faceId='face'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("person", c.getString(0))
                assertEquals(1, c.getInt(1))
                assertEquals("", c.getString(2))
            }
            query("SELECT count(*) FROM negatives").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
            close()
        }
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name)
    }
}
