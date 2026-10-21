package dev.smartgallery.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.smartgallery.analysis.IndexScheduler
import dev.smartgallery.data.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

private val Sage = Color(0xFF526347)
private val Paper = Color(0xFFF9F8F3)
private val Palette =
    lightColorScheme(
        primary = Sage,
        primaryContainer = Color(0xFFDEE7D4),
        secondaryContainer = Color(0xFFE3EAD9),
        secondary = Color(0xFF69775B),
        background = Paper,
        surface = Paper,
        surfaceVariant = Color(0xFFEBEDE3),
        onSurface = Color(0xFF22271F),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(vm: GalleryViewModel) {
    MaterialTheme(colorScheme = Palette) {
        val context = LocalContext.current
        var tab by rememberSaveable { mutableStateOf(0) }
        var settings by rememberSaveable { mutableStateOf(false) }
        BackHandler(enabled = settings) { settings = false }
        var selected by remember { mutableStateOf<Media?>(null) }
        var person by rememberSaveable { mutableStateOf<String?>(null) }
        var face by remember { mutableStateOf<Face?>(null) }
        var access by remember { mutableStateOf(mediaAccess(context)) }
        val launcher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                access = mediaAccess(context)
                vm.refresh()
            }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            access = mediaAccess(context)
            vm.refresh()
        }
        val filter by vm.filter.collectAsState()
        val progress by vm.progress.collectAsState()
        val people by vm.people.collectAsState()
        val undo by vm.undo.collectAsState()
        val message by vm.message.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) {
            if (message != null) {
                snackbar.showSnackbar(message!!)
                vm.message.value = null
            }
        }
        val titles = listOf("Photos", "Albums", "People", "Review faces")
        Scaffold(
            containerColor = Paper,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    "Smart Gallery",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 24.sp,
                                )
                                Text(
                                    "YOUR LIBRARY, ON YOUR PHONE",
                                    fontSize = 10.sp,
                                    letterSpacing = 1.8.sp,
                                    color = Sage,
                                )
                            }
                        },
                        actions = {
                            if (undo != null)
                                IconButton(
                                    onClick = { vm.action("Correction undone") { vm.repo.undo() } }
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Undo,
                                        "Undo ${undo?.description}",
                                    )
                                }
                            IconButton(onClick = { settings = true }) {
                                Icon(Icons.Rounded.Tune, "Settings and indexing")
                            }
                        },
                    )
                    if (!settings && person == null)
                        OutlinedTextField(
                            value = filter.query,
                            onValueChange = {
                                vm.filter.value = filter.copy(query = it)
                                tab = 0
                            },
                            placeholder = {
                                Text("People, folders, dates, text…", fontSize = 14.sp)
                            },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = {
                                if (filter.query.isNotBlank())
                                    IconButton(
                                        onClick = { vm.filter.value = filter.copy(query = "") }
                                    ) {
                                        Icon(Icons.Rounded.Close, "Clear search")
                                    }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(24.dp),
                            modifier =
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                }
            },
            bottomBar = {
                if (!settings && person == null)
                    NavigationBar(containerColor = Paper) {
                        val icons =
                            listOf(
                                Icons.Rounded.GridView,
                                Icons.Rounded.PhotoLibrary,
                                Icons.Rounded.People,
                                Icons.Rounded.FaceRetouchingNatural,
                            )
                        titles.forEachIndexed { i, title ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = {
                                    tab = i
                                    vm.filter.value = GalleryFilter()
                                },
                                icon = { Icon(icons[i], null) },
                                label = { Text(title, fontSize = 11.sp) },
                            )
                        }
                    }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (settings)
                    SettingsPanel(
                        vm,
                        onBack = { settings = false },
                        onRequestAccess = { launcher.launch(permissions()) },
                    )
                else if (person != null)
                    PersonPanel(
                        vm,
                        person!!,
                        onBack = {
                            person = null
                            vm.filter.value = GalleryFilter()
                        },
                        onMedia = { selected = it },
                        onFace = { face = it },
                    )
                else {
                    if (access != "Full access")
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier =
                                Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                                    .fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    if (access == "Selected media") "Indexing only selected media"
                                    else "Choose what Gallery can see",
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Analysis stays here. Originals are never changed.",
                                    fontSize = 12.sp,
                                )
                                Row {
                                    TextButton(onClick = { launcher.launch(permissions()) }) {
                                        Text(
                                            if (access == "Selected media") "Manage selection"
                                            else "Allow photos & videos"
                                        )
                                    }
                                    TextButton(
                                        onClick = {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}"),
                                                )
                                            )
                                        }
                                    ) {
                                        Text("App access")
                                    }
                                }
                            }
                        }
                    IndexBanner(vm, progress)
                    when (tab) {
                        0 -> {
                            Row(
                                Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    filter.folder
                                        ?: filter.category
                                        ?: if (filter.query.isBlank()) "All photos & videos"
                                        else "Search results",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f),
                                )
                                if (filter.folder != null || filter.category != null)
                                    IconButton(
                                        onClick = {
                                            vm.filter.value = GalleryFilter(query = filter.query)
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Close, "Clear album filter")
                                    }
                                IconButton(
                                    onClick = {
                                        vm.filter.value = filter.copy(oldest = !filter.oldest)
                                    }
                                ) {
                                    Icon(Icons.Rounded.SwapVert, "Reverse date order")
                                }
                            }
                            Row(
                                Modifier.horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = filter.videos == null,
                                    onClick = { vm.filter.value = filter.copy(videos = null) },
                                    label = { Text("All") },
                                )
                                FilterChip(
                                    selected = filter.videos == false,
                                    onClick = { vm.filter.value = filter.copy(videos = false) },
                                    label = { Text("Photos") },
                                )
                                FilterChip(
                                    selected = filter.videos == true,
                                    onClick = { vm.filter.value = filter.copy(videos = true) },
                                    label = { Text("Videos") },
                                )
                            }
                            MediaGrid(vm, onOpen = { selected = it })
                        }
                        1 ->
                            AlbumsPanel(
                                vm,
                                onFolder = {
                                    vm.filter.value = GalleryFilter(folder = it)
                                    tab = 0
                                },
                                onCategory = {
                                    vm.filter.value = GalleryFilter(category = it)
                                    tab = 0
                                },
                            )
                        2,
                        3 -> {
                            val review = tab == 3
                            val cards =
                                people.filter {
                                    if (review) !it.promoted && it.support < 2
                                    else it.promoted || it.support >= 2
                                }
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                Text(
                                    if (review) "A little help, when you like"
                                    else "Familiar faces",
                                    fontSize = 25.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (review)
                                        "Usable singletons and uncertain groups. Review is optional."
                                    else "Distinct photos and videos support these groups.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (cards.isEmpty())
                                EmptyState(
                                    if (review) Icons.Rounded.Face else Icons.Rounded.People,
                                    if (review) "Nothing to review yet"
                                    else "People will appear here",
                                    if (review)
                                        "Usable unmatched faces appear after analysis. Tiny or unclear faces stay out of this list."
                                    else
                                        "One photo stays in Review faces until another distinct item supports a match, or you promote it.",
                                )
                            else
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(150.dp),
                                    contentPadding = PaddingValues(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(20.dp),
                                ) {
                                    items(cards, key = { it.id }) { p ->
                                        Column(
                                            Modifier.clickable {
                                                person = p.id
                                                vm.filter.value = GalleryFilter(person = p.id)
                                            },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            AsyncImage(
                                                model = p.cover?.let { File(vm.repo.crops, it) },
                                                contentDescription = "Open ${p.name}",
                                                contentScale = ContentScale.Crop,
                                                modifier =
                                                    Modifier.size(112.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceVariant
                                                        ),
                                            )
                                            Text(
                                                p.name,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(top = 8.dp),
                                            )
                                            Text(
                                                "${p.count} ${if(p.count==1)"item" else "items"}",
                                                fontSize = 12.sp,
                                                color = Sage,
                                            )
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
        selected?.let { media ->
            MediaViewer(vm, media, onClose = { selected = null }, onFace = { face = it })
        }
        face?.let { f -> FaceActions(vm, f, onClose = { face = null }) }
    }
}

private fun mediaAccess(context: android.content.Context): String {
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    return if (Build.VERSION.SDK_INT >= 33) {
        if (
            granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(Manifest.permission.READ_MEDIA_VIDEO)
        )
            "Full access"
        else if (
            (Build.VERSION.SDK_INT >= 34 &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) ||
                granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO)
        )
            "Selected media"
        else "No access"
    } else if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) "Full access" else "No access"
}

private fun permissions() =
    if (Build.VERSION.SDK_INT >= 34)
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    else if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@Composable
private fun IndexBanner(vm: GalleryViewModel, progress: Progress) {
    val context = LocalContext.current
    val work by
        remember {
                combine(
                    WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWorkFlow(IndexScheduler.NOW),
                    WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWorkFlow(IndexScheduler.PERIODIC),
                ) { now, periodic ->
                    now + periodic
                }
            }
            .collectAsState(emptyList())
    var reason by remember { mutableStateOf(vm.repo.settings.reason) }
    var paused by remember { mutableStateOf(vm.repo.settings.paused) }
    LaunchedEffect(Unit) {
        while (true) {
            reason = vm.repo.settings.reason
            paused = vm.repo.settings.paused
            delay(2000)
        }
    }
    val running = work.any { it.state == WorkInfo.State.RUNNING }
    val title =
        when {
            paused -> "Analysis paused"
            running -> "Organizing your library"
            progress.failed > 0 -> "Some items need another try"
            progress.total == 0 -> "Ready for your library"
            progress.done == progress.total -> "Your library is up to date"
            else -> "Analysis scheduled"
        }
    Surface(
        color = Color(0xFFEDF0E7),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (paused) Icons.Rounded.PauseCircle else Icons.Rounded.AutoAwesome,
                null,
                tint = Sage,
                modifier = Modifier.size(23.dp),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (progress.total > 0)
                        "${progress.done} of ${progress.total} items analyzed${if(vm.repo.settings.chargingOnly&&!running&&!paused)" • waits for charging" else ""}"
                    else "Only accessible media is indexed",
                    fontSize = 11.sp,
                )
                if (running && reason.isNotBlank()) Text(reason, fontSize = 11.sp, color = Sage)
            }
            IconButton(
                onClick = {
                    if (paused) IndexScheduler.resume(context) else IndexScheduler.pause(context)
                    paused = !paused
                }
            ) {
                Icon(
                    if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    if (paused) "Resume indexing" else "Pause indexing",
                )
            }
        }
    }
}

@Composable
fun MediaGrid(vm: GalleryViewModel, onOpen: (Media) -> Unit) {
    val filter by vm.filter.collectAsState()
    val items = vm.media.collectAsLazyPagingItems()
    val refreshing by vm.refreshing.collectAsState()
    if (items.itemCount == 0) {
        if (refreshing || items.loadState.refresh is LoadState.Loading)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        else
            EmptyState(
                Icons.Rounded.PhotoLibrary,
                "No accessible matches",
                if (filter.query.isNotBlank())
                    "Try a person, category, folder, date such as 2026-10, or recognized words. Combine terms to narrow results."
                else "Allow access to photos and videos or change your filters.",
            )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items(items.itemCount, key = { index -> items.peek(index)?.id ?: "loading$index" }) { index
            ->
            items[index]?.let { media ->
                Box(
                    Modifier.aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onOpen(media) }
                ) {
                    AsyncImage(
                        model =
                            ImageRequest.Builder(LocalContext.current)
                                .data(media.uri)
                                .size(320)
                                .build(),
                        contentDescription = media.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (media.video)
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(5.dp),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                        ) {
                            Text(
                                "▶ ${media.duration/60000}:${(media.duration/1000%60).toString().padStart(2,'0')}",
                                color = Color.White,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    if (!media.analyzed || media.excluded)
                        Icon(
                            if (media.excluded) Icons.Rounded.PauseCircle
                            else if (media.error.isBlank()) Icons.Rounded.Schedule
                            else Icons.Rounded.ErrorOutline,
                            if (media.excluded) "Excluded from analysis"
                            else if (media.error.isBlank()) "Analysis pending"
                            else "Analysis failed",
                            tint = Color.White,
                            modifier =
                                Modifier.align(Alignment.TopEnd)
                                    .padding(5.dp)
                                    .size(18.dp)
                                    .background(Color.Black.copy(alpha = .4f), CircleShape),
                        )
                }
            }
        }
        if (items.loadState.append is LoadState.Error)
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { items.retry() }) { Text("Loading failed. Retry") }
            }
    }
}

@Composable
private fun AlbumsPanel(
    vm: GalleryViewModel,
    onFolder: (String) -> Unit,
    onCategory: (String) -> Unit,
) {
    val folders by vm.folders.collectAsState()
    val categories by vm.categories.collectAsState()
    var mode by rememberSaveable { mutableStateOf(false) }
    var exclude by remember { mutableStateOf<String?>(null) }
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !mode, onClick = { mode = false }, label = { Text("Folders") })
        FilterChip(selected = mode, onClick = { mode = true }, label = { Text("Content") })
    }
    if ((mode && categories.isEmpty()) || (!mode && folders.isEmpty()))
        EmptyState(
            Icons.Rounded.CollectionsBookmark,
            if (mode) "Categories arrive with analysis" else "No folders yet",
            if (mode)
                "Objects, animals, scenes and documents appear here when found. A Dog label describes a category, not a particular pet."
            else "Accessible folders appear after a library refresh.",
        )
    else
        LazyVerticalGrid(
            columns = GridCells.Adaptive(155.dp),
            contentPadding = PaddingValues(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (mode)
                items(categories, key = { it.label }) { c ->
                    AlbumTile(c.label, c.count, c.cover, onClick = { onCategory(c.label) })
                }
            else
                items(folders, key = { it.folder }) { f ->
                    Column {
                        AlbumTile(
                            f.folder.trimEnd('/').substringAfterLast('/'),
                            f.count,
                            f.cover,
                            onClick = { onFolder(f.folder) },
                        )
                        TextButton(
                            onClick = { exclude = f.folder },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("Exclude from analysis", fontSize = 11.sp)
                        }
                    }
                }
        }
    exclude?.let { folder ->
        AlertDialog(
            onDismissRequest = { exclude = null },
            title = { Text("Exclude this folder?") },
            text = {
                Text(
                    "Derived faces, text and categories will be removed. Originals stay on the phone. Restore indexing in Settings."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.action("Folder excluded") { vm.repo.exclude("folder", folder) }
                        exclude = null
                    }
                ) {
                    Text("Exclude")
                }
            },
            dismissButton = { TextButton(onClick = { exclude = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AlbumTile(name: String, count: Int, cover: String?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(cover).size(360).build(),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(1.3f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Text(
            name,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 2,
        )
        Text("$count items", fontSize = 12.sp, color = Sage)
    }
}

@Composable
private fun SettingsPanel(vm: GalleryViewModel, onBack: () -> Unit, onRequestAccess: () -> Unit) {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(vm.repo.settings.chargingOnly) }
    var thorough by remember { mutableStateOf(vm.repo.settings.thorough) }
    var reset by remember { mutableStateOf(false) }
    var notices by remember { mutableStateOf(false) }
    val exclusions by vm.exclusions.collectAsState()
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("On this phone", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Text(
                "Photos, face data and recognized text stay local. No account, network permission or backup of derived data. Grouping never moves or deletes originals.",
                fontSize = 14.sp,
            )
        }
        item {
            SettingsSwitch(
                "Only while charging",
                "Also waits for adequate battery and storage.",
                charging,
            ) {
                charging = it
                vm.repo.settings.chargingOnly = it
                IndexScheduler.pause(context)
                IndexScheduler.resume(context)
            }
        }
        item {
            SettingsSwitch(
                "Thorough video sampling",
                "Up to 600 frames per clip, versus 120 normally. Reindex to apply to completed videos. Brief appearances may still be missed.",
                thorough,
            ) {
                thorough = it
                vm.repo.settings.thorough = it
            }
        }
        item {
            Button(
                onClick = {
                    vm.action("Scan scheduled") {
                        vm.repo.dao.retryFailures()
                        IndexScheduler.resume(context)
                        vm.refresh()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Sync, null)
                Spacer(Modifier.width(8.dp))
                Text("Scan now")
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    vm.action("Reindex scheduled; corrections preserved") {
                        vm.repo.reindex()
                        IndexScheduler.resume(context)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Reindex · preserve corrections")
            }
        }
        item {
            OutlinedButton(onClick = onRequestAccess, modifier = Modifier.fillMaxWidth()) {
                Text("Manage media access")
            }
        }
        item {
            Text("Recognition limits", fontWeight = FontWeight.SemiBold)
            Text(
                "Visual matches can be wrong, especially with relatives, children, masks or side views. Similarity is not an accuracy percentage. Automatic grouping thresholds are provisional until evaluated on representative labeled photos. Review and correct groups locally.",
                fontSize = 13.sp,
            )
        }
        item {
            Text("Documents", fontWeight = FontWeight.SemiBold)
            Text(
                "Latin-script OCR and conservative visual/text rules support broad documents, receipts and IDs. These labels do not verify authenticity or identify every ID type. Empty categories are hidden.",
                fontSize = 13.sp,
            )
        }
        item {
            Text("Background work", fontWeight = FontWeight.SemiBold)
            Text(
                "Android can defer scans. Paused work keeps checkpoints. Phone-specific background restrictions and target Redmi behavior still need device checks.",
                fontSize = 13.sp,
            )
        }
        if (exclusions.isNotEmpty())
            item { Text("Excluded from analysis", fontWeight = FontWeight.SemiBold) }
        items(exclusions, key = { it.kind + it.value }) { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.value, modifier = Modifier.weight(1f), fontSize = 12.sp)
                TextButton(
                    onClick = {
                        vm.action("Indexing restored") {
                            vm.repo.dao.removeExclusion(e.kind, e.value)
                            vm.refresh()
                        }
                    }
                ) {
                    Text("Restore")
                }
            }
        }
        item { TextButton(onClick = { notices = true }) { Text("Models, licenses & privacy") } }
        item {
            OutlinedButton(onClick = { reset = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset all local derived data", color = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Text("Smart Gallery 0.1.0 • Personal testing build", fontSize = 12.sp, color = Sage)
        }
    }
    if (reset)
        AlertDialog(
            onDismissRequest = { reset = false },
            title = { Text("Reset local data?") },
            text = {
                Text(
                    "Remove crops, embeddings, text, categories, people, corrections and progress. Originals remain intact. Indexing pauses until you resume."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        IndexScheduler.pause(context)
                        vm.action("Local data reset") {
                            vm.repo.reset()
                            coil.Coil.imageLoader(context).memoryCache?.clear()
                            coil.Coil.imageLoader(context).diskCache?.clear()
                        }
                        reset = false
                    }
                ) {
                    Text("Reset derived data")
                }
            },
            dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } },
        )
    if (notices) {
        val text by
            produceState("") {
                value =
                    withContext(Dispatchers.IO) {
                        context.assets.open("notices/MODELS.md").bufferedReader().use {
                            it.readText()
                        }
                    }
            }
        AlertDialog(
            onDismissRequest = { notices = false },
            title = { Text("Models & privacy") },
            text = {
                Text(
                    text,
                    modifier =
                        Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                    fontSize = 12.sp,
                )
            },
            confirmButton = { TextButton(onClick = { notices = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, modifier = Modifier.size(45.dp), tint = Sage)
        Text(
            title,
            fontSize = 21.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Text(
            body,
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
