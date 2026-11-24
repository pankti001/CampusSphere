package com.campussphere.app.presentation.college

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.FacultyAssignment
import com.campussphere.app.data.repository.FacultyRepository

@Composable
fun FacultyScreen(repository: FacultyRepository, authorization: () -> String, onAccessLost: (Int) -> Unit,
                  onExit: () -> Unit, dashboard: Boolean = false) {
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
    val back = {
        if (state.page == FacultyPage.DIRECTORY || dashboard) onExit() else model.cancel()
    }
    BackHandler(enabled = !dashboard) { if (!state.busy) back() }

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = back, enabled = !state.busy || dashboard) {
                Text(if (dashboard) "Sign out" else "Back")
            }
            Text(state.page.title, style = MaterialTheme.typography.headlineMedium)
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
