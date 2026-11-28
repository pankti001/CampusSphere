package com.campussphere.app.presentation.homework

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.repository.HOMEWORK_FORMATS
import com.campussphere.app.data.repository.HOMEWORK_LIMIT_BYTES
import com.campussphere.app.data.repository.HomeworkFailure
import com.campussphere.app.data.repository.HomeworkRepository
import com.campussphere.app.data.repository.homeworkPickerTypes
import com.campussphere.app.data.repository.homeworkSize
import com.campussphere.app.ui.brand.*
import kotlinx.coroutines.launch

private val ROSTER_FILTERS = listOf("ALL" to "All", "PENDING" to "Pending", "SUBMITTED" to "To mark", "LATE" to "Late",
    "EVALUATED" to "Marked", "RETURNED" to "Returned")
private val STATUS_FILTERS = listOf("DRAFT" to "Drafts", "PUBLISHED" to "Published", "CLOSED" to "Closed", "ARCHIVED" to "Archived")

@Composable
fun FacultyHomeworkScreen(repo: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit, startCreating: Boolean = false,
                          exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<FacultyHomeworkViewModel>(viewModelStoreOwner = owner) { FacultyHomeworkViewModel(repo, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<(suspend (android.net.Uri) -> Unit)?>(null) }
    var pendingName by remember { mutableStateOf("") }

    LaunchedEffect(vm) { if (!ui.loaded) vm.load() }
    var creationOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ui.loaded) {
        if (startCreating && ui.loaded && !creationOpened && ui.classes.isNotEmpty()) { creationOpened = true; vm.newForm() }
    }
    val busy = ui.busy || ui.loading
    BackHandler { if (!busy && !vm.back()) exit() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val action = pendingDownload
        if (uri != null && action != null) vm.download("Downloaded $pendingName.") { action(uri) }
        pendingDownload = null
    }
    fun download(name: String, action: suspend (android.net.Uri) -> Unit) {
        pendingName = name
        pendingDownload = action
        save.launch(name)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                vm.attach(repo.read(context.contentResolver, uri, HOMEWORK_FORMATS.map { it.first }, HOMEWORK_LIMIT_BYTES))
            } catch (e: HomeworkFailure) {
                vm.clearMessages()
                android.widget.Toast.makeText(context, e.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val selected = ui.selected
    CampusPage(
        title = when (ui.page) {
            FacultyHomeworkPage.LIST -> "My assignments"
            FacultyHomeworkPage.FORM -> if (ui.form?.id == null) "Create assignment" else "Edit assignment"
            FacultyHomeworkPage.DETAIL -> "Assignment"
            FacultyHomeworkPage.PREVIEW -> "Preview"
            FacultyHomeworkPage.SUBMISSIONS -> "Submissions"
            FacultyHomeworkPage.REVIEW -> "Review submission"
            FacultyHomeworkPage.HISTORY -> "History"
        },
        subtitle = when (ui.page) {
            FacultyHomeworkPage.LIST -> null
            FacultyHomeworkPage.FORM -> if (ui.form?.id == null) null else selected?.title
            FacultyHomeworkPage.DETAIL, FacultyHomeworkPage.PREVIEW ->
                selected?.let { "${it.subjectCode} · ${it.subject} · ${it.division}" }
            else -> selected?.title
        },
        onBack = { if (!vm.back()) exit() },
        backEnabled = !busy,
        busy = busy,
        // The list, each assignment, each roster filter and each submission keep their own scroll position.
        stateKey = when (ui.page) {
            FacultyHomeworkPage.LIST -> "list-${ui.classFilter}-${ui.statusFilter}"
            FacultyHomeworkPage.FORM -> "form-${ui.form?.id ?: "new"}"
            FacultyHomeworkPage.DETAIL -> "detail-${selected?.id}"
            FacultyHomeworkPage.PREVIEW -> "preview-${selected?.id}"
            FacultyHomeworkPage.SUBMISSIONS -> "submissions-${selected?.id}-${ui.rosterFilter}"
            FacultyHomeworkPage.REVIEW -> "submission-${ui.review?.submission?.id}"
            FacultyHomeworkPage.HISTORY -> "history-${selected?.id}"
        },
    ) {
        ui.progress?.let { p ->
            item(key = "progress") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Uploading ${(p * 100).toInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                }
            }
        }
        if (ui.error != null || ui.notice != null) item(key = "messages") {
            HomeworkMessages(ui.error, ui.notice, vm::clearMessages)
        }
        when (ui.page) {
            FacultyHomeworkPage.LIST -> listPage(ui, vm, busy)
            FacultyHomeworkPage.FORM -> formPage(ui, vm, busy)
            FacultyHomeworkPage.DETAIL -> selected?.let { hw ->
                item(key = "details") {
                    InfoCard {
                        StatusChip(homeworkStatusLabel(hw.status), homeworkTone(hw.status))
                        HomeworkDetails(hw) {
                            download(hw.attachmentName ?: "attachment") { uri ->
                                repo.saveAttachment(auth(), "faculty", hw.id, context.contentResolver, uri)
                            }
                        }
                    }
                }
                hw.counts?.takeIf { hw.status != "DRAFT" }?.let { c ->
                    item(key = "stats") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HomeworkStat("Students", c.roster, Modifier.weight(1f))
                                HomeworkStat("Pending", c.pending, Modifier.weight(1f))
                                HomeworkStat("To mark", c.toMark, Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HomeworkStat("Submitted", c.submitted, Modifier.weight(1f))
                                HomeworkStat("Late", c.late, Modifier.weight(1f))
                                HomeworkStat("Marked", c.evaluated, Modifier.weight(1f))
                            }
                        }
                    }
                }
                item(key = "actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (hw.status == "DRAFT") PrimaryButton("Preview and publish", { vm.preview() }, enabled = !busy,
                            icon = CampusIcons.Eye)
                        else PrimaryButton("View submissions", { vm.submissions("ALL") }, enabled = !busy, icon = CampusIcons.Users)
                        SectionHeader("Manage")
                        if (hw.canEdit) {
                            SecondaryButton("Edit", { vm.editForm() }, enabled = !busy, icon = CampusIcons.Edit)
                            SecondaryButton(if (hw.attachmentName == null) "Attach a file for students" else "Replace attachment",
                                { pick.launch(homeworkPickerTypes(HOMEWORK_FORMATS.map { it.first })) }, enabled = !busy,
                                icon = CampusIcons.Upload)
                            if (hw.attachmentName != null) SecondaryButton("Remove attachment", { vm.detach() }, enabled = !busy,
                                icon = CampusIcons.Trash, danger = true)
                        }
                        SecondaryButton("History", { vm.history() }, enabled = !busy, icon = CampusIcons.Clock)
                        if (hw.status == "PUBLISHED") SecondaryButton("Close submissions", { confirm = "close" }, enabled = !busy,
                            icon = CampusIcons.Close, danger = true)
                        if (hw.status != "ARCHIVED") SecondaryButton("Archive", { confirm = "archive" }, enabled = !busy,
                            icon = CampusIcons.Archive, danger = true)
                    }
                }
            }
            FacultyHomeworkPage.PREVIEW -> selected?.let { hw ->
                item(key = "hint") { MessageBanner("This is what students will see.", MessageKind.Info) }
                item(key = "details") { InfoCard { HomeworkDetails(hw, null) } }
                item(key = "actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Publish now", { confirm = "publish" }, enabled = !busy, icon = CampusIcons.Megaphone)
                        SecondaryButton("Make changes", { vm.editForm() }, enabled = !busy, icon = CampusIcons.Edit)
                    }
                }
            }
            FacultyHomeworkPage.SUBMISSIONS -> {
                item(key = "filters") {
                    val index = ROSTER_FILTERS.indexOfFirst { it.first == ui.rosterFilter }.coerceAtLeast(0)
                    RosterTabs(ROSTER_FILTERS.map { it.second }, index) { i ->
                        // Filters do nothing while a request is running, as before.
                        if (!busy) vm.submissions(ROSTER_FILTERS[i].first)
                    }
                }
                if (ui.roster.isEmpty() && !busy) item(key = "empty") {
                    EmptyState("No students", "No students match this filter.")
                }
                items(ui.roster, key = { "student-${it.studentId}" }) { row ->
                    ListRow(row.student,
                        subtitle = row.submittedAt?.let {
                            "Submitted ${homeworkTime(it)}" + (if (row.late) " · late" else "") +
                                (row.attempt?.takeIf { a -> a > 1 }?.let { a -> " · attempt $a" } ?: "")
                        },
                        meta = row.marks?.let { "Marks: ${homeworkMarks(it)} / ${homeworkMarks(selected?.maxMarks)}" },
                        icon = if (row.submissionId != null) CampusIcons.File else CampusIcons.Users,
                        chip = { StatusChip(homeworkStatusLabel(row.status), homeworkTone(row.status)) },
                        onClick = if (row.submissionId != null) ({ if (!busy) vm.openReview(row) }) else null)
                }
                if (ui.rosterHasMore) item(key = "more") { SecondaryButton("Load more", vm::moreSubmissions, enabled = !busy) }
            }
            FacultyHomeworkPage.REVIEW -> ui.review?.let { review ->
                val s = review.submission
                val canMark = selected != null && selected.status in setOf("PUBLISHED", "CLOSED") &&
                    s.status in setOf("SUBMITTED", "EVALUATED")
                item(key = "submission") {
                    InfoCard {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.student, style = MaterialTheme.typography.titleMedium)
                            s.studentEmail?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusChip(homeworkStatusLabel(s.status), homeworkTone(s.status))
                            if (s.late) StatusChip("Late", Tone.Warning)
                        }
                        InfoRow("Submitted", "${homeworkTime(s.submittedAt)} · attempt ${s.attempt}")
                        InfoRow("File", "${s.fileName} (${homeworkSize(s.sizeBytes)})")
                        s.comment?.takeIf { it.isNotBlank() }?.let { InfoRow("Student's comment", it) }
                        s.evaluatedBy?.let { InfoRow("Last reviewed", "$it on ${homeworkTime(s.evaluatedAt)}") }
                        SecondaryButton("Download submitted file", {
                            download(s.fileName) { uri ->
                                repo.saveSubmission(auth(), "faculty", s.assignmentId, s.id, context.contentResolver, uri)
                            }
                        }, enabled = !busy, icon = CampusIcons.Download)
                    }
                }
                if (canMark) {
                    item(key = "marks-header") { SectionHeader("Marks (out of ${homeworkMarks(review.maxMarks)})") }
                    item(key = "marks") {
                        InfoCard {
                            OutlinedTextField(ui.marks, vm::setMarks, label = { Text("Marks") }, singleLine = true, enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small)
                            OutlinedTextField(ui.feedback, vm::setFeedback, label = { Text("Feedback (optional)") }, enabled = !busy,
                                minLines = 3, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                            PrimaryButton(if (s.status == "EVALUATED") "Update marks" else "Save marks", vm::evaluate,
                                enabled = !busy, icon = CampusIcons.Check)
                        }
                    }
                } else item(key = "locked") {
                    MessageBanner(when (s.status) {
                        "RETURNED" -> "Returned for correction. Feedback: ${s.feedback.orEmpty()}"
                        "SUPERSEDED" -> "This attempt was replaced by a later one."
                        else -> "This assignment is archived, so marks can no longer change."
                    }, MessageKind.Info)
                }
                if (canMark && s.status == "SUBMITTED") {
                    item(key = "return-header") { SectionHeader("Or return it for correction") }
                    item(key = "return") {
                        InfoCard {
                            OutlinedTextField(ui.returnNote, vm::setReturnNote, label = { Text("What should the student fix?") },
                                enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                            SecondaryButton("Return for correction", vm::returnWork, enabled = !busy, icon = CampusIcons.Refresh,
                                danger = true)
                        }
                    }
                }
                if (review.attempts.size > 1) {
                    item(key = "attempts-header") { SectionHeader("All attempts") }
                    items(review.attempts, key = { "attempt-${it.id}" }) { a ->
                        ListRow("Attempt ${a.attempt}",
                            subtitle = homeworkTime(a.submittedAt) + if (a.late) " · late" else "",
                            meta = a.marks?.let { "${homeworkMarks(it)} marks" },
                            icon = CampusIcons.File,
                            chip = { StatusChip(homeworkStatusLabel(a.status), homeworkTone(a.status)) })
                    }
                }
            }
            FacultyHomeworkPage.HISTORY -> {
                if (ui.history.isEmpty()) item(key = "empty") {
                    EmptyState("No history yet", "Changes to this assignment will appear here.")
                }
                items(ui.history.size, key = { i -> "event-$i" }) { i ->
                    val e = ui.history[i]
                    HistoryRow(e.actor, e.action, e.note, e.time)
                }
            }
        }
    }

    confirm?.let { action ->
        ConfirmDialog(
            title = when (action) { "publish" -> "Publish this assignment?"; "close" -> "Close this assignment?"; else -> "Archive this assignment?" },
            body = when (action) {
                "publish" -> "Students of ${ui.selected?.division} will see it and be notified. Its class and issue date can't change afterwards."
                "close" -> "Students can no longer hand in work (except work you return for correction). Marking stays open."
                else -> "It moves out of your active list. Students keep seeing their own work and results."
            },
            confirm = action.replaceFirstChar { it.uppercase() },
            onConfirm = { confirm = null; vm.lifecycle(action) },
            onDismiss = { confirm = null },
            danger = action != "publish")
    }
}

internal fun historyLabel(action: String) = when (action) {
    "CREATE" -> "created"; "EDIT" -> "edited"; "ATTACH" -> "attached a file"; "DETACH" -> "removed the attachment"
    "PUBLISH" -> "published"; "DEADLINE" -> "changed the deadline"; "CLOSE" -> "closed submissions"
    "ARCHIVE" -> "archived"; "SUBMIT" -> "submitted work"; "EVALUATE" -> "marked work"
    "REEVALUATE" -> "changed marks"; "RETURN" -> "returned work for correction"; else -> action.lowercase()
}

/** One history entry: who did what, the note, and when. */
@Composable
private fun HistoryRow(actor: String, action: String, note: String?, time: String) {
    ListRow("$actor ${historyLabel(action)}", subtitle = note, meta = homeworkTime(time), icon = CampusIcons.Clock)
}

private fun LazyListScope.listPage(ui: FacultyHomeworkUi, vm: FacultyHomeworkViewModel, busy: Boolean) {
    item(key = "create") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Create assignment", vm::newForm, enabled = !busy && ui.classes.isNotEmpty(), icon = CampusIcons.Plus)
            if (ui.loaded && ui.classes.isEmpty()) {
                MessageBanner("You have no active classes. Your College Admin assigns your subjects and divisions.", MessageKind.Info)
            }
        }
    }
    item(key = "filters") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(ui.query, vm::setQuery, "Search titles", onSearch = { if (!busy) vm.search() })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeworkChoice("Class", ui.classes.map { it.classId to "${it.subjectCode} · ${it.division}" }, ui.classFilter,
                    enabled = !busy) { vm.setClassFilter(it) }
                HomeworkChoice("Status", STATUS_FILTERS, ui.statusFilter, enabled = !busy, allLabel = "Active") { vm.setStatusFilter(it) }
            }
        }
    }
    if (ui.loaded && ui.items.isEmpty() && !busy) item(key = "empty") {
        if (ui.query.isBlank() && ui.statusFilter == null && ui.classFilter == null)
            EmptyState("No assignments yet", "Create one for a class you teach.")
        else EmptyState("Nothing found", "No assignments match these filters.")
    }
    items(ui.items, key = { "hw-${it.id}" }) { item -> HomeworkCard(item, !busy) { vm.open(item) } }
    if (ui.hasMore) item(key = "more") { SecondaryButton("Load more", vm::loadMore, enabled = !busy) }
}

/** The create/edit form, split into a few cards: what, when, and what students may hand in. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.formPage(ui: FacultyHomeworkUi, vm: FacultyHomeworkViewModel, busy: Boolean) {
    val form = ui.form ?: return
    val locked = form.id != null && form.published
    if (locked) item(key = "locked") {
        MessageBanner("Published: the class and issue date are fixed, the deadline can only move later, and maximum marks " +
            "lock once work is marked.", MessageKind.Info)
    }
    item(key = "what") {
        InfoCard {
            if (locked) {
                ui.selected?.let { InfoRow("Class", "${it.subjectCode} · ${it.subject} · ${it.division}") }
            } else {
                HomeworkChoice("Class", ui.classes.map { it.classId to "${it.subjectCode} · ${it.subject} · ${it.division}" },
                    form.classId, enabled = !busy, allLabel = null) { id -> vm.updateForm { it.copy(classId = id) } }
                ui.classes.firstOrNull { it.classId == form.classId }?.let {
                    Text("${it.program} · ${it.academicYear} · ${it.semester}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(form.title, { t -> vm.updateForm { it.copy(title = t) } }, label = { Text("Title") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            OutlinedTextField(form.description, { t -> vm.updateForm { it.copy(description = t) } }, label = { Text("Description") },
                enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            OutlinedTextField(form.instructions, { t -> vm.updateForm { it.copy(instructions = t) } }, label = { Text("Instructions") },
                enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
        }
    }
    item(key = "when-header") { SectionHeader("Dates") }
    item(key = "when") {
        InfoCard {
            FieldLabel("Issue date (leave empty for now)")
            DateTimeRow(form.issueDate, form.issueTime, !busy && !locked, "2026-12-01", "09:00",
                { d -> vm.updateForm { it.copy(issueDate = d) } }) { t -> vm.updateForm { it.copy(issueTime = t) } }
            FieldLabel("Deadline")
            DateTimeRow(form.dueDate, form.dueTime, !busy, "2026-12-15", "23:59",
                { d -> vm.updateForm { it.copy(dueDate = d) } }) { t -> vm.updateForm { it.copy(dueTime = t) } }
            SwitchRow("Accept late work (marked late) until a cut-off", form.lateAllowed, !busy) { on ->
                vm.updateForm { it.copy(lateAllowed = on) }
            }
            if (form.lateAllowed) DateTimeRow(form.lateDate, form.lateTime, !busy, "2026-12-20", "23:59",
                { d -> vm.updateForm { it.copy(lateDate = d) } }) { t -> vm.updateForm { it.copy(lateTime = t) } }
            SwitchRow("Let students resubmit until the deadline (earlier attempts are kept)", form.resubmit, !busy) { on ->
                vm.updateForm { it.copy(resubmit = on) }
            }
        }
    }
    item(key = "work-header") { SectionHeader("Marks and files") }
    item(key = "work") {
        InfoCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.maxMarks, { t -> vm.updateForm { it.copy(maxMarks = t) } }, label = { Text("Maximum marks") },
                    singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small)
                OutlinedTextField(form.maxSizeMb, { t -> vm.updateForm { it.copy(maxSizeMb = t) } }, label = { Text("File limit (MB)") },
                    singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small)
            }
            FieldLabel("File types students may upload")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HOMEWORK_FORMATS.forEach { (key, label) ->
                    FilterChip(key in form.formats, {
                        vm.updateForm { it.copy(formats = if (key in it.formats) it.formats - key else it.formats + key) }
                    }, { Text(label) }, enabled = !busy)
                }
            }
        }
    }
    item(key = "save") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ui.formError?.let { MessageBanner(it, MessageKind.Error) }
            PrimaryButton(if (form.id == null) "Save as draft" else "Save changes", vm::save, enabled = !busy, icon = CampusIcons.Check)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun DateTimeRow(date: String, time: String, enabled: Boolean, dateHint: String, timeHint: String,
                        onDate: (String) -> Unit, onTime: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(date, onDate, label = { Text("Date") }, placeholder = { Text(dateHint) }, singleLine = true,
            enabled = enabled, modifier = Modifier.weight(3f), shape = MaterialTheme.shapes.small)
        OutlinedTextField(time, onTime, label = { Text("Time") }, placeholder = { Text(timeHint) }, singleLine = true,
            enabled = enabled, modifier = Modifier.weight(2f), shape = MaterialTheme.shapes.small)
    }
}

/** Six roster filters do not fit a fixed tab row on a phone, so these scroll sideways like the student tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RosterTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    PrimaryScrollableTabRow(selectedTabIndex = selected, containerColor = Color.Transparent, edgePadding = 0.dp) {
        titles.forEachIndexed { index, title ->
            Tab(selected == index, { onSelect(index) }, text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}
