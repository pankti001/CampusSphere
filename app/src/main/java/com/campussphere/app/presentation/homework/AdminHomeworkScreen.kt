package com.campussphere.app.presentation.homework

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
import com.campussphere.app.data.remote.api.HomeworkOption
import com.campussphere.app.data.repository.HomeworkRepository

private val ADMIN_STATUSES = listOf("PUBLISHED" to "Published", "CLOSED" to "Closed", "ARCHIVED" to "Archived")

/** College-wide assignment monitoring: statistics and published assignments only. No marks, feedback or files. */
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

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton({ if (!vm.back()) exit() }, enabled = !ui.loading) { Text("Back") }
            Text(if (ui.selected == null) "Assignment overview" else "Assignment", style = MaterialTheme.typography.headlineMedium)
            Text("Monitoring only: faculty create and mark assignments; marks and student files stay with them.",
                style = MaterialTheme.typography.bodySmall)
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        ui.error?.let { item { HomeworkMessages(it, null) { } } }
        val selected = ui.selected
        if (selected != null) {
            item { Card(Modifier.fillMaxWidth()) { Box(Modifier.padding(14.dp)) { HomeworkDetails(selected, null) } } }
            selected.counts?.let { k ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeworkStat("Students", k.roster, Modifier.weight(1f))
                        HomeworkStat("Submitted", k.submitted, Modifier.weight(1f))
                        HomeworkStat("Pending", k.pending, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeworkStat("Late", k.late, Modifier.weight(1f))
                        HomeworkStat("To mark", k.toMark, Modifier.weight(1f))
                        HomeworkStat("Marked", k.evaluated, Modifier.weight(1f))
                    }
                }
            }
            if (ui.history.isNotEmpty()) {
                item { Text("History", style = MaterialTheme.typography.titleSmall) }
                items(ui.history.size) { i ->
                    val e = ui.history[i]
                    Text("${homeworkTime(e.time)} · ${e.actor}: ${historyLabel(e.action)}" + (e.note?.let { " — $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            item {
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
            item {
                OutlinedButton({ showFilters = !showFilters }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showFilters) "Hide filters" else "Filters")
                }
            }
            if (showFilters) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun opts(list: List<HomeworkOption>?) = list.orEmpty().map { it.id to it.label }
                    HomeworkChoice("Program", opts(f?.programs), c.programId) { v -> vm.choose { it.copy(programId = v) } }
                    HomeworkChoice("Year", opts(f?.academicYears), c.academicYearId) { v -> vm.choose { it.copy(academicYearId = v) } }
                    HomeworkChoice("Semester", opts(f?.semesters), c.semesterId) { v -> vm.choose { it.copy(semesterId = v) } }
                    HomeworkChoice("Division", opts(f?.divisions), c.divisionId) { v -> vm.choose { it.copy(divisionId = v) } }
                    HomeworkChoice("Subject", opts(f?.subjects), c.subjectId) { v -> vm.choose { it.copy(subjectId = v) } }
                    HomeworkChoice("Faculty", opts(f?.faculty), c.facultyId) { v -> vm.choose { it.copy(facultyId = v) } }
                    HomeworkChoice("Status", ADMIN_STATUSES, c.status) { v -> vm.choose { it.copy(status = v) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(c.from, { v -> vm.choose { it.copy(from = v) } }, label = { Text("Due from") },
                            placeholder = { Text("2026-12-01") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(c.to, { v -> vm.choose { it.copy(to = v) } }, label = { Text("Due to") },
                            placeholder = { Text("2026-12-31") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(c.query, { v -> vm.choose { it.copy(query = v) } }, label = { Text("Search titles") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(vm::apply, enabled = !ui.loading) { Text("Apply") }
                        TextButton(vm::clear, enabled = !ui.loading) { Text("Clear") }
                    }
                }
            }
            if (ui.loaded && ui.items.isEmpty() && !ui.loading) item {
                Text("No published assignments match. Faculty publish assignments from their own dashboard.")
            }
            items(ui.items, key = { it.id }) { hw -> HomeworkCard(hw, !ui.loading) { vm.open(hw) } }
            if (ui.hasMore) item { OutlinedButton(vm::loadMore, enabled = !ui.loading) { Text("Load more") } }
        }
    }
}
