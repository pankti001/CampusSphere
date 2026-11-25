package com.campussphere.app.presentation.attendance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.AttendanceRepository
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun AttendanceScreen(repository: AttendanceRepository, authorization: () -> String, onAccessLost: (Int) -> Unit,
                     mode: AttendanceMode, onExit: () -> Unit) {
    val owner = remember(mode) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val model = viewModel<AttendanceViewModel>(viewModelStoreOwner = owner) {
        AttendanceViewModel(repository, authorization, onAccessLost, mode)
    }
    val state by model.state.collectAsStateWithLifecycle()
    var newSession by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var filtersOpen by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<String?>(null) }
    var correction by remember { mutableStateOf<AttendanceRosterRow?>(null) }
    val back = { if (!model.back()) onExit() }
    BackHandler(onBack = back)

    key(state.page) {
        LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = back, enabled = !state.busy) { Text("Back") }
                Text(when (state.page) {
                    AttendancePage.HOME -> when (mode) {
                        AttendanceMode.FACULTY -> "Class attendance"
                        AttendanceMode.ADMIN -> "College attendance"
                        AttendanceMode.STUDENT -> "My attendance"
                    }
                    AttendancePage.DETAIL -> "Lecture attendance"
                    AttendancePage.POLICY -> "Attendance policy"
                    AttendancePage.ALERTS -> "Students below threshold"
                    AttendancePage.AUDIT -> "Correction history"
                }, style = MaterialTheme.typography.headlineMedium)
            }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh") }
            } }
            state.success?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }

            when (state.page) {
                AttendancePage.HOME -> when (mode) {
                    AttendanceMode.FACULTY -> facultyHome(state, model) { newSession = true }
                    AttendanceMode.STUDENT, AttendanceMode.ADMIN -> {
                        item {
                            OutlinedButton(enabled = !state.busy, onClick = { filtersOpen = true }) {
                                Text(if (filters.isEmpty()) "Filter attendance" else "Filters applied")
                            }
                        }
                        if (mode == AttendanceMode.STUDENT) studentHome(state)
                        else adminHome(state, model)
                    }
                }
                AttendancePage.DETAIL -> state.detail?.let { detail ->
                    sessionDetail(detail, state, mode, model, setAction = { action = it }, edit = { correction = it })
                }
                AttendancePage.POLICY -> state.policy?.let { policy ->
                    item { PolicyForm(policy, state.busy, model::updatePolicy) }
                }
                AttendancePage.ALERTS -> {
                    if (!state.busy && state.error == null && state.alerts.isEmpty()) {
                        item { Text("No active low-attendance alerts on this page.") }
                    }
                    items(state.alerts, key = { it.id }) { alert -> AlertCard(alert, showStudent = true) }
                }
                AttendancePage.AUDIT -> {
                    if (!state.busy && state.error == null && state.corrections.isEmpty()) {
                        item { Text("No corrections on this page.") }
                    }
                    items(state.corrections, key = { it.id }) { audit ->
                        AttendanceCard {
                            Text(audit.actor, style = MaterialTheme.typography.titleMedium)
                            val student = state.detail?.roster?.firstOrNull { it.studentId == audit.studentId }
                            Text("${student?.name.orEmpty()} · ${audit.previousStatus.label()} to ${audit.newStatus.label()}")
                            Text(audit.reason)
                            Text(audit.correctedAt, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (!state.busy && (state.number > 0 || state.hasMore)) item {
                val load: (Int) -> Unit = when (state.page) {
                    AttendancePage.ALERTS -> model::alerts
                    AttendancePage.AUDIT -> { page -> state.detail?.session?.id?.let { model.audit(it, page) }; Unit }
                    else -> { page -> model.home(filters, page) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { load(state.number - 1) }, enabled = state.number > 0) { Text("Previous") }
                    Text("Page ${state.number + 1}")
                    TextButton(onClick = { load(state.number + 1) }, enabled = state.hasMore) { Text("Next") }
                }
            }
        }
    }

    if (newSession) NewSessionDialog(state.assignments, close = { newSession = false }) { id, date, number, start, end ->
        if (model.create(id, date, number, start, end)) newSession = false
    }
    if (filtersOpen) FilterDialog(filters, mode, state, close = { filtersOpen = false }) {
        filters = it
        filtersOpen = false
        model.home(filters, 0)
    }
    action?.let { name ->
        val submit = name == "Submit attendance"
        AlertDialog(onDismissRequest = { action = null }, title = { Text(name) },
            text = {
                if (submit) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Review the roster totals before publishing.")
                    AttendanceMark.entries.forEach { mark -> Text("${mark.label()} · ${state.marks.values.count { it == mark }}") }
                    Text("Changes after submission require an audited correction.")
                } else Text("Faculty will no longer be able to correct this lecture. A College Admin can still record an audited correction.")
            },
            confirmButton = {
                TextButton(onClick = { action = null; if (submit) model.save(true) else model.lock() }) { Text(name) }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Back to lecture") } })
    }
    correction?.let { row -> CorrectionDialog(row, { correction = null }) { mark, reason ->
        correction = null
        model.correct(row.studentId, mark, reason)
    } }
}

private fun LazyListScope.facultyHome(state: AttendanceUi, model: AttendanceViewModel, create: () -> Unit) {
    item {
        Button(onClick = create, enabled = !state.busy && state.assignments.isNotEmpty()) { Text("Open lecture") }
        if (!state.busy && state.error == null && state.assignments.isEmpty()) Text("No active classes are assigned to you.")
        Text("Session history", style = MaterialTheme.typography.titleLarge)
    }
    if (!state.busy && state.error == null && state.sessions.isEmpty()) item { Text("No attendance sessions on this page.") }
    items(state.sessions, key = { it.id }) { session -> SessionCard(session) { model.detail(session.id) } }
}

private fun LazyListScope.studentHome(state: AttendanceUi) {
    val student = state.student ?: return
    item { SummaryCard("Overall attendance", student.overall) }
    if (student.alerts.isNotEmpty()) item { Text("Attendance alerts", style = MaterialTheme.typography.titleLarge) }
    items(student.alerts, key = { "alert-${it.id}" }) { AlertCard(it, showStudent = false) }
    item { Text("By subject", style = MaterialTheme.typography.titleLarge) }
    items(student.subjects, key = { "subject-${it.subjectId}" }) { SummaryCard(it.subject, it.summary) }
    item { Text("Lecture history", style = MaterialTheme.typography.titleLarge) }
    if (student.history.isEmpty()) item { Text("No submitted attendance matches these filters.") }
    items(student.history, key = { "history-${it.sessionId}" }) { row ->
        AttendanceCard {
            Text(row.subject, style = MaterialTheme.typography.titleMedium)
            Text("${row.date} · Lecture ${row.lectureNumber} · ${row.division}")
            Text(row.status.label())
        }
    }
}

private fun LazyListScope.adminHome(state: AttendanceUi, model: AttendanceViewModel) {
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.alerts() }, enabled = !state.busy) { Text("Low attendance") }
            OutlinedButton(onClick = model::policy, enabled = !state.busy) { Text("Policy") }
        }
    }
    val report = state.report ?: return
    item { SummaryCard("College attendance", report.overall) }
    item { Text("By subject", style = MaterialTheme.typography.titleLarge) }
    items(report.subjects, key = { "subject-${it.subjectId}" }) { SummaryCard(it.subject, it.summary) }
    item { Text("By student", style = MaterialTheme.typography.titleLarge) }
    items(report.students, key = { "student-${it.studentId}" }) { SummaryCard(it.student, it.summary) }
    item { Text("Reported sessions", style = MaterialTheme.typography.titleLarge) }
    if (report.sessions.isEmpty()) item { Text("No submitted sessions match these filters on this page.") }
    items(report.sessions, key = { "session-${it.id}" }) { session -> SessionCard(session) { model.detail(session.id) } }
}

private fun LazyListScope.sessionDetail(detail: AttendanceDetail, state: AttendanceUi, mode: AttendanceMode,
                                        model: AttendanceViewModel, setAction: (String) -> Unit,
                                        edit: (AttendanceRosterRow) -> Unit) {
    val session = detail.session
    val admin = mode == AttendanceMode.ADMIN
    val draft = session.status == AttendanceSessionState.DRAFT && !admin
    item {
        SessionCard(session)
        if (draft) {
            Text("${state.marks.size} of ${detail.roster.size} marked")
            OutlinedButton(onClick = model::markAllPresent, enabled = !state.busy) { Text("Mark all present") }
        }
        if (admin) {
            TextButton(onClick = { model.audit(session.id) }, enabled = !state.busy) { Text("Correction history") }
            if (session.status == AttendanceSessionState.SUBMITTED) {
                OutlinedButton(onClick = { setAction("Lock session") }, enabled = !state.busy) { Text("Lock session") }
            }
        }
    }
    items(detail.roster, key = { it.studentId }) { row ->
        AttendanceCard {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(state.marks[row.studentId]?.label() ?: "Unmarked")
            if (draft) MarkOptions(state.marks[row.studentId], !state.busy) { model.mark(row.studentId, it) }
            else if (session.status == AttendanceSessionState.SUBMITTED || admin && session.status == AttendanceSessionState.LOCKED) {
                TextButton(onClick = { edit(row) }, enabled = !state.busy) { Text("Record correction") }
            }
        }
    }
    if (draft) item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { model.save(false) }, enabled = !state.busy) { Text("Save draft") }
            Button(onClick = { setAction("Submit attendance") }, enabled = !state.busy &&
                detail.roster.isNotEmpty() && detail.roster.all { it.studentId in state.marks }) { Text("Review submission") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkOptions(selected: AttendanceMark?, enabled: Boolean, select: (AttendanceMark) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AttendanceMark.entries.forEach { mark ->
            FilterChip(selected = mark == selected, onClick = { select(mark) }, enabled = enabled,
                label = { Text(mark.label()) })
        }
    }
}

@Composable
private fun SessionCard(session: AttendanceSession, open: (() -> Unit)? = null) {
    AttendanceCard(open) {
        Text("${session.subject} · ${session.division}", style = MaterialTheme.typography.titleMedium)
        Text("${session.date} · Lecture ${session.lectureNumber} · ${session.status.label()}")
        session.startTime?.let { Text(if (session.endTime != null) "$it–${session.endTime}" else it) }
        Text("${session.faculty} · ${session.department} · ${session.program} · ${session.semester}")
    }
}

@Composable
private fun SummaryCard(title: String, summary: AttendanceSummary) {
    AttendanceCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(summary.percentage?.let { "${it.toPlainString()}% · ${summary.severity.label()}" } ?: "No eligible sessions",
            style = MaterialTheme.typography.titleLarge)
        Text("${summary.attended} of ${summary.eligible} eligible sessions attended")
        Text("Present ${summary.present} · Absent ${summary.absent} · Late ${summary.late} · Excused ${summary.excused}")
    }
}

@Composable
private fun AlertCard(alert: AttendanceAlert, showStudent: Boolean) {
    AttendanceCard {
        if (showStudent) Text(alert.student, style = MaterialTheme.typography.titleMedium)
        Text("${alert.severity.label()} · ${alert.percentage.toPlainString()}%" +
            if (alert.resolvedAt != null) " · Resolved" else "",
            color = if (alert.resolvedAt == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AttendanceCard(open: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val body: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
    if (open == null) Card(modifier = Modifier.fillMaxWidth(), content = body)
    else Card(onClick = open, modifier = Modifier.fillMaxWidth(), content = body)
}

private fun Enum<*>.label(): String = name.lowercase().replaceFirstChar { it.uppercaseChar() }

@Composable
private fun NewSessionDialog(assignments: List<AttendanceAssignment>, close: () -> Unit,
                             create: (Long, String, String, String, String) -> Unit) {
    var selected by remember { mutableStateOf<AttendanceAssignment?>(null) }
    var date by remember { mutableStateOf(LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()) }
    var lecture by remember { mutableStateOf("1") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    val input = selected?.let { attendanceCreate(it.id, date, lecture, start, end) }
    AlertDialog(onDismissRequest = close, title = { Text("Open lecture") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Assigned class", selected?.let { "${it.subject} · ${it.division}" },
                assignments.map { it to "${it.subject} · ${it.division} · ${it.semester} · ${it.program}" },
                onChoose = { selected = it })
            AttendanceField(date, { date = it }, "Lecture date (YYYY-MM-DD)")
            AttendanceField(lecture, { lecture = it }, "Lecture number (1–99)")
            AttendanceField(start, { start = it }, "Start time (optional, HH:MM)")
            AttendanceField(end, { end = it }, "End time (optional, HH:MM)")
            if (selected != null && input == null) {
                Text("Check the date, lecture number, and time range.", color = MaterialTheme.colorScheme.error)
            }
        }
    }, confirmButton = {
        TextButton(onClick = { input?.let { create(it.assignmentId, date, lecture, start, end) } },
            enabled = input != null) { Text("Load roster") }
    }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun FilterDialog(applied: Map<String, String>, mode: AttendanceMode, ui: AttendanceUi,
                         close: () -> Unit, apply: (Map<String, String>) -> Unit) {
    var draft by remember { mutableStateOf(applied) }
    val from = draft["from"].orEmpty()
    val to = draft["to"].orEmpty()
    val first = runCatching { LocalDate.parse(from) }.getOrNull()
    val last = runCatching { LocalDate.parse(to) }.getOrNull()
    val validDates = (from.isBlank() || first != null) && (to.isBlank() || last != null) &&
        (first == null || last == null || first <= last)
    val labels = if (mode == AttendanceMode.STUDENT) mapOf("subject" to "Subject") else linkedMapOf(
        "department" to "Department", "program" to "Program", "semester" to "Semester", "division" to "Division",
        "subject" to "Subject", "student" to "Student", "faculty" to "Faculty")
    AlertDialog(onDismissRequest = close, title = { Text("Filter attendance") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AttendanceField(from, { draft = draft + ("from" to it.trim()) }, "From (YYYY-MM-DD)")
            AttendanceField(to, { draft = draft + ("to" to it.trim()) }, "To (YYYY-MM-DD)")
            if (!validDates) Text("Enter a valid date range.", color = MaterialTheme.colorScheme.error)
            for ((key, label) in labels) {
                val param = "${key}Id"
                val options = if (mode == AttendanceMode.STUDENT) ui.subjectOptions else ui.options[key].orEmpty()
                Choice(label, options.firstOrNull { it.id.toString() == draft[param] }?.label,
                    listOf(null to "All") + options.map { it to it.label }, onChoose = { option ->
                        draft = if (option == null) draft - param else draft + (param to option.id.toString())
                    })
            }
        }
    }, confirmButton = { TextButton(enabled = validDates, onClick = {
        apply(draft.filterValues { it.isNotBlank() })
    }) { Text("Apply") } }, dismissButton = {
        TextButton(onClick = { draft = emptyMap() }) { Text("Clear filters") }
    })
}

@Composable
private fun CorrectionDialog(row: AttendanceRosterRow, close: () -> Unit,
                             save: (AttendanceMark, String) -> Unit) {
    var mark by remember(row.studentId) { mutableStateOf(row.status) }
    var reason by remember(row.studentId) { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Correct attendance") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Student: ${row.name}")
            Text("Previous status: ${row.status?.label().orEmpty()}")
            MarkOptions(mark, true) { mark = it }
            AttendanceField(reason, { if (it.length <= 500) reason = it }, "Reason for correction")
        }
    }, confirmButton = {
        TextButton(onClick = { mark?.let { save(it, reason) } },
            enabled = mark != null && mark != row.status && reason.isNotBlank()) { Text("Save correction") }
    }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun PolicyForm(policy: AttendancePolicy, busy: Boolean, save: (AttendancePolicy) -> Unit) {
    var warning by remember(policy) { mutableStateOf(policy.warningThreshold.toPlainString()) }
    var critical by remember(policy) { mutableStateOf(policy.criticalThreshold.toPlainString()) }
    var hours by remember(policy) { mutableStateOf(policy.correctionHours.toString()) }
    var late by remember(policy) { mutableStateOf(policy.countLate) }
    var excused by remember(policy) { mutableStateOf(policy.excludeExcused) }
    val w = warning.toBigDecimalOrNull()?.takeIf { it.scale() in 0..2 && it > java.math.BigDecimal.ZERO && it <= 100.toBigDecimal() }
    val c = critical.toBigDecimalOrNull()?.takeIf { it.scale() in 0..2 && it >= java.math.BigDecimal.ZERO && w != null && it < w }
    val h = hours.toIntOrNull()?.takeIf { it in 0..720 }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AttendanceField(warning, { warning = it }, "Warning threshold (%)", !busy)
        AttendanceField(critical, { critical = it }, "Critical threshold (%)", !busy)
        AttendanceField(hours, { hours = it }, "Faculty correction window (hours)", !busy)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = late, onCheckedChange = { late = it }, enabled = !busy)
            Text("Count Late as attended")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = excused, onCheckedChange = { excused = it }, enabled = !busy)
            Text("Exclude Excused from eligible sessions")
        }
        if (w == null || c == null || h == null) {
            Text("Set a lower critical threshold and a correction window from 0 to 720 hours.",
                color = MaterialTheme.colorScheme.error)
        }
        Button(enabled = !busy && w != null && c != null && h != null, onClick = {
            if (w != null && c != null && h != null) save(AttendancePolicy(w, c, late, excused, h))
        }) { Text("Save policy") }
    }
}

@Composable
private fun AttendanceField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), enabled = enabled)
}

@Composable
private fun <T> Choice(title: String, selected: String?, items: List<Pair<T, String>>, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$title · ${selected ?: "Select"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onChoose(value) })
            }
        }
    }
}
