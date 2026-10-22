package dev.smartgallery.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.smartgallery.analysis.FaceEmbedder
import dev.smartgallery.domain.IdentityPolicy
import dev.smartgallery.domain.SearchSpec
import dev.smartgallery.domain.VideoPlan
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GallerySettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(v) {
            prefs.edit().putBoolean("paused", v).commit()
        }

    var chargingOnly: Boolean
        get() = prefs.getBoolean("charging", true)
        set(v) {
            prefs.edit().putBoolean("charging", v).commit()
        }

    var thorough: Boolean
        get() = prefs.getBoolean("thorough", false)
        set(v) {
            prefs.edit().putBoolean("thorough", v).commit()
        }

    internal var legacyPeopleConfirmed: Boolean
        get() = prefs.getBoolean("legacyPeopleConfirmed-v1", false)
        set(v) {
            prefs.edit().putBoolean("legacyPeopleConfirmed-v1", v).commit()
        }

    internal var legacyConstraintTargetsConfirmed: Boolean
        get() = prefs.getBoolean("legacyConstraintTargetsConfirmed-v1", false)
        set(v) {
            prefs.edit().putBoolean("legacyConstraintTargetsConfirmed-v1", v).commit()
        }

    var generation: Long
        get() = prefs.getLong("generation", 0)
        set(v) {
            prefs.edit().putLong("generation", v).commit()
        }

    var reason: String
        get() = prefs.getString("reason", "") ?: ""
        set(v) {
            prefs.edit().putString("reason", v).apply()
        }
}

data class GalleryFilter(
    val query: String = "",
    val folder: String? = null,
    val person: String? = null,
    val category: String? = null,
    val videos: Boolean? = null,
    val oldest: Boolean = false,
)

class GalleryRepository(
    val context: Context,
    val db: GalleryDatabase = GalleryDatabase.get(context),
) {
    val dao = db.dao()
    val settings = GallerySettings(context)
    val crops = File(context.noBackupFilesDir, "faces").apply { mkdirs() }

    fun gallery(filter: GalleryFilter) =
        Pager(PagingConfig(pageSize = 90, initialLoadSize = 90, maxSize = 360)) {
                pageSource(filter)
            }
            .flow

    internal fun pageSource(filter: GalleryFilter) = run {
        val args = mutableListOf<Any>()
        val sql = StringBuilder("SELECT md.* FROM media md WHERE md.accessible=1")
        if (filter.folder != null) {
            sql.append(" AND md.folder=?")
            args += filter.folder
        }
        if (filter.videos != null)
            sql.append(
                if (filter.videos) " AND md.mime LIKE 'video/%'" else " AND md.mime LIKE 'image/%'"
            )
        if (filter.person != null) {
            sql.append(
                " AND EXISTS(SELECT 1 FROM faces f JOIN memberships m ON f.id=m.faceId WHERE f.mediaId=md.id AND f.active=1 AND f.usable=1 AND m.personId=? AND m.dismissed=0)"
            )
            args += filter.person
        }
        if (filter.category != null) {
            sql.append(
                " AND (EXISTS(SELECT 1 FROM labels l WHERE l.mediaId=md.id AND l.label=?) OR EXISTS(SELECT 1 FROM label_overrides o WHERE o.mediaId=md.id AND o.label=? AND o.included=1)) AND NOT EXISTS(SELECT 1 FROM label_overrides o WHERE o.mediaId=md.id AND o.label=? AND o.included=0)"
            )
            repeat(3) { args += filter.category }
        }
        for (token in SearchSpec.parse(filter.query).tokens) {
            sql.append(
                " AND (lower(md.searchText) LIKE ? ESCAPE '\\' OR EXISTS(SELECT 1 FROM ocr o WHERE o.mediaId=md.id AND lower(o.text) LIKE ? ESCAPE '\\'))"
            )
            val pattern =
                "%" + token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            repeat(2) { args += pattern }
        }
        sql.append(
            if (filter.oldest) " ORDER BY md.dateTaken ASC,md.id"
            else " ORDER BY md.dateTaken DESC,md.id"
        )
        dao.page(SimpleSQLiteQuery(sql.toString(), args.toTypedArray()))
    }

    /**
     * Reconciliation only updates metadata. Confirmed decisions live independently of derived rows.
     */
    suspend fun reconcile() =
        reconcileGate.withLock {
            withContext(Dispatchers.IO) {
                preserveLegacyPeopleDecisions()
                val generation = settings.generation
                val token = UUID.randomUUID().toString()
                val exclusions = dao.exclusionList()
                val volumes =
                    if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context)
                    else setOf("external")
                for (volume in volumes) for (video in listOf(false, true)) {
                    val collection =
                        if (video) MediaStore.Video.Media.getContentUri(volume)
                        else MediaStore.Images.Media.getContentUri(volume)
                    val projection =
                        arrayOf(
                            "_id",
                            "_display_name",
                            "mime_type",
                            "datetaken",
                            "date_modified",
                            "_size",
                            if (Build.VERSION.SDK_INT >= 29) "relative_path"
                            else "bucket_display_name",
                            if (video) "duration" else "width",
                        )
                    try {
                        val cursor =
                            context.contentResolver.query(collection, projection, null, null, null)
                                ?: throw java.io.IOException("Media provider unavailable")
                        cursor.use { c ->
                            while (c.moveToNext()) {
                                val id = "$volume:${if(video) "v" else "i"}:${c.getLong(0)}"
                                val old = dao.mediaById(id)
                                val name = c.getString(1) ?: "Media"
                                val folder = c.getString(6) ?: "Unknown folder"
                                val modified = c.getLong(4)
                                val size = c.getLong(5)
                                val excluded =
                                    exclusions.any {
                                        (it.kind == "folder" && it.value == folder) ||
                                            (it.kind == "media" && it.value == id)
                                    }
                                val item =
                                    Media(
                                        id,
                                        ContentUris.withAppendedId(collection, c.getLong(0))
                                            .toString(),
                                        name,
                                        folder,
                                        c.getString(2) ?: if (video) "video/mp4" else "image/jpeg",
                                        c.getLong(3).takeIf { it > 0 } ?: modified * 1000,
                                        modified,
                                        size,
                                        if (video) c.getLong(7) else 0,
                                        old?.hash ?: "",
                                        true,
                                        excluded,
                                        token,
                                        old?.searchText ?: "$name $folder",
                                        old?.analyzed ?: false,
                                        old?.error ?: "",
                                    )
                                val changed =
                                    old == null ||
                                        old.modified != modified ||
                                        old.size != size ||
                                        !old.accessible ||
                                        old.excluded != excluded
                                gate.withLock {
                                    db.withTransaction {
                                        if (settings.generation != generation)
                                            throw kotlinx.coroutines.CancellationException()
                                        dao.media(
                                            item.copy(
                                                analyzed = if (changed) false else item.analyzed
                                            )
                                        )
                                        if (excluded) purgeDerived(id)
                                        else if (changed) dao.stage(Stage(id, "0hash"))
                                    }
                                }
                                if (old?.name != name || old.folder != folder) refreshSearch(id)
                            }
                        }
                    } catch (_: SecurityException) {
                        // Media absent from this accessible snapshot is hidden and purged below.
                    }
                }
                for (id in dao.unseen(token)) gate.withLock {
                    db.withTransaction {
                        dao.inaccessible(id)
                        purgeDerived(id)
                    }
                }
                // Incompatible embedding versions are queued for reanalysis, never compared
                // together.
                for (item in dao.accessibleMedia()) {
                    val stage = dao.stageById(item.id, if (item.video) "1video" else "1faces")
                    val content = if (item.video) null else dao.stageById(item.id, "2content")
                    val outdated =
                        stage == null ||
                            !stage.version.startsWith(FaceEmbedder.VERSION) ||
                            (if (item.video)
                                !stage.version.contains(CONTENT_VERSION) ||
                                    !stage.version.contains(VideoPlan.VERSION)
                            else content?.version != CONTENT_VERSION)
                    if (item.hash.isNotBlank() && outdated)
                        gate.withLock {
                            if (
                                settings.generation == generation &&
                                    dao.mediaById(item.id)?.excluded == false
                            )
                                enqueueAnalysis(item)
                        }
                }
            }
        }

    /** Upgrade older explicit group references once, without pinning later machine additions. */
    internal suspend fun preserveLegacyPeopleDecisions() {
        if (settings.legacyPeopleConfirmed && settings.legacyConstraintTargetsConfirmed) return
        gate.withLock {
            if (settings.legacyPeopleConfirmed && settings.legacyConstraintTargetsConfirmed)
                return@withLock
            db.withTransaction {
                val targets =
                    if (settings.legacyConstraintTargetsConfirmed) emptySet()
                    else
                        dao.negatives()
                            .flatMap { listOf(it.a, it.b) }
                            .filter { it.startsWith("p:") }
                            .map { it.removePrefix("p:") }
                            .toSet()
                for (person in dao.allPeople()) {
                    val named = person.promoted || person.name != Person("").name
                    if ((settings.legacyPeopleConfirmed || !named) && person.id !in targets)
                        continue
                    for (member in dao.membershipsForPerson(person.id)) {
                        dao.membership(
                            member.copy(
                                manual = true,
                                anchor = dao.faceById(member.faceId)?.let(::anchor) ?: member.anchor,
                            )
                        )
                    }
                }
            }
            settings.legacyPeopleConfirmed = true
            settings.legacyConstraintTargetsConfirmed = true
        }
    }

    suspend fun enqueueAnalysis(item: Media) {
        dao.analyzed(item.id, false)
        if (item.video)
            dao.stage(Stage(item.id, "1video", version = videoVersion(settings.thorough)))
        else {
            dao.stage(Stage(item.id, "1faces", version = FaceEmbedder.VERSION))
            dao.stage(Stage(item.id, "2content", version = CONTENT_VERSION))
        }
    }

    suspend fun purgeDerived(id: String) {
        dao.facesForMedia(id).forEach {
            File(crops, it.crop).takeIf { f -> f.parentFile == crops }?.delete()
        }
        // Also remove crops orphaned by process death before the Room transaction committed.
        Files.newDirectoryStream(crops.toPath(), cropPrefix(id) + "-*.jpg").use { paths ->
            paths.forEach { Files.deleteIfExists(it) }
        }
        dao.deleteFaces(id)
        dao.deleteLabels(id)
        dao.deleteOcr(id)
        dao.deleteStages(id)
        // Explicit memberships/negatives/label decisions remain private durable corrections.
    }

    suspend fun refreshSearch(id: String) {
        val item = dao.mediaById(id) ?: return
        if (!item.accessible) {
            dao.searchText(id, "")
            return
        }
        val date =
            DateTimeFormatter.ofPattern("yyyy-MM-dd yyyy MMMM MMM")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(item.dateTaken))
        if (item.excluded) {
            dao.searchText(id, "${item.name} ${item.folder} $date".lowercase())
            return
        }
        val removed = dao.overrides(id).filter { !it.included }.map { it.label }.toSet()
        val labels =
            (dao.distinctLabels(id) + dao.overrides(id).filter { it.included }.map { it.label })
                .filter { it !in removed }
        val names = dao.personNamesForMedia(id)
        val text =
            listOf(item.name, item.folder, date, labels.joinToString(" "), names.joinToString(" "))
                .joinToString(" ")
        dao.searchText(id, text.lowercase())
    }

    suspend fun group(face: Face) {
        val existing = dao.membershipById(face.id)
        if (existing?.manual == true) return
        val anchor = anchor(face)
        val decisions = dao.membershipsByAnchor(anchor).filter { it.manual }.distinctDecisions()
        val confirmed = decisions.singleOrNull()
        if (confirmed != null) {
            dao.membership(confirmed.copy(faceId = face.id))
            return
        }
        if (existing != null) return
        if (decisions.size > 1) {
            // Older contradictory aliases cannot establish a single confirmed identity.
            val person = UUID.randomUUID().toString()
            dao.person(Person(person))
            dao.membership(Membership(face.id, person, confident = false, anchor = anchor))
            return
        }
        val constraints = dao.negatives()
        val scores = mutableListOf<Pair<String, Float>>()
        for (person in dao.allPeople()) {
            val banned =
                constraints.any { n ->
                    pair(n.a, n.b) == pair("f:${face.id}", "p:${person.id}") ||
                        pair(n.a, n.b) == pair("a:$anchor", "p:${person.id}")
                }
            if (!banned) {
                personScore(face, person.id, IdentityPolicy.MATCH - IdentityPolicy.MARGIN)?.let {
                    scores += person.id to it
                }
            }
        }
        val chosen = IdentityPolicy.choose(scores)
        val person = chosen ?: UUID.randomUUID().toString().also { dao.person(Person(it)) }
        dao.membership(Membership(face.id, person, confident = true, anchor = anchor))
    }

    /** Complete-link comparison in bounded pages, including long videos and large identities. */
    private suspend fun personScore(face: Face, personId: String, floor: Float): Float? {
        val vector = face.vector()
        var after = ""
        var minimum: Float? = null
        while (true) {
            val page = dao.personFacePage(personId, face.model, after, 64)
            if (page.isEmpty()) return minimum
            for (member in page) {
                val score = IdentityPolicy.cosine(vector, member.vector())
                minimum = minOf(minimum ?: score, score)
                // This upper bound is already too low to affect matching/ambiguity or review.
                if (minimum < floor) return minimum
            }
            after = page.last().id
        }
    }

    suspend fun suggestions(face: Face): List<Pair<Person, Float>> {
        val constraints = dao.negatives()
        val current = dao.membershipById(face.id)?.personId
        val persistentAnchor = anchor(face)
        return dao.allPeople()
            .filter { p ->
                p.id != current &&
                    constraints.none {
                        pair(it.a, it.b) == pair("f:${face.id}", "p:${p.id}") ||
                            pair(it.a, it.b) == pair("a:$persistentAnchor", "p:${p.id}") ||
                            (current != null && pair(it.a, it.b) == pair("p:$current", "p:${p.id}"))
                    }
            }
            .mapNotNull { p -> personScore(face, p.id, IdentityPolicy.REVIEW)?.let { p to it } }
            .filter { it.second >= IdentityPolicy.REVIEW }
            .sortedByDescending { it.second }
            .take(3)
    }

    /** Journal records only affected keys; undo leaves later machine analysis intact. */
    private suspend fun correct(
        description: String,
        faces: List<String> = emptyList(),
        people: List<String> = emptyList(),
        negatives: List<Negative> = emptyList(),
        overrides: List<Pair<String, String>> = emptyList(),
        confirmPeople: Boolean = false,
        action: suspend () -> Unit,
    ) =
        gate.withLock {
            db.withTransaction {
                val primaryFaces =
                    (faces +
                            if (confirmPeople)
                                people.flatMap { id ->
                                    dao.membershipsForPerson(id).map { it.faceId }
                                }
                            else emptyList())
                        .distinct()
                val anchors =
                    primaryFaces
                        .mapNotNull { id ->
                            dao.faceById(id)?.let(::anchor)
                                ?: dao.membershipById(id)?.anchor?.takeIf { it.isNotBlank() }
                        }
                        .distinct()
                val affectedFaces =
                    (primaryFaces +
                            anchors.flatMap { key ->
                                dao.membershipsByAnchor(key).map { it.faceId }
                            })
                        .distinct()
                val root = JSONObject()
                root.put(
                    "anchorStates",
                    JSONArray(
                        anchors.map { key ->
                            val prior =
                                dao.membershipsByAnchor(key)
                                    .filter { it.manual }
                                    .distinctDecisions()
                                    .singleOrNull()
                            JSONObject()
                                .put("key", key)
                                .put("prior", prior?.let(::membershipJson) ?: JSONObject.NULL)
                        }
                    ),
                )
                root.put(
                    "members",
                    JSONArray(
                        affectedFaces.map { key ->
                            JSONObject()
                                .put("key", key)
                                .put(
                                    "old",
                                    dao.membershipById(key)?.let(::membershipJson)
                                        ?: JSONObject.NULL,
                                )
                        }
                    ),
                )
                root.put(
                    "people",
                    JSONArray(
                        people.distinct().map { key ->
                            JSONObject()
                                .put("key", key)
                                .put(
                                    "old",
                                    dao.personById(key)?.let {
                                        JSONObject()
                                            .put("name", it.name)
                                            .put("promoted", it.promoted)
                                    } ?: JSONObject.NULL,
                                )
                        }
                    ),
                )
                val oldNegatives = dao.negatives()
                root.put(
                    "negatives",
                    JSONArray(
                        negatives.map {
                            JSONObject()
                                .put("a", it.a)
                                .put("b", it.b)
                                .put("existed", it in oldNegatives)
                        }
                    ),
                )
                root.put(
                    "overrides",
                    JSONArray(
                        overrides.map { (id, label) ->
                            JSONObject()
                                .put("media", id)
                                .put("label", label)
                                .put(
                                    "old",
                                    dao.overrides(id).find { it.label == label }?.included
                                        ?: JSONObject.NULL,
                                )
                        }
                    ),
                )
                action()
                if (confirmPeople)
                    for (id in people) {
                        for (member in dao.membershipsForPerson(id)) {
                            dao.membership(
                                member.copy(
                                    manual = true,
                                    anchor =
                                        dao.faceById(member.faceId)?.let(::anchor) ?: member.anchor,
                                )
                            )
                        }
                    }
                for (key in anchors) {
                    // The explicitly acted-on face wins over its obsolete detector aliases.
                    val explicit =
                        faces
                            .mapNotNull { dao.membershipById(it) }
                            .filter { it.manual && it.anchor == key }
                    val candidates =
                        if (explicit.isNotEmpty()) explicit
                        else
                            primaryFaces
                                .mapNotNull { dao.membershipById(it) }
                                .filter { it.manual && it.anchor == key }
                    val decision = candidates.distinctDecisions().singleOrNull() ?: continue
                    for (alias in dao.membershipsByAnchor(key)) {
                        dao.membership(decision.copy(faceId = alias.faceId))
                    }
                }
                dao.undo(Undo(description = description, payload = root.toString()))
                dao.trimUndo()
                affectedSearch(affectedFaces, people, overrides.map { it.first })
            }
        }

    suspend fun rename(id: String, name: String) =
        correct("Rename person", people = listOf(id), confirmPeople = true) {
            val p = dao.personById(id) ?: return@correct
            dao.person(p.copy(name = name.trim().take(80).ifBlank { "Unnamed person" }))
        }

    suspend fun promote(id: String) =
        correct("Promote person", people = listOf(id), confirmPeople = true) {
            dao.personById(id)?.let { dao.person(it.copy(promoted = true)) }
        }

    suspend fun assign(faceId: String, target: String?, name: String = "New person") {
        val id = target ?: UUID.randomUUID().toString()
        correct("Assign face", faces = listOf(faceId), people = listOf(id), confirmPeople = true) {
            val p = dao.personById(id) ?: Person(id, name.trim().take(80).ifBlank { "New person" })
            dao.person(p.copy(promoted = true))
            dao.membership(
                Membership(
                    faceId,
                    id,
                    true,
                    confident = true,
                    anchor = dao.faceById(faceId)?.let(::anchor) ?: "",
                )
            )
        }
    }

    suspend fun dismiss(faceId: String) =
        correct("Dismiss face", faces = listOf(faceId)) {
            dao.membershipById(faceId)?.let {
                dao.membership(it.copy(manual = true, dismissed = true))
            }
        }

    suspend fun split(faceIds: List<String>, oldId: String) {
        val id = UUID.randomUUID().toString()
        val n = negative("p:$id", "p:$oldId")
        correct(
            "Split person",
            faces = faceIds,
            people = listOf(id, oldId),
            negatives = listOf(n),
            confirmPeople = true,
        ) {
            dao.person(Person(id, promoted = true))
            faceIds.forEach {
                dao.membership(
                    Membership(
                        it,
                        id,
                        true,
                        confident = true,
                        anchor = dao.faceById(it)?.let(::anchor) ?: "",
                    )
                )
            }
            dao.negative(n)
        }
    }

    suspend fun remove(faceId: String, oldId: String) {
        val id = UUID.randomUUID().toString()
        val n = negative("p:$id", "p:$oldId")
        correct(
            "Remove face from person",
            faces = listOf(faceId),
            people = listOf(id, oldId),
            negatives = listOf(n),
            confirmPeople = true,
        ) {
            dao.person(Person(id))
            dao.membership(
                Membership(
                    faceId,
                    id,
                    true,
                    confident = true,
                    anchor = dao.faceById(faceId)?.let(::anchor) ?: "",
                )
            )
            dao.negative(n)
        }
    }

    suspend fun reject(faceId: String, personId: String) {
        val n = negative("f:$faceId", "p:$personId")
        val persistent = dao.faceById(faceId)?.let { negative("a:${anchor(it)}", "p:$personId") }
        correct(
            "Reject suggestion",
            people = listOf(personId),
            negatives = listOfNotNull(n, persistent),
            confirmPeople = true,
        ) {
            dao.negative(n)
            if (persistent != null) dao.negative(persistent)
        }
    }

    suspend fun different(a: String, b: String) {
        val n = negative("p:$a", "p:$b")
        correct(
            "Mark different people",
            people = listOf(a, b),
            negatives = listOf(n),
            confirmPeople = true,
        ) {
            dao.negative(n)
        }
    }

    suspend fun merge(source: String, target: String) {
        require(source != target)
        val n = negative("p:$source", "p:$target")
        check(n !in dao.negatives()) {
            "These people are marked different. Undo that correction before merging."
        }
        val members = dao.membershipsForPerson(source)
        val inherited =
            dao.negatives()
                .filter { it.a == "p:$source" || it.b == "p:$source" }
                .map {
                    negative(
                        if (it.a == "p:$source") "p:$target" else it.a,
                        if (it.b == "p:$source") "p:$target" else it.b,
                    )
                }
        correct(
            "Merge people",
            faces = members.map { it.faceId },
            people = listOf(target),
            negatives = inherited,
            confirmPeople = true,
        ) {
            members.forEach {
                dao.membership(it.copy(personId = target, manual = true, confident = true))
            }
            dao.personById(target)?.let { dao.person(it.copy(promoted = true)) }
            inherited.forEach { dao.negative(it) }
            // Source person remains as a durable constraint target and for undo.
        }
    }

    suspend fun label(id: String, label: String, included: Boolean) {
        val normalized = label.trim().take(80)
        require(normalized.isNotBlank())
        correct("Correct category", overrides = listOf(id to normalized)) {
            dao.overrideLabel(LabelOverride(id, normalized, included))
        }
    }

    suspend fun includeDocumentPortrait(face: Face) =
        gate.withLock {
            if (face.embedding.isNotEmpty())
                db.withTransaction {
                    val f = face.copy(usable = true, excludedReason = "", userIncluded = true)
                    dao.face(f)
                    group(f)
                }
        }

    suspend fun undo() =
        gate.withLock {
            db.withTransaction {
                val entry = dao.lastUndo() ?: return@withTransaction
                val root = JSONObject(entry.payload)
                val anchorStates =
                    root.optJSONArray("anchorStates")
                        ?: legacyUndoAnchorStates(root.getJSONArray("members"))
                val fs = mutableListOf<String>()
                val ps = mutableListOf<String>()
                val ms = mutableListOf<String>()
                val people = root.getJSONArray("people")
                for (i in 0 until people.length()) {
                    val o = people.getJSONObject(i)
                    val k = o.getString("key")
                    ps += k
                    if (o.isNull("old")) dao.deletePerson(k)
                    else {
                        val v = o.getJSONObject("old")
                        dao.person(Person(k, v.getString("name"), v.getBoolean("promoted")))
                    }
                }
                val members = root.getJSONArray("members")
                for (i in 0 until members.length()) {
                    val o = members.getJSONObject(i)
                    val k = o.getString("key")
                    fs += k
                    if (o.isNull("old")) dao.deleteMembership(k)
                    else {
                        val v = o.getJSONObject("old")
                        dao.membership(membershipFromJson(k, v))
                    }
                }
                // Analysis may have made new aliases since the correction was journaled.
                // Restore the prior anchor decision for those too, or return them to matching.
                val regroup = mutableListOf<String>()
                val snapshotted = fs.toSet()
                for (i in 0 until anchorStates.length()) {
                    val state = anchorStates.getJSONObject(i)
                    for (alias in dao.membershipsByAnchor(state.getString("key"))) {
                        if (alias.faceId in snapshotted) continue
                        fs += alias.faceId
                        if (!state.isNull("prior")) {
                            dao.membership(
                                membershipFromJson(alias.faceId, state.getJSONObject("prior"))
                            )
                        } else {
                            dao.deleteMembership(alias.faceId)
                            regroup += alias.faceId
                        }
                    }
                }
                val negatives = root.getJSONArray("negatives")
                for (i in 0 until negatives.length()) {
                    val o = negatives.getJSONObject(i)
                    if (!o.getBoolean("existed"))
                        dao.deleteNegative(o.getString("a"), o.getString("b"))
                }
                val overrides = root.getJSONArray("overrides")
                for (i in 0 until overrides.length()) {
                    val o = overrides.getJSONObject(i)
                    val id = o.getString("media")
                    ms += id
                    val l = o.getString("label")
                    if (o.isNull("old")) dao.deleteOverride(id, l)
                    else dao.overrideLabel(LabelOverride(id, l, o.getBoolean("old")))
                }
                regroup.forEach { id -> dao.faceById(id)?.let { group(it) } }
                dao.deleteUndo(entry.id)
                affectedSearch(fs, ps, ms)
            }
        }

    /** Older journals saved face keys; recover their anchor scope before restoring those keys. */
    private suspend fun legacyUndoAnchorStates(members: JSONArray): JSONArray {
        val states = mutableMapOf<String, MutableList<Membership>>()
        for (i in 0 until members.length()) {
            val row = members.getJSONObject(i)
            val id = row.getString("key")
            val prior =
                if (row.isNull("old")) null else membershipFromJson(id, row.getJSONObject("old"))
            val key =
                prior?.anchor?.takeIf { it.isNotBlank() }
                    ?: dao.membershipById(id)?.anchor?.takeIf { it.isNotBlank() }
                    ?: dao.faceById(id)?.let(::anchor)
                    ?: continue
            val choices = states.getOrPut(key) { mutableListOf() }
            if (prior?.manual == true) choices += prior
        }
        return JSONArray(
            states.map { (key, choices) ->
                JSONObject()
                    .put("key", key)
                    .put(
                        "prior",
                        choices.distinctDecisions().singleOrNull()?.let(::membershipJson)
                            ?: JSONObject.NULL,
                    )
            }
        )
    }

    private suspend fun affectedSearch(
        faces: List<String>,
        people: List<String>,
        media: List<String>,
    ) {
        val ids =
            (faces.mapNotNull { dao.faceById(it)?.mediaId } +
                    people.flatMap { dao.personFaces(it).map { f -> f.mediaId } } +
                    media)
                .distinct()
        ids.forEach { refreshSearch(it) }
    }

    suspend fun exclude(kind: String, value: String) =
        gate.withLock {
            db.withTransaction {
                dao.exclusion(Exclusion(kind, value))
                dao.accessibleMedia()
                    .filter { if (kind == "folder") it.folder == value else it.id == value }
                    .forEach {
                        dao.media(it.copy(excluded = true, searchText = "", analyzed = false))
                        purgeDerived(it.id)
                        refreshSearch(it.id)
                    }
            }
        }

    suspend fun reindex() {
        preserveLegacyPeopleDecisions()
        gate.withLock {
            // In-flight decodes must not restore an older video checkpoint over the new queue.
            settings.generation++
            db.withTransaction {
                // Rebuild machine groups, including groups fragmented by older comparison caps.
                // Confirmed memberships and negative constraints survive this analysis reset.
                dao.clearAutomaticMemberships()
                for (item in dao.accessibleMedia()) if (item.hash.isBlank())
                    dao.stage(Stage(item.id, "0hash"))
                else enqueueAnalysis(item)
            }
        }
    }

    suspend fun reset() =
        gate.withLock {
            settings.generation++
            settings.paused = true
            val exclusions = dao.exclusionList()
            withContext(Dispatchers.IO) {
                db.clearAllTables()
                // Exclusions are privacy preferences, not derived recognition results.
                exclusions.forEach { dao.exclusion(it) }
                crops.listFiles()?.forEach { it.delete() }
                context.cacheDir
                    .listFiles()
                    ?.filter { it.name.startsWith("image") }
                    ?.forEach { it.deleteRecursively() }
            }
        }

    companion object {
        val gate = Mutex()
        private val reconcileGate = Mutex()

        private fun List<Membership>.distinctDecisions() = distinctBy {
            it.copy(faceId = "", anchor = "")
        }

        private fun membershipJson(member: Membership) =
            JSONObject()
                .put("person", member.personId)
                .put("manual", member.manual)
                .put("dismissed", member.dismissed)
                .put("confident", member.confident)
                .put("anchor", member.anchor)

        private fun membershipFromJson(id: String, value: JSONObject) =
            Membership(
                id,
                value.getString("person"),
                value.getBoolean("manual"),
                value.getBoolean("dismissed"),
                value.getBoolean("confident"),
                value.optString("anchor", ""),
            )

        const val CONTENT_VERSION = "mlkit-label17.0.9-ocr16.0.1-rules1"

        fun videoVersion(thorough: Boolean) =
            FaceEmbedder.VERSION +
                "|" +
                CONTENT_VERSION +
                "|" +
                VideoPlan.VERSION +
                if (thorough) "-thorough" else "-normal"

        fun cropPrefix(mediaId: String) =
            MessageDigest.getInstance("SHA-256").digest(mediaId.toByteArray()).joinToString("") {
                "%02x".format(it)
            }

        fun pair(a: String, b: String) = if (a < b) a to b else b to a

        fun negative(a: String, b: String) = pair(a, b).let { Negative(it.first, it.second) }

        fun anchor(face: Face) =
            if (face.hash.isBlank() || face.location.isBlank()) "face:${face.id}"
            else "${face.hash}:${face.timestamp}:${face.location}"
    }
}
