package com.campussphere.app.preview

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun DepartmentListScreen(
    state: AdminPreviewState,
    onSearch: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val results = state.results
    LazyColumn(
        modifier = Modifier.widthIn(max = 780.dp).fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            PageTitle("Departments", "Northfield College · Sample directory")
        }
        item {
            OutlinedTextField(
                value = state.search,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                label = { Text("Search departments") },
                leadingIcon = { PreviewIcon(Glyph.SEARCH, Muted) },
                trailingIcon = if (state.search.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { onSearch("") },
                            modifier = Modifier.semantics { contentDescription = "Clear search" }
                        ) {
                            PreviewIcon(Glyph.CLOSE, Muted)
                        }
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White
                )
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val count = results.size
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (count == 1) "1 department" else "$count departments",
                        color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall
                    )
                    if (state.search.isNotBlank()) {
                        Text("of ${state.departments.size} total", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = { keyboard?.hide(); onAdd() },
                    modifier = Modifier.heightIn(min = 46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    PreviewIcon(Glyph.PLUS, Color.White, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Add department")
                }
            }
        }
        state.notice?.let { item { PreviewNote(it) } }
        if (results.isEmpty()) {
            item {
                EmptyDepartments(
                    searching = state.search.isNotBlank(),
                    onAction = if (state.search.isNotBlank()) {
                        { onSearch("") }
                    } else {
                        { keyboard?.hide(); onAdd() }
                    }
                )
            }
        } else {
            items(results, key = { it.id }) { department ->
                DepartmentRow(department) {
                    keyboard?.hide()
                    onEdit(department.id)
                }
            }
        }
    }
}

@Composable
private fun DepartmentRow(department: PreviewDepartment, onEdit: () -> Unit) {
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(17.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(17.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            IconTile(Glyph.BUILDING, BlueTint, Blue)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(department.name, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = Ink)
                Text(department.code, style = MaterialTheme.typography.labelMedium, color = Blue)
            }
            PreviewIcon(Glyph.EDIT, Teal, Modifier.size(20.dp))
        }
    }
}

@Composable
private fun EmptyDepartments(searching: Boolean, onAction: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp), color = Color.White
    ) {
        Column(
            Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconTile(if (searching) Glyph.SEARCH else Glyph.BUILDING, TealTint, Teal)
            Text(if (searching) "No matching departments" else "No departments yet",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(if (searching) "Try a different department name or code." else "Add a department to start the directory.",
                color = Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction) { Text(if (searching) "Clear search" else "Add department") }
        }
    }
}

@Composable
internal fun DepartmentFormScreen(
    editing: Boolean,
    draft: DepartmentDraft,
    onName: (String) -> Unit,
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.widthIn(max = 780.dp).fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            PageTitle(
                if (editing) "Edit department" else "Add department",
                if (editing) "Update a department in the sample directory."
                else "Set up a department for the sample college."
            )
            Surface(
                Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp), color = Color.White
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        IconTile(if (editing) Glyph.EDIT else Glyph.BUILDING, TealTint, Teal)
                        Column {
                            Text("Department details", style = MaterialTheme.typography.titleMedium,
                                color = Ink, fontWeight = FontWeight.Bold)
                            Text("Northfield College", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                    HorizontalDivider(color = Line)
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = onName,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Department name") },
                        placeholder = { Text("e.g. Computer Science") },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        isError = draft.nameError != null,
                        supportingText = draft.nameError?.let { message -> { Text(message) } },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) })
                    )
                    OutlinedTextField(
                        value = draft.code,
                        onValueChange = onCode,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Department code") },
                        placeholder = { Text("e.g. CSE") },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        isError = draft.codeError != null,
                        supportingText = {
                            Text(draft.codeError ?: "Use a unique short code, such as CSE.")
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() })
                    )
                    draft.formError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            PreviewNote("Preview changes stay in this session and reset when you close it.")
        }
        Surface(color = Color.White) {
            Column {
                HorizontalDivider(color = Line)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { keyboard?.hide(); onCancel() },
                        modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { keyboard?.hide(); focus.clearFocus(); onSave() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (editing) "Save changes" else "Add department")
                    }
                }
            }
        }
    }
}
