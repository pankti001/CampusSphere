package com.campussphere.app.presentation.materials

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.MaterialFields
import com.campussphere.app.data.remote.api.MaterialItem
import com.campussphere.app.data.repository.MATERIAL_PICKER_TYPES
import com.campussphere.app.data.repository.MaterialRepository
import com.campussphere.app.data.repository.MaterialUpload
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Mode { LIST, DETAIL, UPLOAD, EDIT }

fun materialSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.UK, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format(Locale.UK, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun materialTime(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK))
}.getOrDefault(iso)

@Composable
private fun Picker(label: String, options: List<Pair<String, String>>, value: String?, modifier: Modifier = Modifier,
                   enabled: Boolean = true, select: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("$label: " + (options.firstOrNull { it.first == value }?.second ?: "All"), maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("All") }, { select(null); open = false })
            options.forEach { (key, text) -> DropdownMenuItem({ Text(text) }, { select(key); open = false }) }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val color = when (status) {
        "PUBLISHED" -> MaterialTheme.colorScheme.primaryContainer
        "HIDDEN" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(materialStatusLabel(status), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun MaterialScreen(repo: MaterialRepository, area: String, auth: () -> String, lost: (Int) -> Unit, exit: () -> Unit) {
    val owner = remember(area) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<MaterialViewModel>(viewModelStoreOwner = owner) { MaterialViewModel(repo, area, auth, lost) }
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Mode.LIST) }
    var downloaded by remember { mutableStateOf<Uri?>(null) }
    var viewerMissing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var moderation by remember { mutableStateOf<String?>(null) }

    // Form state shared by upload and edit.
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("NOTES") }
    var assignment by remember { mutableStateOf<Long?>(null) }
    var file by remember { mutableStateOf<MaterialUpload?>(null) }

    val item = vm.selected
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readFile({ repo.read(context.contentResolver, uri) }) { file = it; vm.resetUpload() }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val current = vm.selected
        if (uri != null && current != null) vm.download({
            repo.download(auth(), area, current.id, context.contentResolver, uri)
            downloaded = uri
            viewerMissing = false
        }, "Downloaded ${current.fileName}.")
    }

    LaunchedEffect(vm) { if (!vm.loaded) vm.load() }
    LaunchedEffect(item) { if (item == null && mode == Mode.DETAIL) mode = Mode.LIST }
    val back: () -> Unit = {
        when (mode) {
            Mode.UPLOAD -> { vm.resetUpload(); mode = Mode.LIST }
            Mode.EDIT -> mode = Mode.DETAIL
            Mode.DETAIL -> { vm.close(); downloaded = null; mode = Mode.LIST }
            Mode.LIST -> exit()
        }
    }
    val uploading = vm.upload is UploadState.Uploading
    BackHandler { if (!vm.busy && !uploading) back() }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(back, enabled = !vm.busy && !uploading) { Text("Back") }
            Text(when (mode) {
                Mode.UPLOAD -> "Upload material"
                Mode.EDIT -> "Edit material"
                Mode.DETAIL -> "Material details"
                Mode.LIST -> if (area == "college") "College study materials" else "Study materials"
            }, style = MaterialTheme.typography.headlineMedium)
            if (vm.loading || vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            vm.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton({ if (mode == Mode.DETAIL) vm.refreshSelected() else vm.load() },
                            enabled = !vm.loading && !vm.busy) { Text("Retry") }
                    }
                }
            }
            vm.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        }

        when (mode) {
            Mode.UPLOAD -> item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Class", style = MaterialTheme.typography.titleMedium)
                    Picker("Subject and division", vm.assignments.map {
                        it.id.toString() to "${it.subject} · ${it.division} · ${it.semester}"
                    }, assignment?.toString(), enabled = !uploading) { assignment = it?.toLong() }
                    OutlinedTextField(title, { title = it.take(160) }, label = { Text("Title") }, singleLine = true,
                        enabled = !uploading, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it.take(2000) }, label = { Text("Description") },
                        enabled = !uploading, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(topic, { topic = it.take(120) }, label = { Text("Topic or unit (optional)") },
                        singleLine = true, enabled = !uploading, modifier = Modifier.fillMaxWidth())
                    Picker("Type", MATERIAL_TYPES, type, enabled = !uploading) { type = it ?: "NOTES" }
                    OutlinedButton({ pick.launch(MATERIAL_PICKER_TYPES) }, enabled = !uploading && !vm.busy) {
                        Text(if (file == null) "Choose document" else "Choose a different document")
                    }
                    Text(file?.let { "${it.fileName} · ${materialSize(it.bytes.size.toLong())}" }
                        ?: "PDF, Word (.docx), PowerPoint (.pptx), Excel (.xlsx) or text · up to 10 MB",
                        style = MaterialTheme.typography.bodyMedium)
                    when (val state = vm.upload) {
                        is UploadState.Uploading -> {
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                            Text("Uploading… ${(state.progress * 100).toInt()}%")
                        }
                        is UploadState.Failed -> Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Upload failed. ${state.message}", color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(vm::retryUpload) { Text("Retry upload") }
                            }
                        }
                        UploadState.Idle -> Unit
                    }
                    Button(onClick = {
                        val selectedFile = file ?: return@Button
                        val target = assignment ?: return@Button
                        vm.startUpload(target, MaterialFields(title.trim(), description.trim(), topic.trim().ifEmpty { null }, type),
                            selectedFile) { mode = Mode.LIST; file = null }
                    }, enabled = !uploading && !vm.busy && title.isNotBlank() && assignment != null && file != null,
                        modifier = Modifier.fillMaxWidth()) { Text(if (uploading) "Uploading…" else "Upload") }
                }
            }

            Mode.EDIT -> if (item != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${item.subject} · ${item.division}", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(title, { title = it.take(160) }, label = { Text("Title") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(description, { description = it.take(2000) }, label = { Text("Description") },
                        minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(topic, { topic = it.take(120) }, label = { Text("Topic or unit (optional)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Picker("Type", MATERIAL_TYPES, type) { type = it ?: type }
                    Button({
                        vm.update(item.id, MaterialFields(title.trim(), description.trim(), topic.trim().ifEmpty { null }, type)) {
                            mode = Mode.DETAIL
                        }
                    }, enabled = !vm.busy && title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
                }
            }

            Mode.DETAIL -> if (item != null) item {
                MaterialDetail(item, area, vm.busy, downloaded != null, viewerMissing, vm.events,
                    download = { save.launch(item.fileName) },
                    open = {
                        val uri = downloaded ?: return@MaterialDetail
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mimeType)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        viewerMissing = runCatching { context.startActivity(view) }.isFailure
                    },
                    edit = {
                        title = item.title; description = item.description; topic = item.topic.orEmpty(); type = item.type
                        mode = Mode.EDIT
                    },
                    delete = { confirmDelete = true },
                    moderate = { moderation = it },
                    restore = { vm.moderate(item.id, "RESTORE", null) })
            }

            Mode.LIST -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (area == "faculty") {
                            Button({
                                title = ""; description = ""; topic = ""; type = "NOTES"; file = null
                                assignment = vm.assignments.singleOrNull()?.id
                                vm.resetUpload(); vm.clearMessages(); mode = Mode.UPLOAD
                            }, enabled = vm.assignments.isNotEmpty() && !vm.loading, modifier = Modifier.fillMaxWidth()) {
                                Text("Upload material")
                            }
                            if (vm.loaded && vm.assignments.isEmpty())
                                Text("You have no active teaching assignments, so you cannot upload materials yet.")
                        }
                        OutlinedTextField(vm.filter.query, { vm.filter = vm.filter.copy(query = it.take(100)) },
                            label = { Text("Search by title") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth())
                        val subjects = vm.items.map { it.subjectId.toString() to it.subject }.distinct().sortedBy { it.second }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Picker("Subject", subjects, vm.filter.subjectId?.toString()) {
                                vm.filter = vm.filter.copy(subjectId = it?.toLong())
                            }
                            Picker("Type", MATERIAL_TYPES, vm.filter.type) { vm.filter = vm.filter.copy(type = it) }
                        }
                        if (area != "student") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val divisions = vm.items.map { it.divisionId.toString() to it.division }.distinct().sortedBy { it.second }
                            Picker("Division", divisions, vm.filter.divisionId?.toString()) {
                                vm.filter = vm.filter.copy(divisionId = it?.toLong())
                            }
                            Picker("Status", MATERIAL_STATUSES.filter { area == "college" || it.first != "DELETED" },
                                vm.filter.status) { vm.filter = vm.filter.copy(status = it) }
                        }
                        if (area == "college") {
                            val faculty = vm.items.mapNotNull { m -> m.facultyId?.let { it.toString() to m.faculty } }
                                .distinct().sortedBy { it.second }
                            Picker("Faculty", faculty, vm.filter.facultyId?.toString()) {
                                vm.filter = vm.filter.copy(facultyId = it?.toLong())
                            }
                        }
                    }
                }
                val visible = vm.visible
                if (vm.loaded && visible.isEmpty() && vm.error == null) item {
                    Text(if (vm.items.isEmpty()) when (area) {
                        "student" -> "No study materials have been shared with your classes yet."
                        "faculty" -> "You have not uploaded any materials yet."
                        else -> "No study materials have been uploaded in your college yet."
                    } else "No materials match these filters.")
                }
                items(visible, key = { it.id }) { m ->
                    OutlinedCard(onClick = { vm.open(m); downloaded = null; viewerMissing = false; mode = Mode.DETAIL },
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                if (area != "student") StatusBadge(m.status)
                            }
                            Text("${m.subject} · ${m.division} · ${materialTypeLabel(m.type)}")
                            Text("${m.faculty} · ${materialTime(m.uploadedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && item != null) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this material?") },
        text = { Text("Students will no longer see it. Your college keeps the record and its history.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.delete(item.id) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })

    moderation?.let { action ->
        if (item != null) ModerationDialog(action, onDismiss = { moderation = null }) { note ->
            moderation = null
            vm.moderate(item.id, action, note)
        }
    }
}

@Composable
private fun MaterialDetail(item: MaterialItem, area: String, busy: Boolean, hasDownload: Boolean, viewerMissing: Boolean,
                           events: List<com.campussphere.app.data.remote.api.MaterialEvent>, download: () -> Unit,
                           open: () -> Unit, edit: () -> Unit, delete: () -> Unit, moderate: (String) -> Unit,
                           restore: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge)
        if (area != "student") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(item.status)
            item.moderationNote?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall) }
        }
        if (item.description.isNotBlank()) Text(item.description)
        item.topic?.let { Text("Topic: $it", fontWeight = FontWeight.Medium) }
        Text("${materialTypeLabel(item.type)} · ${item.subject} · ${item.division}")
        Text("${item.program} · ${item.semester} · ${item.academicYear}", style = MaterialTheme.typography.bodySmall)
        Text("Uploaded by ${item.faculty} on ${materialTime(item.uploadedAt)}", style = MaterialTheme.typography.bodySmall)
        if (item.updatedAt != item.uploadedAt) Text("Last changed ${materialTime(item.updatedAt)}", style = MaterialTheme.typography.bodySmall)
        Text("${item.fileName} · ${item.format.uppercase()} · ${materialSize(item.sizeBytes)}", style = MaterialTheme.typography.bodySmall)

        if (item.status != "DELETED") {
            Button(download, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Download") }
            if (hasDownload) OutlinedButton(open, modifier = Modifier.fillMaxWidth()) { Text("Open downloaded file") }
            if (viewerMissing) Text("No app on this device can open this file type. The download is saved in Files.")
        }
        if (item.canEdit) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(edit, enabled = !busy) { Text("Edit details") }
            OutlinedButton(delete, enabled = !busy) { Text("Delete") }
        }
        if (item.canModerate) {
            Text("Moderation", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.status == "PUBLISHED") OutlinedButton({ moderate("HIDE") }, enabled = !busy) { Text("Hide") }
                if (item.status == "HIDDEN") OutlinedButton(restore, enabled = !busy) { Text("Restore") }
                OutlinedButton({ moderate("REMOVE") }, enabled = !busy) { Text("Remove") }
            }
        }
        if (area == "college") {
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            if (events.isEmpty()) Text("No activity recorded yet.", style = MaterialTheme.typography.bodySmall)
            events.forEach { e ->
                Text("${e.action} · ${e.actor} · ${materialTime(e.time)}" + (e.note?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ModerationDialog(action: String, onDismiss: () -> Unit, confirm: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    val remove = action == "REMOVE"
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (remove) "Remove this material?" else "Hide this material?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (remove) "Removal is final. Students and Faculty can no longer use it."
                     else "Students stop seeing it until you restore it.")
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("Reason (shown to the Faculty member)") })
            }
        },
        confirmButton = { TextButton({ confirm(note.trim()) }, enabled = note.trim().length >= 3) { Text(if (remove) "Remove" else "Hide") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
