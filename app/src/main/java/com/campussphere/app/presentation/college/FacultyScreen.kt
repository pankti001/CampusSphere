package com.campussphere.app.presentation.college

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.FacultyAssignment
import com.campussphere.app.data.remote.api.FacultyAccessRequest
import com.campussphere.app.data.repository.FacultyRepository
import com.campussphere.app.ui.brand.*

/** The steps of adding a teaching assignment, in order (the department comes from the faculty member). */
private val ASSIGNMENT_STEPS = listOf(FacultyPage.PROGRAM, FacultyPage.YEAR, FacultyPage.SEMESTER,
    FacultyPage.DIVISION, FacultyPage.SUBJECT, FacultyPage.REVIEW)

@Composable
fun FacultyScreen(repository: FacultyRepository, authorization: () -> String, onAccessLost: (Int) -> Unit,
                  onExit: () -> Unit, dashboard: Boolean = false, onAttendance: (() -> Unit)? = null, onMaterials: (() -> Unit)? = null,
                  onAnnouncements: (() -> Unit)? = null, onTimetable: (() -> Unit)? = null,
                  onNotifications: (() -> Unit)? = null, unreadNotifications: Int = 0,
                  homeworkCard: (@Composable () -> Unit)? = null, onResources: (() -> Unit)? = null) {
    val owner = remember {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val model = viewModel<FacultyViewModel>(viewModelStoreOwner = owner) {
        FacultyViewModel(repository, authorization, onAccessLost, dashboard)
    }
    val state by model.state.collectAsStateWithLifecycle()
    var query by remember(state.page) { mutableStateOf("") }
    var endConfirmation by remember { mutableStateOf<FacultyAssignment?>(null) }
    var reviewing by remember { mutableStateOf<FacultyAccessRequest?>(null) }
    val back = {
        if (state.page == FacultyPage.DIRECTORY || dashboard) onExit() else model.cancel()
    }
    BackHandler(enabled = !dashboard) { if (!state.busy) back() }

    val page = state.page
    val member = state.member
    val step = ASSIGNMENT_STEPS.indexOf(page)
    // Lists are hidden while loading or after an error, as before.
    val listHidden = state.busy || state.error != null
    // A failed load offers Retry; a failed change (or a validation message) offers Refresh.
    val retryError = state.error?.takeIf { state.canRetry }
    val refreshError = state.error?.takeUnless { state.canRetry }

    CampusPage(
        title = when (page) {
            FacultyPage.DIRECTORY -> "Faculty"
            FacultyPage.REQUESTS -> "Access requests"
            FacultyPage.PROFILE -> member?.name ?: page.title
            else -> page.title
        },
        subtitle = when {
            page == FacultyPage.DIRECTORY -> "Faculty management"
            page == FacultyPage.REQUESTS -> "Faculty waiting for approval"
            page == FacultyPage.PROFILE -> "Faculty profile"
            page == FacultyPage.DASHBOARD -> "Teach, mark and share with your classes"
            step >= 0 -> "Step ${step + 1} of ${ASSIGNMENT_STEPS.size}" + (member?.name?.let { " · $it" } ?: "")
            else -> member?.name
        },
        onBack = if (dashboard) null else back,
        backEnabled = !state.busy,
        busy = state.busy,
        // Lists use a separate key while loading (they are empty then), so coming back restores the old position.
        // The live search text stays out of the key so typing doesn't rebuild the list (and drop the keyboard).
        stateKey = when (page) {
            FacultyPage.DIRECTORY -> if (state.busy) "directory-loading" else "directory"
            FacultyPage.REQUESTS -> if (state.busy) "requests-loading" else "requests"
            FacultyPage.PROFILE -> "profile-${member?.id}"
            FacultyPage.DASHBOARD -> "dashboard"
            else -> "pick-${page.name}"
        },
        error = retryError,
        onRetry = model::retry,
        notice = state.success,
        actions = {
            if (page == FacultyPage.DIRECTORY || page == FacultyPage.REQUESTS || page == FacultyPage.DASHBOARD) {
                IconButton(model::refresh, enabled = !listHidden) { Icon(CampusIcons.Refresh, "Refresh") }
            }
            if (dashboard) IconButton(back) { Icon(CampusIcons.Logout, "Sign out") }
        },
    ) {
        refreshError?.let { message ->
            item(key = "refresh-error") {
                MessageBanner(message, MessageKind.Error, actionLabel = "Refresh", onAction = model::refresh,
                    enabled = !state.busy)
            }
        }
        if (state.busy) item(key = "loading") {
            Text("Loading faculty information", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (page) {
            FacultyPage.DASHBOARD -> {
                item(key = "shortcuts") {
                    val shortcuts = buildList {
                        onNotifications?.let { add(Shortcut("Notifications", CampusIcons.Bell, unreadNotifications.toLong(), it)) }
                        onTimetable?.let { add(Shortcut("My timetable", CampusIcons.Calendar, 0, it)) }
                        // Attendance stays unavailable while the assignments load or after an error, as before.
                        onAttendance?.let { open ->
                            add(Shortcut("Attendance", CampusIcons.Checklist, 0) { if (!listHidden) open() })
                        }
                        onMaterials?.let { add(Shortcut("Study materials", CampusIcons.Books, 0, it)) }
                        onResources?.let { add(Shortcut("Syllabus & papers", CampusIcons.Book, 0, it)) }
                        onAnnouncements?.let { add(Shortcut("Announcements", CampusIcons.Megaphone, 0, it)) }
                    }
                    ShortcutGrid(shortcuts)
                }
                homeworkCard?.let { card -> item(key = "homework") { card() } }
                if (!listHidden) {
                    item(key = "classes-header") { SectionHeader("My subjects and classes") }
                    if (state.assignments.isEmpty()) item(key = "no-classes") {
                        EmptyState("No classes yet", "No classes have been assigned to you.")
                    }
                    items(state.assignments, key = { "assignment-${it.id}" }) { AssignmentRow(it) }
                }
            }
            FacultyPage.DIRECTORY -> {
                item(key = "requests") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Review access requests", model::pendingRequests, enabled = !state.busy,
                            icon = CampusIcons.Users)
                        MessageBanner("New faculty register in the app with your college code. Their requests appear here for approval.",
                            MessageKind.Info)
                    }
                }
                if (!listHidden) {
                    item(key = "search") {
                        SearchField(query, { query = it }, "Search faculty",
                            Modifier.semantics { contentDescription = "Search faculty" })
                    }
                    val visible = state.members.filter { m ->
                        listOfNotNull(m.name, m.email, m.department).any { it.contains(query.trim(), true) }
                    }
                    if (visible.isEmpty()) item(key = "empty") {
                        if (query.isBlank()) EmptyState("No faculty yet", "No active, verified faculty belong to this college.")
                        else EmptyState("No matches", "No faculty match your search.")
                    }
                    items(visible, key = { "member-${it.id}" }) { m ->
                        ListRow(m.name, subtitle = m.department ?: "Department unassigned", meta = m.email,
                            icon = CampusIcons.Users, onClick = { model.profile(m.id) })
                    }
                }
            }
            FacultyPage.REQUESTS -> if (!listHidden) {
                if (state.requests.isEmpty()) item(key = "empty") {
                    EmptyState("No requests", "No pending Faculty access requests.")
                }
                items(state.requests, key = { "request-${it.id}" }) { request ->
                    ListRow(request.name, subtitle = request.email, icon = CampusIcons.Users,
                        chip = { StatusChip("Pending", Tone.Warning) }, onClick = { reviewing = request })
                }
            }
            FacultyPage.PROFILE -> member?.let { m ->
                item(key = "profile") {
                    InfoCard {
                        InfoRow("Email", m.email)
                        InfoRow("Department", m.department ?: "Department unassigned")
                    }
                }
                item(key = "profile-actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Add assignment", model::startAssignment, enabled = !state.busy, icon = CampusIcons.Plus)
                        SecondaryButton("Set department", model::chooseDepartment, enabled = !state.busy,
                            icon = CampusIcons.Building)
                    }
                }
                item(key = "assignments-header") { SectionHeader("Active assignments") }
                if (state.assignments.isEmpty()) item(key = "no-assignments") {
                    EmptyState("Nothing assigned", "No active assignments.")
                }
                items(state.assignments, key = { "assignment-${it.id}" }) { assignment ->
                    AssignmentRow(assignment, if (state.busy) null else { { endConfirmation = assignment } })
                }
            }
            FacultyPage.REVIEW -> item(key = "review") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SelectionCard(member?.name.orEmpty(), state.selection)
                    PrimaryButton("Save assignment", model::save, enabled = !state.busy && member != null,
                        icon = CampusIcons.Check)
                }
            }
            else -> {
                // Department, program, year, semester, division and subject pickers.
                if (step >= 0) item(key = "so-far") { SelectionCard(member?.name.orEmpty(), state.selection) }
                if (!state.busy && member != null) {
                    if (state.options.isEmpty() && state.error == null) item(key = "empty") {
                        EmptyState("Nothing to choose", "No options are available.")
                    }
                    items(state.options, key = { "option-${it.id}" }) { option ->
                        ListRow(option.name, subtitle = option.code.ifBlank { null }, icon = stepIcon(page),
                            onClick = { model.select(option) })
                    }
                }
            }
        }
    }

    endConfirmation?.let { assignment ->
        ConfirmDialog("End assignment?", assignment.subject + " · " + assignment.division, "End assignment",
            onConfirm = { endConfirmation = null; model.end(assignment) },
            onDismiss = { endConfirmation = null }, danger = true)
    }

    reviewing?.let { request ->
        var confirmed by remember(request.id) { mutableStateOf(false) }
        var rejecting by remember(request.id) { mutableStateOf(false) }
        ConfirmDialog(
            title = if (rejecting) "Reject access request?" else "Review Faculty access",
            body = request.name + "\n" + request.email,
            confirm = if (rejecting) "Reject" else "Approve",
            onConfirm = {
                reviewing = null
                model.decide(request, approved = !rejecting, employmentConfirmed = confirmed && !rejecting)
            },
            onDismiss = { reviewing = null },
            danger = rejecting,
            confirmEnabled = (confirmed || rejecting) && !state.busy,
            extra = if (rejecting) null else ({
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                            .toggleable(value = confirmed, role = Role.Checkbox, onValueChange = { confirmed = it })) {
                        Checkbox(checked = confirmed, onCheckedChange = null)
                        Text("I verified the applicant's institutional identity and employment.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { rejecting = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Reject request")
                    }
                }
            }),
        )
    }
}

private fun stepIcon(page: FacultyPage) = when (page) {
    FacultyPage.DEPARTMENT -> CampusIcons.Building
    FacultyPage.YEAR, FacultyPage.SEMESTER -> CampusIcons.Calendar
    FacultyPage.DIVISION -> CampusIcons.Users
    FacultyPage.SUBJECT -> CampusIcons.Book
    else -> CampusIcons.Books
}

/** What has been chosen so far for a new assignment. */
@Composable
private fun SelectionCard(name: String, selection: AssignmentSelection) {
    InfoCard {
        if (name.isNotBlank()) InfoRow("Faculty", name)
        listOf("Department" to selection.department, "Program" to selection.program, "Academic year" to selection.year,
            "Semester" to selection.semester, "Division" to selection.division, "Subject" to selection.subject)
            .forEach { (label, item) -> item?.name?.takeIf { it.isNotBlank() }?.let { InfoRow(label, it) } }
    }
}

@Composable
private fun AssignmentRow(assignment: FacultyAssignment, onEnd: (() -> Unit)? = null) {
    val endButton: (@Composable () -> Unit)? = if (onEnd == null) null else ({
        TextButton(onEnd, Modifier.semantics { contentDescription = "End " + assignment.subject },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("End") }
    })
    ListRow(assignment.subject,
        subtitle = assignment.subjectCode + " · " + assignment.division + " · " + assignment.program + " · " + assignment.semester,
        meta = assignment.department + " · " + assignment.academicYear,
        icon = CampusIcons.Book, chip = endButton)
}
