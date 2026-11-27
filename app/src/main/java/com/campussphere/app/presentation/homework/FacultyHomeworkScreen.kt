package com.campussphere.app.presentation.homework

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(when (action) { "publish" -> "Publish this assignment?"; "close" -> "Close this assignment?"; else -> "Archive this assignment?" }) },
            text = { Text(when (action) {
                "publish" -> "Students of ${ui.selected?.division} will see it and be notified. Its class and issue date cannot change afterwards."
                "close" -> "Students can no longer hand in work (except work you return for correction). Marking stays open."
                else -> "It moves out of your active list. Students keep seeing their own work and results."
            }) },
            confirmButton = { TextButton({ confirm = null; vm.lifecycle(action) }) { Text(action.replaceFirstChar { it.uppercase() }) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton({ if (!vm.back()) exit() }, enabled = !busy) { Text("Back") }
            Text(when (ui.page) {
                FacultyHomeworkPage.LIST -> "My assignments"
                FacultyHomeworkPage.FORM -> if (ui.form?.id == null) "Create assignment" else "Edit assignment"
                FacultyHomeworkPage.DETAIL -> "Assignment"
                FacultyHomeworkPage.PREVIEW -> "Preview"
                FacultyHomeworkPage.SUBMISSIONS -> "Submissions"
                FacultyHomeworkPage.REVIEW -> "Review submission"
                FacultyHomeworkPage.HISTORY -> "History"
            }, style = MaterialTheme.typography.headlineMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            ui.progress?.let { p -> LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 4.dp)) }
        }
        item { HomeworkMessages(ui.error, ui.notice, vm::clearMessages) }
        when (ui.page) {
            FacultyHomeworkPage.LIST -> listPage(ui, vm, busy)
            FacultyHomeworkPage.FORM -> item { FormPage(ui, vm, busy) }
            FacultyHomeworkPage.DETAIL -> ui.selected?.let { hw ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeworkBadge(homeworkStatusLabel(hw.status), statusColor(hw.status))
                            HomeworkDetails(hw) {
                                download(hw.attachmentName ?: "attachment") { uri ->
                                    repo.saveAttachment(auth(), "faculty", hw.id, context.contentResolver, uri)
                                }
                            }
                        }
                    }
                }
                hw.counts?.takeIf { hw.status != "DRAFT" }?.let { c ->
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeworkStat("Students", c.roster, Modifier.weight(1f))
                            HomeworkStat("Pending", c.pending, Modifier.weight(1f))
                            HomeworkStat("To mark", c.toMark, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeworkStat("Submitted", c.submitted, Modifier.weight(1f))
                            HomeworkStat("Late", c.late, Modifier.weight(1f))
                            HomeworkStat("Marked", c.evaluated, Modifier.weight(1f))
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hw.status == "DRAFT") Button({ vm.preview() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Preview and publish")
                        }
                        if (hw.status != "DRAFT") Button({ vm.submissions("ALL") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("View submissions")
                        }
                        if (hw.canEdit) {
                            OutlinedButton({ vm.editForm() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Edit") }
                            OutlinedButton({ pick.launch(homeworkPickerTypes(HOMEWORK_FORMATS.map { it.first })) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth()) {
                                Text(if (hw.attachmentName == null) "Attach a file for students" else "Replace attachment")
                            }
                            if (hw.attachmentName != null) TextButton({ vm.detach() }, enabled = !busy) { Text("Remove attachment") }
                        }
                        if (hw.status == "PUBLISHED") OutlinedButton({ confirm = "close" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Close submissions")
                        }
                        if (hw.status != "ARCHIVED") TextButton({ confirm = "archive" }, enabled = !busy) { Text("Archive") }
                        TextButton({ vm.history() }, enabled = !busy) { Text("History") }
                    }
                }
            }
            FacultyHomeworkPage.PREVIEW -> ui.selected?.let { hw ->
                item { Text("This is what students will see.", style = MaterialTheme.typography.bodyMedium) }
                item { Card(Modifier.fillMaxWidth()) { Box(Modifier.padding(14.dp)) { HomeworkDetails(hw, null) } } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ confirm = "publish" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Publish now") }
                        OutlinedButton({ vm.editForm() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Make changes") }
                    }
                }
            }
            FacultyHomeworkPage.SUBMISSIONS -> {
                item {
                    Text(ui.selected?.title.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ROSTER_FILTERS.forEach { (key, label) ->
                            FilterChip(ui.rosterFilter == key, { vm.submissions(key) }, { Text(label) }, enabled = !busy)
                        }
                    }
                }
                if (ui.roster.isEmpty() && !busy) item { Text("No students match this filter.") }
                items(ui.roster, key = { it.studentId }) { row ->
                    Card(onClick = { vm.openReview(row) }, enabled = !busy && row.submissionId != null, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(row.student, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                HomeworkBadge(homeworkStatusLabel(row.status), statusColor(row.status))
                            }
                            row.submittedAt?.let { Text("Submitted ${homeworkTime(it)}" + (if (row.late) " · late" else "") +
                                (row.attempt?.takeIf { a -> a > 1 }?.let { a -> " · attempt $a" } ?: "")) }
                            row.marks?.let { Text("Marks: ${homeworkMarks(it)} / ${homeworkMarks(ui.selected?.maxMarks)}") }
                        }
                    }
                }
                if (ui.rosterHasMore) item { OutlinedButton(vm::moreSubmissions, enabled = !busy) { Text("Load more") } }
            }
            FacultyHomeworkPage.REVIEW -> ui.review?.let { review ->
                val s = review.submission
                val assignment = ui.selected
                val canMark = assignment != null && assignment.status in setOf("PUBLISHED", "CLOSED") && s.status in setOf("SUBMITTED", "EVALUATED")
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(s.student, style = MaterialTheme.typography.titleMedium)
                            s.studentEmail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            HomeworkBadge(homeworkStatusLabel(s.status), statusColor(s.status))
                            Text("Attempt ${s.attempt} · submitted ${homeworkTime(s.submittedAt)}" + if (s.late) " · LATE" else "")
                            Text("${s.fileName} (${homeworkSize(s.sizeBytes)})")
                            OutlinedButton({
                                download(s.fileName) { uri ->
                                    repo.saveSubmission(auth(), "faculty", s.assignmentId, s.id, context.contentResolver, uri)
                                }
                            }, enabled = !busy) { Text("Download submitted file") }
                            s.comment?.takeIf { it.isNotBlank() }?.let { Text("Student's comment: $it") }
                            s.evaluatedBy?.let { Text("Last reviewed by $it on ${homeworkTime(s.evaluatedAt)}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                if (canMark) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Marks (out of ${homeworkMarks(review.maxMarks)})", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(ui.marks, vm::setMarks, label = { Text("Marks") }, singleLine = true, enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(ui.feedback, vm::setFeedback, label = { Text("Feedback (optional)") }, enabled = !busy,
                            minLines = 3, modifier = Modifier.fillMaxWidth())
                        Button(vm::evaluate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text(if (s.status == "EVALUATED") "Update marks" else "Save marks")
                        }
                    }
                } else item {
                    Text(when {
                        s.status == "RETURNED" -> "Returned for correction. Feedback: ${s.feedback.orEmpty()}"
                        s.status == "SUPERSEDED" -> "This attempt was replaced by a later one."
                        else -> "This assignment is archived; marks can no longer change."
                    })
                }
                if (canMark && s.status == "SUBMITTED") item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text("Or return it for correction", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(ui.returnNote, vm::setReturnNote, label = { Text("What should the student fix?") },
                            enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(vm::returnWork, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Return for correction") }
                    }
                }
                if (review.attempts.size > 1) {
                    item { Text("All attempts", style = MaterialTheme.typography.titleSmall) }
                    items(review.attempts, key = { "attempt-${it.id}" }) { a ->
                        Text("Attempt ${a.attempt}: ${homeworkStatusLabel(a.status)} · ${homeworkTime(a.submittedAt)}" +
                            (if (a.late) " · late" else "") + (a.marks?.let { " · ${homeworkMarks(it)} marks" } ?: ""),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            FacultyHomeworkPage.HISTORY -> {
                if (ui.history.isEmpty()) item { Text("No history yet.") }
                items(ui.history.size) { i ->
                    val e = ui.history[i]
                    Text("${homeworkTime(e.time)} · ${e.actor}: ${historyLabel(e.action)}" + (e.note?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

internal fun historyLabel(action: String) = when (action) {
    "CREATE" -> "created"; "EDIT" -> "edited"; "ATTACH" -> "attached a file"; "DETACH" -> "removed the attachment"
    "PUBLISH" -> "published"; "DEADLINE" -> "changed the deadline"; "CLOSE" -> "closed submissions"
    "ARCHIVE" -> "archived"; "SUBMIT" -> "submitted work"; "EVALUATE" -> "marked work"
    "REEVALUATE" -> "changed marks"; "RETURN" -> "returned work for correction"; else -> action.lowercase()
}

private fun androidx.compose.foundation.lazy.LazyListScope.listPage(ui: FacultyHomeworkUi, vm: FacultyHomeworkViewModel, busy: Boolean) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(vm::newForm, enabled = !busy && ui.classes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Create assignment") }
            if (ui.loaded && ui.classes.isEmpty()) {
                Text("You have no active classes. Your College Admin assigns your subjects and divisions.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeworkChoice("Class", ui.classes.map { it.classId to "${it.subjectCode} · ${it.division}" }, ui.classFilter,
                    enabled = !busy) { vm.setClassFilter(it) }
                HomeworkChoice("Status", STATUS_FILTERS, ui.statusFilter, enabled = !busy, allLabel = "Active") { vm.setStatusFilter(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ui.query, vm::setQuery, label = { Text("Search titles") }, singleLine = true,
                    modifier = Modifier.weight(1f))
                OutlinedButton(vm::search, enabled = !busy) { Text("Search") }
            }
        }
    }
    if (ui.loaded && ui.items.isEmpty() && !busy) item {
        Text(if (ui.query.isBlank() && ui.statusFilter == null && ui.classFilter == null)
            "No assignments yet. Create one for a class you teach." else "No assignments match these filters.")
    }
    items(ui.items, key = { it.id }) { item -> HomeworkCard(item, !busy) { vm.open(item) } }
    if (ui.hasMore) item { OutlinedButton(vm::loadMore, enabled = !busy) { Text("Load more") } }
}

@Composable
private fun FormPage(ui: FacultyHomeworkUi, vm: FacultyHomeworkViewModel, busy: Boolean) {
    val form = ui.form ?: return
    val locked = form.id != null && form.published
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (locked) {
            Text("Published: the class and issue date are fixed, the deadline can only move later, and maximum marks lock once work is marked.",
                style = MaterialTheme.typography.bodySmall)
            ui.selected?.let { Text("Class: ${it.subjectCode} · ${it.subject} · ${it.division}") }
        } else {
            HomeworkChoice("Class", ui.classes.map { it.classId to "${it.subjectCode} · ${it.subject} · ${it.division}" },
                form.classId, enabled = !busy, allLabel = null) { id -> vm.updateForm { it.copy(classId = id) } }
            ui.classes.firstOrNull { it.classId == form.classId }?.let {
                Text("${it.program} · ${it.academicYear} · ${it.semester}", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(form.title, { t -> vm.updateForm { it.copy(title = t) } }, label = { Text("Title") },
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(form.description, { t -> vm.updateForm { it.copy(description = t) } }, label = { Text("Description") },
            enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(form.instructions, { t -> vm.updateForm { it.copy(instructions = t) } }, label = { Text("Instructions") },
            enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text("Issue date (leave empty for now)", style = MaterialTheme.typography.titleSmall)
        DateTimeRow(form.issueDate, form.issueTime, !busy && !locked, "2026-12-01", "09:00",
            { d -> vm.updateForm { it.copy(issueDate = d) } }) { t -> vm.updateForm { it.copy(issueTime = t) } }
        Text("Deadline", style = MaterialTheme.typography.titleSmall)
        DateTimeRow(form.dueDate, form.dueTime, !busy, "2026-12-15", "23:59",
            { d -> vm.updateForm { it.copy(dueDate = d) } }) { t -> vm.updateForm { it.copy(dueTime = t) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(form.maxMarks, { t -> vm.updateForm { it.copy(maxMarks = t) } }, label = { Text("Maximum marks") },
                singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f))
            OutlinedTextField(form.maxSizeMb, { t -> vm.updateForm { it.copy(maxSizeMb = t) } }, label = { Text("File limit (MB)") },
                singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f))
        }
        Text("File types students may upload", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HOMEWORK_FORMATS.forEach { (key, label) ->
                FilterChip(key in form.formats, {
                    vm.updateForm { it.copy(formats = if (key in it.formats) it.formats - key else it.formats + key) }
                }, { Text(label) }, enabled = !busy)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(form.lateAllowed, { on -> vm.updateForm { it.copy(lateAllowed = on) } }, enabled = !busy)
            Spacer(Modifier.width(8.dp))
            Text("Accept late work (marked late) until a cut-off")
        }
        if (form.lateAllowed) DateTimeRow(form.lateDate, form.lateTime, !busy, "2026-12-20", "23:59",
            { d -> vm.updateForm { it.copy(lateDate = d) } }) { t -> vm.updateForm { it.copy(lateTime = t) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(form.resubmit, { on -> vm.updateForm { it.copy(resubmit = on) } }, enabled = !busy)
            Spacer(Modifier.width(8.dp))
            Text("Let students resubmit until the deadline (earlier attempts are kept)")
        }
        ui.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(vm::save, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (form.id == null) "Save as draft" else "Save changes")
        }
    }
}

@Composable
private fun DateTimeRow(date: String, time: String, enabled: Boolean, dateHint: String, timeHint: String,
                        onDate: (String) -> Unit, onTime: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(date, onDate, label = { Text("Date") }, placeholder = { Text(dateHint) }, singleLine = true,
            enabled = enabled, modifier = Modifier.weight(3f))
        OutlinedTextField(time, onTime, label = { Text("Time") }, placeholder = { Text(timeHint) }, singleLine = true,
            enabled = enabled, modifier = Modifier.weight(2f))
    }
}
