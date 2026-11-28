package com.campussphere.app.presentation.timetable

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.TimetableDetail
import com.campussphere.app.data.remote.api.TimetableEntry
import com.campussphere.app.data.remote.api.TimetableSummary
import com.campussphere.app.data.repository.TimetableImportRepository
import com.campussphere.app.data.repository.TimetableRepository
import com.campussphere.app.data.repository.XLSX_MIME
import com.campussphere.app.presentation.materials.materialTime
import com.campussphere.app.ui.brand.*

private enum class Confirm { DELETE_ENTRY, DISCARD, UNPUBLISH, PUBLISH, IMPORT, COPY }

/** College Admin timetable management. */
@Composable
fun TimetableAdminScreen(repo: TimetableRepository, imports: TimetableImportRepository, auth: () -> String,
                         lost: (Int) -> Unit, exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<TimetableAdminViewModel>(viewModelStoreOwner = owner) { TimetableAdminViewModel(repo, auth, lost, imports) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var entryToDelete by remember { mutableStateOf<TimetableEntry?>(null) }
    var copySource by remember { mutableStateOf<TimetableSummary?>(null) }
    val context = LocalContext.current
    val pickSheet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pickImportFile { imports.readSheet(context.contentResolver, uri) }
    }
    val saveTemplate = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        if (uri != null) vm.downloadTemplate { api, semester -> api.downloadTemplate(auth(), semester, context.contentResolver, uri) }
    }

    LaunchedEffect(vm) { if (!vm.ui.value.loaded) vm.loadScopes() }
    BackHandler { if (!vm.back()) exit() }
    val detail = ui.detail
    val semesterScope = ui.scopes.firstOrNull { it.semesterId == ui.choice.semesterId }
    val classScope = ui.scopes.firstOrNull { it.semesterId == ui.choice.semesterId && it.divisionId == ui.choice.divisionId }

    CampusPage(
        title = when (ui.page) {
            AdminPage.SCOPE -> "Timetables"
            AdminPage.VERSIONS -> "Timetable versions"
            AdminPage.EDITOR -> detail?.let { "Version ${it.timetable.version}" } ?: "Timetable"
            AdminPage.ENTRY -> if (ui.editingEntryId == null) "Add period" else "Edit period"
            AdminPage.PREVIEW -> "Preview and publish"
            AdminPage.HISTORY -> "Change history"
            AdminPage.CLASSROOMS -> "Classrooms"
            AdminPage.IMPORT -> "Upload Excel timetable"
            AdminPage.COPY -> "Copy a timetable"
        },
        subtitle = when (ui.page) {
            AdminPage.SCOPE -> "Choose a class"
            AdminPage.VERSIONS -> ui.versions.firstOrNull()?.let { "${it.program} · ${it.semester} · ${it.division}" }
                ?: classScope?.let { "${it.program} · ${it.semester} · ${it.division}" }
            AdminPage.EDITOR, AdminPage.ENTRY, AdminPage.PREVIEW, AdminPage.HISTORY -> detail?.timetable?.let {
                "${it.program} · ${it.semester} · ${it.division} · ${it.academicYear}"
            }
            AdminPage.CLASSROOMS -> "Whole college"
            AdminPage.IMPORT -> semesterScope?.let { "${it.program} · ${it.semester} · ${it.academicYear}" }
            AdminPage.COPY -> null
        },
        onBack = { if (!vm.back()) exit() },
        backEnabled = !ui.busy,
        busy = ui.loading || ui.busy,
        // Each view keeps its own scroll position; the editor remembers one per day.
        stateKey = when (ui.page) {
            AdminPage.SCOPE -> "scope"
            AdminPage.VERSIONS -> "versions-${ui.choice.semesterId}-${ui.choice.divisionId}"
            AdminPage.EDITOR -> "timetable-${detail?.timetable?.id}-${ui.day}"
            AdminPage.ENTRY -> "entry-${detail?.timetable?.id}-${ui.editingEntryId ?: "new"}"
            AdminPage.PREVIEW -> "preview-${detail?.timetable?.id}"
            AdminPage.HISTORY -> "history-${detail?.timetable?.id}"
            AdminPage.CLASSROOMS -> "classrooms"
            AdminPage.IMPORT -> "import-${ui.choice.semesterId}"
            AdminPage.COPY -> "copy-${ui.choice.semesterId}-${ui.choice.divisionId}"
        },
        error = ui.error,
        // As before, a retry is offered only when the class list itself could not be loaded.
        onRetry = if (ui.page == AdminPage.SCOPE && !ui.loaded) ({ vm.loadScopes() }) else null,
        notice = ui.notice,
    ) {
        if (ui.clashes.isNotEmpty()) item(key = "clashes") {
            MessageBanner("Conflict:\n" + ui.clashes.joinToString("\n") { "• " + clashMessage(it) }, MessageKind.Error)
        }

        when (ui.page) {
            AdminPage.SCOPE -> scopePage(ui, vm)
            AdminPage.VERSIONS -> versionsPage(ui, vm)
            AdminPage.EDITOR -> if (detail != null) editorPage(ui, detail, vm, { entryToDelete = it; confirm = Confirm.DELETE_ENTRY }) { confirm = it }
            AdminPage.ENTRY -> entryPage(ui, vm) { entryToDelete = it; confirm = Confirm.DELETE_ENTRY }
            AdminPage.PREVIEW -> if (detail != null) previewPage(ui, detail) { confirm = Confirm.PUBLISH }
            AdminPage.HISTORY -> historyPage(ui)
            AdminPage.CLASSROOMS -> classroomPage(ui, vm)
            AdminPage.IMPORT -> importPage(ui, { saveTemplate.launch("timetable-template.xlsx") },
                { pickSheet.launch(arrayOf(XLSX_MIME, "application/octet-stream")) }) { confirm = Confirm.IMPORT }
            AdminPage.COPY -> copyPage(ui, classScope?.let { "${it.program} · ${it.semester} · Division ${it.division}" }) {
                copySource = it; confirm = Confirm.COPY
            }
        }
    }

    confirm?.let { kind ->
        val version = detail?.timetable?.version
        val (title, text, button) = when (kind) {
            Confirm.DELETE_ENTRY -> Triple("Remove this period?", entryToDelete?.let {
                "${dayName(it.day)} ${hhmm(it.start)}–${hhmm(it.end)} · ${entryTitle(it)}" }.orEmpty(), "Remove")
            Confirm.DISCARD -> Triple("Discard this draft?", "The draft is archived and kept in the history. The published timetable, if any, does not change.", "Discard")
            Confirm.UNPUBLISH -> Triple("Unpublish version $version?", "Students and faculty will no longer see a timetable for this class until a new version is published.", "Unpublish")
            Confirm.IMPORT -> ui.importPreview.let { p -> Triple("Import as drafts?", "${p?.validRows ?: 0} periods will be saved as ${p?.divisions?.size ?: 0} draft timetable(s). Nothing is published until you publish each draft.", "Import") }
            Confirm.COPY -> Triple("Copy this timetable?", copySource?.let { "From ${it.program} · ${it.semester} · ${it.division}, version ${it.version}. A new draft is created for the chosen class; lessons without a matching faculty assignment are left out and listed." }.orEmpty(), "Copy")
            Confirm.PUBLISH -> Triple("Publish version $version?", "Students of this division and their faculty see it immediately. The currently published version, if any, is archived and kept in the history.", "Publish")
        }
        ConfirmDialog(title, text, button,
            onConfirm = {
                confirm = null
                when (kind) {
                    Confirm.DELETE_ENTRY -> entryToDelete?.let(vm::deleteEntry)
                    Confirm.DISCARD, Confirm.UNPUBLISH -> vm.archive()
                    Confirm.PUBLISH -> vm.publish()
                    Confirm.IMPORT -> vm.confirmImport()
                    Confirm.COPY -> copySource?.let { vm.copyFrom(it.id) }
                }
            },
            onDismiss = { confirm = null },
            danger = kind == Confirm.DELETE_ENTRY || kind == Confirm.DISCARD || kind == Confirm.UNPUBLISH)
    }
}

private fun entryKind(e: TimetableEntry) = runCatching { RowKind.valueOf(e.kind) }.getOrDefault(RowKind.LESSON)

private fun LazyListScope.scopePage(ui: TimetableAdminUi, vm: TimetableAdminViewModel) {
    item(key = "classrooms") {
        SecondaryButton("Manage classrooms", vm::openClassrooms, enabled = !ui.busy, icon = CampusIcons.Building)
    }
    if (ui.loaded && ui.scopes.isEmpty()) item(key = "no-scopes") {
        EmptyState("No classes yet",
            "No semester with a division exists yet. Create academic years, programs, semesters and divisions first.")
    } else if (ui.scopes.isNotEmpty()) {
        item(key = "choose-header") { SectionHeader("Choose a class") }
        item(key = "choose") {
            InfoCard {
                val lists = scopeLists(ui.scopes, ui.choice)
                ChoicePicker("Academic year", lists.years, ui.choice.yearId, modifier = Modifier.fillMaxWidth()) { vm.choose(0, it) }
                ChoicePicker("Department", lists.departments, ui.choice.departmentId, ui.choice.yearId != null, Modifier.fillMaxWidth()) { vm.choose(1, it) }
                ChoicePicker("Program", lists.programs, ui.choice.programId, ui.choice.departmentId != null, Modifier.fillMaxWidth()) { vm.choose(2, it) }
                ChoicePicker("Semester", lists.semesters, ui.choice.semesterId, ui.choice.programId != null, Modifier.fillMaxWidth()) { vm.choose(3, it) }
                ChoicePicker("Division", lists.divisions, ui.choice.divisionId, ui.choice.semesterId != null, Modifier.fillMaxWidth()) { vm.choose(4, it) }
                if (ui.choice.complete) PrimaryButton("Open timetable", vm::loadVersions, enabled = !ui.loading, icon = CampusIcons.Calendar)
                if (ui.choice.semesterId != null) SecondaryButton("Upload Excel for this semester", vm::startImport,
                    enabled = !ui.busy, icon = CampusIcons.Upload)
            }
        }
    }
}

private fun LazyListScope.versionsPage(ui: TimetableAdminUi, vm: TimetableAdminViewModel) {
    val hasDraft = ui.versions.any { it.status == "DRAFT" }
    val hasPublished = ui.versions.any { it.status == "PUBLISHED" }
    if (!hasDraft) {
        item(key = "create-header") { SectionHeader("Create a timetable") }
        item(key = "create") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(if (hasPublished) "Start from the published version" else "Add periods by hand", vm::createDraft,
                    enabled = !ui.busy, icon = CampusIcons.Plus)
                SecondaryButton("Upload an Excel file", vm::startImport, enabled = !ui.busy, icon = CampusIcons.Upload)
                SecondaryButton("Copy another timetable", vm::startCopy, enabled = !ui.busy, icon = CampusIcons.File)
            }
        }
    } else item(key = "draft-exists") {
        MessageBanner("A draft exists. Open it below, or discard it to create a new one another way.", MessageKind.Info)
    }
    if (ui.loaded && ui.versions.isEmpty() && !ui.loading) item(key = "no-versions") {
        EmptyState("No timetable yet", "This class has no timetable yet. Create one above.")
    }
    if (ui.versions.isNotEmpty()) item(key = "versions-header") { SectionHeader("Versions") }
    items(ui.versions, key = { "version-${it.id}" }) { v ->
        ListRow("Version ${v.version}",
            subtitle = "${v.lessons} lessons · ${v.breaks} breaks",
            meta = when (v.status) {
                "PUBLISHED" -> "Published ${v.publishedAt?.let(::materialTime).orEmpty()}"
                "ARCHIVED" -> "Archived ${v.archivedAt?.let(::materialTime).orEmpty()}"
                else -> "Last changed ${materialTime(v.updatedAt)}"
            },
            icon = CampusIcons.Calendar,
            chip = { StatusBadge(v.status) },
            onClick = { vm.open(v.id) })
    }
}

private fun LazyListScope.editorPage(
    ui: TimetableAdminUi, detail: TimetableDetail, vm: TimetableAdminViewModel,
    deleteEntry: (TimetableEntry) -> Unit, confirm: (Confirm) -> Unit,
) {
    val t = detail.timetable
    val editable = t.status == "DRAFT"
    item(key = "summary") {
        InfoCard {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(t.status)
                Text("${t.lessons} lessons · ${t.breaks} breaks", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (t.status) {
                "DRAFT" -> if (detail.problems.isEmpty()) MessageBanner("Ready to publish.", MessageKind.Success)
                    else MessageBanner("${detail.problems.size} problem(s) to fix before publishing.", MessageKind.Error)
                "PUBLISHED" -> Text("Students and faculty see this version. To change it, start a new version.",
                    style = MaterialTheme.typography.bodyMedium)
                else -> Text("Archived. Kept for history; nobody sees it.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (ui.copySkipped.isNotEmpty()) item(key = "copy-skipped") {
        MessageBanner("Left out while copying (add them by hand if needed):\n" + ui.copySkipped.joinToString("\n") { "• $it" },
            MessageKind.Info)
    }
    if (t.status == "PUBLISHED" && detail.problems.isNotEmpty()) item(key = "attention") {
        MessageBanner("Needs attention:\n" + detail.problems.joinToString("\n") { "• " + it.detail }, MessageKind.Error)
    }
    item(key = "days") { DayChips(shownDays(detail.entries.map { it.day }), ui.day) { vm.setDay(it) } }
    val dayEntries = detail.entries.filter { it.day == ui.day }.sortedBy { minutesOf(it.start) ?: 0 }
    if (dayEntries.isEmpty()) item(key = "day-empty") {
        EmptyState("Nothing scheduled", "Nothing on ${dayName(ui.day)} yet.")
    }
    items(dayEntries, key = { "entry-${it.id}" }) { e ->
        val problem = detail.problems.firstOrNull { it.entryId == e.id }
        PeriodCard(hhmm(e.start), hhmm(e.end), entryKind(e), entryTitle(e),
            listOfNotNull(e.faculty, e.classroomCode?.let { "Room $it" }, problem?.detail),
            badge = if (problem != null) "Problem" else null,
            onClick = if (editable) ({ vm.startEdit(e) }) else null)
    }
    item(key = "editor-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editable) {
                SectionHeader("Add a period")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Lesson", { vm.startAdd("LESSON") }, Modifier.weight(1f), enabled = !ui.busy)
                    SecondaryButton("Break", { vm.startAdd("BREAK") }, Modifier.weight(1f), enabled = !ui.busy)
                    SecondaryButton("Lunch", { vm.startAdd("LUNCH") }, Modifier.weight(1f), enabled = !ui.busy)
                }
                if (dayEntries.isNotEmpty()) Text("Tap a period to edit or remove it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                PrimaryButton("Preview and publish", vm::preview, enabled = !ui.busy, icon = CampusIcons.Eye)
                SecondaryButton("Discard draft", { confirm(Confirm.DISCARD) }, enabled = !ui.busy, icon = CampusIcons.Trash, danger = true)
            } else if (t.status == "PUBLISHED") {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                PrimaryButton("Start a new version", vm::startRevision, enabled = !ui.busy, icon = CampusIcons.Edit)
                SecondaryButton("Unpublish", { confirm(Confirm.UNPUBLISH) }, enabled = !ui.busy, icon = CampusIcons.Hide, danger = true)
            }
            SecondaryButton("Change history", vm::openHistory, enabled = !ui.busy, icon = CampusIcons.Clock)
        }
    }
}

private fun LazyListScope.entryPage(
    ui: TimetableAdminUi, vm: TimetableAdminViewModel, delete: (TimetableEntry) -> Unit,
) {
    val form = ui.form
    val options = ui.options
    item(key = "entry-form") {
        InfoCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("LESSON" to "Lesson", "BREAK" to "Break", "LUNCH" to "Lunch").forEach { (kind, text) ->
                    FilterChip(form.kind == kind, { vm.editForm { it.copy(kind = kind) } }, { Text(text) })
                }
            }
            ChoicePicker("Day", (1..7).map { it.toLong() to dayName(it) }, form.day.toLong(), modifier = Modifier.fillMaxWidth()) { d ->
                vm.editForm { it.copy(day = d.toInt()) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.start, { t -> vm.editForm { it.copy(start = t.take(5)) } }, label = { Text("Start (HH:MM)") },
                    singleLine = true, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(form.end, { t -> vm.editForm { it.copy(end = t.take(5)) } }, label = { Text("End (HH:MM)") },
                    singleLine = true, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (form.kind == "LESSON") {
                val assignments = options?.assignments.orEmpty()
                if (assignments.isEmpty()) MessageBanner("No faculty member is assigned to a subject of this division yet. " +
                    "Assign faculty in Manage faculty first.", MessageKind.Info)
                else ChoicePicker("Subject and faculty", assignments.map { it.id to "${it.subjectCode} ${it.subject} · ${it.faculty}" },
                    form.assignmentId, modifier = Modifier.fillMaxWidth()) { id -> vm.editForm { it.copy(assignmentId = id) } }
                val rooms = options?.classrooms.orEmpty()
                if (rooms.isEmpty()) MessageBanner("No active classroom exists yet.", MessageKind.Info,
                    actionLabel = "Manage classrooms", onAction = vm::openClassrooms)
                else ChoicePicker("Classroom", rooms.map { it.id to (it.code + " · " + it.name + (it.capacity?.let { c -> " ($c seats)" } ?: "")) },
                    form.classroomId, modifier = Modifier.fillMaxWidth()) { id -> vm.editForm { it.copy(classroomId = id) } }
            }
            OutlinedTextField(form.label, { t -> vm.editForm { it.copy(label = t.take(60)) } },
                label = { Text(if (form.kind == "LESSON") "Note, for example Lab (optional)" else "Label (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            PrimaryButton("Save period", vm::saveEntry, enabled = !ui.busy, icon = CampusIcons.Check)
            ui.editingEntryId?.let { id ->
                ui.detail?.entries?.firstOrNull { it.id == id }?.let { entry ->
                    SecondaryButton("Remove period", { delete(entry) }, enabled = !ui.busy, icon = CampusIcons.Trash, danger = true)
                }
            }
        }
    }
}

private fun LazyListScope.previewPage(ui: TimetableAdminUi, detail: TimetableDetail, publish: () -> Unit) {
    val problems = (ui.publishProblems.ifEmpty { detail.problems })
    item(key = "preview-check") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (problems.isEmpty()) MessageBanner("No problems found. Faculty and classroom bookings do not clash with other published timetables.",
                MessageKind.Success)
            else MessageBanner("Fix before publishing:\n" + problems.joinToString("\n") { "• " + it.detail }, MessageKind.Error)
            PrimaryButton("Publish version ${detail.timetable.version}", publish,
                enabled = !ui.busy && detail.timetable.status == "DRAFT" && problems.isEmpty(), icon = CampusIcons.Check)
        }
    }
    shownDays(detail.entries.map { it.day }).forEach { day ->
        val entries = detail.entries.filter { it.day == day }.sortedBy { minutesOf(it.start) ?: 0 }
        item(key = "day-$day") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(dayName(day))
                if (entries.isEmpty()) Text("No periods", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.forEach { e ->
                    PeriodCard(hhmm(e.start), hhmm(e.end), entryKind(e), entryTitle(e),
                        listOfNotNull(e.faculty, e.classroomCode?.let { "Room $it" }))
                }
            }
        }
    }
}

private fun LazyListScope.historyPage(ui: TimetableAdminUi) {
    if (ui.history.isEmpty()) item(key = "history-empty") { EmptyState("No changes yet", "No changes recorded yet.") }
    items(ui.history, key = { "event-${it.id}" }) { e ->
        ListRow("Version ${e.version} · " + e.action.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
            subtitle = e.detail, meta = "${e.actor} · ${materialTime(e.at)}", icon = CampusIcons.Clock)
    }
}

private fun LazyListScope.classroomPage(ui: TimetableAdminUi, vm: TimetableAdminViewModel) {
    val form = ui.roomForm
    item(key = "room-form") {
        InfoCard {
            Text(if (ui.editingRoomId == null) "Add a classroom" else "Edit classroom", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(form.code, { t -> vm.editRoom { it.copy(code = t.take(30)) } }, label = { Text("Code, for example A-101") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            OutlinedTextField(form.name, { t -> vm.editRoom { it.copy(name = t.take(150)) } }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
            OutlinedTextField(form.capacity, { t -> vm.editRoom { it.copy(capacity = t.filter(Char::isDigit).take(4)) } },
                label = { Text("Seats (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(form.active, { a -> vm.editRoom { it.copy(active = a) } })
                Text(if (form.active) "Active" else "Inactive (cannot be booked)", style = MaterialTheme.typography.bodyMedium)
            }
            PrimaryButton("Save classroom", vm::saveRoom, enabled = !ui.busy, icon = CampusIcons.Check)
            if (ui.editingRoomId != null) SecondaryButton("Cancel", { vm.startRoomEdit(null) })
        }
    }
    if (ui.classrooms.isNotEmpty()) item(key = "rooms-header") { SectionHeader("All classrooms") }
    else item(key = "rooms-empty") { EmptyState("No classrooms yet", "Add the first classroom above.") }
    items(ui.classrooms, key = { "room-${it.id}" }) { room ->
        ListRow(room.code, subtitle = room.name + (room.capacity?.let { " · $it seats" } ?: ""), icon = CampusIcons.Building,
            chip = if (!room.active) ({ StatusChip("Inactive", Tone.Neutral) }) else null,
            onClick = { vm.startRoomEdit(room) })
    }
}

private fun LazyListScope.importPage(
    ui: TimetableAdminUi, downloadTemplate: () -> Unit, chooseFile: () -> Unit, confirmImport: () -> Unit,
) {
    val preview = ui.importPreview
    item(key = "import-steps") {
        InfoCard {
            ImportStep(1, "Download the template. Its Reference sheet lists this semester's divisions, subjects with their faculty, and classrooms.")
            SecondaryButton("Download template", downloadTemplate, enabled = !ui.busy, icon = CampusIcons.Download)
            ImportStep(2, "Fill in one row per period and save it as .xlsx (up to 2 MB, 1000 rows).")
            ImportStep(3, "Upload it. You see every problem before anything is saved. Imports always become drafts.")
            if (ui.importFile == null) PrimaryButton("Choose Excel file", chooseFile, enabled = !ui.busy, icon = CampusIcons.Upload)
            else SecondaryButton("Choose a corrected file", chooseFile, enabled = !ui.busy, icon = CampusIcons.Upload)
            ui.importFile?.let {
                Text("File: ${it.fileName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (preview == null) return
    item(key = "import-preview") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Preview")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip("${preview.rows} rows")
                StatusChip("${preview.validRows} ready", Tone.Positive)
                if (preview.errorRows > 0) StatusChip("${preview.errorRows} with problems", Tone.Danger)
            }
            if (preview.fileErrors.isNotEmpty()) MessageBanner(preview.fileErrors.joinToString("\n") { "• $it" }, MessageKind.Error)
            preview.divisions.forEach { d ->
                ListRow("Division ${d.division}",
                    subtitle = "${d.periods} periods (${d.lessons} lessons) → new draft version ${d.newVersion}",
                    meta = d.publishedVersion?.let { v -> "Replaces published version $v only when you publish it" },
                    icon = CampusIcons.Users)
            }
            if (preview.canImport) PrimaryButton("Import ${preview.validRows} periods as drafts", confirmImport,
                enabled = !ui.busy, icon = CampusIcons.Check)
            else MessageBanner("Nothing can be imported yet. Correct the file and choose it again.", MessageKind.Info)
        }
    }
    val rows = preview.details.sortedBy { if (it.errors.isEmpty()) 1 else 0 }.take(300)
    if (rows.isNotEmpty()) item(key = "rows-header") { SectionHeader("Rows") }
    items(rows, key = { "row-${it.row}" }) { r ->
        ImportRow("Row ${r.row} · ${r.division} · ${r.day} ${r.start}–${r.end}",
            listOf(r.type.ifBlank { "Lesson" }, r.subjectCode, r.facultyEmail, r.classroomCode).filter { it.isNotBlank() }.joinToString(" · "),
            r.errors)
    }
    if (preview.details.size > rows.size) item(key = "rows-more") {
        Text("Showing ${rows.size} of ${preview.details.size} rows (problems first).", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImportStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
            Text("$number", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge)
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** One spreadsheet row in the import preview; rows with problems get a red border and list each problem. */
@Composable
private fun ImportRow(title: String, details: String, errors: List<String>) {
    val scheme = MaterialTheme.colorScheme
    val bad = errors.isNotEmpty()
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (bad) scheme.error.copy(alpha = 0.6f) else scheme.outlineVariant.copy(alpha = 0.7f))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (bad) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (bad) StatusChip("Problem", Tone.Danger)
            }
            if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = scheme.error) }
        }
    }
}

private fun LazyListScope.copyPage(ui: TimetableAdminUi, target: String?, choose: (TimetableSummary) -> Unit) {
    item(key = "copy-intro") {
        InfoCard {
            target?.let { InfoRow("Copy into", it) }
            Text("Choose a timetable to copy, for example last semester's. Lessons are matched to this class's current " +
                "faculty assignments by subject code.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (ui.copySources.isEmpty()) item(key = "copy-empty") {
        EmptyState("Nothing to copy", "There is no other timetable in your college yet.")
    }
    items(ui.copySources, key = { "source-${it.id}" }) { s ->
        ListRow("${s.program} · ${s.semester} · ${s.division}",
            subtitle = "${s.academicYear} · version ${s.version}",
            meta = "${s.lessons} lessons · ${s.breaks} breaks",
            icon = CampusIcons.Calendar,
            chip = { StatusBadge(s.status) },
            // The card used to be disabled while busy; ignore taps then instead.
            onClick = { if (!ui.busy) choose(s) })
    }
}
