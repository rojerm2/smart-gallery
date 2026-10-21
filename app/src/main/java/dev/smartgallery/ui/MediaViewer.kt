package dev.smartgallery.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dev.smartgallery.data.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MediaViewer(vm: GalleryViewModel, media: Media, onClose: () -> Unit, onFace: (Face) -> Unit) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    var details by remember { mutableStateOf(false) }
    var addLabel by remember { mutableStateOf(false) }
    var exclude by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var timestamp by remember { mutableLongStateOf(0) }
    var labels by remember { mutableStateOf(emptyList<String>()) }
    var ocr by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(emptyList<Long>()) }
    val faces by remember(media.id) { vm.repo.dao.mediaFaces(media.id) }.collectAsState(emptyList())
    LaunchedEffect(media.id, details) {
        withContext(Dispatchers.IO) {
            val overrides = vm.repo.dao.overrides(media.id)
            labels =
                (vm.repo.dao.labels(media.id).map { it.label } +
                        overrides.filter { it.included }.map { it.label })
                    .distinct()
                    .filter { label -> overrides.none { it.label == label && !it.included } }
            ocr = vm.repo.dao.ocrPreview(media.id).joinToString("\n") { it.text }.take(12000)
            val filter = vm.filter.value
            matches =
                when {
                        filter.person != null ->
                            vm.repo.dao
                                .personFaces(filter.person)
                                .filter { it.mediaId == media.id }
                                .map { it.timestamp }
                        filter.category != null ->
                            vm.repo.dao
                                .labels(media.id)
                                .filter { it.label == filter.category }
                                .map { it.timestamp }
                        else -> emptyList()
                    }
                    .distinct()
                    .sorted()
            timestamp = matches.firstOrNull() ?: 0
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color(0xFF11140F), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            "Close viewer",
                            tint = Color.White,
                        )
                    }
                    Text(
                        media.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { details = !details }) {
                        Icon(Icons.Rounded.Info, "Details and corrections", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            try {
                                val uri = Uri.parse(media.uri)
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = media.mime
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            clipData =
                                                ClipData.newUri(
                                                    context.contentResolver,
                                                    "Media",
                                                    uri,
                                                )
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        "Share media",
                                    )
                                )
                            } catch (_: Exception) {
                                failed = true
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Share, "Share", tint = Color.White)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (failed)
                        Text(
                            "This item is no longer accessible or could not be opened.",
                            color = Color.White,
                            modifier = Modifier.padding(30.dp),
                        )
                    else if (media.video)
                        VideoPlayer(media.uri, timestamp, onError = { failed = true })
                    else {
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offset by remember { mutableStateOf(Offset.Zero) }
                        AsyncImage(
                            model =
                                ImageRequest.Builder(context).data(media.uri).size(2560).build(),
                            contentDescription = media.name,
                            contentScale = ContentScale.Fit,
                            onError = { failed = true },
                            modifier =
                                Modifier.fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            scale = (scale * zoom).coerceIn(1f, 6f)
                                            offset = if (scale == 1f) Offset.Zero else offset + pan
                                        }
                                    }
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                scale = if (scale > 1f) 1f else 2.5f
                                                offset = Offset.Zero
                                            }
                                        )
                                    }
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        translationX = offset.x,
                                        translationY = offset.y,
                                    ),
                        )
                    }
                }
                if (media.video && matches.isNotEmpty())
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        matches.forEach { time ->
                            TextButton(onClick = { timestamp = time }) {
                                Text("Jump to ${time/1000}s", color = Color.White)
                            }
                        }
                    }
                if (details)
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                            Text(
                                SimpleDateFormat("d MMM yyyy • HH:mm", locale)
                                    .format(Date(media.dateTaken))
                            )
                            Text(media.folder, fontSize = 12.sp)
                            Text(
                                if (media.excluded) "Excluded from analysis · original available"
                                else if (media.analyzed)
                                    if (labels.isEmpty() && faces.none { it.usable })
                                        "Analyzed · no confident result"
                                    else "Analyzed"
                                else if (media.error.isNotBlank()) media.error
                                else "Analysis pending or incomplete",
                                fontSize = 12.sp,
                            )
                            if (faces.isNotEmpty()) {
                                Text(
                                    "Faces · tap to correct",
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Row(Modifier.horizontalScroll(rememberScrollState())) {
                                    faces
                                        .filter { it.usable }
                                        .forEach { face ->
                                            AsyncImage(
                                                model = File(vm.repo.crops, face.crop),
                                                contentDescription = "Correct face",
                                                contentScale = ContentScale.Crop,
                                                modifier =
                                                    Modifier.padding(4.dp).size(60.dp).clickable {
                                                        onFace(face)
                                                    },
                                            )
                                        }
                                }
                            }
                            if (faces.any { !it.usable })
                                TextButton(
                                    onClick = { faces.firstOrNull { !it.usable }?.let(onFace) }
                                ) {
                                    Text("Inspect excluded portrait")
                                }
                            labels.forEach { label ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(label, modifier = Modifier.weight(1f))
                                    TextButton(
                                        onClick = {
                                            vm.action("Category removed") {
                                                vm.repo.label(media.id, label, false)
                                            }
                                            labels = labels - label
                                        }
                                    ) {
                                        Text("Remove")
                                    }
                                }
                            }
                            TextButton(onClick = { addLabel = true }) { Text("Add category") }
                            if (ocr.isNotBlank()) {
                                Text("Recognized text", fontSize = 13.sp)
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(ocr.take(12000), fontSize = 12.sp)
                                }
                            }
                            TextButton(onClick = { exclude = true }) {
                                Text("Exclude this item from analysis")
                            }
                        }
                    }
            }
        }
    }
    if (addLabel)
        TextInputDialog("Add a category", "", onDismiss = { addLabel = false }) { label ->
            vm.action("Category added") { vm.repo.label(media.id, label, true) }
            labels = (labels + label).distinct()
            addLabel = false
        }
    if (exclude)
        AlertDialog(
            onDismissRequest = { exclude = false },
            title = { Text("Exclude this item?") },
            text = {
                Text(
                    "Its derived faces, text and categories will be removed. The original stays intact. Restore indexing in Settings."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.action("Item excluded") { vm.repo.exclude("media", media.id) }
                        exclude = false
                        onClose()
                    }
                ) {
                    Text("Exclude")
                }
            },
            dismissButton = { TextButton(onClick = { exclude = false }) { Text("Cancel") } },
        )
}

@Composable
private fun VideoPlayer(uri: String, timestamp: Long, onError: () -> Unit) {
    val context = LocalContext.current
    val player =
        remember(uri) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = false
                addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            onError()
                        }
                    }
                )
            }
        }
    LaunchedEffect(timestamp) { player.seekTo(timestamp) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
