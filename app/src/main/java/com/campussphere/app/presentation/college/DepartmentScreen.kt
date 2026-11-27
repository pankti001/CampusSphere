package com.campussphere.app.presentation.college

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.dto.AcademicCatalogItem

@Composable
fun DepartmentScreen(state: DepartmentState, model: DepartmentViewModel,
                     onPrograms: ((AcademicCatalogItem) -> Unit)? = null, onBack: () -> Unit) {
    AcademicCatalogScreen(state, model, "department", "Departments",
        "No departments have been added to your college yet.",
        actionLabel = "Manage programs", itemAction = onPrograms, canDeactivate = true, onBack = onBack)
}

@Composable
fun ProgramScreen(state: DepartmentState, model: AcademicCatalogViewModel, department: AcademicCatalogItem,
                  onSemesters: ((AcademicCatalogItem) -> Unit)? = null, onBack: () -> Unit) {
    AcademicCatalogScreen(state, model, "program", "Programs",
        "No programs have been added to this department yet.", department.name,
        actionLabel = "Manage semesters", itemAction = onSemesters, onBack = onBack)
}

@Composable
internal fun AcademicCatalogScreen(state: DepartmentState, model: AcademicCatalogViewModel, resource: String,
    titleText: String, emptyText: String, parentName: String? = null,
    codeLabel: String = "${resource.replaceFirstChar { it.uppercaseChar() }} code",
    actionLabel: String = "", itemAction: ((AcademicCatalogItem) -> Unit)? = null,
    selectItem: ((AcademicCatalogItem) -> Unit)? = null, codeHint: String? = null, withDates: Boolean = false,
    canDeactivate: Boolean = false, onBack: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var confirmActive by remember { mutableStateOf<Boolean?>(null) }
    val form = state.form
    confirmActive?.let { activate ->
        val item = state.list.orEmpty().firstOrNull { it.id == form?.id }
        AlertDialog(onDismissRequest = { confirmActive = null },
            title = { Text(if (activate) "Reactivate this $resource?" else "Deactivate this $resource?") },
            text = { Text(if (activate) "It can receive new programs, faculty and students again."
                          else "Its programs, people and history stay. It cannot receive new programs, faculty or students until you reactivate it.") },
            confirmButton = { TextButton(onClick = { confirmActive = null; item?.let { model.setActive(it, activate) } }) {
                Text(if (activate) "Reactivate" else "Deactivate") } },
            dismissButton = { TextButton(onClick = { confirmActive = null }) { Text("Cancel") } })
    }
    fun back() { if (form != null) model.cancelEdit() else onBack() }
    BackHandler { if (!state.busy) back() }

    Surface {
        LazyColumn(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "heading") {
                TextButton(onClick = ::back, enabled = !state.busy) { Text("Back") }
                val title = when {
                    form == null -> titleText
                    form.id == null -> "Add $resource"
                    else -> "Edit $resource"
                }
                Text(title, style = MaterialTheme.typography.headlineMedium)
                parentName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            }
            if (state.busy) item(key = "loading") {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading $titleText" })
            }
            if (form == null) state.error?.let { item(key = "error") { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.notice?.let { item(key = "notice") { Text(it, color = MaterialTheme.colorScheme.primary) } }
            if (form != null) {
                item(key = "name-field") {
                    OutlinedTextField(form.name, model::changeName,
                        label = { Text("${resource.replaceFirstChar { it.uppercaseChar() }} name") }, singleLine = true, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth())
                }
                item(key = "code-field") {
                    OutlinedTextField(form.code, model::changeCode,
                        label = { Text(codeLabel) }, singleLine = true, enabled = !state.busy,
                        supportingText = { Text(codeHint ?: "Short code, for example CS or BSC-CS. Letters, digits, - and _.") },
                        isError = state.error != null, modifier = Modifier.fillMaxWidth())
                }
                if (withDates) item(key = "date-fields") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Dates (optional)", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(form.startDate, model::changeStart, label = { Text("Start") },
                                placeholder = { Text("2026-06-01") }, singleLine = true, enabled = !state.busy,
                                modifier = Modifier.weight(1f))
                            OutlinedTextField(form.endDate, model::changeEnd, label = { Text("End") },
                                placeholder = { Text("2027-05-31") }, singleLine = true, enabled = !state.busy,
                                modifier = Modifier.weight(1f))
                        }
                        Text("YYYY-MM-DD. Give both or leave both empty." +
                            if (resource == "semester") " A semester must fall inside its academic year's dates." else "",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.error?.let { item(key = "form-error") { Text(it, color = MaterialTheme.colorScheme.error) } }
                item(key = "save") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::save, enabled = !state.busy) { Text("Save $resource") }
                        TextButton(onClick = model::cancelEdit, enabled = !state.busy) { Text("Cancel") }
                    }
                }
                val current = state.list.orEmpty().firstOrNull { it.id == form.id }
                if (canDeactivate && current != null) item(key = "status") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HorizontalDivider()
                        if (current.active == false) {
                            Text("Inactive: kept for history, but no new programs or people can be added.",
                                style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { confirmActive = true }, enabled = !state.busy) { Text("Reactivate $resource") }
                        } else OutlinedButton(onClick = { confirmActive = false }, enabled = !state.busy) { Text("Deactivate $resource") }
                    }
                }
            } else {
                val departments = state.list
                if (departments == null && !state.busy) {
                    item(key = "retry") { OutlinedButton(onClick = model::refresh) { Text("Retry") } }
                }
                if (departments != null) {
                    item(key = "list-actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { model.edit() }, enabled = !state.busy) { Text("Add $resource") }
                            OutlinedButton(onClick = model::refresh, enabled = !state.busy) { Text("Refresh") }
                        }
                    }
                    if (departments.isEmpty()) item(key = "empty-list") { Text(emptyText) }
                    else {
                        item(key = "search") {
                            OutlinedTextField(search, { search = it }, label = { Text("Search ${resource}s") },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        val query = search.trim()
                        val visible = departments.filter { it.name.contains(query, true) || it.code.contains(query, true) }
                        if (visible.isEmpty()) item(key = "empty-search") { Text("No ${resource}s match your search.") }
                        items(visible, key = { it.id }) { department ->
                            Card(onClick = { if (selectItem == null) model.edit(department) else selectItem(department) },
                                enabled = !state.busy) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(department.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                                        if (department.active == false) StatusLabel("Inactive", MaterialTheme.colorScheme.errorContainer)
                                        if (isCurrent(department.startDate, department.endDate)) StatusLabel("Current", MaterialTheme.colorScheme.primaryContainer)
                                    }
                                    Text(department.code)
                                    periodLabel(department.startDate, department.endDate)?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    // The next level (programs, semesters, subjects, divisions) is a clear button; tapping the card edits it.
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                        itemAction?.let { open ->
                                            FilledTonalButton(onClick = { open(department) }, enabled = !state.busy) {
                                                Text("$actionLabel →")
                                            }
                                        }
                                        if (selectItem == null) OutlinedButton(onClick = { model.edit(department) }, enabled = !state.busy) {
                                            Text("Edit")
                                        } else OutlinedButton(onClick = { selectItem(department) }, enabled = !state.busy) {
                                            Text("Semesters →")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private val PERIOD_DATE = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")

/** "1 Jun 2026 – 31 May 2027", or null without dates. */
internal fun periodLabel(start: String?, end: String?): String? {
    val from = start?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
    val to = end?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return null
    return "${from.format(PERIOD_DATE)} – ${to.format(PERIOD_DATE)}"
}

/** True when today falls inside the dates. */
internal fun isCurrent(start: String?, end: String?, today: java.time.LocalDate = java.time.LocalDate.now()): Boolean {
    val from = start?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return false
    val to = end?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: return false
    return !today.isBefore(from) && !today.isAfter(to)
}
