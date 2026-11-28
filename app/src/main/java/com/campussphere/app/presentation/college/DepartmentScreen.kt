package com.campussphere.app.presentation.college

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import com.campussphere.app.ui.brand.*

@Composable
fun DepartmentScreen(state: DepartmentState, model: DepartmentViewModel,
                     onPrograms: ((AcademicCatalogItem) -> Unit)? = null, onBack: () -> Unit) {
    AcademicCatalogScreen(state, model, "department", "Departments",
        "No departments have been added to your college yet.",
        actionLabel = "Manage programs", itemAction = onPrograms, canDeactivate = true,
        stateKey = "departments", onBack = onBack)
}

@Composable
fun ProgramScreen(state: DepartmentState, model: AcademicCatalogViewModel, department: AcademicCatalogItem,
                  onSemesters: ((AcademicCatalogItem) -> Unit)? = null, onBack: () -> Unit) {
    AcademicCatalogScreen(state, model, "program", "Programs",
        "No programs have been added to this department yet.", department.name,
        actionLabel = "Manage semesters", itemAction = onSemesters,
        stateKey = "programs-${department.id}", onBack = onBack)
}

/**
 * One level of the academic structure (departments, programs, years, semesters, divisions or subjects): a searchable
 * list, and an add/edit form. [stateKey] names the level so its list and form each keep their own scroll position.
 */
@Composable
internal fun AcademicCatalogScreen(state: DepartmentState, model: AcademicCatalogViewModel, resource: String,
    titleText: String, emptyText: String, parentName: String? = null,
    codeLabel: String = "${resource.replaceFirstChar { it.uppercaseChar() }} code",
    actionLabel: String = "", itemAction: ((AcademicCatalogItem) -> Unit)? = null,
    selectItem: ((AcademicCatalogItem) -> Unit)? = null, codeHint: String? = null, withDates: Boolean = false,
    canDeactivate: Boolean = false, stateKey: String = resource, onBack: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var confirmActive by remember { mutableStateOf<Boolean?>(null) }
    val form = state.form
    confirmActive?.let { activate ->
        val item = state.list.orEmpty().firstOrNull { it.id == form?.id }
        ConfirmDialog(
            title = if (activate) "Reactivate this $resource?" else "Deactivate this $resource?",
            body = if (activate) "It can receive new programs, faculty and students again."
                   else "Its programs, people and history stay. It cannot receive new programs, faculty or students until you reactivate it.",
            confirm = if (activate) "Reactivate" else "Deactivate",
            onConfirm = { confirmActive = null; item?.let { model.setActive(it, activate) } },
            onDismiss = { confirmActive = null },
            danger = !activate)
    }
    fun back() { if (form != null) model.cancelEdit() else onBack() }
    BackHandler { if (!state.busy) back() }

    val title = when {
        form == null -> titleText
        form.id == null -> "Add $resource"
        else -> "Edit $resource"
    }
    val capitalised = resource.replaceFirstChar { it.uppercaseChar() }

    CampusPage(
        title = title,
        subtitle = parentName,
        onBack = ::back,
        backEnabled = !state.busy,
        busy = state.busy,
        // The list and each form keep their own scroll position.
        stateKey = if (form == null) "$stateKey-list" else "$stateKey-form-${form.id ?: "new"}",
        // In the form, problems are shown next to the fields instead.
        error = if (form == null) state.error else null,
        onRetry = if (form == null && state.list == null) ({ model.refresh() }) else null,
        notice = state.notice,
        actions = {
            if (form == null && state.list != null) {
                IconButton({ model.refresh() }, enabled = !state.busy) { Icon(CampusIcons.Refresh, "Refresh") }
            }
        },
    ) {
        if (form != null) {
            item(key = "form") {
                InfoCard {
                    OutlinedTextField(form.name, model::changeName,
                        label = { Text("$capitalised name") }, singleLine = true, enabled = !state.busy,
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(form.code, model::changeCode,
                        label = { Text(codeLabel) }, singleLine = true, enabled = !state.busy,
                        supportingText = { Text(codeHint ?: "Short code, for example CS or BSC-CS. Letters, digits, - and _.") },
                        isError = state.error != null, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
                    if (withDates) {
                        Text("Dates (optional)", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(form.startDate, model::changeStart, label = { Text("Start") },
                                placeholder = { Text("2026-06-01") }, singleLine = true, enabled = !state.busy,
                                shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f))
                            OutlinedTextField(form.endDate, model::changeEnd, label = { Text("End") },
                                placeholder = { Text("2027-05-31") }, singleLine = true, enabled = !state.busy,
                                shape = MaterialTheme.shapes.small, modifier = Modifier.weight(1f))
                        }
                        Text("YYYY-MM-DD. Give both or leave both empty." +
                            if (resource == "semester") " A semester must fall inside its academic year's dates." else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.error?.let { MessageBanner(it, MessageKind.Error) }
                    PrimaryButton("Save $resource", model::save, enabled = !state.busy, icon = CampusIcons.Check)
                    SecondaryButton("Cancel", model::cancelEdit, enabled = !state.busy)
                }
            }
            val current = state.list.orEmpty().firstOrNull { it.id == form.id }
            if (canDeactivate && current != null) item(key = "status") {
                val inactive = current.active == false
                InfoCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Status", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        StatusChip(if (inactive) "Inactive" else "Active", if (inactive) Tone.Danger else Tone.Positive)
                    }
                    if (inactive) {
                        Text("Kept for history, but no new programs or people can be added.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SecondaryButton("Reactivate $resource", { confirmActive = true }, enabled = !state.busy,
                            icon = CampusIcons.Refresh)
                    } else {
                        SecondaryButton("Deactivate $resource", { confirmActive = false }, enabled = !state.busy,
                            icon = CampusIcons.Archive, danger = true)
                    }
                }
            }
        } else {
            val list = state.list
            // Without an error message the banner has no Retry, so offer it here.
            if (list == null && !state.busy && state.error == null) item(key = "retry") {
                SecondaryButton("Retry", { model.refresh() }, icon = CampusIcons.Refresh)
            }
            if (list != null) {
                item(key = "add") {
                    PrimaryButton("Add $resource", { model.edit() }, enabled = !state.busy, icon = CampusIcons.Plus)
                }
                if (list.isEmpty()) item(key = "empty-list") { EmptyState("No ${resource}s yet", emptyText) }
                else {
                    item(key = "search") { SearchField(search, { search = it }, "Search ${resource}s") }
                    val query = search.trim()
                    val visible = list.filter { it.name.contains(query, true) || it.code.contains(query, true) }
                    if (visible.isEmpty()) item(key = "empty-search") {
                        EmptyState("No matches", "No ${resource}s match your search.")
                    }
                    items(visible, key = { "item-${it.id}" }) { entry ->
                        // Tapping the row edits it (or opens its semesters); the next level is a clear button.
                        CatalogRow(entry, resourceIcon(resource), enabled = !state.busy,
                            onClick = { if (selectItem == null) model.edit(entry) else selectItem(entry) }) {
                            itemAction?.let { open ->
                                FilledTonalButton(onClick = { open(entry) }, enabled = !state.busy, shape = MaterialTheme.shapes.small) {
                                    Text(actionLabel)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(CampusIcons.ChevronRight, null, Modifier.size(18.dp))
                                }
                            }
                            if (selectItem == null) OutlinedButton(onClick = { model.edit(entry) }, enabled = !state.busy,
                                shape = MaterialTheme.shapes.small) {
                                Icon(CampusIcons.Edit, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Edit")
                            } else OutlinedButton(onClick = { selectItem(entry) }, enabled = !state.busy,
                                shape = MaterialTheme.shapes.small) {
                                Text("Semesters")
                                Spacer(Modifier.width(4.dp))
                                Icon(CampusIcons.ChevronRight, null, Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun resourceIcon(resource: String): ImageVector = when (resource) {
    "department" -> CampusIcons.Building
    "program" -> CampusIcons.Book
    "academic year", "semester" -> CampusIcons.Calendar
    "division" -> CampusIcons.Users
    else -> CampusIcons.Books
}

/** A list row in the kit's style (icon, name, code, dates, status) with the row's own buttons underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogRow(item: AcademicCatalogItem, icon: ImageVector, enabled: Boolean, onClick: () -> Unit,
                       actions: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLowest, border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.small, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                    Icon(icon, null, Modifier.padding(8.dp).size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(item.code, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    periodLabel(item.startDate, item.endDate)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.outline, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.active == false) StatusChip("Inactive", Tone.Danger)
                    if (isCurrent(item.startDate, item.endDate)) StatusChip("Current", Tone.Accent)
                }
            }
            // Wraps onto a second line on narrow phones instead of squeezing the labels.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                actions()
            }
        }
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
