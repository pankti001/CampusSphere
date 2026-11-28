package com.campussphere.app.presentation.announcements

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.campussphere.app.ui.brand.*

private fun priorityTone(priority: String) = when (priority) {
    "URGENT" -> Tone.Danger
    "HIGH" -> Tone.Warning
    else -> Tone.Neutral
}

private fun statusTone(status: String) = when (status) {
    "PUBLISHED" -> Tone.Positive
    "SCHEDULED" -> Tone.Accent
    "DRAFT" -> Tone.Warning
    else -> Tone.Neutral
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
        if (uri != null && current != null) vm.run("Downloaded ${current.attachmentName}") {
            repo.download(auth(), area, current.id, context.contentResolver, uri)
            downloaded = uri
            viewerMissing = false
        }
    }

    LaunchedEffect(vm) { if (!vm.ui.value.loaded) vm.load() }
    BackHandler { if (!vm.back()) exit() }
    val manager = area != "student"
    val item = ui.selected

    CampusPage(
        title = when (ui.page) {
            AnnouncementPage.FORM -> if (ui.editingId == null) "New announcement" else "Edit announcement"
            AnnouncementPage.PREVIEW -> "Preview and publish"
            AnnouncementPage.DETAIL -> "Announcement"
            AnnouncementPage.LIST -> if (area == "college") "College announcements" else "Announcements"
        },
        onBack = { if (!vm.back()) exit() },
        backEnabled = !ui.busy && ui.uploadProgress == null,
        busy = ui.loading || ui.busy,
        stateKey = when (ui.page) {
            AnnouncementPage.LIST -> "list-${ui.filter}"
            AnnouncementPage.DETAIL -> "detail-${item?.id}"
            AnnouncementPage.FORM -> "form-${ui.editingId}"
            AnnouncementPage.PREVIEW -> "preview-${item?.id}"
        },
        error = ui.error,
        onRetry = if (ui.page == AnnouncementPage.LIST) ({ vm.load() }) else null,
        notice = ui.notice,
    ) {
        when (ui.page) {
            AnnouncementPage.LIST -> {
                ui.overview?.let { o ->
                    item(key = "overview") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("DRAFT" to o.drafts, "SCHEDULED" to o.scheduled, "PUBLISHED" to o.published,
                                "EXPIRED" to o.expired, "ARCHIVED" to o.archived).forEach { (status, count) ->
                                FilterChip(selected = ui.filter.status == status, onClick = {
                                    vm.setFilter(ui.filter.copy(status = if (ui.filter.status == status) null else status))
                                }, label = { Text("${label(ANNOUNCEMENT_STATUSES, status)} $count") })
                            }
                        }
                    }
                }
                if (area == "faculty") item(key = "mine-tabs") {
                    CampusTabs(listOf("For me", "My announcements"), if (ui.filter.mine) 1 else 0) {
                        if (it == 0) vm.setFilter(ui.filter.copy(mine = false, status = null))
                        else vm.setFilter(ui.filter.copy(mine = true))
                    }
                }
                if (manager) item(key = "create") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("New announcement", vm::startCreate,
                            enabled = !ui.loading && (area == "college" || ui.targets.isNotEmpty()), icon = CampusIcons.Plus)
                        if (area == "faculty" && ui.loaded && ui.targets.isEmpty())
                            MessageBanner("You have no active classes, so you can't post announcements yet.")
                    }
                }
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchField(search, {
                            search = it.take(100)
                            if (it.isBlank() && ui.filter.q.isNotEmpty()) vm.setFilter(ui.filter.copy(q = ""))
                        }, "Search announcements", onSearch = { vm.setFilter(ui.filter.copy(q = search)) })
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ChoiceChip("Category", ANNOUNCEMENT_CATEGORIES, ui.filter.category, allLabel = "All categories") {
                                vm.setFilter(ui.filter.copy(category = it))
                            }
                            ChoiceChip("Priority", ANNOUNCEMENT_PRIORITIES, ui.filter.priority, allLabel = "All priorities") {
                                vm.setFilter(ui.filter.copy(priority = it))
                            }
                            if (area == "faculty" && ui.filter.mine)
                                ChoiceChip("Status", ANNOUNCEMENT_STATUSES, ui.filter.status, allLabel = "All statuses") {
                                    vm.setFilter(ui.filter.copy(status = it))
                                }
                            if (area != "college" && !ui.filter.mine)
                                FilterChip(selected = ui.filter.unread, onClick = { vm.setFilter(ui.filter.copy(unread = !ui.filter.unread)) },
                                    label = { Text("Unread only") })
                        }
                    }
                }
                if (ui.loaded && ui.items.isEmpty() && ui.error == null) item(key = "empty") {
                    when {
                        ui.filter != AnnouncementFilter(mine = ui.filter.mine) ->
                            EmptyState("No matching announcements", "Try another search or filter.")
                        area == "college" -> EmptyState("No announcements yet", "Create the first one for your college.")
                        ui.filter.mine -> EmptyState("Nothing written yet", "Announcements you write appear here.")
                        else -> EmptyState("No announcements", "New announcements for you appear here.")
                    }
                }
                items(ui.items, key = { it.id }) { a ->
                    AnnouncementRow(a, area, ui.filter.mine) { downloaded = null; vm.open(a) }
                }
                if (ui.items.size >= 30) item(key = "limit") {
                    MessageBanner("Showing the 30 most relevant. Use search or filters to find older ones.")
                }
            }

            AnnouncementPage.DETAIL -> if (item != null) item(key = "detail") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(label(ANNOUNCEMENT_PRIORITIES, item.priority), priorityTone(item.priority))
                            StatusChip(label(ANNOUNCEMENT_CATEGORIES, item.category))
                            if (manager && (area == "college" || item.canEdit || item.status != "PUBLISHED"))
                                StatusChip(label(ANNOUNCEMENT_STATUSES, item.status), statusTone(item.status))
                        }
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        InfoRow("For", "${label(ANNOUNCEMENT_AUDIENCES, item.audience)} · ${item.target}")
                        InfoRow("Posted by", item.author + (item.publishAt?.let { " · ${materialTime(it)}" } ?: " · not published yet"))
                        item.expiresAt?.let { InfoRow("Expires", materialTime(it)) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(item.body, style = MaterialTheme.typography.bodyLarge)
                    }
                    item.attachmentName?.let { name ->
                        SectionHeader("Attachment")
                        ListRow(name, subtitle = materialSize(item.attachmentSize ?: 0), icon = CampusIcons.File)
                        if (item.canPublish) SecondaryButton("Download", { save.launch(name) }, enabled = !ui.busy, icon = CampusIcons.Download)
                        else PrimaryButton("Download", { save.launch(name) }, enabled = !ui.busy, icon = CampusIcons.Download)
                        if (downloaded != null) SecondaryButton("Open", {
                            val view = Intent(Intent.ACTION_VIEW).setDataAndType(downloaded, "*/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            viewerMissing = runCatching { context.startActivity(view) }.isFailure
                        }, icon = CampusIcons.Eye)
                        if (item.canEdit) SecondaryButton("Remove attachment", vm::removeAttachment, enabled = !ui.busy,
                            icon = CampusIcons.Trash, danger = true)
                        if (viewerMissing) MessageBanner("No app on this device can open this file. It's saved in Files.")
                    }
                    ui.stats?.let { s ->
                        InfoCard { InfoRow("Read by", "${s.readCount} of ${s.audienceSize} people in the audience") }
                    }
                    if (item.canEdit || item.canPublish || item.canArchive || item.canDelete) {
                        SectionHeader("Manage")
                        if (item.canPublish) PrimaryButton(if (item.status == "SCHEDULED") "Reschedule" else "Publish",
                            { schedule = ""; vm.startPreview() }, enabled = !ui.busy, icon = CampusIcons.Megaphone)
                        if (item.canEdit) SecondaryButton("Edit", { vm.startEdit(item) }, enabled = !ui.busy, icon = CampusIcons.Edit)
                        if (item.canArchive) SecondaryButton("Archive", { confirm = "ARCHIVE" }, enabled = !ui.busy,
                            icon = CampusIcons.Archive, danger = true)
                        if (item.canDelete) SecondaryButton("Delete draft", { confirm = "DELETE" }, enabled = !ui.busy,
                            icon = CampusIcons.Trash, danger = true)
                    }
                }
            }

            AnnouncementPage.FORM -> item(key = "form") {
                val form = ui.form
                val uploading = ui.uploadProgress != null
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoCard {
                        OutlinedTextField(form.title, { t -> vm.editForm { it.copy(title = t.take(200)) } }, Modifier.fillMaxWidth(),
                            label = { Text("Title") }, singleLine = true, enabled = !uploading)
                        OutlinedTextField(form.body, { t -> vm.editForm { it.copy(body = t.take(10_000)) } }, Modifier.fillMaxWidth(),
                            label = { Text("Message") }, minLines = 4, enabled = !uploading)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            FormChoice("Category", ANNOUNCEMENT_CATEGORIES, form.category) { c -> vm.editForm { it.copy(category = c ?: it.category) } }
                            FormChoice("Priority", ANNOUNCEMENT_PRIORITIES, form.priority) { p -> vm.editForm { it.copy(priority = p ?: it.priority) } }
                        }
                        OutlinedTextField(form.expires, { t -> vm.editForm { it.copy(expires = t.take(16)) } }, Modifier.fillMaxWidth(),
                            label = { Text("Expires (optional) · YYYY-MM-DD HH:MM") }, singleLine = true, enabled = !uploading)
                    }
                    SectionHeader("Audience")
                    InfoCard {
                        if (area == "college") {
                            FormChoice("Who", ANNOUNCEMENT_AUDIENCES, form.audience) { a -> vm.editForm { it.copy(audience = a ?: it.audience) } }
                            FormChoice("Reach", ANNOUNCEMENT_SCOPES, form.scope) { s -> vm.editForm { it.copy(scope = s ?: it.scope, targetId = null) } }
                            if (form.scope != "COLLEGE") {
                                val options = ui.targets.filter { it.scope == form.scope }.map { it.id.toString() to it.label }
                                if (options.isEmpty()) Text("Nothing of this kind exists in your college yet.",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else FormChoice(label(ANNOUNCEMENT_SCOPES, form.scope), options, form.targetId?.toString()) { id ->
                                    vm.editForm { it.copy(targetId = id?.toLong()) }
                                }
                            }
                        } else {
                            Text("Students of one of your classes", style = MaterialTheme.typography.bodyMedium)
                            FormChoice("Class", ui.targets.map { it.id.toString() to it.label }, form.targetId?.toString()) { id ->
                                vm.editForm { it.copy(targetId = id?.toLong()) }
                            }
                        }
                    }
                    SecondaryButton(ui.file?.let { "${it.fileName} · ${materialSize(it.bytes.size.toLong())}" } ?: "Attach a file (optional)",
                        { pick.launch(MATERIAL_PICKER_TYPES) }, enabled = !uploading && !ui.busy, icon = CampusIcons.File)
                    ui.uploadProgress?.let { p ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                            Text("Uploading attachment ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    PrimaryButton(if (ui.editingId == null) "Save draft and preview" else "Save changes", vm::save,
                        enabled = !ui.busy && !uploading, icon = CampusIcons.Check)
                }
            }

            AnnouncementPage.PREVIEW -> if (item != null) item(key = "preview") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MessageBanner("This is how readers will see it.")
                    AnnouncementRow(item, "student", false, null)
                    InfoCard { Text(item.body, style = MaterialTheme.typography.bodyLarge) }
                    if (item.canPublish) {
                        PrimaryButton("Publish now", { vm.publish("") }, enabled = !ui.busy, icon = CampusIcons.Megaphone)
                        SectionHeader("Or schedule it")
                        InfoCard {
                            OutlinedTextField(schedule, { schedule = it.take(16) }, Modifier.fillMaxWidth(),
                                label = { Text("Date and time · YYYY-MM-DD HH:MM") }, singleLine = true)
                        }
                        SecondaryButton("Schedule", { vm.publish(schedule) }, enabled = !ui.busy && schedule.isNotBlank(),
                            icon = CampusIcons.Calendar)
                    }
                    TextButton({ vm.back() }, Modifier.fillMaxWidth()) { Text("Keep as it is") }
                }
            }
        }
    }

    confirm?.let { kind ->
        val delete = kind == "DELETE"
        ConfirmDialog(if (delete) "Delete this draft?" else "Archive this announcement?",
            if (delete) "The draft and its attachment are removed. This can't be undone."
            else "Readers will no longer see it. It stays in your college's archive.",
            if (delete) "Delete" else "Archive",
            { confirm = null; if (delete) vm.delete() else vm.archive() }, { confirm = null }, danger = true)
    }
}

@Composable
private fun AnnouncementRow(a: AnnouncementItem, area: String, showStatus: Boolean, open: (() -> Unit)?) {
    val unread = area != "college" && a.read == false
    val urgent = a.priority == "URGENT" || a.priority == "HIGH"
    val status = area == "college" || showStatus
    val chips: (@Composable () -> Unit)? = if (unread || urgent || status) (@Composable {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (unread) StatusChip("New", Tone.Accent)
            if (urgent) StatusChip(label(ANNOUNCEMENT_PRIORITIES, a.priority), priorityTone(a.priority))
            if (status) StatusChip(label(ANNOUNCEMENT_STATUSES, a.status), statusTone(a.status))
        }
    }) else null
    ListRow(a.title,
        subtitle = "${label(ANNOUNCEMENT_CATEGORIES, a.category)} · ${a.target}",
        meta = "${a.author} · ${a.publishAt?.let { materialTime(it) } ?: "Draft"}" + if (a.attachmentName != null) " · Attachment" else "",
        icon = if (a.attachmentName != null) CampusIcons.File else CampusIcons.Megaphone,
        chip = chips,
        onClick = open)
}
