package com.campussphere.app.presentation.attendance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.AttendanceRepository
import com.campussphere.app.ui.brand.*
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

    val detail = state.detail
    val draftDetail = state.page == AttendancePage.DETAIL && detail != null &&
        detail.session.status == AttendanceSessionState.DRAFT && mode != AttendanceMode.ADMIN

    CampusPage(
        title = when (state.page) {
            AttendancePage.HOME -> when (mode) {
                AttendanceMode.FACULTY -> "Class attendance"
                AttendanceMode.ADMIN -> "College attendance"
                AttendanceMode.STUDENT -> "My attendance"
            }
            AttendancePage.DETAIL -> "Lecture attendance"
            AttendancePage.POLICY -> "Attendance policy"
            AttendancePage.ALERTS -> "Students below threshold"
            AttendancePage.AUDIT -> "Correction history"
        },
        subtitle = when (state.page) {
            AttendancePage.DETAIL, AttendancePage.AUDIT -> detail?.session?.let { "${it.subject} · ${it.division}" }
            AttendancePage.HOME -> if (mode != AttendanceMode.FACULTY && filters.isNotEmpty()) "Filtered" else null
            else -> null
        },
        onBack = back,
        backEnabled = !state.busy,
        busy = state.busy,
        // Each page (and each page of results) keeps its own scroll position.
        stateKey = when (state.page) {
            AttendancePage.HOME -> "home-$mode-${state.number}"
            AttendancePage.DETAIL -> "detail-${detail?.session?.id}"
            AttendancePage.POLICY -> "policy"
            AttendancePage.ALERTS -> "alerts-${state.number}"
            AttendancePage.AUDIT -> "audit-${detail?.session?.id}-${state.number}"
        },
        error = state.error,
        onRetry = model::refresh,
        notice = state.success,
        bottomBar = if (draftDetail && detail != null) {
            {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Save draft", { model.save(false) }, Modifier.weight(1f), enabled = !state.busy)
                    PrimaryButton("Review submission", { action = "Submit attendance" }, Modifier.weight(1f),
                        enabled = !state.busy && detail.roster.isNotEmpty() && detail.roster.all { it.studentId in state.marks })
                }
            }
        } else null,
    ) {
        when (state.page) {
            AttendancePage.HOME -> when (mode) {
                AttendanceMode.FACULTY -> facultyHome(state, model) { newSession = true }
                AttendanceMode.STUDENT, AttendanceMode.ADMIN -> {
                    item(key = "filters") {
                        SecondaryButton(if (filters.isEmpty()) "Filter attendance" else "Filters applied",
                            { filtersOpen = true }, enabled = !state.busy, icon = CampusIcons.Filter)
                    }
                    if (mode == AttendanceMode.STUDENT) studentHome(state)
                    else adminHome(state, model)
                }
            }
            AttendancePage.DETAIL -> detail?.let {
                sessionDetail(it, state, mode, model, setAction = { name -> action = name }, edit = { row -> correction = row })
            }
            AttendancePage.POLICY -> state.policy?.let { policy ->
                item(key = "policy") { PolicyForm(policy, state.busy, model::updatePolicy) }
            }
            AttendancePage.ALERTS -> {
                if (!state.busy && state.error == null && state.alerts.isEmpty()) item(key = "empty") {
                    EmptyState("No low-attendance alerts", "There are no active alerts on this page.")
                }
                items(state.alerts, key = { "alert-${it.id}" }) { alert -> AlertRow(alert, showStudent = true) }
            }
            AttendancePage.AUDIT -> {
                if (!state.busy && state.error == null && state.corrections.isEmpty()) item(key = "empty") {
                    EmptyState("No corrections", "There are no corrections on this page.")
                }
                items(state.corrections, key = { "audit-${it.id}" }) { audit ->
                    val student = state.detail?.roster?.firstOrNull { it.studentId == audit.studentId }
                    InfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(audit.actor, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(audit.correctedAt, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                        student?.name?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusChip(audit.previousStatus.label(), markTone(audit.previousStatus))
                            Text("to", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            StatusChip(audit.newStatus.label(), markTone(audit.newStatus))
                        }
                        InfoRow("Reason", audit.reason)
                    }
                }
            }
        }
        if (!state.busy && (state.number > 0 || state.hasMore)) item(key = "pager") {
            val load: (Int) -> Unit = when (state.page) {
                AttendancePage.ALERTS -> model::alerts
                AttendancePage.AUDIT -> { page -> state.detail?.session?.id?.let { model.audit(it, page) }; Unit }
                else -> { page -> model.home(filters, page) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { load(state.number - 1) }, enabled = state.number > 0) { Text("Previous") }
                Text("Page ${state.number + 1}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                TextButton(onClick = { load(state.number + 1) }, enabled = state.hasMore) { Text("Next") }
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
        ConfirmDialog(
            title = name,
            body = if (submit) "Check the roster totals before publishing."
            else "Faculty will no longer be able to correct this lecture. A College Admin can still record an audited correction.",
            confirm = name,
            onConfirm = { action = null; if (submit) model.save(true) else model.lock() },
            onDismiss = { action = null },
            danger = !submit,
            extra = if (submit) {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AttendanceMark.entries.forEach { mark ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(mark.label(), Modifier.weight(1f))
                                StatusChip(state.marks.values.count { it == mark }.toString(), markTone(mark))
                            }
                        }
                        Text("Changes after submission need an audited correction.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else null,
        )
    }
    correction?.let { row -> CorrectionDialog(row, { correction = null }) { mark, reason ->
        correction = null
        model.correct(row.studentId, mark, reason)
    } }
}

private fun LazyListScope.facultyHome(state: AttendanceUi, model: AttendanceViewModel, create: () -> Unit) {
    item(key = "open-lecture") {
        PrimaryButton("Open lecture", create, enabled = !state.busy && state.assignments.isNotEmpty(), icon = CampusIcons.Plus)
    }
    if (!state.busy && state.error == null && state.assignments.isEmpty()) item(key = "no-classes") {
        MessageBanner("No active classes are assigned to you.", MessageKind.Info)
    }
    item(key = "history-header") { SectionHeader("Session history") }
    if (!state.busy && state.error == null && state.sessions.isEmpty()) item(key = "empty") {
        EmptyState("No attendance sessions", "There are no sessions on this page.")
    }
    items(state.sessions, key = { "session-${it.id}" }) { session -> SessionRow(session) { model.detail(session.id) } }
}

private fun LazyListScope.studentHome(state: AttendanceUi) {
    val student = state.student ?: return
    item(key = "overall") { SummaryTiles("Overall attendance", student.overall) }
    if (student.alerts.isNotEmpty()) item(key = "alerts-header") { SectionHeader("Attendance alerts") }
    items(student.alerts, key = { "alert-${it.id}" }) { AlertRow(it, showStudent = false) }
    item(key = "subjects-header") { SectionHeader("By subject") }
    items(student.subjects, key = { "subject-${it.subjectId}" }) { SummaryRow(it.subject, it.summary, CampusIcons.Book) }
    item(key = "history-header") { SectionHeader("Lecture history") }
    if (student.history.isEmpty()) item(key = "history-empty") {
        EmptyState("No lectures yet", "No submitted attendance matches these filters.")
    }
    items(student.history, key = { "history-${it.sessionId}" }) { row ->
        ListRow(row.subject, subtitle = "${row.date} · Lecture ${row.lectureNumber} · ${row.division}",
            chip = { StatusChip(row.status.label(), markTone(row.status)) })
    }
}

private fun LazyListScope.adminHome(state: AttendanceUi, model: AttendanceViewModel) {
    item(key = "admin-actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Low attendance", { model.alerts() }, Modifier.weight(1f), enabled = !state.busy,
                icon = CampusIcons.Warning)
            SecondaryButton("Policy", model::policy, Modifier.weight(1f), enabled = !state.busy, icon = CampusIcons.Settings)
        }
    }
    val report = state.report ?: return
    item(key = "overall") { SummaryTiles("College attendance", report.overall) }
    item(key = "subjects-header") { SectionHeader("By subject") }
    items(report.subjects, key = { "subject-${it.subjectId}" }) { SummaryRow(it.subject, it.summary, CampusIcons.Book) }
    item(key = "students-header") { SectionHeader("By student") }
    items(report.students, key = { "student-${it.studentId}" }) { SummaryRow(it.student, it.summary, CampusIcons.Users) }
    item(key = "sessions-header") { SectionHeader("Reported sessions") }
    if (report.sessions.isEmpty()) item(key = "sessions-empty") {
        EmptyState("No sessions", "No submitted sessions match these filters on this page.")
    }
    items(report.sessions, key = { "session-${it.id}" }) { session -> SessionRow(session) { model.detail(session.id) } }
}

private fun LazyListScope.sessionDetail(detail: AttendanceDetail, state: AttendanceUi, mode: AttendanceMode,
                                        model: AttendanceViewModel, setAction: (String) -> Unit,
                                        edit: (AttendanceRosterRow) -> Unit) {
    val session = detail.session
    val admin = mode == AttendanceMode.ADMIN
    val draft = session.status == AttendanceSessionState.DRAFT && !admin
    item(key = "session") { SessionSummary(session) }
    if (draft) item(key = "marking") {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${state.marks.size} of ${detail.roster.size} marked", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall)
            }
            if (detail.roster.isNotEmpty()) {
                LinearProgressIndicator({ state.marks.size.toFloat() / detail.roster.size }, Modifier.fillMaxWidth())
            }
            SecondaryButton("Mark all present", model::markAllPresent, enabled = !state.busy, icon = CampusIcons.Check)
        }
    }
    if (admin) item(key = "admin-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Correction history", { model.audit(session.id) }, enabled = !state.busy, icon = CampusIcons.Clock)
            if (session.status == AttendanceSessionState.SUBMITTED) {
                SecondaryButton("Lock session", { setAction("Lock session") }, enabled = !state.busy, danger = true)
            }
        }
    }
    item(key = "roster-header") { SectionHeader("Students (${detail.roster.size})") }
    items(detail.roster, key = { "roster-${it.studentId}" }) { row ->
        val mark = state.marks[row.studentId]
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                StatusChip(mark?.label() ?: "Unmarked", mark?.let(::markTone) ?: Tone.Neutral)
            }
            if (draft) MarkOptions(mark, !state.busy) { model.mark(row.studentId, it) }
            else if (session.status == AttendanceSessionState.SUBMITTED || admin && session.status == AttendanceSessionState.LOCKED) {
                TextButton(onClick = { edit(row) }, enabled = !state.busy, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Icon(CampusIcons.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Record correction")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarkOptions(selected: AttendanceMark?, enabled: Boolean, select: (AttendanceMark) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AttendanceMark.entries.forEach { mark ->
            FilterChip(selected = mark == selected, onClick = { select(mark) }, enabled = enabled,
                label = { Text(mark.label()) },
                leadingIcon = if (mark == selected) ({ Icon(CampusIcons.Check, null, Modifier.size(16.dp)) }) else null)
        }
    }
}

/** A lecture in a list: subject and division, date and lecture number, who taught it, and its state. */
@Composable
private fun SessionRow(session: AttendanceSession, open: () -> Unit) {
    ListRow("${session.subject} · ${session.division}",
        subtitle = "${session.date} · Lecture ${session.lectureNumber}" + (sessionTime(session)?.let { " · $it" } ?: ""),
        meta = "${session.faculty} · ${session.department} · ${session.program} · ${session.semester}",
        icon = CampusIcons.Checklist,
        chip = { StatusChip(session.status.label(), sessionTone(session.status)) },
        onClick = open)
}

@Composable
private fun SessionSummary(session: AttendanceSession) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${session.subject} · ${session.division}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            StatusChip(session.status.label(), sessionTone(session.status))
        }
        InfoRow("Lecture", "${session.date} · Lecture ${session.lectureNumber}" + (sessionTime(session)?.let { " · $it" } ?: ""))
        InfoRow("Faculty", session.faculty)
        InfoRow("Class", "${session.department} · ${session.program} · ${session.semester}")
    }
}

private fun sessionTime(session: AttendanceSession): String? =
    session.startTime?.let { if (session.endTime != null) "$it–${session.endTime}" else it }

/** Overall attendance as stat tiles: the percentage, sessions attended, and each kind of mark. */
@Composable
private fun SummaryTiles(title: String, summary: AttendanceSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title, action = { PercentChip(summary) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Attendance", summary.percentage?.let { "${it.toPlainString()}%" } ?: "–", Modifier.weight(1f), large = true)
            StatTile("Attended", "${summary.attended} of ${summary.eligible}", Modifier.weight(1f), large = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Present", summary.present.toString(), Modifier.weight(1f))
            StatTile("Absent", summary.absent.toString(), Modifier.weight(1f))
            StatTile("Late", summary.late.toString(), Modifier.weight(1f))
            StatTile("Excused", summary.excused.toString(), Modifier.weight(1f))
        }
        if (summary.percentage == null) {
            Text("No eligible sessions yet.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One subject or student: sessions attended, the mark breakdown, and the percentage as a coloured chip. */
@Composable
private fun SummaryRow(title: String, summary: AttendanceSummary, icon: ImageVector) {
    ListRow(title,
        subtitle = "${summary.attended} of ${summary.eligible} attended" +
            if (summary.percentage != null && summary.severity != AttendanceSeverity.NORMAL) " · ${summary.severity.label()}" else "",
        meta = "Present ${summary.present} · Absent ${summary.absent} · Late ${summary.late} · Excused ${summary.excused}",
        icon = icon,
        chip = { PercentChip(summary) })
}

@Composable
private fun PercentChip(summary: AttendanceSummary) {
    val percentage = summary.percentage
    if (percentage == null) StatusChip("No sessions")
    else StatusChip("${percentage.toPlainString()}%", severityTone(summary.severity))
}

@Composable
private fun AlertRow(alert: AttendanceAlert, showStudent: Boolean) {
    val resolved = alert.resolvedAt != null
    ListRow(if (showStudent) alert.student else "${alert.severity.label()} attendance",
        subtitle = listOfNotNull(if (showStudent) alert.severity.label() else null, if (resolved) "Resolved" else "Active")
            .joinToString(" · "),
        icon = CampusIcons.Warning,
        chip = { StatusChip("${alert.percentage.toPlainString()}%", if (resolved) Tone.Neutral else severityTone(alert.severity)) })
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = if (large) 14.dp else 10.dp, vertical = if (large) 14.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun severityTone(severity: AttendanceSeverity) = when (severity) {
    AttendanceSeverity.NORMAL -> Tone.Positive
    AttendanceSeverity.WARNING -> Tone.Warning
    AttendanceSeverity.CRITICAL -> Tone.Danger
}

private fun markTone(mark: AttendanceMark) = when (mark) {
    AttendanceMark.PRESENT -> Tone.Positive
    AttendanceMark.ABSENT -> Tone.Danger
    AttendanceMark.LATE -> Tone.Warning
    AttendanceMark.EXCUSED -> Tone.Neutral
}

private fun sessionTone(status: AttendanceSessionState) = when (status) {
    AttendanceSessionState.DRAFT -> Tone.Warning
    AttendanceSessionState.SUBMITTED -> Tone.Positive
    AttendanceSessionState.LOCKED -> Tone.Neutral
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
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice("Assigned class", selected?.let { "${it.subject} · ${it.division}" },
                assignments.map { it to "${it.subject} · ${it.division} · ${it.semester} · ${it.program}" },
                onChoose = { selected = it })
            AttendanceField(date, { date = it }, "Lecture date (YYYY-MM-DD)")
            AttendanceField(lecture, { lecture = it }, "Lecture number (1–99)")
            AttendanceField(start, { start = it }, "Start time (optional, HH:MM)")
            AttendanceField(end, { end = it }, "End time (optional, HH:MM)")
            if (selected != null && input == null) {
                MessageBanner("Check the date, lecture number and time range.", MessageKind.Error)
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
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AttendanceField(from, { draft = draft + ("from" to it.trim()) }, "From (YYYY-MM-DD)")
            AttendanceField(to, { draft = draft + ("to" to it.trim()) }, "To (YYYY-MM-DD)")
            if (!validDates) MessageBanner("Enter a valid date range.", MessageKind.Error)
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
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoRow("Student", row.name)
            InfoRow("Previous status", row.status?.label() ?: "Unmarked")
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
        SectionHeader("Thresholds")
        InfoCard {
            AttendanceField(warning, { warning = it }, "Warning threshold (%)", !busy)
            AttendanceField(critical, { critical = it }, "Critical threshold (%)", !busy)
            AttendanceField(hours, { hours = it }, "Faculty correction window (hours)", !busy)
        }
        SectionHeader("Counting")
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Count late as attended", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Checkbox(checked = late, onCheckedChange = { late = it }, enabled = !busy)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Exclude excused from eligible sessions", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Checkbox(checked = excused, onCheckedChange = { excused = it }, enabled = !busy)
            }
        }
        if (w == null || c == null || h == null) {
            MessageBanner("Set a lower critical threshold and a correction window from 0 to 720 hours.", MessageKind.Error)
        }
        PrimaryButton("Save policy", {
            if (w != null && c != null && h != null) save(AttendancePolicy(w, c, late, excused, h))
        }, enabled = !busy && w != null && c != null && h != null)
    }
}

@Composable
private fun AttendanceField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), enabled = enabled, shape = MaterialTheme.shapes.small)
}

@Composable
private fun <T> Choice(title: String, selected: String?, items: List<Pair<T, String>>, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            shape = MaterialTheme.shapes.small) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected ?: "Select", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(CampusIcons.ChevronDown, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onChoose(value) })
            }
        }
    }
}
