package com.campussphere.app.presentation.announcements

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.AnnouncementItem
import com.campussphere.app.data.repository.AnnouncementRepository
import com.campussphere.app.data.repository.MATERIAL_PICKER_TYPES
import com.campussphere.app.presentation.materials.materialSize
import com.campussphere.app.presentation.materials.materialTime

@Composable
private fun Picker(label: String, options: List<Pair<String, String>>, value: String?, allowAll: Boolean = true,
                   enabled: Boolean = true, select: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("$label: " + (options.firstOrNull { it.first == value }?.second ?: if (allowAll) "All" else "Choose"), maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            if (allowAll) DropdownMenuItem({ Text("All") }, { select(null); open = false })
            options.forEach { (key, text) -> DropdownMenuItem({ Text(text) }, { select(key); open = false }) }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun priorityColor(priority: String) = when (priority) {
    "URGENT" -> MaterialTheme.colorScheme.errorContainer
    "HIGH" -> MaterialTheme.colorScheme.tertiaryContainer
    "LOW" -> MaterialTheme.colorScheme.surfaceVariant
    else -> MaterialTheme.colorScheme.secondaryContainer
}

@Composable
fun AnnouncementScreen(repo: AnnouncementRepository, area: String, auth: () -> String, lost: (Int) -> Unit, exit: () -> Unit) {
    val owner = remember(area) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<AnnouncementViewModel>(viewModelStoreOwner = owner) { AnnouncementViewModel(repo, area, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var downloaded by remember { mutableStateOf<Uri?>(null) }
    var viewerMissing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var schedule by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.run(null) { vm.chooseFile(repo.readFile(context.contentResolver, uri)) }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val current = vm.ui.value.selected
        if (uri != null && current != null) vm.run("Downloaded ${current.attachmentName}.") {
            repo.download(auth(), area, current.id, context.contentResolver, uri)
            downloaded = uri
            viewerMissing = false
        }
    }

    LaunchedEffect(vm) { if (!vm.ui.value.loaded) vm.load() }
    BackHandler { if (!vm.back()) exit() }
    val manager = area != "student"
    val item = ui.selected

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton({ if (!vm.back()) exit() }, enabled = !ui.busy && ui.uploadProgress == null) { Text("Back") }
            Text(when (ui.page) {
                AnnouncementPage.FORM -> if (ui.editingId == null) "New announcement" else "Edit announcement"
                AnnouncementPage.PREVIEW -> "Preview and publish"
                AnnouncementPage.DETAIL -> "Announcement"
                AnnouncementPage.LIST -> if (area == "college") "College announcements" else "Announcements"
            }, style = MaterialTheme.typography.headlineMedium)
            if (ui.loading || ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            ui.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                        if (ui.page == AnnouncementPage.LIST) TextButton(vm::load, enabled = !ui.loading) { Text("Retry") }
                    }
                }
            }
            ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        }

        when (ui.page) {
            AnnouncementPage.LIST -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.overview?.let { o ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("DRAFT" to o.drafts, "SCHEDULED" to o.scheduled, "PUBLISHED" to o.published,
                                    "EXPIRED" to o.expired, "ARCHIVED" to o.archived).forEach { (status, count) ->
                                    FilterChip(selected = ui.filter.status == status, onClick = {
                                        vm.setFilter(ui.filter.copy(status = if (ui.filter.status == status) null else status))
                                    }, label = { Text("${label(ANNOUNCEMENT_STATUSES, status)} $count") })
                                }
                            }
                        }
                        if (area == "faculty") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!ui.filter.mine, { vm.setFilter(ui.filter.copy(mine = false, status = null)) }, { Text("For me") })
                            FilterChip(ui.filter.mine, { vm.setFilter(ui.filter.copy(mine = true)) }, { Text("My announcements") })
                        }
                        if (manager) {
                            Button(vm::startCreate, enabled = !ui.loading && (area == "college" || ui.targets.isNotEmpty()),
                                modifier = Modifier.fillMaxWidth()) { Text("New announcement") }
                            if (area == "faculty" && ui.loaded && ui.targets.isEmpty())
                                Text("You have no active classes, so you cannot post announcements yet.")
                        }
                        OutlinedTextField(search, { search = it.take(100) }, label = { Text("Search announcements") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { vm.setFilter(ui.filter.copy(q = search)) }))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Picker("Category", ANNOUNCEMENT_CATEGORIES, ui.filter.category) { vm.setFilter(ui.filter.copy(category = it)) }
                            Picker("Priority", ANNOUNCEMENT_PRIORITIES, ui.filter.priority) { vm.setFilter(ui.filter.copy(priority = it)) }
                        }
                        if (area == "faculty" && ui.filter.mine)
                            Picker("Status", ANNOUNCEMENT_STATUSES, ui.filter.status) { vm.setFilter(ui.filter.copy(status = it)) }
                        if (area != "college" && !ui.filter.mine) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(ui.filter.unread, { vm.setFilter(ui.filter.copy(unread = it)) })
                            Text("Unread only")
                        }
                    }
                }
                if (ui.loaded && ui.items.isEmpty() && ui.error == null) item {
                    Text(when {
                        ui.filter != AnnouncementFilter(mine = ui.filter.mine) -> "No announcements match these filters."
                        area == "college" -> "No announcements yet. Create the first one for your college."
                        ui.filter.mine -> "You have not written any announcements yet."
                        else -> "No announcements for you right now."
                    })
                }
                items(ui.items, key = { it.id }) { a -> AnnouncementCard(a, area, ui.filter.mine) { downloaded = null; vm.open(a) } }
                if (ui.items.size >= 30) item { Text("Showing the 30 most relevant. Use search or filters to find older ones.",
                    style = MaterialTheme.typography.bodySmall) }
            }

            AnnouncementPage.DETAIL -> if (item != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(label(ANNOUNCEMENT_PRIORITIES, item.priority), priorityColor(item.priority))
                        Badge(label(ANNOUNCEMENT_CATEGORIES, item.category), MaterialTheme.colorScheme.surfaceVariant)
                        if (manager && (area == "college" || item.canEdit || item.status != "PUBLISHED"))
                            Badge(label(ANNOUNCEMENT_STATUSES, item.status), MaterialTheme.colorScheme.primaryContainer)
                    }
                    Text("For: ${label(ANNOUNCEMENT_AUDIENCES, item.audience)} · ${item.target}", style = MaterialTheme.typography.bodyMedium)
                    Text("By ${item.author}" + (item.publishAt?.let { " · ${materialTime(it)}" } ?: " · not published yet"),
                        style = MaterialTheme.typography.bodySmall)
                    item.expiresAt?.let { Text("Expires ${materialTime(it)}", style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                    Text(item.body)
                    item.attachmentName?.let { name ->
                        HorizontalDivider()
                        Text("Attachment: $name · ${materialSize(item.attachmentSize ?: 0)}", fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ save.launch(name) }, enabled = !ui.busy) { Text("Download") }
                            if (downloaded != null) OutlinedButton({
                                val view = Intent(Intent.ACTION_VIEW).setDataAndType(downloaded, "*/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                viewerMissing = runCatching { context.startActivity(view) }.isFailure
                            }) { Text("Open") }
                            if (item.canEdit) TextButton(vm::removeAttachment, enabled = !ui.busy) { Text("Remove") }
                        }
                        if (viewerMissing) Text("No app on this device can open this file. It is saved in Files.")
                    }
                    ui.stats?.let { s ->
                        HorizontalDivider()
                        Text("Read by ${s.readCount} of ${s.audienceSize} people in the audience", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (item.canEdit || item.canPublish || item.canArchive || item.canDelete) {
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.canEdit) OutlinedButton({ vm.startEdit(item) }, enabled = !ui.busy) { Text("Edit") }
                            if (item.canPublish) Button({ schedule = ""; vm.startPreview() }, enabled = !ui.busy) {
                                Text(if (item.status == "SCHEDULED") "Reschedule" else "Publish")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.canArchive) OutlinedButton({ confirm = "ARCHIVE" }, enabled = !ui.busy) { Text("Archive") }
                            if (item.canDelete) OutlinedButton({ confirm = "DELETE" }, enabled = !ui.busy) { Text("Delete draft") }
                        }
                    }
                }
            }

            AnnouncementPage.FORM -> item {
                val form = ui.form
                val uploading = ui.uploadProgress != null
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(form.title, { t -> vm.editForm { it.copy(title = t.take(200)) } }, label = { Text("Title") },
                        singleLine = true, enabled = !uploading, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(form.body, { t -> vm.editForm { it.copy(body = t.take(10_000)) } }, label = { Text("Message") },
                        minLines = 4, enabled = !uploading, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Picker("Category", ANNOUNCEMENT_CATEGORIES, form.category, allowAll = false) { c -> vm.editForm { it.copy(category = c ?: it.category) } }
                        Picker("Priority", ANNOUNCEMENT_PRIORITIES, form.priority, allowAll = false) { p -> vm.editForm { it.copy(priority = p ?: it.priority) } }
                    }
                    Text("Audience", style = MaterialTheme.typography.titleMedium)
                    if (area == "college") {
                        Picker("Who", ANNOUNCEMENT_AUDIENCES, form.audience, allowAll = false) { a -> vm.editForm { it.copy(audience = a ?: it.audience) } }
                        Picker("Reach", ANNOUNCEMENT_SCOPES, form.scope, allowAll = false) { s -> vm.editForm { it.copy(scope = s ?: it.scope, targetId = null) } }
                        if (form.scope != "COLLEGE") {
                            val options = ui.targets.filter { it.scope == form.scope }.map { it.id.toString() to it.label }
                            if (options.isEmpty()) Text("Nothing of this kind exists in your college yet.")
                            else Picker(label(ANNOUNCEMENT_SCOPES, form.scope), options, form.targetId?.toString(), allowAll = false) { id ->
                                vm.editForm { it.copy(targetId = id?.toLong()) }
                            }
                        }
                    } else {
                        Text("Students of one of your classes")
                        Picker("Class", ui.targets.map { it.id.toString() to it.label }, form.targetId?.toString(), allowAll = false) { id ->
                            vm.editForm { it.copy(targetId = id?.toLong()) }
                        }
                    }
                    OutlinedTextField(form.expires, { t -> vm.editForm { it.copy(expires = t.take(16)) } },
                        label = { Text("Expires (optional) · YYYY-MM-DD HH:MM") }, singleLine = true, enabled = !uploading,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedButton({ pick.launch(MATERIAL_PICKER_TYPES) }, enabled = !uploading && !ui.busy) {
                        Text(if (ui.file == null) "Attach a file (optional)" else "Choose a different file")
                    }
                    ui.file?.let { Text("${it.fileName} · ${materialSize(it.bytes.size.toLong())}") }
                    ui.uploadProgress?.let { p ->
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("Uploading attachment… ${(p * 100).toInt()}%")
                    }
                    Button(vm::save, enabled = !ui.busy && !uploading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ui.editingId == null) "Save draft and preview" else "Save changes")
                    }
                }
            }

            AnnouncementPage.PREVIEW -> if (item != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("This is how readers will see it:", style = MaterialTheme.typography.bodyMedium)
                    AnnouncementCard(item, "student", false) {}
                    Text(item.body)
                    HorizontalDivider()
                    if (item.canPublish) {
                        Button({ vm.publish("") }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Publish now") }
                        OutlinedTextField(schedule, { schedule = it.take(16) }, label = { Text("Or schedule · YYYY-MM-DD HH:MM") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton({ vm.publish(schedule) }, enabled = !ui.busy && schedule.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()) { Text("Schedule") }
                    }
                    TextButton({ vm.back() }) { Text("Keep as it is") }
                }
            }
        }
    }

    confirm?.let { kind ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (kind == "DELETE") "Delete this draft?" else "Archive this announcement?") },
            text = { Text(if (kind == "DELETE") "The draft and its attachment are removed. This cannot be undone."
                          else "Readers will no longer see it. It stays in your college's archive.") },
            confirmButton = { TextButton({ confirm = null; if (kind == "DELETE") vm.delete() else vm.archive() }) {
                Text(if (kind == "DELETE") "Delete" else "Archive") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun AnnouncementCard(a: AnnouncementItem, area: String, showStatus: Boolean, open: () -> Unit) {
    OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (area != "college" && a.read == false) Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Text(a.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                    fontWeight = if (a.read == false) FontWeight.Bold else FontWeight.Normal)
                if (a.priority == "URGENT" || a.priority == "HIGH") Badge(label(ANNOUNCEMENT_PRIORITIES, a.priority), priorityColor(a.priority))
            }
            Text("${label(ANNOUNCEMENT_CATEGORIES, a.category)} · ${a.target}", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${a.author} · ${a.publishAt?.let { materialTime(it) } ?: "Draft"}", style = MaterialTheme.typography.bodySmall)
                if (area == "college" || showStatus) Badge(label(ANNOUNCEMENT_STATUSES, a.status), MaterialTheme.colorScheme.primaryContainer)
                if (a.attachmentName != null) Text("📎", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
