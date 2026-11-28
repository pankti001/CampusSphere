package com.campussphere.app.presentation.resources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.materials.UploadState
import com.campussphere.app.presentation.materials.materialSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Page { LIST, DETAIL, UPLOAD, EDIT, UNITS, REPLACE, COVERAGE, STATS }

/** One unit being edited; the number is its position in the list. */
private data class UnitDraft(val title: String = "", val description: String = "", val topics: String = "",
                             val objectives: String = "", val references: String = "")

private fun actionLabel(action: String) = when (action) {
    "HIDE" -> "Hide from students"; "RESTORE" -> "Restore for students"; "ARCHIVE" -> "Archive"; "REMOVE" -> "Remove"
    else -> action
}

private fun eventLabel(action: String) = when (action) {
    "UPLOAD" -> "Uploaded"; "EDIT" -> "Details edited"; "REPLACE" -> "File replaced"; "UNITS" -> "Units updated"
    "ARCHIVE" -> "Archived"; "UNARCHIVE" -> "Published again"; "HIDE" -> "Hidden"; "RESTORE" -> "Restored"; "REMOVE" -> "Removed"
    else -> action
}

/**
 * Syllabus and question papers for faculty ([area] "faculty": upload and manage their own) and College Admins
 * ([area] "college": review, moderate, coverage and statistics for the whole college).
 */
@Composable
fun ManageResourceScreen(repo: ResourceRepository, area: String, auth: () -> String, lost: (Int) -> Unit, exit: () -> Unit) {
    val owner = remember(area) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<ManageResourceViewModel>(viewModelStoreOwner = owner) { ManageResourceViewModel(repo, area, auth, lost) }
    val context = LocalContext.current
    val faculty = area == "faculty"
    var page by remember { mutableStateOf(Page.LIST) }
    var moderation by remember { mutableStateOf<String?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }

    // Form state for upload and edit.
    var formKind by rememberSaveable { mutableStateOf(SYLLABUS) }
    var subjectId by rememberSaveable { mutableStateOf<Long?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var versionLabel by rememberSaveable { mutableStateOf("") }
    var effectiveFrom by rememberSaveable { mutableStateOf("") }
    var examType by rememberSaveable { mutableStateOf<String?>(null) }
    var examLabelText by rememberSaveable { mutableStateOf("") }
    var examYear by rememberSaveable { mutableStateOf("") }
    var examSession by rememberSaveable { mutableStateOf("") }
    var file by remember { mutableStateOf<MaterialUpload?>(null) }
    var formProblem by remember { mutableStateOf<String?>(null) }
    val units = remember { mutableStateListOf<UnitDraft>() }

    fun clearForm() {
        title = ""; description = ""; versionLabel = ""; effectiveFrom = ""; examType = null; examLabelText = ""
        examYear = ""; examSession = ""; file = null; formProblem = null
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readFile({ repo.read(context.contentResolver, uri) }) { file = it; vm.resetUpload() }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val item = vm.selected
        if (uri != null && item != null) vm.download({
            repo.download(auth(), area, ManageResourceViewModel.kindOf(item), item.id, context.contentResolver, uri)
        }, "Saved ${item.fileName}.")
    }

    LaunchedEffect(vm) {
        if (vm.preview == null) withContext(Dispatchers.IO) { ResourceRepository.clearPreviews(context.cacheDir) }
        if (!vm.loaded) vm.load()
    }
    val item = vm.selected
    LaunchedEffect(item) { if (item == null && page in listOf(Page.DETAIL, Page.EDIT, Page.UNITS, Page.REPLACE)) page = Page.LIST }
    val uploading = vm.upload is UploadState.Uploading
    val back: () -> Unit = {
        when {
            vm.preview != null -> vm.closePreview()
            page == Page.UPLOAD -> { vm.resetUpload(); page = Page.LIST }
            page == Page.EDIT || page == Page.UNITS -> page = Page.DETAIL
            page == Page.REPLACE -> { vm.resetUpload(); page = Page.DETAIL }
            page == Page.DETAIL -> { vm.close(); page = Page.LIST }
            page == Page.COVERAGE || page == Page.STATS -> page = Page.LIST
            else -> exit()
        }
    }
    BackHandler { if (!vm.busy && !uploading) back() }

    val previewFile = vm.preview
    if (previewFile != null && item != null) {
        PdfPreview(previewFile, item.title, vm::closePreview, vm::previewFailed, Modifier.safeDrawingPadding())
        return
    }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(back, enabled = !vm.busy && !uploading) { Text("Back") }
            Text(when (page) {
                Page.UPLOAD -> if (formKind == SYLLABUS) "Upload syllabus" else "Upload question paper"
                Page.EDIT -> "Edit details"
                Page.UNITS -> "Unit-wise syllabus"
                Page.REPLACE -> "Replace PDF"
                Page.DETAIL -> if (item?.kind == "PAPER") "Question paper" else "Syllabus"
                Page.COVERAGE -> "Syllabus coverage"
                Page.STATS -> "Upload statistics"
                Page.LIST -> if (faculty) "My syllabus & papers" else "College syllabus & papers"
            }, style = MaterialTheme.typography.headlineMedium)
            if (vm.loading || vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            vm.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton({
                            when (page) {
                                Page.DETAIL -> vm.refreshSelected()
                                Page.COVERAGE -> vm.loadCoverage()
                                Page.STATS -> vm.loadStats()
                                else -> vm.load()
                            }
                        }, enabled = !vm.loading && !vm.busy) { Text("Retry") }
                    }
                }
            }
            vm.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        }

        when (page) {
            Page.LIST -> listPage(vm, faculty,
                upload = { clearForm(); formKind = vm.kind; vm.resetUpload(); page = Page.UPLOAD },
                coverage = { vm.loadCoverage(); page = Page.COVERAGE },
                stats = { vm.loadStats(); page = Page.STATS },
                open = { vm.open(it); page = Page.DETAIL })

            Page.DETAIL -> if (item != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResourceSummary(item)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ vm.startPreview(context.cacheDir) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Preview") }
                        OutlinedButton({ save.launch(item.fileName) }, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Download") }
                    }
                    if (faculty) {
                        if (!item.canEdit) Text(when (item.status) {
                            "HIDDEN", "REMOVED" -> "The College Admin ${resourceStatusLabel(item.status).lowercase()} this, so it can no longer be changed."
                            else -> "You no longer teach this subject, so this can no longer be changed."
                        }, style = MaterialTheme.typography.bodySmall)
                        if (item.canEdit) {
                            OutlinedButton({
                                title = item.title; description = item.description.orEmpty()
                                versionLabel = item.versionLabel.orEmpty(); effectiveFrom = item.effectiveFrom.orEmpty()
                                examType = item.examType; examLabelText = item.examTypeLabel.orEmpty()
                                examYear = item.examYear?.toString().orEmpty(); examSession = item.examSession.orEmpty()
                                formProblem = null; page = Page.EDIT
                            }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Edit details") }
                            if (item.kind != "PAPER") OutlinedButton({
                                units.clear()
                                units.addAll(item.units.orEmpty().map {
                                    UnitDraft(it.title, it.description.orEmpty(), it.topics.orEmpty(), it.objectives.orEmpty(), it.references.orEmpty())
                                })
                                formProblem = null; page = Page.UNITS
                            }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (item.units.isNullOrEmpty()) "Add unit-wise content" else "Edit units")
                            }
                            OutlinedButton({ file = null; vm.resetUpload(); page = Page.REPLACE }, enabled = !vm.busy,
                                modifier = Modifier.fillMaxWidth()) { Text("Replace PDF") }
                            OutlinedButton({ confirmArchive = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                                Text(if (item.status == "ARCHIVED") "Publish again" else "Archive")
                            }
                        }
                    } else {
                        val actions = moderationActions(item.status)
                        if (actions.isNotEmpty()) Text("Moderation", style = MaterialTheme.typography.titleMedium)
                        actions.forEach { a ->
                            OutlinedButton({ moderation = a }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(actionLabel(a)) }
                        }
                        if (vm.events.isNotEmpty()) {
                            Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                            vm.events.forEach { e ->
                                Text("${eventLabel(e.action)} by ${e.actor} · ${resourceTime(e.time)}" +
                                    (e.note?.let { "\n“$it”" } ?: ""), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    val units = item.units.orEmpty()
                    if (item.kind != "PAPER" && units.isNotEmpty()) {
                        Text("Units", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        units.forEach { Text("Unit ${it.number}: ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }

            Page.UPLOAD, Page.EDIT -> item {
                val editing = page == Page.EDIT
                val kindNow = if (editing) item?.let { ManageResourceViewModel.kindOf(it) } ?: SYLLABUS else formKind
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!editing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(formKind == SYLLABUS, { formKind = SYLLABUS }, { Text("Syllabus") }, enabled = !uploading)
                            FilterChip(formKind == PAPERS, { formKind = PAPERS }, { Text("Question paper") }, enabled = !uploading)
                        }
                        if (vm.subjects.isEmpty()) Text("You have no current teaching assignment, so there is no subject to upload for.")
                        else Choice("Subject", vm.subjects.map { it.subjectId.toString() to "${it.code} · ${it.name} (${it.semester}, ${it.academicYear})" },
                            subjectId?.toString(), allLabel = null, modifier = Modifier.fillMaxWidth()) { id -> subjectId = id?.toLong() }
                    }
                    if (kindNow == SYLLABUS) {
                        OutlinedTextField(title, { title = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                        OutlinedTextField(versionLabel, { versionLabel = it.take(40) }, Modifier.fillMaxWidth(),
                            label = { Text("Version label, e.g. 2026-27") }, singleLine = true)
                        OutlinedTextField(effectiveFrom, { effectiveFrom = it.take(10) }, Modifier.fillMaxWidth(),
                            label = { Text("Effective from (YYYY-MM-DD, optional)") }, singleLine = true)
                    } else {
                        Choice("Exam", EXAM_TYPES, examType, allLabel = null) { examType = it }
                        if (examType == "OTHER") OutlinedTextField(examLabelText, { examLabelText = it.take(60) }, Modifier.fillMaxWidth(),
                            label = { Text("Exam name, e.g. Class test") }, singleLine = true)
                        OutlinedTextField(examYear, { examYear = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(),
                            label = { Text("Exam year") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(examSession, { examSession = it.take(60) }, Modifier.fillMaxWidth(),
                            label = { Text("Session, e.g. May (optional)") }, singleLine = true)
                        OutlinedTextField(title, { title = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                    }
                    OutlinedTextField(description, { description = it.take(2000) }, Modifier.fillMaxWidth(),
                        label = { Text("Description (optional)") }, minLines = 2)
                    if (!editing) {
                        OutlinedButton({ pick.launch(arrayOf("application/pdf")) }, enabled = !uploading && !vm.busy) {
                            Text(file?.let { "PDF: ${it.fileName} (${materialSize(it.bytes.size.toLong())})" } ?: "Choose PDF (up to 10 MB)")
                        }
                    }
                    formProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    UploadProgress(vm.upload, vm::retryUpload)
                    Button({
                        val problem = if (kindNow == SYLLABUS) syllabusProblem(title, versionLabel, effectiveFrom)
                            else paperProblem(examType, examLabelText, examYear, title)
                        formProblem = problem ?: if (!editing && subjectId == null) "Choose the subject."
                            else if (!editing && file == null) "Choose the PDF file." else null
                        if (formProblem != null) return@Button
                        if (editing) {
                            val current = item ?: return@Button
                            if (kindNow == SYLLABUS) vm.editSyllabus(current.id, SyllabusEdit(title.trim(), description.trim(),
                                versionLabel.trim(), effectiveFrom.trim().ifEmpty { null })) { page = Page.DETAIL }
                            else vm.editPaper(current.id, PaperEdit(examType!!, examLabelText.trim().takeIf { examType == "OTHER" },
                                examYear.trim().toInt(), examSession.trim(), title.trim(), description.trim())) { page = Page.DETAIL }
                        } else {
                            val fields = if (kindNow == SYLLABUS) mapOf("title" to title, "description" to description,
                                "versionLabel" to versionLabel, "effectiveFrom" to effectiveFrom)
                            else mapOf("examType" to examType, "examTypeLabel" to examLabelText.takeIf { examType == "OTHER" },
                                "examYear" to examYear, "examSession" to examSession, "title" to title, "description" to description)
                            // After uploading, show the tab of what was uploaded so it is visible straight away.
                            vm.startUpload(kindNow, subjectId!!, fields, file!!) { clearForm(); vm.show(kindNow); page = Page.LIST }
                        }
                    }, enabled = !uploading && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(if (editing) "Save changes" else "Upload") }
                }
            }

            Page.UNITS -> {
                item { Text("Add the units exactly as in the official syllabus. Students see them below the PDF.",
                    style = MaterialTheme.typography.bodySmall) }
                itemsIndexed(units) { index, unit ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Unit ${index + 1}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                TextButton({ units.removeAt(index) }, enabled = !vm.busy) { Text("Remove") }
                            }
                            OutlinedTextField(unit.title, { units[index] = unit.copy(title = it.take(160)) }, Modifier.fillMaxWidth(),
                                label = { Text("Unit title") }, singleLine = true)
                            OutlinedTextField(unit.topics, { units[index] = unit.copy(topics = it.take(4000)) }, Modifier.fillMaxWidth(),
                                label = { Text("Topics") }, minLines = 2)
                            OutlinedTextField(unit.objectives, { units[index] = unit.copy(objectives = it.take(2000)) }, Modifier.fillMaxWidth(),
                                label = { Text("Learning objectives (optional)") })
                            OutlinedTextField(unit.references, { units[index] = unit.copy(references = it.take(2000)) }, Modifier.fillMaxWidth(),
                                label = { Text("Reference books (optional)") })
                            OutlinedTextField(unit.description, { units[index] = unit.copy(description = it.take(2000)) }, Modifier.fillMaxWidth(),
                                label = { Text("Notes (optional)") })
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ units.add(UnitDraft()) }, enabled = units.size < 30 && !vm.busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Add unit")
                        }
                        formProblem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button({
                            formProblem = if (units.any { it.title.isBlank() }) "Every unit needs a title." else null
                            val current = item ?: return@Button
                            if (formProblem == null) vm.saveUnits(current.id, units.mapIndexed { i, u ->
                                ResourceUnit(i + 1, u.title.trim(), u.description.trim(), u.topics.trim(), u.objectives.trim(), u.references.trim())
                            }) { page = Page.DETAIL }
                        }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Save units") }
                    }
                }
            }

            Page.REPLACE -> item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Use this to correct the PDF of this same version or paper. For a new syllabus version, upload it separately.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ pick.launch(arrayOf("application/pdf")) }, enabled = !uploading && !vm.busy) {
                        Text(file?.let { "PDF: ${it.fileName} (${materialSize(it.bytes.size.toLong())})" } ?: "Choose new PDF (up to 10 MB)")
                    }
                    UploadProgress(vm.upload, vm::retryUpload)
                    Button({ file?.let { f -> vm.replaceFile(f) { file = null; page = Page.DETAIL } } },
                        enabled = file != null && !uploading && !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Replace") }
                }
            }

            Page.COVERAGE -> coveragePage(vm.coverage, vm.busy)
            Page.STATS -> vm.stats?.let { s -> item { StatsView(s) } }
        }
    }

    if (confirmArchive && item != null) {
        val archiving = item.status != "ARCHIVED"
        AlertDialog(onDismissRequest = { confirmArchive = false },
            title = { Text(if (archiving) "Archive this?" else "Publish again?") },
            text = { Text(if (archiving) "Students will no longer see it. You can publish it again later."
                else "Students of this subject will see it again.") },
            confirmButton = { TextButton({ confirmArchive = false; vm.archive(archiving) }) { Text(if (archiving) "Archive" else "Publish") } },
            dismissButton = { TextButton({ confirmArchive = false }) { Text("Cancel") } })
    }

    moderation?.let { action ->
        var note by remember(action) { mutableStateOf("") }
        val needsNote = moderationNeedsNote(action)
        AlertDialog(onDismissRequest = { moderation = null },
            title = { Text(actionLabel(action) + "?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when (action) {
                        "HIDE" -> "Students will no longer see it. You can restore it later. The faculty member sees your reason."
                        "REMOVE" -> "It is removed for good and can no longer be restored."
                        "RESTORE" -> "Students of this subject will see it again."
                        else -> "Students will no longer see it."
                    })
                    OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(),
                        label = { Text(if (needsNote) "Reason (required)" else "Note (optional)") })
                }
            },
            confirmButton = {
                TextButton({ moderation = null; vm.moderate(action, note) }, enabled = !needsNote || note.trim().length >= 3) {
                    Text(actionLabel(action))
                }
            },
            dismissButton = { TextButton({ moderation = null }) { Text("Cancel") } })
    }
}

private fun LazyListScope.listPage(vm: ManageResourceViewModel, faculty: Boolean, upload: () -> Unit, coverage: () -> Unit,
                                   stats: () -> Unit, open: (ResourceItem) -> Unit) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (faculty) Button(upload, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Upload syllabus or question paper") }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(coverage, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Coverage") }
                OutlinedButton(stats, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Statistics") }
            }
            TabRow(if (vm.kind == SYLLABUS) 0 else 1) {
                Tab(vm.kind == SYLLABUS, { vm.show(SYLLABUS) }, text = { Text("Syllabus") })
                Tab(vm.kind == PAPERS, { vm.show(PAPERS) }, text = { Text("Question papers") })
            }
            var search by remember(vm.kind) { mutableStateOf(vm.query) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Choice("Status", RESOURCE_STATUSES, vm.status) { vm.filter(it, search) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it.take(80) }, Modifier.weight(1f), label = { Text("Search title or subject") },
                    singleLine = true)
                TextButton({ vm.filter(vm.status, search) }, enabled = !vm.loading) { Text("Search") }
            }
        }
    }
    if (vm.loaded && vm.items.isEmpty() && !vm.loading) item {
        Text(if (faculty) "You have not uploaded any ${if (vm.kind == SYLLABUS) "syllabus" else "question papers"} with these filters yet."
            else "Nothing found with these filters.")
    }
    items(vm.items, key = { it.kind + it.id }) { r ->
        OutlinedCard(onClick = { open(r) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.kind == "PAPER") paperHeading(r) else r.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Tag(resourceStatusLabel(r.status), strong = r.status == "PUBLISHED")
                    if (r.current) Tag("Current", strong = true)
                }
                Text("${r.subjectCode} · ${r.subject} · ${r.semester}", style = MaterialTheme.typography.bodySmall)
                Text((if (r.kind == "PAPER") r.title + " · " else "Version ${r.versionLabel} · ") +
                    (if (faculty) resourceTime(r.createdAt) else "by ${r.faculty}"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (vm.more) item {
        OutlinedButton(vm::loadMore, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
    }
}

private fun LazyListScope.coveragePage(rows: List<ResourceCoverage>, busy: Boolean) {
    val missing = rows.count { !it.hasCurrentSyllabus }
    item {
        Text(if (rows.isEmpty()) (if (busy) "" else "No subjects have been set up yet.")
            else "${rows.size - missing} of ${rows.size} subjects have a published syllabus · " +
                "${rows.count { it.papers > 0 }} have question papers", style = MaterialTheme.typography.bodyMedium)
    }
    items(rows.sortedWith(compareBy<ResourceCoverage> { it.hasCurrentSyllabus }.thenBy { it.code }), key = { it.subjectId }) { c ->
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor =
            if (c.hasCurrentSyllabus) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${c.code} · ${c.subject}", fontWeight = FontWeight.SemiBold)
                Text("${c.program} · ${c.semester} · ${c.academicYear}", style = MaterialTheme.typography.bodySmall)
                Text((if (c.hasCurrentSyllabus) "Published syllabus" else "No published syllabus") +
                    " · ${c.papers} question paper" + (if (c.papers == 1L) "" else "s"), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatsView(s: ResourceStats) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        @Composable fun block(title: String, rows: List<ResourceCount>, empty: String, label: (String) -> String = { it }) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (rows.isEmpty()) Text(empty, style = MaterialTheme.typography.bodySmall)
                    val top = rows.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                    rows.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label(r.label), Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            LinearProgressIndicator({ r.count.toFloat() / top }, Modifier.weight(1f))
                            Text(r.count.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        block("Syllabus versions", s.syllabusByStatus, "None uploaded yet.", ::resourceStatusLabel)
        block("Question papers", s.papersByStatus, "None uploaded yet.", ::resourceStatusLabel)
        block("Uploads in the last 12 months", s.uploadsByMonth, "No uploads yet.")
        block("Most uploads by faculty", s.uploadsByFaculty, "No uploads yet.")
    }
}

@Composable
private fun ResourceSummary(item: ResourceItem) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (item.kind == "PAPER") paperHeading(item) else item.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Tag(resourceStatusLabel(item.status), strong = item.status == "PUBLISHED")
        }
        if (item.kind == "PAPER") Text(item.title, style = MaterialTheme.typography.titleMedium)
        else Text(listOfNotNull("Version ${item.versionLabel}", resourceDate(item.effectiveFrom)?.let { "effective from $it" },
            if (item.current) "current" else null).joinToString(" · "))
        Text("${item.subjectCode} · ${item.subject}", style = MaterialTheme.typography.bodyMedium)
        Text("${item.program} · ${item.semester} · ${item.academicYear}", style = MaterialTheme.typography.bodySmall)
        Text("Uploaded by ${item.faculty} · ${resourceTime(item.createdAt)}", style = MaterialTheme.typography.bodySmall)
        item.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
        Text("${item.fileName} · ${materialSize(item.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
        item.moderationNote?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Text("College Admin note: $it", Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun UploadProgress(state: UploadState, retry: () -> Unit) {
    when (state) {
        is UploadState.Uploading -> Column {
            LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth())
            Text("Uploading ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        is UploadState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton(retry) { Text("Retry") }
        }
        UploadState.Idle -> {}
    }
}
