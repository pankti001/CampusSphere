package com.campussphere.app.presentation.timetable

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.TimetableDetail
import com.campussphere.app.data.remote.api.TimetableEntry
import com.campussphere.app.data.repository.TimetableRepository
import com.campussphere.app.presentation.materials.materialTime

private enum class Confirm { DELETE_ENTRY, DISCARD, UNPUBLISH, PUBLISH }

/** College Admin timetable management. */
@Composable
fun TimetableAdminScreen(repo: TimetableRepository, auth: () -> String, lost: (Int) -> Unit, exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<TimetableAdminViewModel>(viewModelStoreOwner = owner) { TimetableAdminViewModel(repo, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var entryToDelete by remember { mutableStateOf<TimetableEntry?>(null) }

    LaunchedEffect(vm) { if (!vm.ui.value.loaded) vm.loadScopes() }
    BackHandler { if (!vm.back()) exit() }
    val detail = ui.detail

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton({ if (!vm.back()) exit() }, enabled = !ui.busy) { Text("Back") }
            Text(when (ui.page) {
                AdminPage.SCOPE -> "Timetable management"
                AdminPage.VERSIONS -> "Timetable versions"
                AdminPage.EDITOR -> detail?.let { "Version ${it.timetable.version}" } ?: "Timetable"
                AdminPage.ENTRY -> if (ui.editingEntryId == null) "Add period" else "Edit period"
                AdminPage.PREVIEW -> "Preview and publish"
                AdminPage.HISTORY -> "Change history"
                AdminPage.CLASSROOMS -> "Classrooms"
            }, style = MaterialTheme.typography.headlineMedium)
            if (ui.page in setOf(AdminPage.EDITOR, AdminPage.ENTRY, AdminPage.PREVIEW, AdminPage.HISTORY)) detail?.timetable?.let {
                Text("${it.program} · ${it.semester} · ${it.division} · ${it.academicYear}", style = MaterialTheme.typography.bodyMedium)
            }
            if (ui.loading || ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        ui.error?.let { item { MessageCard(it, error = true) } }
        if (ui.clashes.isNotEmpty()) item {
            MessageCard("Conflict:\n" + ui.clashes.joinToString("\n") { "• " + clashMessage(it) }, error = true)
        }
        ui.notice?.let { item { MessageCard(it, error = false) } }

        when (ui.page) {
            AdminPage.SCOPE -> scopePage(ui, vm)
            AdminPage.VERSIONS -> versionsPage(ui, vm)
            AdminPage.EDITOR -> if (detail != null) editorPage(ui, detail, vm, { entryToDelete = it; confirm = Confirm.DELETE_ENTRY }) { confirm = it }
            AdminPage.ENTRY -> entryPage(ui, vm) { entryToDelete = it; confirm = Confirm.DELETE_ENTRY }
            AdminPage.PREVIEW -> if (detail != null) previewPage(ui, detail) { confirm = Confirm.PUBLISH }
            AdminPage.HISTORY -> historyPage(ui)
            AdminPage.CLASSROOMS -> classroomPage(ui, vm)
        }
    }

    confirm?.let { kind ->
        val version = detail?.timetable?.version
        val (title, text, button) = when (kind) {
            Confirm.DELETE_ENTRY -> Triple("Remove this period?", entryToDelete?.let {
                "${dayName(it.day)} ${hhmm(it.start)}–${hhmm(it.end)} · ${entryTitle(it)}" }.orEmpty(), "Remove")
            Confirm.DISCARD -> Triple("Discard this draft?", "The draft is archived and kept in the history. The published timetable, if any, does not change.", "Discard")
            Confirm.UNPUBLISH -> Triple("Unpublish version $version?", "Students and faculty will no longer see a timetable for this class until a new version is published.", "Unpublish")
            Confirm.PUBLISH -> Triple("Publish version $version?", "Students of this division and their faculty see it immediately. The currently published version, if any, is archived and kept in the history.", "Publish")
        }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(title) }, text = { Text(text) },
            confirmButton = {
                TextButton({
                    confirm = null
                    when (kind) {
                        Confirm.DELETE_ENTRY -> entryToDelete?.let(vm::deleteEntry)
                        Confirm.DISCARD, Confirm.UNPUBLISH -> vm.archive()
                        Confirm.PUBLISH -> vm.publish()
                    }
                }) { Text(button) }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.scopePage(ui: TimetableAdminUi, vm: TimetableAdminViewModel) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(vm::openClassrooms, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Manage classrooms") }
            if (ui.loaded && ui.scopes.isEmpty()) MessageCard(
                "No semester with a division exists yet. Create academic years, programs, semesters and divisions first.", error = false)
            else if (ui.scopes.isNotEmpty()) {
                Text("Choose a class", style = MaterialTheme.typography.titleMedium)
                val lists = scopeLists(ui.scopes, ui.choice)
                ChoicePicker("Academic year", lists.years, ui.choice.yearId, modifier = Modifier.fillMaxWidth()) { vm.choose(0, it) }
                ChoicePicker("Department", lists.departments, ui.choice.departmentId, ui.choice.yearId != null, Modifier.fillMaxWidth()) { vm.choose(1, it) }
                ChoicePicker("Program", lists.programs, ui.choice.programId, ui.choice.departmentId != null, Modifier.fillMaxWidth()) { vm.choose(2, it) }
                ChoicePicker("Semester", lists.semesters, ui.choice.semesterId, ui.choice.programId != null, Modifier.fillMaxWidth()) { vm.choose(3, it) }
                ChoicePicker("Division", lists.divisions, ui.choice.divisionId, ui.choice.semesterId != null, Modifier.fillMaxWidth()) { vm.choose(4, it) }
                if (ui.choice.complete) Button(vm::loadVersions, enabled = !ui.loading, modifier = Modifier.fillMaxWidth()) { Text("Open timetable") }
            }
            if (ui.error != null && !ui.loaded) TextButton(vm::loadScopes) { Text("Retry") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.versionsPage(ui: TimetableAdminUi, vm: TimetableAdminViewModel) {
    val hasDraft = ui.versions.any { it.status == "DRAFT" }
    val hasPublished = ui.versions.any { it.status == "PUBLISHED" }
    item {
        ui.versions.firstOrNull()?.let { Text("${it.program} · ${it.semester} · ${it.division}", style = MaterialTheme.typography.titleMedium) }
        if (!hasDraft) Button(vm::createDraft, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(if (hasPublished) "Start a new version from the published one" else "Create timetable")
        }
        if (ui.loaded && ui.versions.isEmpty() && !ui.loading) Text("This class has no timetable yet.", Modifier.padding(top = 8.dp))
    }
    items(ui.versions, key = { it.id }) { v ->
        OutlinedCard(onClick = { vm.open(v.id) }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Version ${v.version}", style = MaterialTheme.typography.titleMedium)
                    StatusBadge(v.status)
                }
                Text("${v.lessons} lessons · ${v.breaks} breaks", style = MaterialTheme.typography.bodyMedium)
                Text(when (v.status) {
                    "PUBLISHED" -> "Published ${v.publishedAt?.let(::materialTime).orEmpty()}"
                    "ARCHIVED" -> "Archived ${v.archivedAt?.let(::materialTime).orEmpty()}"
                    else -> "Last changed ${materialTime(v.updatedAt)}"
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.editorPage(
    ui: TimetableAdminUi, detail: TimetableDetail, vm: TimetableAdminViewModel,
    deleteEntry: (TimetableEntry) -> Unit, confirm: (Confirm) -> Unit,
) {
    val t = detail.timetable
    val editable = t.status == "DRAFT"
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(t.status)
                Text("${t.lessons} lessons · ${t.breaks} breaks", style = MaterialTheme.typography.bodyMedium)
            }
            when (t.status) {
                "DRAFT" -> Text(if (detail.problems.isEmpty()) "Ready to publish." else "${detail.problems.size} problem(s) to fix before publishing.",
                    color = if (detail.problems.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                "PUBLISHED" -> Text("Students and faculty see this version. To change it, start a new version.")
                else -> Text("Archived. Kept for history; nobody sees it.")
            }
            if (t.status == "PUBLISHED" && detail.problems.isNotEmpty())
                MessageCard("Needs attention:\n" + detail.problems.joinToString("\n") { "• " + it.detail }, error = true)
            DayChips(shownDays(detail.entries.map { it.day }), ui.day) { vm.setDay(it) }
        }
    }
    val dayEntries = detail.entries.filter { it.day == ui.day }.sortedBy { minutesOf(it.start) ?: 0 }
    if (dayEntries.isEmpty()) item { Text("Nothing on ${dayName(ui.day)} yet.") }
    items(dayEntries, key = { it.id }) { e ->
        val problem = detail.problems.firstOrNull { it.entryId == e.id }
        PeriodCard(hhmm(e.start), hhmm(e.end), runCatching { RowKind.valueOf(e.kind) }.getOrDefault(RowKind.LESSON), entryTitle(e),
            listOfNotNull(e.faculty, e.classroomCode?.let { "Room $it" }, problem?.detail),
            badge = if (problem != null) "Problem" else null,
            onClick = if (editable) ({ vm.startEdit(e) }) else null)
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.startAdd("LESSON") }, enabled = !ui.busy) { Text("Add lesson") }
                    OutlinedButton({ vm.startAdd("BREAK") }, enabled = !ui.busy) { Text("Break") }
                    OutlinedButton({ vm.startAdd("LUNCH") }, enabled = !ui.busy) { Text("Lunch") }
                }
                if (dayEntries.isNotEmpty()) Text("Tap a period to edit or remove it.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Button(vm::preview, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Preview and publish") }
                OutlinedButton({ confirm(Confirm.DISCARD) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Discard draft") }
            } else if (t.status == "PUBLISHED") {
                HorizontalDivider()
                Button(vm::startRevision, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Start a new version") }
                OutlinedButton({ confirm(Confirm.UNPUBLISH) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Unpublish") }
            }
            TextButton(vm::openHistory, enabled = !ui.busy) { Text("Change history") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.entryPage(
    ui: TimetableAdminUi, vm: TimetableAdminViewModel, delete: (TimetableEntry) -> Unit,
) {
    val form = ui.form
    val options = ui.options
    item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("LESSON" to "Lesson", "BREAK" to "Break", "LUNCH" to "Lunch").forEach { (kind, text) ->
                    FilterChip(form.kind == kind, { vm.editForm { it.copy(kind = kind) } }, { Text(text) })
                }
            }
            ChoicePicker("Day", (1..7).map { it.toLong() to dayName(it) }, form.day.toLong(), modifier = Modifier.fillMaxWidth()) { d ->
                vm.editForm { it.copy(day = d.toInt()) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.start, { t -> vm.editForm { it.copy(start = t.take(5)) } }, label = { Text("Start HH:MM") },
                    singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(form.end, { t -> vm.editForm { it.copy(end = t.take(5)) } }, label = { Text("End HH:MM") },
                    singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (form.kind == "LESSON") {
                val assignments = options?.assignments.orEmpty()
                if (assignments.isEmpty()) MessageCard("No faculty member is assigned to a subject of this division yet. Assign faculty in Manage faculty first.", error = false)
                else ChoicePicker("Subject and faculty", assignments.map { it.id to "${it.subjectCode} ${it.subject} · ${it.faculty}" },
                    form.assignmentId, modifier = Modifier.fillMaxWidth()) { id -> vm.editForm { it.copy(assignmentId = id) } }
                val rooms = options?.classrooms.orEmpty()
                if (rooms.isEmpty()) MessageCard("No active classroom exists yet.", error = false, action = "Manage classrooms" to vm::openClassrooms)
                else ChoicePicker("Classroom", rooms.map { it.id to (it.code + " · " + it.name + (it.capacity?.let { c -> " ($c seats)" } ?: "")) },
                    form.classroomId, modifier = Modifier.fillMaxWidth()) { id -> vm.editForm { it.copy(classroomId = id) } }
            }
            OutlinedTextField(form.label, { t -> vm.editForm { it.copy(label = t.take(60)) } },
                label = { Text(if (form.kind == "LESSON") "Note, for example Lab (optional)" else "Label (optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(vm::saveEntry, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Save period") }
            ui.editingEntryId?.let { id ->
                ui.detail?.entries?.firstOrNull { it.id == id }?.let { entry ->
                    OutlinedButton({ delete(entry) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Remove period") }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.previewPage(ui: TimetableAdminUi, detail: TimetableDetail, publish: () -> Unit) {
    val problems = (ui.publishProblems.ifEmpty { detail.problems })
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (problems.isEmpty()) MessageCard("No problems found. Faculty and classroom bookings do not clash with other published timetables.", error = false)
            else MessageCard("Fix before publishing:\n" + problems.joinToString("\n") { "• " + it.detail }, error = true)
            Button(publish, enabled = !ui.busy && detail.timetable.status == "DRAFT" && problems.isEmpty(),
                modifier = Modifier.fillMaxWidth()) { Text("Publish version ${detail.timetable.version}") }
        }
    }
    shownDays(detail.entries.map { it.day }).forEach { day ->
        val entries = detail.entries.filter { it.day == day }.sortedBy { minutesOf(it.start) ?: 0 }
        item(key = "day-$day") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(dayName(day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (entries.isEmpty()) Text("No periods", style = MaterialTheme.typography.bodySmall)
                entries.forEach { e ->
                    Text("${hhmm(e.start)}–${hhmm(e.end)}  ${entryTitle(e)}" +
                            (e.faculty?.let { " · $it" } ?: "") + (e.classroomCode?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (e.kind == "LESSON") FontWeight.Normal else FontWeight.Light)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.historyPage(ui: TimetableAdminUi) {
    if (ui.history.isEmpty()) item { Text("No changes recorded yet.") }
    items(ui.history, key = { it.id }) { e ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Version ${e.version} · " + e.action.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleSmall)
            Text(e.detail, style = MaterialTheme.typography.bodyMedium)
            Text("${e.actor} · ${materialTime(e.at)}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(top = 6.dp))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.classroomPage(ui: TimetableAdminUi, vm: TimetableAdminViewModel) {
    val form = ui.roomForm
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ui.editingRoomId == null) "Add a classroom" else "Edit classroom", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(form.code, { t -> vm.editRoom { it.copy(code = t.take(30)) } }, label = { Text("Code, for example A-101") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(form.name, { t -> vm.editRoom { it.copy(name = t.take(150)) } }, label = { Text("Name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(form.capacity, { t -> vm.editRoom { it.copy(capacity = t.filter(Char::isDigit).take(4)) } },
                label = { Text("Seats (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(form.active, { a -> vm.editRoom { it.copy(active = a) } })
                Text(if (form.active) "  Active" else "  Inactive (cannot be booked)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(vm::saveRoom, enabled = !ui.busy) { Text("Save classroom") }
                if (ui.editingRoomId != null) TextButton({ vm.startRoomEdit(null) }) { Text("Cancel") }
            }
            HorizontalDivider()
            if (ui.classrooms.isEmpty()) Text("No classrooms yet.")
        }
    }
    items(ui.classrooms, key = { it.id }) { room ->
        OutlinedCard(onClick = { vm.startRoomEdit(room) }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(room.code, fontWeight = FontWeight.SemiBold)
                Text(room.name + (room.capacity?.let { " · $it seats" } ?: ""), modifier = Modifier.weight(1f))
                if (!room.active) Badge("Inactive", MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}
