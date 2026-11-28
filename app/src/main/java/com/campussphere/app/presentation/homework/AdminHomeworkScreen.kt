package com.campussphere.app.presentation.homework

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.HomeworkOption
import com.campussphere.app.data.repository.HomeworkRepository
import com.campussphere.app.ui.brand.*

private val ADMIN_STATUSES = listOf("PUBLISHED" to "Published", "CLOSED" to "Closed", "ARCHIVED" to "Archived")

/** College-wide assignment monitoring: statistics and published assignments only. No marks, feedback or files. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminHomeworkScreen(repo: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit, exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<AdminHomeworkViewModel>(viewModelStoreOwner = owner) { AdminHomeworkViewModel(repo, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showFilters by remember { mutableStateOf(false) }

    LaunchedEffect(vm) { if (!ui.loaded) vm.load() }
    BackHandler { if (!ui.loading && !vm.back()) exit() }
    val c = ui.choice
    val f = ui.filters
    val selected = ui.selected

    CampusPage(
        title = if (selected == null) "Assignment overview" else "Assignment",
        subtitle = selected?.let { "${it.subjectCode} · ${it.subject} · ${it.division}" } ?: "Whole college",
        onBack = { if (!vm.back()) exit() },
        backEnabled = !ui.loading,
        busy = ui.loading,
        // The overview and each assignment keep their own scroll position.
        stateKey = if (selected == null) "list" else "detail-${selected.id}",
        error = ui.error,
    ) {
        if (selected != null) {
            item(key = "details") {
                InfoCard {
                    StatusChip(homeworkStatusLabel(selected.status), homeworkTone(selected.status))
                    HomeworkDetails(selected, null)
                }
            }
            selected.counts?.let { k ->
                item(key = "stats") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeworkStat("Students", k.roster, Modifier.weight(1f))
                            HomeworkStat("Submitted", k.submitted, Modifier.weight(1f))
                            HomeworkStat("Pending", k.pending, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeworkStat("Late", k.late, Modifier.weight(1f))
                            HomeworkStat("To mark", k.toMark, Modifier.weight(1f))
                            HomeworkStat("Marked", k.evaluated, Modifier.weight(1f))
                        }
                    }
                }
            }
            if (ui.history.isNotEmpty()) {
                item(key = "history-header") { SectionHeader("History") }
                items(ui.history.size, key = { i -> "event-$i" }) { i ->
                    val e = ui.history[i]
                    ListRow("${e.actor} ${historyLabel(e.action)}", subtitle = e.note, meta = homeworkTime(e.time),
                        icon = CampusIcons.Clock)
                }
            }
        } else {
            item(key = "scope") {
                MessageBanner("Monitoring only. Faculty create and mark assignments; marks and student files stay with them.",
                    MessageKind.Info)
            }
            item(key = "stats") {
                val s = ui.stats
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeworkStat("Published", s?.published, Modifier.weight(1f))
                        HomeworkStat("Closed", s?.closed, Modifier.weight(1f))
                        HomeworkStat("Drafts", s?.drafts, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeworkStat("Submissions", s?.submissions, Modifier.weight(1f))
                        HomeworkStat("Pending", s?.pending, Modifier.weight(1f))
                        HomeworkStat("Late", s?.late, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeworkStat("To mark", s?.toMark, Modifier.weight(1f))
                        HomeworkStat("Marked", s?.evaluated, Modifier.weight(1f))
                        HomeworkStat("Archived", s?.archived, Modifier.weight(1f))
                    }
                }
            }
            item(key = "filters-toggle") {
                SecondaryButton(if (showFilters) "Hide filters" else "Filters", { showFilters = !showFilters },
                    icon = CampusIcons.Filter)
            }
            if (showFilters) item(key = "filters") {
                InfoCard {
                    fun opts(list: List<HomeworkOption>?) = list.orEmpty().map { it.id to it.label }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        HomeworkChoice("Program", opts(f?.programs), c.programId) { v -> vm.choose { it.copy(programId = v) } }
                        HomeworkChoice("Year", opts(f?.academicYears), c.academicYearId) { v -> vm.choose { it.copy(academicYearId = v) } }
                        HomeworkChoice("Semester", opts(f?.semesters), c.semesterId) { v -> vm.choose { it.copy(semesterId = v) } }
                        HomeworkChoice("Division", opts(f?.divisions), c.divisionId) { v -> vm.choose { it.copy(divisionId = v) } }
                        HomeworkChoice("Subject", opts(f?.subjects), c.subjectId) { v -> vm.choose { it.copy(subjectId = v) } }
                        HomeworkChoice("Faculty", opts(f?.faculty), c.facultyId) { v -> vm.choose { it.copy(facultyId = v) } }
                        HomeworkChoice("Status", ADMIN_STATUSES, c.status) { v -> vm.choose { it.copy(status = v) } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(c.from, { v -> vm.choose { it.copy(from = v) } }, label = { Text("Due from") },
                            placeholder = { Text("2026-12-01") }, singleLine = true, modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small)
                        OutlinedTextField(c.to, { v -> vm.choose { it.copy(to = v) } }, label = { Text("Due to") },
                            placeholder = { Text("2026-12-31") }, singleLine = true, modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.small)
                    }
                    // No keyboard search here: filters only take effect on Apply, as before.
                    SearchField(c.query, { v -> vm.choose { it.copy(query = v) } }, "Search titles")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Clear", vm::clear, Modifier.weight(1f), enabled = !ui.loading)
                        PrimaryButton("Apply", vm::apply, Modifier.weight(1f), enabled = !ui.loading, icon = CampusIcons.Filter)
                    }
                }
            }
            if (ui.loaded && ui.items.isEmpty() && !ui.loading) item(key = "empty") {
                EmptyState("Nothing found", "No published assignments match. Faculty publish assignments from their own dashboard.")
            }
            items(ui.items, key = { "hw-${it.id}" }) { hw -> HomeworkCard(hw, !ui.loading) { vm.open(hw) } }
            if (ui.hasMore) item(key = "more") { SecondaryButton("Load more", vm::loadMore, enabled = !ui.loading) }
        }
    }
}
