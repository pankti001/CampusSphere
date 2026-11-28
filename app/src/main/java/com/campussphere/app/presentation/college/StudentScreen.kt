package com.campussphere.app.presentation.college

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.StudentAffiliation
import com.campussphere.app.data.remote.api.StudentEnrollment
import com.campussphere.app.data.repository.StudentRepository
import com.campussphere.app.ui.brand.*

@Composable
fun StudentScreen(repository: StudentRepository, authorization: () -> String, onAccessLost: (Int) -> Unit,
                  onExit: () -> Unit, mode: StudentMode = StudentMode.ADMIN, onAttendance: (() -> Unit)? = null, onMaterials: (() -> Unit)? = null,
                  onAnnouncements: (() -> Unit)? = null, onTimetable: (() -> Unit)? = null,
                  homeworkCard: (@Composable () -> Unit)? = null) {
    val owner = remember(mode) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val model = viewModel<StudentViewModel>(viewModelStoreOwner = owner) {
        StudentViewModel(repository, authorization, onAccessLost, mode)
    }
    val state by model.state.collectAsStateWithLifecycle()
    var query by remember(state.page) { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var endConfirmation by remember { mutableStateOf<StudentEnrollment?>(null) }
    var review by remember { mutableStateOf<StudentAffiliation?>(null) }
    val back = {
        if (mode != StudentMode.ADMIN || state.page == StudentPage.DIRECTORY) onExit() else model.back()
    }
    BackHandler(enabled = mode != StudentMode.STUDENT) { if (!state.busy) back() }

    val page = state.page
    val student = state.student
    val selection = state.selection
    // The student's own dashboard is their home page: it offers sign out instead of a back arrow.
    val signOut = mode == StudentMode.STUDENT

    CampusPage(
        title = studentPageTitle(page),
        subtitle = studentPageSubtitle(page, student?.name),
        onBack = if (signOut) null else back,
        backEnabled = !state.busy,
        busy = state.busy,
        stateKey = when {
            // Lists are empty while they reload; a separate key keeps each list's own position for when it returns.
            state.busy && page != StudentPage.PROFILE && page != StudentPage.REVIEW -> "loading-${page.name}"
            page == StudentPage.DIRECTORY -> "directory"
            page == StudentPage.PROFILE -> "student-${student?.id}"
            page == StudentPage.REVIEW -> "enroll-review-${student?.id}"
            page == StudentPage.DEPARTMENT || enrollmentStep(page) != null -> with(selection) {
                "enroll-${page.name}-${student?.id}-${department?.id}-${program?.id}-${year?.id}-${semester?.id}"
            }
            else -> page.name.lowercase()
        },
        error = state.error,
        onRetry = if (state.canRetry) model::retry else model::refresh,
        notice = state.success,
        actions = {
            if (signOut) IconButton(onExit) { Icon(CampusIcons.Logout, "Sign out") }
        },
    ) {
        if (signOut) {
            if (homeworkCard != null) item(key = "homework") { homeworkCard() }
            onTimetable?.let { open ->
                item(key = "timetable") { ListRow("My timetable", icon = CampusIcons.Calendar, onClick = open) }
            }
            onAnnouncements?.let { open ->
                item(key = "announcements") { ListRow("Announcements", icon = CampusIcons.Megaphone, onClick = open) }
            }
            onMaterials?.let { open ->
                item(key = "materials") { ListRow("Study materials", icon = CampusIcons.Book, onClick = open) }
            }
            onAttendance?.let { open ->
                item(key = "attendance") {
                    ListRow("My attendance", icon = CampusIcons.Checklist,
                        onClick = if (!state.busy && state.error == null) open else null)
                }
            }
        }

        when (page) {
            StudentPage.DIRECTORY -> if (!state.busy) {
                item(key = "requests") {
                    ListRow("Join requests",
                        subtitle = "Students register in the app, then ask to join with your college code.",
                        icon = CampusIcons.Handshake, onClick = model::queue)
                }
                if (state.error == null) {
                    item(key = "students-header") { SectionHeader("Students", action = { RefreshAction(model::refresh) }) }
                    item(key = "search") { SearchField(query, { query = it }, "Search students") }
                    val visible = state.students.filter { member ->
                        listOfNotNull(member.name, member.email, member.department)
                            .any { it.contains(query.trim(), true) }
                    }
                    if (visible.isEmpty()) item(key = "students-empty") {
                        if (query.isBlank()) EmptyState("No students yet", "No verified students belong to this college.")
                        else EmptyState("No matches", "No students match your search.")
                    }
                    items(visible, key = { "student-${it.id}" }) { member ->
                        ListRow(member.name, subtitle = member.department ?: "No department yet", meta = member.email,
                            icon = CampusIcons.Users, onClick = { model.profile(member.id) })
                    }
                }
            }
            StudentPage.PROFILE -> student?.let { member ->
                item(key = "profile") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        InfoCard {
                            InfoRow("Name", member.name)
                            InfoRow("Email", member.email)
                            InfoRow("Department", member.department ?: "Not set")
                        }
                        PrimaryButton("Enroll student", model::startEnrollment, enabled = !state.busy, icon = CampusIcons.Plus)
                        SecondaryButton("Set department", model::selectDepartment, enabled = !state.busy,
                            icon = CampusIcons.Building)
                    }
                }
                item(key = "history-header") { SectionHeader("Enrollment history") }
                if (state.enrollments.isEmpty()) item(key = "history-empty") {
                    EmptyState("No enrollments yet", "Set the student's department, then enroll them.")
                }
                items(state.enrollments, key = { "enrollment-${it.id}" }) { enrollment ->
                    EnrollmentCard(enrollment, showStatus = true,
                        onEnd = if (state.busy || !enrollment.current) null else { { endConfirmation = enrollment } })
                }
            }
            StudentPage.DASHBOARD -> if (!state.busy && state.error == null) {
                item(key = "enrollment-header") {
                    SectionHeader("Current enrollment", action = { RefreshAction(model::refresh) })
                }
                if (state.enrollments.isEmpty()) item(key = "enrollment-empty") {
                    EmptyState("No current enrollment", "Your college hasn't placed you in a class yet.")
                }
                items(state.enrollments, key = { "enrollment-${it.id}" }) { enrollment -> EnrollmentCard(enrollment) }
            }
            StudentPage.REQUEST -> if (!state.busy) {
                val request = state.requests.singleOrNull()
                request?.let { current ->
                    item(key = "request-${current.id}") { AffiliationStatus(current, model::refresh) }
                }
                if (request?.status != "PENDING" && !state.canRetry) item(key = "request-form") {
                    InfoCard {
                        Text("College code", style = MaterialTheme.typography.titleMedium)
                        Text("Enter the code your college gave you.", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("College code") }, shape = MaterialTheme.shapes.small)
                        PrimaryButton("Send request", { model.submit(code) })
                    }
                }
            }
            StudentPage.QUEUE -> if (!state.busy && state.error == null) {
                item(key = "queue-header") { SectionHeader("Waiting for review", action = { RefreshAction(model::refresh) }) }
                if (state.requests.isEmpty()) item(key = "queue-empty") {
                    EmptyState("All caught up", "No students are waiting to join.")
                }
                items(state.requests, key = { "request-${it.id}" }) { request ->
                    ListRow(request.name, subtitle = request.email, icon = CampusIcons.Users, onClick = { review = request })
                }
            }
            StudentPage.REVIEW -> student?.let { member ->
                item(key = "review-header") { SectionHeader(studentStepHeading(page)) }
                item(key = "review") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        InfoCard {
                            InfoRow("Student", member.name)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            InfoPair("Department", selection.department?.name, "Program", selection.program?.name)
                            InfoPair("Academic year", selection.year?.name, "Semester", selection.semester?.name)
                            InfoRow("Division", selection.division?.name.shown())
                        }
                        PrimaryButton("Save enrollment", model::save, enabled = !state.busy, icon = CampusIcons.Check)
                    }
                }
            }
            else -> if (!state.busy && student != null) {
                val chosen = with(selection) {
                    listOfNotNull(department?.let { "Department" to it.name }, program?.let { "Program" to it.name },
                        year?.let { "Academic year" to it.name }, semester?.let { "Semester" to it.name })
                }
                if (chosen.isNotEmpty()) item(key = "chosen") {
                    InfoCard { chosen.forEach { (label, value) -> InfoRow(label, value) } }
                }
                item(key = "step-header") { SectionHeader(studentStepHeading(page)) }
                if (state.options.isEmpty() && state.error == null) item(key = "options-empty") {
                    EmptyState("Nothing to choose", "Go back to the profile and check this student's academic setup.")
                }
                items(state.options, key = { "option-${it.id}" }) { option ->
                    ListRow(option.name, subtitle = option.code.takeIf { it.isNotBlank() }, icon = studentStepIcon(page),
                        onClick = { model.choose(option) })
                }
            }
        }
    }

    endConfirmation?.let { enrollment ->
        ConfirmDialog("End current enrollment?",
            "It leaves the student's dashboard and stays in their enrollment history.", "End enrollment",
            onConfirm = { endConfirmation = null; model.end(enrollment) }, onDismiss = { endConfirmation = null },
            danger = true, extra = { InfoRow("Program", enrollment.program) })
    }
    review?.let { request ->
        AffiliationReview(request, onClose = { review = null }) { approved, confirmed ->
            review = null
            model.decide(request, approved, confirmed)
        }
    }
}

private fun String?.shown(): String = this?.takeIf { it.isNotBlank() } ?: "Not set"

@Composable
private fun RefreshAction(onClick: () -> Unit) {
    IconButton(onClick) { Icon(CampusIcons.Refresh, "Refresh", Modifier.size(20.dp)) }
}

/** Two label/value pairs side by side. */
@Composable
private fun InfoPair(firstLabel: String, first: String?, secondLabel: String, second: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.weight(1f)) { InfoRow(firstLabel, first.shown()) }
        Box(Modifier.weight(1f)) { InfoRow(secondLabel, second.shown()) }
    }
}

@Composable
private fun AffiliationStatus(request: StudentAffiliation, onRefresh: () -> Unit) {
    val pending = request.status == "PENDING"
    InfoCard {
        StatusChip(if (pending) "Waiting for your college" else "Previous request rejected",
            if (pending) Tone.Warning else Tone.Danger)
        Text(request.college, style = MaterialTheme.typography.titleMedium)
        if (pending) {
            Text("Your college confirms your admission before you get access.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton("Check status", onRefresh, icon = CampusIcons.Refresh)
        }
    }
}

/** Approve needs the admission check ticked; reject does not, as before. */
@Composable
private fun AffiliationReview(request: StudentAffiliation, onClose: () -> Unit, decide: (Boolean, Boolean) -> Unit) {
    var confirmed by remember(request.id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Review join request") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow("Student", request.name)
                InfoRow("Email", request.email)
                val shape = MaterialTheme.shapes.small
                Surface(shape = shape, color = if (confirmed) scheme.tertiaryContainer else scheme.surfaceContainerLow,
                    contentColor = if (confirmed) scheme.onTertiaryContainer else scheme.onSurface,
                    border = BorderStroke(1.dp, scheme.outlineVariant)) {
                    Row(Modifier.fillMaxWidth().clip(shape)
                        .toggleable(confirmed, role = Role.Checkbox, onValueChange = { confirmed = it })
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Checkbox(confirmed, onCheckedChange = null)
                        Text("I checked this student's admission in our college records.", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ decide(true, confirmed) }, enabled = confirmed) { Text("Approve") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClose) { Text("Cancel") }
                TextButton({ decide(false, false) },
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.error)) { Text("Reject") }
            }
        }
    )
}

@Composable
private fun EnrollmentCard(enrollment: StudentEnrollment, showStatus: Boolean = false, onEnd: (() -> Unit)? = null) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(enrollment.program, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (showStatus) {
                StatusChip(if (enrollment.current) "Current" else "Past",
                    if (enrollment.current) Tone.Positive else Tone.Neutral)
            }
        }
        InfoPair("College", enrollment.college, "Department", enrollment.department)
        InfoPair("Academic year", enrollment.academicYear, "Semester", enrollment.semester)
        InfoRow("Division", enrollment.division.shown())
        onEnd?.let { SecondaryButton("End enrollment", it, danger = true) }
    }
}
