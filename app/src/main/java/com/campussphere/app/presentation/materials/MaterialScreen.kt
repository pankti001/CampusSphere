package com.campussphere.app.presentation.materials

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.MaterialEvent
import com.campussphere.app.data.remote.api.MaterialFields
import com.campussphere.app.data.remote.api.MaterialItem
import com.campussphere.app.data.repository.MATERIAL_PICKER_TYPES
import com.campussphere.app.data.repository.MaterialRepository
import com.campussphere.app.data.repository.MaterialUpload
import com.campussphere.app.ui.brand.*
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

private fun materialTone(status: String) = when (status) {
    "PUBLISHED" -> Tone.Positive
    "HIDDEN" -> Tone.Warning
    else -> Tone.Danger
}

private fun materialEventLabel(action: String) = when (action) {
    "UPLOAD" -> "Uploaded"; "EDIT" -> "Details edited"; "DELETE" -> "Deleted"; "HIDE" -> "Hidden"
    "RESTORE" -> "Restored"; "REMOVE" -> "Removed"
    else -> action
}

/** A form choice with its name above it, so the chosen value still says what it is. */
@Composable
private fun FormChoice(caption: String, options: List<Pair<String, String>>, value: String?, select: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceChip("Choose", options, value, allLabel = null, select = select)
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
        }, "Downloaded ${current.fileName}")
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

    val f = vm.filter
    CampusPage(
        title = when (mode) {
            Mode.UPLOAD -> "Upload material"
            Mode.EDIT -> "Edit material"
            Mode.DETAIL -> "Material details"
            Mode.LIST -> if (area == "college") "College study materials" else "Study materials"
        },
        subtitle = when (mode) {
            Mode.DETAIL, Mode.EDIT -> item?.let { "${it.subject} · ${it.division}" }
            else -> null
        },
        onBack = back, backEnabled = !vm.busy && !uploading, busy = vm.loading || vm.busy,
        // The search text is left out of the key so typing doesn't rebuild the list (and drop the keyboard).
        stateKey = when (mode) {
            Mode.LIST -> "list-${f.subjectId}-${f.type}-${f.divisionId}-${f.status}-${f.facultyId}"
            Mode.DETAIL -> "detail-${item?.id}"
            else -> mode.name
        },
        error = vm.error,
        onRetry = { if (mode == Mode.DETAIL) vm.refreshSelected() else vm.load() },
        notice = vm.notice,
    ) {
        when (mode) {
            Mode.UPLOAD -> item(key = "upload") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard {
                        FormChoice("Subject and division", vm.assignments.map {
                            it.id.toString() to "${it.subject} · ${it.division} · ${it.semester}"
                        }, assignment?.toString()) { if (!uploading) assignment = it?.toLong() }
                        OutlinedTextField(title, { title = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("Title") },
                            singleLine = true, enabled = !uploading)
                        OutlinedTextField(description, { description = it.take(2000) }, Modifier.fillMaxWidth(),
                            label = { Text("Description") }, enabled = !uploading, minLines = 2)
                        OutlinedTextField(topic, { topic = it.take(120) }, Modifier.fillMaxWidth(),
                            label = { Text("Topic or unit (optional)") }, singleLine = true, enabled = !uploading)
                        FormChoice("Type", MATERIAL_TYPES, type) { if (!uploading) type = it ?: "NOTES" }
                    }
                    SecondaryButton(file?.let { "${it.fileName} · ${materialSize(it.bytes.size.toLong())}" } ?: "Choose document",
                        { pick.launch(MATERIAL_PICKER_TYPES) }, enabled = !uploading && !vm.busy, icon = CampusIcons.File)
                    if (file == null) Text("PDF, Word (.docx), PowerPoint (.pptx), Excel (.xlsx) or text · up to 10 MB",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    UploadProgress(vm.upload, vm::retryUpload)
                    PrimaryButton(if (uploading) "Uploading…" else "Upload", {
                        val selectedFile = file ?: return@PrimaryButton
                        val target = assignment ?: return@PrimaryButton
                        vm.startUpload(target, MaterialFields(title.trim(), description.trim(), topic.trim().ifEmpty { null }, type),
                            selectedFile) { mode = Mode.LIST; file = null }
                    }, enabled = !uploading && !vm.busy && title.isNotBlank() && assignment != null && file != null,
                        icon = CampusIcons.Upload)
                }
            }

            Mode.EDIT -> if (item != null) item(key = "edit") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard {
                        OutlinedTextField(title, { title = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("Title") },
                            singleLine = true)
                        OutlinedTextField(description, { description = it.take(2000) }, Modifier.fillMaxWidth(),
                            label = { Text("Description") }, minLines = 2)
                        OutlinedTextField(topic, { topic = it.take(120) }, Modifier.fillMaxWidth(),
                            label = { Text("Topic or unit (optional)") }, singleLine = true)
                        FormChoice("Type", MATERIAL_TYPES, type) { type = it ?: type }
                    }
                    PrimaryButton("Save changes", {
                        vm.update(item.id, MaterialFields(title.trim(), description.trim(), topic.trim().ifEmpty { null }, type)) {
                            mode = Mode.DETAIL
                        }
                    }, enabled = !vm.busy && title.isNotBlank(), icon = CampusIcons.Check)
                }
            }

            Mode.DETAIL -> if (item != null) item(key = "detail") {
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
                if (area == "faculty") item(key = "upload-action") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Upload material", {
                            title = ""; description = ""; topic = ""; type = "NOTES"; file = null
                            assignment = vm.assignments.singleOrNull()?.id
                            vm.resetUpload(); vm.clearMessages(); mode = Mode.UPLOAD
                        }, enabled = vm.assignments.isNotEmpty() && !vm.loading, icon = CampusIcons.Upload)
                        if (vm.loaded && vm.assignments.isEmpty())
                            MessageBanner("You have no active teaching assignments, so you can't upload materials yet.")
                    }
                }
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(vm.filter.query, { vm.filter = vm.filter.copy(query = it.take(100)) }, "Search by title")
                        val subjects = vm.items.map { it.subjectId.toString() to it.subject }.distinct().sortedBy { it.second }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoiceChip("Subject", subjects, vm.filter.subjectId?.toString(), allLabel = "All subjects") {
                                vm.filter = vm.filter.copy(subjectId = it?.toLong())
                            }
                            ChoiceChip("Type", MATERIAL_TYPES, vm.filter.type, allLabel = "All types") {
                                vm.filter = vm.filter.copy(type = it)
                            }
                            if (area != "student") {
                                val divisions = vm.items.map { it.divisionId.toString() to it.division }.distinct().sortedBy { it.second }
                                ChoiceChip("Division", divisions, vm.filter.divisionId?.toString(), allLabel = "All divisions") {
                                    vm.filter = vm.filter.copy(divisionId = it?.toLong())
                                }
                                ChoiceChip("Status", MATERIAL_STATUSES.filter { area == "college" || it.first != "DELETED" },
                                    vm.filter.status, allLabel = "All statuses") { vm.filter = vm.filter.copy(status = it) }
                            }
                            if (area == "college") {
                                val faculty = vm.items.mapNotNull { m -> m.facultyId?.let { it.toString() to m.faculty } }
                                    .distinct().sortedBy { it.second }
                                ChoiceChip("Faculty", faculty, vm.filter.facultyId?.toString(), allLabel = "All faculty") {
                                    vm.filter = vm.filter.copy(facultyId = it?.toLong())
                                }
                            }
                        }
                    }
                }
                val visible = vm.visible
                if (vm.loaded && visible.isEmpty() && vm.error == null) item(key = "empty") {
                    if (vm.items.isEmpty()) when (area) {
                        "student" -> EmptyState("No study materials yet", "Materials your teachers share with your classes appear here.")
                        "faculty" -> EmptyState("Nothing uploaded yet", "Upload notes or slides for a class you teach.")
                        else -> EmptyState("No study materials yet", "Materials uploaded in your college appear here.")
                    } else EmptyState("No matching materials", "Try another search or filter.")
                }
                items(visible, key = { it.id }) { m ->
                    ListRow(m.title, subtitle = "${m.subject} · ${m.division} · ${materialTypeLabel(m.type)}",
                        meta = "${m.faculty} · ${materialTime(m.uploadedAt)}", icon = CampusIcons.File,
                        chip = { if (area != "student") StatusChip(materialStatusLabel(m.status), materialTone(m.status)) },
                        onClick = { vm.open(m); downloaded = null; viewerMissing = false; mode = Mode.DETAIL })
                }
            }
        }
    }

    if (confirmDelete && item != null) ConfirmDialog("Delete this material?",
        "Students will no longer see it. Your college keeps the record and its history.", "Delete",
        { confirmDelete = false; vm.delete(item.id) }, { confirmDelete = false }, danger = true)

    moderation?.let { action ->
        if (item != null) ModerationDialog(action, onDismiss = { moderation = null }) { note ->
            moderation = null
            vm.moderate(item.id, action, note)
        }
    }
}

@Composable
private fun MaterialDetail(item: MaterialItem, area: String, busy: Boolean, hasDownload: Boolean, viewerMissing: Boolean,
                           events: List<MaterialEvent>, download: () -> Unit,
                           open: () -> Unit, edit: () -> Unit, delete: () -> Unit, moderate: (String) -> Unit,
                           restore: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoCard {
            if (area != "student") StatusChip(materialStatusLabel(item.status), materialTone(item.status))
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodyMedium)
            item.topic?.let { InfoRow("Topic", it) }
            InfoRow("Type", materialTypeLabel(item.type))
            InfoRow("Class", "${item.subject} · ${item.division}")
            InfoRow("Program", "${item.program} · ${item.semester} · ${item.academicYear}")
            InfoRow("Uploaded by", "${item.faculty} · ${materialTime(item.uploadedAt)}")
            if (item.updatedAt != item.uploadedAt) InfoRow("Last changed", materialTime(item.updatedAt))
            InfoRow("File", "${item.fileName} · ${item.format.uppercase()} · ${materialSize(item.sizeBytes)}")
        }
        if (area != "student") item.moderationNote?.let { MessageBanner("Reason: $it", MessageKind.Info) }

        if (item.status != "DELETED") {
            PrimaryButton("Download", download, enabled = !busy, icon = CampusIcons.Download)
            if (hasDownload) SecondaryButton("Open downloaded file", open, icon = CampusIcons.Eye)
            if (viewerMissing) MessageBanner("No app on this device can open this file type. The download is saved in Files.")
        }
        if (item.canEdit) {
            SectionHeader("Manage")
            SecondaryButton("Edit details", edit, enabled = !busy, icon = CampusIcons.Edit)
            SecondaryButton("Delete", delete, enabled = !busy, icon = CampusIcons.Trash, danger = true)
        }
        if (item.canModerate) {
            SectionHeader("Moderation")
            if (item.status == "PUBLISHED") SecondaryButton("Hide", { moderate("HIDE") }, enabled = !busy,
                icon = CampusIcons.Hide, danger = true)
            if (item.status == "HIDDEN") SecondaryButton("Restore", restore, enabled = !busy, icon = CampusIcons.Eye)
            SecondaryButton("Remove", { moderate("REMOVE") }, enabled = !busy, icon = CampusIcons.Trash, danger = true)
        }
        if (area == "college") {
            SectionHeader("Activity")
            InfoCard {
                if (events.isEmpty()) Text("No activity recorded yet.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                events.forEach { e ->
                    InfoRow("${materialEventLabel(e.action)} · ${materialTime(e.time)}", e.actor + (e.note?.let { " · “$it”" } ?: ""))
                }
            }
        }
    }
}

@Composable
private fun UploadProgress(state: UploadState, retry: () -> Unit) {
    when (state) {
        is UploadState.Uploading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text("Uploading ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        is UploadState.Failed -> MessageBanner("Upload failed. ${state.message}", MessageKind.Error,
            actionLabel = "Retry", onAction = retry)
        UploadState.Idle -> Unit
    }
}

@Composable
private fun ModerationDialog(action: String, onDismiss: () -> Unit, confirm: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    val remove = action == "REMOVE"
    ConfirmDialog(if (remove) "Remove this material?" else "Hide this material?",
        if (remove) "Removal is final. Students and faculty can no longer use it."
        else "Students stop seeing it until you restore it.",
        if (remove) "Remove" else "Hide", { confirm(note.trim()) }, onDismiss,
        danger = true, confirmEnabled = note.trim().length >= 3) {
        OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(),
            label = { Text("Reason (shown to the faculty member)") })
    }
}
