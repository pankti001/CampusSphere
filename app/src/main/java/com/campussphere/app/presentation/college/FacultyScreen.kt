package com.campussphere.app.presentation.college

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.campussphere.app.ui.brand.appear
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.FacultyAssignment
import com.campussphere.app.data.remote.api.FacultyAccessRequest
import com.campussphere.app.data.repository.FacultyRepository

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

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.page == FacultyPage.DASHBOARD) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.campussphere.app.ui.brand.BrandHero(Modifier.appear(0)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        com.campussphere.app.ui.brand.CampusBrandRow(Modifier.weight(1f), logoSize = 34.dp, fontSize = 16.sp, onDark = true)
                        TextButton(onClick = back) { Text("Sign out", color = Color.White) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Faculty dashboard", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text("Teach, mark and share with your classes.", style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f))
                }
                val tiles = buildList {
                    onNotifications?.let { add(Triple("🔔", "Notifications" to if (unreadNotifications > 0) "$unreadNotifications new about your lessons" else "Lesson changes and updates", it)) }
                    onTimetable?.let { add(Triple("🗓", "My timetable" to "Your lessons this week", it)) }
                    onAttendance?.let { add(Triple("✅", "Attendance" to "Take and review class attendance", it)) }
                    onMaterials?.let { add(Triple("📚", "Study materials" to "Share notes and files", it)) }
                    onResources?.let { add(Triple("📖", "Syllabus & papers" to "Upload syllabus and past question papers", it)) }
                    onAnnouncements?.let { add(Triple("📣", "Announcements" to "Notices for your classes", it)) }
                }
                val tints = listOf(Color(0xFFFEF3C7), Color(0xFFDBE6FF), Color(0xFFDCFCE7), Color(0xFFCCFBF1), Color(0xFFFCE7F3))
                tiles.chunked(2).forEachIndexed { row, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEachIndexed { i, (symbol, text, onClick) ->
                            val index = row * 2 + i
                            com.campussphere.app.ui.brand.ActionTile(symbol, text.first, text.second, Modifier.weight(1f),
                                order = index + 1, tint = tints[index % tints.size],
                                enabled = !(symbol == "✅" && (state.busy || state.error != null)), onClick = onClick)
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                homeworkCard?.let { card -> Box(Modifier.appear(6)) { card() } }
            }
        } else item {
            TextButton(onClick = back, enabled = !state.busy || dashboard) {
                Text(if (dashboard) "Sign out" else "Back")
            }
            Text(state.page.title, style = MaterialTheme.typography.headlineMedium)
            if (state.page == FacultyPage.DIRECTORY) {
                Button(onClick = model::pendingRequests, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Approve faculty requests")
                }
                Text("New faculty register in the app with your college code; their requests appear here for approval.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.busy) item {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Loading faculty information")
        }
        state.error?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error)
                if (state.canRetry) OutlinedButton(onClick = model::retry) { Text("Retry") }
                else OutlinedButton(onClick = model::refresh) { Text("Refresh") }
            }
        }
        state.success?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
        when (state.page) {
            FacultyPage.DIRECTORY -> if (!state.busy && state.error == null) {
                item {
                    OutlinedTextField(query, { query = it }, label = { Text("Search faculty") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = model::refresh) { Text("Refresh") }
                }
                val visible = state.members.filter { member ->
                    listOfNotNull(member.name, member.email, member.department).any { it.contains(query.trim(), true) }
                }
                if (visible.isEmpty()) item {
                    Text(if (query.isBlank()) "No active, verified faculty belong to this college." else "No faculty match your search.")
                }
                items(visible, key = { it.id }) { member ->
                    FacultyCard(member.name, member.department ?: "Department unassigned", onClick = { model.profile(member.id) })
                }
            }
            FacultyPage.REQUESTS -> if (!state.busy && state.error == null) {
                item { OutlinedButton(onClick = model::refresh) { Text("Refresh") } }
                if (state.requests.isEmpty()) item { Text("No pending Faculty access requests.") }
                items(state.requests, key = { it.id }) { request ->
                    FacultyCard(request.name, request.email) { reviewing = request }
                }
            }
            FacultyPage.PROFILE -> state.member?.let { member ->
                item {
                    FacultyCard(member.name, member.email)
                    Text(member.department ?: "Department unassigned")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = model::chooseDepartment, enabled = !state.busy) { Text("Set department") }
                        Button(onClick = model::startAssignment, enabled = !state.busy) { Text("Add assignment") }
                    }
                    Text("Active assignments", style = MaterialTheme.typography.titleLarge)
                }
                if (state.assignments.isEmpty()) item { Text("No active assignments.") }
                items(state.assignments, key = { it.id }) { assignment ->
                    AssignmentCard(assignment, if (state.busy) null else { { endConfirmation = assignment } })
                }
            }
            FacultyPage.DASHBOARD -> if (!state.busy && state.error == null) {
                item {
                    Text("My subjects and classes", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = model::refresh) { Text("Refresh") }
                }
                if (state.assignments.isEmpty()) item { Text("No classes have been assigned to you.") }
                items(state.assignments, key = { it.id }) { AssignmentCard(it) }
            }
            FacultyPage.REVIEW -> {
                item {
                    Text(state.member?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    with(state.selection) {
                        Text(listOfNotNull(department, program, year, semester, division, subject)
                            .joinToString(" · ") { it.name })
                    }
                    Button(onClick = model::save, enabled = !state.busy && state.member != null) { Text("Save assignment") }
                }
            }
            else -> if (!state.busy && state.member != null) {
                if (state.options.isEmpty() && state.error == null) item { Text("No options are available.") }
                items(state.options, key = { it.id }) { option ->
                    FacultyCard(option.name, option.code) { model.select(option) }
                }
            }
        }
    }

    endConfirmation?.let { assignment ->
        AlertDialog(onDismissRequest = { endConfirmation = null },
            title = { Text("End assignment?") },
            text = { Text(assignment.subject + " · " + assignment.division) },
            confirmButton = {
                TextButton(onClick = { endConfirmation = null; model.end(assignment) }) { Text("End assignment") }
            },
            dismissButton = { TextButton(onClick = { endConfirmation = null }) { Text("Cancel") } })
    }

    reviewing?.let { request ->
        var confirmed by remember(request.id) { mutableStateOf(false) }
        var rejecting by remember(request.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { reviewing = null },
            title = { Text(if (rejecting) "Reject access request?" else "Review Faculty access") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.name)
                    Text(request.email)
                    if (!rejecting) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                                .toggleable(value = confirmed, role = Role.Checkbox,
                                    onValueChange = { confirmed = it })) {
                            Checkbox(checked = confirmed, onCheckedChange = null)
                            Text("I verified the applicant's institutional identity and employment.")
                        }
                        TextButton(onClick = { rejecting = true }) { Text("Reject request") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = (confirmed || rejecting) && !state.busy, onClick = {
                    reviewing = null
                    model.decide(request, approved = !rejecting, employmentConfirmed = confirmed && !rejecting)
                }) { Text(if (rejecting) "Reject" else "Approve") }
            },
            dismissButton = { TextButton(onClick = { reviewing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FacultyCard(title: String, detail: String, onClick: (() -> Unit)? = null) {
    val content: @Composable ColumnScope.() -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail)
        }
    }
    if (onClick == null) Card(Modifier.fillMaxWidth(), content = content)
    else Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun AssignmentCard(assignment: FacultyAssignment, onEnd: (() -> Unit)? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(assignment.subject, style = MaterialTheme.typography.titleMedium)
            Text(assignment.subjectCode + " · " + assignment.division)
            Text(assignment.program + " · " + assignment.semester)
            Text(assignment.department + " · " + assignment.academicYear)
            onEnd?.let { TextButton(onClick = it) { Text("End " + assignment.subject) } }
        }
    }
}
