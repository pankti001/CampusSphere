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
        actionLabel = "Manage programs", itemAction = onPrograms, onBack = onBack)
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
    selectItem: ((AcademicCatalogItem) -> Unit)? = null, codeHint: String? = null, onBack: () -> Unit) {
    var search by remember { mutableStateOf("") }
    val form = state.form
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
                state.error?.let { item(key = "form-error") { Text(it, color = MaterialTheme.colorScheme.error) } }
                item(key = "save") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = model::save, enabled = !state.busy) { Text("Save $resource") }
                        TextButton(onClick = model::cancelEdit, enabled = !state.busy) { Text("Cancel") }
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
                                    Text(department.name, style = MaterialTheme.typography.titleMedium)
                                    Text(department.code)
                                    // The next level (programs, semesters, subjects, divisions) is a clear button; tapping the card edits it.
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                        itemAction?.let { open ->
                                            FilledTonalButton(onClick = { open(department) }, enabled = !state.busy) {
                                                Text(actionLabel + " 2192")
                                            }
                                        }
                                        if (selectItem == null) OutlinedButton(onClick = { model.edit(department) }, enabled = !state.busy) {
                                            Text("Edit")
                                        } else OutlinedButton(onClick = { selectItem(department) }, enabled = !state.busy) {
                                            Text("Semesters 2192")
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
