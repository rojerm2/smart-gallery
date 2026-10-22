package dev.smartgallery.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "media", indices = [Index("folder"), Index("dateTaken"), Index("hash")])
data class Media(
    @PrimaryKey val id: String,
    val uri: String,
    val name: String,
    val folder: String,
    val mime: String,
    val dateTaken: Long,
    val modified: Long,
    val size: Long,
    val duration: Long = 0,
    val hash: String = "",
    val accessible: Boolean = true,
    val excluded: Boolean = false,
    val scanToken: String = "",
    val searchText: String = "",
    val analyzed: Boolean = false,
    val error: String = "",
) {
    val video: Boolean
        get() = mime.startsWith("video/")
}

@Entity(tableName = "stages", primaryKeys = ["mediaId", "kind"], indices = [Index("state")])
data class Stage(
    val mediaId: String,
    val kind: String,
    val state: String = "pending",
    val checkpoint: Int = 0,
    val attempts: Int = 0,
    val version: String = "",
    val error: String = "",
)

@Entity(tableName = "faces", indices = [Index("mediaId"), Index("model"), Index("hash")])
data class Face(
    @PrimaryKey val id: String,
    val mediaId: String,
    val hash: String,
    val timestamp: Long,
    val embedding: ByteArray,
    val model: String,
    val quality: Double,
    val crop: String,
    val usable: Boolean = true,
    val active: Boolean = true,
    val excludedReason: String = "",
    @ColumnInfo(defaultValue = "''") val location: String = "",
    @ColumnInfo(defaultValue = "0") val userIncluded: Boolean = false,
) {
    fun vector(): FloatArray {
        val b = ByteBuffer.wrap(embedding).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(embedding.size / 4) { b.float }
    }
}

@Entity(tableName = "people")
data class Person(
    @PrimaryKey val id: String,
    val name: String = "Unnamed person",
    val promoted: Boolean = false,
)

@Entity(tableName = "memberships", indices = [Index("personId")])
data class Membership(
    @PrimaryKey val faceId: String,
    val personId: String,
    val manual: Boolean = false,
    val dismissed: Boolean = false,
    val confident: Boolean = false,
    @ColumnInfo(defaultValue = "''") val anchor: String = "",
)

@Entity(tableName = "negatives", primaryKeys = ["a", "b"])
data class Negative(val a: String, val b: String)

@Entity(tableName = "exclusions", primaryKeys = ["kind", "value"])
data class Exclusion(val kind: String, val value: String)

@Entity(
    tableName = "labels",
    primaryKeys = ["mediaId", "label", "timestamp"],
    indices = [Index("label")],
)
data class Label(
    val mediaId: String,
    val label: String,
    val timestamp: Long = 0,
    val confidence: Float = 1f,
)

@Entity(tableName = "label_overrides", primaryKeys = ["mediaId", "label"])
data class LabelOverride(val mediaId: String, val label: String, val included: Boolean)

@Entity(tableName = "ocr", primaryKeys = ["mediaId", "timestamp"])
data class Ocr(val mediaId: String, val timestamp: Long, val text: String)

@Entity(tableName = "undo")
data class Undo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val description: String,
    val payload: String,
)

data class PersonCard(
    val id: String,
    val name: String,
    val promoted: Boolean,
    val cover: String?,
    val support: Int,
    val count: Int,
)

data class FolderCard(val folder: String, val count: Int, val cover: String?)

data class CategoryCard(val label: String, val count: Int, val cover: String?)

data class Progress(val total: Int, val done: Int, val failed: Int)

@Dao
interface GalleryDao {
    @Upsert suspend fun media(media: Media)

    @Query("SELECT * FROM media WHERE id=:id") suspend fun mediaById(id: String): Media?

    @Query("SELECT * FROM media WHERE id=:id") fun observeMedia(id: String): Flow<Media?>

    @Query("SELECT * FROM media WHERE accessible=1 AND excluded=0 ORDER BY dateTaken DESC")
    suspend fun accessibleMedia(): List<Media>

    @Query("SELECT id FROM media WHERE scanToken!=:token AND accessible=1")
    suspend fun unseen(token: String): List<String>

    @Query("UPDATE media SET accessible=0,searchText='',analyzed=0 WHERE id=:id")
    suspend fun inaccessible(id: String)

    @Query("UPDATE media SET analyzed=:done,error=:error WHERE id=:id")
    suspend fun analyzed(id: String, done: Boolean, error: String = "")

    @Query("UPDATE media SET hash=:hash WHERE id=:id") suspend fun hash(id: String, hash: String)

    @Query("UPDATE media SET searchText=:text WHERE id=:id")
    suspend fun searchText(id: String, text: String)

    @RawQuery(
        observedEntities =
            [
                Media::class,
                Label::class,
                LabelOverride::class,
                Face::class,
                Membership::class,
                Person::class,
                Ocr::class,
            ]
    )
    fun page(query: SupportSQLiteQuery): PagingSource<Int, Media>

    @Query(
        "SELECT folder,count(*) AS count,max(uri) AS cover FROM media WHERE accessible=1 GROUP BY folder ORDER BY folder"
    )
    fun folders(): Flow<List<FolderCard>>

    @Query("SELECT * FROM media WHERE accessible=1 AND excluded=0 ORDER BY dateTaken DESC LIMIT 1")
    fun latest(): Flow<Media?>

    @Query(
        "SELECT count(*) AS total,COALESCE(sum(CASE WHEN analyzed=1 THEN 1 ELSE 0 END),0) AS done,COALESCE(sum(CASE WHEN error!='' THEN 1 ELSE 0 END),0) AS failed FROM media WHERE accessible=1 AND excluded=0"
    )
    fun progress(): Flow<Progress>

    @Upsert suspend fun stage(stage: Stage)

    @Query("SELECT * FROM stages WHERE mediaId=:mediaId AND kind=:kind")
    suspend fun stageById(mediaId: String, kind: String): Stage?

    @Query(
        "SELECT * FROM stages WHERE state IN ('pending','running','retry') ORDER BY mediaId,kind LIMIT 1"
    )
    suspend fun nextStage(): Stage?

    @Query("DELETE FROM stages WHERE mediaId=:id") suspend fun deleteStages(id: String)

    @Query("UPDATE stages SET state='pending',attempts=0,error='' WHERE state='failed'")
    suspend fun retryFailures()

    @Upsert suspend fun face(face: Face)

    @Query("SELECT * FROM faces WHERE id=:id") suspend fun faceById(id: String): Face?

    @Query("SELECT * FROM faces WHERE mediaId=:id AND active=1 ORDER BY timestamp,quality DESC")
    fun mediaFaces(id: String): Flow<List<Face>>

    @Query("SELECT * FROM faces WHERE active=1 AND usable=1 AND model=:model ORDER BY quality DESC")
    suspend fun usableFaces(model: String): List<Face>

    @Query(
        "SELECT f.* FROM faces f JOIN memberships m ON f.id=m.faceId JOIN media md ON md.id=f.mediaId WHERE m.personId=:id AND f.active=1 AND f.usable=1 AND m.dismissed=0 AND md.accessible=1 AND md.excluded=0 ORDER BY f.quality DESC"
    )
    suspend fun personFaces(id: String): List<Face>

    @Query("SELECT * FROM faces WHERE mediaId=:id")
    suspend fun facesForMedia(id: String): List<Face>

    @Query("DELETE FROM faces WHERE mediaId=:id") suspend fun deleteFaces(id: String)

    @Query("DELETE FROM labels WHERE mediaId=:id") suspend fun deleteLabels(id: String)

    @Query("DELETE FROM ocr WHERE mediaId=:id") suspend fun deleteOcr(id: String)

    @Upsert suspend fun person(person: Person)

    @Query("SELECT * FROM people WHERE id=:id") suspend fun personById(id: String): Person?

    @Query("SELECT * FROM people") suspend fun allPeople(): List<Person>

    @Query("DELETE FROM people WHERE id=:id") suspend fun deletePerson(id: String)

    @Upsert suspend fun membership(membership: Membership)

    @Query("SELECT * FROM memberships WHERE faceId=:id")
    suspend fun membershipById(id: String): Membership?

    @Query("SELECT * FROM memberships WHERE anchor=:anchor AND manual=1 LIMIT 1")
    suspend fun manualByAnchor(anchor: String): Membership?

    @Query("SELECT * FROM memberships WHERE personId=:id")
    suspend fun membershipsForPerson(id: String): List<Membership>

    @Query("DELETE FROM memberships WHERE faceId=:id") suspend fun deleteMembership(id: String)

    @Query("SELECT * FROM memberships") suspend fun memberships(): List<Membership>

    @Query("DELETE FROM memberships WHERE manual=0") suspend fun clearAutomaticMemberships()

    @Upsert suspend fun negative(negative: Negative)

    @Query("SELECT * FROM negatives") suspend fun negatives(): List<Negative>

    @Query("DELETE FROM negatives WHERE a=:a AND b=:b")
    suspend fun deleteNegative(a: String, b: String)

    @Upsert suspend fun exclusion(exclusion: Exclusion)

    @Query("SELECT * FROM exclusions") fun exclusions(): Flow<List<Exclusion>>

    @Query("SELECT * FROM exclusions") suspend fun exclusionList(): List<Exclusion>

    @Query("DELETE FROM exclusions WHERE kind=:kind AND value=:value")
    suspend fun removeExclusion(kind: String, value: String)

    @Upsert suspend fun label(label: Label)

    @Upsert suspend fun overrideLabel(label: LabelOverride)

    @Query("SELECT * FROM label_overrides WHERE mediaId=:id")
    suspend fun overrides(id: String): List<LabelOverride>

    @Query("DELETE FROM label_overrides WHERE mediaId=:id AND label=:label")
    suspend fun deleteOverride(id: String, label: String)

    @Query("SELECT * FROM labels WHERE mediaId=:id") suspend fun labels(id: String): List<Label>

    @Upsert suspend fun ocr(ocr: Ocr)

    @Query("SELECT * FROM ocr WHERE mediaId=:id ORDER BY timestamp")
    suspend fun ocrForMedia(id: String): List<Ocr>

    @Query("SELECT * FROM ocr WHERE mediaId=:id ORDER BY timestamp LIMIT 12")
    suspend fun ocrPreview(id: String): List<Ocr>

    @Query(
        "SELECT DISTINCT p.name FROM people p JOIN memberships m ON m.personId=p.id JOIN faces f ON f.id=m.faceId WHERE f.mediaId=:id AND f.active=1 AND f.usable=1 AND m.dismissed=0 LIMIT 1000"
    )
    suspend fun personNamesForMedia(id: String): List<String>

    @Query("SELECT DISTINCT label FROM labels WHERE mediaId=:id")
    suspend fun distinctLabels(id: String): List<String>

    @Insert suspend fun undo(undo: Undo)

    @Query("SELECT * FROM undo ORDER BY id DESC LIMIT 1") suspend fun lastUndo(): Undo?

    @Query("DELETE FROM undo WHERE id=:id") suspend fun deleteUndo(id: Long)

    @Query("DELETE FROM undo WHERE id NOT IN (SELECT id FROM undo ORDER BY id DESC LIMIT 10)")
    suspend fun trimUndo()

    @Query("SELECT * FROM undo ORDER BY id DESC LIMIT 1") fun undoState(): Flow<Undo?>

    @Query(
        "SELECT p.id,p.name,p.promoted,(SELECT f2.crop FROM faces f2 JOIN memberships m2 ON f2.id=m2.faceId JOIN media md2 ON md2.id=f2.mediaId WHERE m2.personId=p.id AND m2.dismissed=0 AND f2.active=1 AND f2.usable=1 AND md2.accessible=1 AND md2.excluded=0 ORDER BY f2.quality DESC LIMIT 1) AS cover, count(DISTINCT CASE WHEN m.confident=1 THEN f.hash END) AS support,count(DISTINCT f.mediaId) AS count FROM people p JOIN memberships m ON m.personId=p.id JOIN faces f ON f.id=m.faceId JOIN media md ON md.id=f.mediaId WHERE m.dismissed=0 AND f.active=1 AND f.usable=1 AND md.accessible=1 AND md.excluded=0 GROUP BY p.id ORDER BY p.name"
    )
    fun peopleCards(): Flow<List<PersonCard>>

    @Query(
        "SELECT l.label,count(DISTINCT l.mediaId) AS count,max(md.uri) AS cover FROM (SELECT mediaId,label FROM labels UNION SELECT mediaId,label FROM label_overrides WHERE included=1) l JOIN media md ON l.mediaId=md.id WHERE md.accessible=1 AND md.excluded=0 AND NOT EXISTS(SELECT 1 FROM label_overrides o WHERE o.mediaId=l.mediaId AND o.label=l.label AND o.included=0) GROUP BY l.label ORDER BY count DESC,l.label"
    )
    fun categories(): Flow<List<CategoryCard>>
}

@Database(
    entities =
        [
            Media::class,
            Stage::class,
            Face::class,
            Person::class,
            Membership::class,
            Negative::class,
            Exclusion::class,
            Label::class,
            LabelOverride::class,
            Ocr::class,
            Undo::class,
        ],
    version = 2,
    exportSchema = true,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun dao(): GalleryDao

    companion object {
        @Volatile private var instance: GalleryDatabase? = null
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE faces ADD COLUMN location TEXT NOT NULL DEFAULT ''")
                    db.execSQL(
                        "ALTER TABLE faces ADD COLUMN userIncluded INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL("ALTER TABLE memberships ADD COLUMN anchor TEXT NOT NULL DEFAULT ''")
                }
            }

        fun get(context: Context): GalleryDatabase =
            instance
                ?: synchronized(this) {
                    instance
                        ?: Room.databaseBuilder(
                                context.applicationContext,
                                GalleryDatabase::class.java,
                                "gallery.db",
                            )
                            .addMigrations(MIGRATION_1_2)
                            .build()
                            .also { instance = it }
                }
    }
}
