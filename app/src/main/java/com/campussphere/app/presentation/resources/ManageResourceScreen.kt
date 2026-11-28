package com.campussphere.app.presentation.resources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.materials.UploadState
import com.campussphere.app.presentation.materials.materialSize
import com.campussphere.app.ui.brand.*
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

private fun actionIcon(action: String) = when (action) {
    "HIDE" -> CampusIcons.Hide; "RESTORE" -> CampusIcons.Eye; "ARCHIVE" -> CampusIcons.Archive; else -> CampusIcons.Trash
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
    var page by rememberSaveable { mutableStateOf(Page.LIST) }
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
        }, "Saved ${item.fileName}")
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

    // Each view keeps its own saved state, so the list keeps its place while a PDF preview is open.
    val views = rememberSaveableStateHolder()
    val previewFile = vm.preview
    if (previewFile != null && item != null) {
        views.SaveableStateProvider("preview") {
            PdfPreview(previewFile, item.title, vm::closePreview, vm::previewFailed, Modifier.safeDrawingPadding())
        }
        return
    }

    views.SaveableStateProvider("page") {
        CampusPage(
            title = when (page) {
                Page.UPLOAD -> if (formKind == SYLLABUS) "Upload syllabus" else "Upload question paper"
                Page.EDIT -> "Edit details"
                Page.UNITS -> "Units"
                Page.REPLACE -> "Replace PDF"
                Page.DETAIL -> item?.let { if (it.kind == "PAPER") paperHeading(it) else it.title } ?: ""
                Page.COVERAGE -> "Syllabus coverage"
                Page.STATS -> "Statistics"
                Page.LIST -> "Syllabus & papers"
            },
            subtitle = when (page) {
                Page.LIST -> if (faculty) "Your uploads" else "Whole college"
                Page.DETAIL, Page.EDIT, Page.UNITS, Page.REPLACE -> item?.let { "${it.subjectCode} · ${it.subject}" }
                else -> null
            },
            onBack = back, backEnabled = !vm.busy && !uploading, busy = vm.loading || vm.busy,
            stateKey = when (page) {
                Page.LIST -> "list-${vm.kind}-${vm.status}-${vm.query}"
                Page.DETAIL -> "detail-${item?.kind}-${item?.id}"
                else -> page.name
            },
            error = vm.error,
            onRetry = {
                when (page) {
                    Page.DETAIL -> vm.refreshSelected()
                    Page.COVERAGE -> vm.loadCoverage()
                    Page.STATS -> vm.loadStats()
                    else -> vm.load()
                }
            },
            notice = vm.notice,
        ) {
            when (page) {
                Page.LIST -> listPage(vm, faculty,
                    upload = { clearForm(); formKind = vm.kind; vm.resetUpload(); page = Page.UPLOAD },
                    coverage = { vm.loadCoverage(); page = Page.COVERAGE },
                    stats = { vm.loadStats(); page = Page.STATS },
                    open = { vm.open(it); page = Page.DETAIL })

                Page.DETAIL -> if (item != null) item(key = "detail") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ResourceSummary(item)
                        PrimaryButton("Preview", { vm.startPreview(context.cacheDir) }, enabled = !vm.busy, icon = CampusIcons.Eye)
                        SecondaryButton("Download", { save.launch(item.fileName) }, enabled = !vm.busy, icon = CampusIcons.Download)
                        if (faculty) {
                            if (!item.canEdit) MessageBanner(when (item.status) {
                                "HIDDEN", "REMOVED" -> "The College Admin ${resourceStatusLabel(item.status).lowercase()} this, so it can't be changed."
                                else -> "You no longer teach this subject, so this can't be changed."
                            }, MessageKind.Info)
                            if (item.canEdit) {
                                SectionHeader("Manage")
                                SecondaryButton("Edit details", {
                                    title = item.title; description = item.description.orEmpty()
                                    versionLabel = item.versionLabel.orEmpty(); effectiveFrom = item.effectiveFrom.orEmpty()
                                    examType = item.examType; examLabelText = item.examTypeLabel.orEmpty()
                                    examYear = item.examYear?.toString().orEmpty(); examSession = item.examSession.orEmpty()
                                    formProblem = null; page = Page.EDIT
                                }, enabled = !vm.busy, icon = CampusIcons.Edit)
                                if (item.kind != "PAPER") SecondaryButton(if (item.units.isNullOrEmpty()) "Add units" else "Edit units", {
                                    units.clear()
                                    units.addAll(item.units.orEmpty().map {
                                        UnitDraft(it.title, it.description.orEmpty(), it.topics.orEmpty(), it.objectives.orEmpty(), it.references.orEmpty())
                                    })
                                    formProblem = null; page = Page.UNITS
                                }, enabled = !vm.busy, icon = CampusIcons.Checklist)
                                SecondaryButton("Replace PDF", { file = null; vm.resetUpload(); page = Page.REPLACE }, enabled = !vm.busy,
                                    icon = CampusIcons.Upload)
                                SecondaryButton(if (item.status == "ARCHIVED") "Publish again" else "Archive", { confirmArchive = true },
                                    enabled = !vm.busy, icon = CampusIcons.Archive)
                            }
                        } else {
                            val actions = moderationActions(item.status)
                            if (actions.isNotEmpty()) SectionHeader("Moderation")
                            actions.forEach { a ->
                                SecondaryButton(actionLabel(a), { moderation = a }, enabled = !vm.busy, icon = actionIcon(a),
                                    danger = a == "REMOVE" || a == "HIDE")
                            }
                            if (vm.events.isNotEmpty()) {
                                SectionHeader("History")
                                InfoCard {
                                    vm.events.forEach { e ->
                                        InfoRow("${eventLabel(e.action)} · ${resourceTime(e.time)}", e.actor + (e.note?.let { " · “$it”" } ?: ""))
                                    }
                                }
                            }
                        }
                        val unitList = item.units.orEmpty()
                        if (item.kind != "PAPER" && unitList.isNotEmpty()) {
                            SectionHeader("Units")
                            unitList.forEach { UnitCard(it) }
                        }
                    }
                }

                Page.UPLOAD, Page.EDIT -> item(key = "form") {
                    val editing = page == Page.EDIT
                    val kindNow = if (editing) item?.let { ManageResourceViewModel.kindOf(it) } ?: SYLLABUS else formKind
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!editing) {
                            CampusTabs(listOf("Syllabus", "Question paper"), if (formKind == SYLLABUS) 0 else 1) {
                                if (!uploading) formKind = if (it == 0) SYLLABUS else PAPERS
                            }
                            if (vm.subjects.isEmpty()) MessageBanner("You have no current teaching assignment, so there's no subject to upload for.")
                            else ChoiceChip("Choose subject", vm.subjects.map {
                                it.subjectId.toString() to "${it.code} · ${it.name} (${it.semester}, ${it.academicYear})"
                            }, subjectId?.toString(), allLabel = null) { id -> subjectId = id?.toLong() }
                        }
                        InfoCard {
                            if (kindNow == SYLLABUS) {
                                OutlinedTextField(title, { title = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                                OutlinedTextField(versionLabel, { versionLabel = it.take(40) }, Modifier.fillMaxWidth(),
                                    label = { Text("Version label") }, placeholder = { Text("2026-27") }, singleLine = true)
                                OutlinedTextField(effectiveFrom, { effectiveFrom = it.take(10) }, Modifier.fillMaxWidth(),
                                    label = { Text("Effective from (optional)") }, placeholder = { Text("2026-06-01") }, singleLine = true)
                            } else {
                                ChoiceChip("Choose exam", EXAM_TYPES, examType, allLabel = null) { examType = it }
                                if (examType == "OTHER") OutlinedTextField(examLabelText, { examLabelText = it.take(60) }, Modifier.fillMaxWidth(),
                                    label = { Text("Exam name") }, placeholder = { Text("Class test") }, singleLine = true)
                                OutlinedTextField(examYear, { examYear = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(),
                                    label = { Text("Exam year") }, placeholder = { Text("2025") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                OutlinedTextField(examSession, { examSession = it.take(60) }, Modifier.fillMaxWidth(),
                                    label = { Text("Session (optional)") }, placeholder = { Text("May") }, singleLine = true)
                                OutlinedTextField(title, { title = it.take(160) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                            }
                            OutlinedTextField(description, { description = it.take(2000) }, Modifier.fillMaxWidth(),
                                label = { Text("Description (optional)") }, minLines = 2)
                        }
                        if (!editing) SecondaryButton(file?.let { "${it.fileName} · ${materialSize(it.bytes.size.toLong())}" } ?: "Choose PDF (up to 10 MB)",
                            { pick.launch(arrayOf("application/pdf")) }, enabled = !uploading && !vm.busy, icon = CampusIcons.File)
                        formProblem?.let { MessageBanner(it, MessageKind.Error) }
                        UploadProgress(vm.upload, vm::retryUpload)
                        PrimaryButton(if (editing) "Save changes" else "Upload", {
                            val problem = if (kindNow == SYLLABUS) syllabusProblem(title, versionLabel, effectiveFrom)
                                else paperProblem(examType, examLabelText, examYear, title)
                            formProblem = problem ?: if (!editing && subjectId == null) "Choose the subject."
                                else if (!editing && file == null) "Choose the PDF file." else null
                            if (formProblem != null) return@PrimaryButton
                            if (editing) {
                                val current = item ?: return@PrimaryButton
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
                        }, enabled = !uploading && !vm.busy, icon = if (editing) CampusIcons.Check else CampusIcons.Upload)
                    }
                }

                Page.UNITS -> {
                    item(key = "hint") { MessageBanner("Add the units exactly as in the official syllabus. Students see them below the PDF.") }
                    itemsIndexed(units) { index, unit ->
                        InfoCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Unit ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
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
                    item(key = "save") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("Add unit", { units.add(UnitDraft()) }, enabled = units.size < 30 && !vm.busy, icon = CampusIcons.Plus)
                            formProblem?.let { MessageBanner(it, MessageKind.Error) }
                            PrimaryButton("Save units", {
                                formProblem = if (units.any { it.title.isBlank() }) "Every unit needs a title." else null
                                val current = item ?: return@PrimaryButton
                                if (formProblem == null) vm.saveUnits(current.id, units.mapIndexed { i, u ->
                                    ResourceUnit(i + 1, u.title.trim(), u.description.trim(), u.topics.trim(), u.objectives.trim(), u.references.trim())
                                }) { page = Page.DETAIL }
                            }, enabled = !vm.busy, icon = CampusIcons.Check)
                        }
                    }
                }

                Page.REPLACE -> item(key = "replace") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        MessageBanner("Use this to correct the PDF of this same version or paper. For a new syllabus version, upload it separately.")
                        SecondaryButton(file?.let { "${it.fileName} · ${materialSize(it.bytes.size.toLong())}" } ?: "Choose new PDF (up to 10 MB)",
                            { pick.launch(arrayOf("application/pdf")) }, enabled = !uploading && !vm.busy, icon = CampusIcons.File)
                        UploadProgress(vm.upload, vm::retryUpload)
                        PrimaryButton("Replace", { file?.let { f -> vm.replaceFile(f) { file = null; page = Page.DETAIL } } },
                            enabled = file != null && !uploading && !vm.busy, icon = CampusIcons.Upload)
                    }
                }

                Page.COVERAGE -> coveragePage(vm.coverage, vm.busy)
                Page.STATS -> vm.stats?.let { s -> item(key = "stats") { StatsView(s) } }
            }
        }
    }

    if (confirmArchive && item != null) {
        val archiving = item.status != "ARCHIVED"
        ConfirmDialog(if (archiving) "Archive this?" else "Publish again?",
            if (archiving) "Students will no longer see it. You can publish it again later." else "Students of this subject will see it again.",
            if (archiving) "Archive" else "Publish", { confirmArchive = false; vm.archive(archiving) }, { confirmArchive = false })
    }

    moderation?.let { action ->
        var note by remember(action) { mutableStateOf("") }
        val needsNote = moderationNeedsNote(action)
        ConfirmDialog(actionLabel(action) + "?", when (action) {
            "HIDE" -> "Students will no longer see it. You can restore it later. The faculty member sees your reason."
            "REMOVE" -> "It's removed for good and can't be restored."
            "RESTORE" -> "Students of this subject will see it again."
            else -> "Students will no longer see it."
        }, actionLabel(action), { moderation = null; vm.moderate(action, note) }, { moderation = null },
            danger = action == "REMOVE" || action == "HIDE", confirmEnabled = !needsNote || note.trim().length >= 3) {
            OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(),
                label = { Text(if (needsNote) "Reason (required)" else "Note (optional)") })
        }
    }
}

private fun LazyListScope.listPage(vm: ManageResourceViewModel, faculty: Boolean, upload: () -> Unit, coverage: () -> Unit,
                                   stats: () -> Unit, open: (ResourceItem) -> Unit) {
    item(key = "actions") {
        if (faculty) PrimaryButton("Upload syllabus or paper", upload, enabled = !vm.busy, icon = CampusIcons.Upload)
        else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Coverage", coverage, Modifier.weight(1f), enabled = !vm.busy, icon = CampusIcons.Checklist)
            SecondaryButton("Statistics", stats, Modifier.weight(1f), enabled = !vm.busy, icon = CampusIcons.Chart)
        }
    }
    item(key = "tabs") {
        CampusTabs(listOf("Syllabus", "Question papers"), if (vm.kind == SYLLABUS) 0 else 1) { vm.show(if (it == 0) SYLLABUS else PAPERS) }
    }
    item(key = "filters") {
        var search by remember(vm.kind) { mutableStateOf(vm.query) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(search, { search = it.take(80) }, "Search title or subject", onSearch = { vm.filter(vm.status, search) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Status", RESOURCE_STATUSES, vm.status, allLabel = "All statuses") { vm.filter(it, search) }
            }
        }
    }
    if (vm.loaded && vm.items.isEmpty() && !vm.loading) item(key = "empty") {
        if (faculty) EmptyState("Nothing uploaded yet", "Upload a syllabus or a past paper for a subject you teach.")
        else EmptyState("Nothing found", "Try another status or search.")
    }
    items(vm.items, key = { it.kind + it.id }) { r ->
        ListRow(if (r.kind == "PAPER") paperHeading(r) else r.title,
            subtitle = "${r.subjectCode} · ${r.subject}",
            meta = (if (r.kind == "PAPER") r.title else "Version ${r.versionLabel}") + " · " +
                (if (faculty) resourceTime(r.createdAt) else r.faculty),
            icon = CampusIcons.File,
            chip = { StatusChip(if (r.current && r.status == "PUBLISHED") "Current" else resourceStatusLabel(r.status),
                if (r.current && r.status == "PUBLISHED") Tone.Accent else resourceTone(r.status)) },
            onClick = { open(r) })
    }
    if (vm.more) item(key = "more") { SecondaryButton("Load more", vm::loadMore, enabled = !vm.busy) }
}

private fun LazyListScope.coveragePage(rows: List<ResourceCoverage>, busy: Boolean) {
    val missing = rows.count { !it.hasCurrentSyllabus }
    if (rows.isEmpty()) {
        if (!busy) item(key = "empty") { EmptyState("No subjects yet", "Set up programs, semesters and subjects first.") }
        return
    }
    item(key = "summary") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("With syllabus", "${rows.size - missing} of ${rows.size}", Modifier.weight(1f))
            StatTile("With papers", "${rows.count { it.papers > 0 }} of ${rows.size}", Modifier.weight(1f))
        }
    }
    if (missing > 0) item(key = "missing") { SectionHeader("Missing a syllabus") }
    items(rows.sortedWith(compareBy<ResourceCoverage> { it.hasCurrentSyllabus }.thenBy { it.code }), key = { it.subjectId }) { c ->
        ListRow("${c.code} · ${c.subject}", subtitle = "${c.program} · ${c.semester} · ${c.academicYear}",
            meta = "${c.papers} question paper" + if (c.papers == 1L) "" else "s",
            chip = { if (c.hasCurrentSyllabus) StatusChip("Syllabus", Tone.Positive) else StatusChip("No syllabus", Tone.Warning) })
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun StatsView(s: ResourceStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        @Composable fun block(title: String, rows: List<ResourceCount>, empty: String, label: (String) -> String = { it }) {
            SectionHeader(title)
            InfoCard {
                if (rows.isEmpty()) Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val top = rows.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                rows.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(label(r.label), Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        LinearProgressIndicator({ r.count.toFloat() / top }, Modifier.weight(1f))
                        Text(r.count.toString(), style = MaterialTheme.typography.titleSmall)
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
private fun UploadProgress(state: UploadState, retry: () -> Unit) {
    when (state) {
        is UploadState.Uploading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth())
            Text("Uploading ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        is UploadState.Failed -> MessageBanner(state.message, MessageKind.Error, actionLabel = "Retry", onAction = retry)
        UploadState.Idle -> {}
    }
}
