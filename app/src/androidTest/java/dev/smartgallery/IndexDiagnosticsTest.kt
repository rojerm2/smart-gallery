package dev.smartgallery

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.smartgallery.data.GallerySettings
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit read-only diagnosis: aggregate counters only, safe with granted personal media. */
@RunWith(AndroidJUnit4::class)
class IndexDiagnosticsTest {
    @Test
    fun reportExistingIndexCountersWithoutReadingMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Read-only indexing diagnosis requires explicit host opt-in",
            InstrumentationRegistry.getArguments().getString("indexDiagnostics") == "true",
        )
        val context = instrumentation.targetContext
        val file = context.getDatabasePath("gallery.db")
        check(file.isFile)
        repeat(3) { observation ->
            val output = StringBuilder("\nIndex diagnosis observation=$observation")
            SQLiteDatabase.openDatabase(
                    file.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                )
                .use { db ->
                    fun report(name: String, query: String) {
                        db.rawQuery(query, null).use { cursor ->
                            while (cursor.moveToNext()) {
                                output.append("\n$name")
                                repeat(cursor.columnCount) {
                                    output.append(" ").append(cursor.getString(it))
                                }
                            }
                        }
                    }
                    report(
                        "media(total,eligible,analyzed,hashed,failed)",
                        "SELECT count(*),coalesce(sum(accessible=1 AND excluded=0),0)," +
                            "coalesce(sum(analyzed=1 AND accessible=1 AND excluded=0),0)," +
                            "coalesce(sum(hash!=''),0),coalesce(sum(error!=''),0) FROM media",
                    )
                    report(
                        "stages(kind,state,count,checkpoints,attempts)",
                        "SELECT kind,state,count(*),sum(checkpoint),sum(attempts) FROM stages GROUP BY kind,state ORDER BY kind,state",
                    )
                    report(
                        "faces(total,usable)",
                        "SELECT count(*),coalesce(sum(usable=1 AND active=1),0) FROM faces",
                    )
                }
            val settings = GallerySettings(context)
            // Reason values come from fixed application strings, never a media row.
            output.append(
                "\nsettings paused=${settings.paused} chargingOnly=${settings.chargingOnly} reason=${settings.reason}\n"
            )
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", output.toString()) })
            if (observation < 2) SystemClock.sleep(10_000)
        }
    }
}
