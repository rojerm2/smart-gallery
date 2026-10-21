package dev.smartgallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.smartgallery.data.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PersonPanel(
    vm: GalleryViewModel,
    id: String,
    onBack: () -> Unit,
    onMedia: (Media) -> Unit,
    onFace: (Face) -> Unit,
) {
    BackHandler(onBack = onBack)
    val people by vm.people.collectAsState()
    val person = people.find { it.id == id }
    var rename by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<String?>(null) }
    var faces by remember(id) { mutableStateOf(emptyList<Face>()) }
    var selected by remember(id) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(id, people) {
        faces = withContext(Dispatchers.IO) { vm.repo.dao.personFaces(id) }
    }
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text(person?.name ?: "Person", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text("${person?.count ?: 0} distinct media items", fontSize = 12.sp)
        }
        IconButton(onClick = { rename = true }) { Icon(Icons.Rounded.Edit, "Rename person") }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (person?.promoted != true)
            OutlinedButton(onClick = { vm.action("Person promoted") { vm.repo.promote(id) } }) {
                Text("Show in People")
            }
        OutlinedButton(onClick = { operation = "Merge into" }) { Text("Merge") }
        OutlinedButton(onClick = { operation = "Different from" }) { Text("Mark different") }
    }
    if (faces.isNotEmpty()) {
        Text(
            "Tap a face to correct it. Select faces to split a group.",
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            faces.forEach { face ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = File(vm.repo.crops, face.crop),
                        contentDescription = "Review face",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(62.dp).clip(CircleShape).clickable { onFace(face) },
                    )
                    Checkbox(
                        checked = face.id in selected,
                        onCheckedChange = {
                            selected = if (it) selected + face.id else selected - face.id
                        },
                    )
                }
            }
        }
        if (selected.isNotEmpty())
            TextButton(
                onClick = {
                    val ids = selected.toList()
                    vm.action("Selected faces split into a new person") { vm.repo.split(ids, id) }
                    selected = emptySet()
                }
            ) {
                Text("Split ${selected.size} selected faces")
            }
    }
    MediaGrid(vm, onOpen = onMedia)
    if (rename)
        TextInputDialog("Name this person", person?.name ?: "", onDismiss = { rename = false }) {
            name ->
            vm.action("Person renamed") { vm.repo.rename(id, name) }
            rename = false
        }
    operation?.let { op ->
        AlertDialog(
            onDismissRequest = { operation = null },
            title = { Text(op) },
            text = {
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items(people.filter { it.id != id }, key = { it.id }) { p ->
                        TextButton(
                            onClick = {
                                vm.action(
                                    if (op == "Merge into") "People merged"
                                    else "Marked as different people"
                                ) {
                                    if (op == "Merge into") vm.repo.merge(id, p.id)
                                    else vm.repo.different(id, p.id)
                                }
                                operation = null
                                if (op == "Merge into") onBack()
                            }
                        ) {
                            Text("${p.name} · ${p.count} items")
                        }
                    }
                    if (people.size < 2) item { Text("No other groups are available yet.") }
                }
            },
            confirmButton = { TextButton(onClick = { operation = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun FaceActions(vm: GalleryViewModel, face: Face, onClose: () -> Unit) {
    val people by vm.people.collectAsState()
    var current by remember(face.id) { mutableStateOf<Membership?>(null) }
    var suggestions by remember(face.id) { mutableStateOf(emptyList<Pair<Person, Float>>()) }
    var create by remember { mutableStateOf(false) }
    var assign by remember { mutableStateOf(false) }
    LaunchedEffect(face.id, people) {
        withContext(Dispatchers.IO) {
            current = vm.repo.dao.membershipById(face.id)
            suggestions = if (face.usable) vm.repo.suggestions(face) else emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (face.usable) "Review face" else "Face excluded from grouping") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 450.dp)) {
                AsyncImage(
                    model = File(vm.repo.crops, face.crop),
                    contentDescription = "Face crop",
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier.size(110.dp).align(Alignment.CenterHorizontally).clip(CircleShape),
                )
                if (!face.usable) {
                    Text(
                        face.excludedReason,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    if (face.embedding.isNotEmpty())
                        TextButton(
                            onClick = {
                                vm.action("Portrait included for review") {
                                    vm.repo.includeDocumentPortrait(face)
                                }
                                onClose()
                            }
                        ) {
                            Text("Include this real portrait")
                        }
                } else {
                    if (suggestions.isNotEmpty())
                        Text(
                            "Possible matches · similarity, not accuracy",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    suggestions.forEach { (person, score) ->
                        Column {
                            Text(person.name, fontWeight = FontWeight.Medium)
                            Text("Cosine ${"%.3f".format(score)}", fontSize = 11.sp)
                            Row {
                                TextButton(
                                    onClick = {
                                        vm.action("Face assigned") {
                                            vm.repo.assign(face.id, person.id)
                                        }
                                        onClose()
                                    }
                                ) {
                                    Text("Confirm")
                                }
                                TextButton(
                                    onClick = {
                                        vm.action("Suggestion rejected") {
                                            vm.repo.reject(face.id, person.id)
                                        }
                                        suggestions =
                                            suggestions.filter { it.first.id != person.id }
                                    }
                                ) {
                                    Text("Different person")
                                }
                            }
                        }
                    }
                    TextButton(onClick = { assign = true }) { Text("Assign to existing person") }
                    TextButton(onClick = { create = true }) { Text("Create a person") }
                    current?.let { m ->
                        TextButton(
                            onClick = {
                                vm.action("Face removed from group") {
                                    vm.repo.remove(face.id, m.personId)
                                }
                                onClose()
                            }
                        ) {
                            Text("Remove from this group")
                        }
                    }
                    TextButton(
                        onClick = {
                            vm.action("Face dismissed; undo is available") {
                                vm.repo.dismiss(face.id)
                            }
                            onClose()
                        }
                    ) {
                        Text("Dismiss from review")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
    if (create)
        TextInputDialog("Create a person", "", onDismiss = { create = false }) { name ->
            vm.action("Person created") { vm.repo.assign(face.id, null, name) }
            create = false
            onClose()
        }
    if (assign)
        AlertDialog(
            onDismissRequest = { assign = false },
            title = { Text("Assign to person") },
            text = {
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items(people, key = { it.id }) { p ->
                        TextButton(
                            onClick = {
                                vm.action("Face assigned") { vm.repo.assign(face.id, p.id) }
                                assign = false
                                onClose()
                            }
                        ) {
                            Text(p.name)
                        }
                    }
                    if (people.isEmpty()) item { Text("Create a person first.") }
                }
            },
            confirmButton = { TextButton(onClick = { assign = false }) { Text("Cancel") } },
        )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(80) },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
