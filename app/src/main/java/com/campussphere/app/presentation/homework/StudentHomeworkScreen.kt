package com.campussphere.app.presentation.homework

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.repository.HomeworkFailure
import com.campussphere.app.data.repository.HomeworkRepository
import com.campussphere.app.data.repository.homeworkPickerTypes
import com.campussphere.app.data.repository.homeworkSize
import kotlinx.coroutines.launch

@Composable
fun StudentHomeworkScreen(repo: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit, initialTab: String = "PENDING",
                          exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<StudentHomeworkViewModel>(viewModelStoreOwner = owner) { StudentHomeworkViewModel(repo, auth, lost, initialTab) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDownload by remember { mutableStateOf<(suspend (android.net.Uri) -> Unit)?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var pickError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) { if (!ui.loaded) vm.load() }
    val busy = ui.busy || ui.loading
    BackHandler { if (!busy && !vm.back()) exit() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val action = pendingDownload
        if (uri != null && action != null) vm.download("Downloaded $pendingName.") { action(uri) }
        pendingDownload = null
    }
    fun download(name: String, action: suspend (android.net.Uri) -> Unit) {
        pendingName = name
        pendingDownload = action
        save.launch(name)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val assignment = ui.view?.assignment
        if (uri != null && assignment != null) scope.launch {
            pickError = null
            try {
                vm.chooseFile(repo.read(context.contentResolver, uri, assignment.allowedFormats, assignment.maxFileBytes))
            } catch (e: HomeworkFailure) {
                pickError = e.message
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton({ if (!vm.back()) exit() }, enabled = !busy) { Text("Back") }
            Text(when (ui.page) {
                StudentHomeworkPage.LIST -> "My assignments"
                StudentHomeworkPage.DETAIL -> "Assignment"
                StudentHomeworkPage.NOTICES -> "Assignment notifications"
            }, style = MaterialTheme.typography.headlineMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            ui.progress?.let { p -> LinearProgressIndicator({ p }, Modifier.fillMaxWidth().padding(top = 4.dp)) }
        }
        item { HomeworkMessages(ui.error, ui.notice, vm::clearMessages) }
        when (ui.page) {
            StudentHomeworkPage.LIST -> {
                ui.summary?.let { s ->
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeworkStat("Pending", s.pending, Modifier.weight(1f))
                            HomeworkStat("Due this week", s.dueThisWeek, Modifier.weight(1f))
                            HomeworkStat("Marked", s.evaluated, Modifier.weight(1f))
                        }
                    }
                    item {
                        OutlinedButton(vm::notices, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("🔔 Notifications" + if (s.unreadNotices > 0) " (${s.unreadNotices} new)" else "")
                        }
                    }
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        STUDENT_TABS.forEach { (key, label) ->
                            FilterChip(ui.tab == key, { vm.setTab(key) }, { Text(label) }, enabled = !busy)
                        }
                    }
                }
                if (ui.loaded && ui.items.isEmpty() && !busy) item {
                    Text(when (ui.tab) {
                        "PENDING" -> "Nothing to hand in right now."
                        "SUBMITTED" -> "No submitted work waiting for marks."
                        "LATE" -> "No late submissions."
                        "EVALUATED" -> "No marked work yet."
                        else -> "No assignments for your class yet."
                    })
                }
                items(ui.items, key = { it.id }) { hw -> HomeworkCard(hw, !busy) { vm.open(hw.id) } }
                if (ui.hasMore) item { OutlinedButton(vm::loadMore, enabled = !busy) { Text("Load more") } }
            }
            StudentHomeworkPage.DETAIL -> ui.view?.let { view ->
                val hw = view.assignment
                val current = view.attempts.firstOrNull { it.status != "SUPERSEDED" }
                val canSubmit = when {
                    current == null -> hw.acceptingSubmissions
                    current.status == "RETURNED" -> hw.status == "PUBLISHED" || hw.status == "CLOSED"
                    current.status == "SUBMITTED" -> hw.resubmission == "UNTIL_DEADLINE" && hw.acceptingSubmissions && !hw.overdue
                    else -> false
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            HomeworkDetails(hw) {
                                download(hw.attachmentName ?: "attachment") { uri ->
                                    repo.saveAttachment(auth(), "student", hw.id, context.contentResolver, uri)
                                }
                            }
                        }
                    }
                }
                current?.let { s ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Your work", style = MaterialTheme.typography.titleMedium)
                                HomeworkBadge(homeworkStatusLabel(s.status), statusColor(s.status))
                                Text("${s.fileName} (${homeworkSize(s.sizeBytes)}) · attempt ${s.attempt}")
                                Text("Submitted ${homeworkTime(s.submittedAt)}" + if (s.late) " · late" else " · on time")
                                if (s.status == "EVALUATED") {
                                    Text("Marks: ${homeworkMarks(s.marks)} / ${homeworkMarks(hw.maxMarks)}",
                                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("Marked by ${s.evaluatedBy.orEmpty()} on ${homeworkTime(s.evaluatedAt)}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                s.feedback?.let { Text("Feedback: $it") }
                                if (s.status == "RETURNED") Text("Please correct your work and submit it again.",
                                    color = MaterialTheme.colorScheme.error)
                                OutlinedButton({
                                    download(s.fileName) { uri ->
                                        repo.saveSubmission(auth(), "student", hw.id, s.id, context.contentResolver, uri)
                                    }
                                }, enabled = !busy) { Text("Download my file") }
                            }
                        }
                    }
                }
                if (canSubmit) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (current == null) "Hand in your work" else "Submit again", style = MaterialTheme.typography.titleMedium)
                        if (hw.overdue) Text("The deadline has passed; this will be marked late.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton({ pick.launch(homeworkPickerTypes(hw.allowedFormats)) }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(ui.file?.let { "Chosen: ${it.fileName} (${homeworkSize(it.bytes.size.toLong())})" } ?: "Choose file")
                        }
                        pickError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(ui.comment, vm::setComment, label = { Text("Comment for your teacher (optional)") },
                            enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth())
                        Button(vm::submit, enabled = !busy && ui.file != null, modifier = Modifier.fillMaxWidth()) { Text("Submit") }
                    }
                } else if (current == null) item {
                    Text(if (hw.overdue || hw.status != "PUBLISHED") "This assignment is no longer accepting submissions."
                        else "This assignment is not open for submissions yet.")
                }
                val earlier = view.attempts.filter { it.status == "SUPERSEDED" }
                if (earlier.isNotEmpty()) {
                    item { Text("Earlier attempts", style = MaterialTheme.typography.titleSmall) }
                    items(earlier, key = { it.id }) { a ->
                        Text("Attempt ${a.attempt}: ${a.fileName} · ${homeworkTime(a.submittedAt)}" +
                            (a.feedback?.let { " · feedback: $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            StudentHomeworkPage.NOTICES -> {
                if (ui.notices.isEmpty() && !busy) item { Text("No assignment notifications yet.") }
                items(ui.notices, key = { it.id }) { n ->
                    Card(onClick = { vm.openNotice(n) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(n.title, fontWeight = if (n.readAt == null) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f))
                                if (n.readAt == null) HomeworkBadge("New", MaterialTheme.colorScheme.primaryContainer)
                            }
                            Text(n.body)
                            Text(homeworkTime(n.createdAt) + (n.dueAt?.let { " · due ${homeworkTime(it)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (ui.noticesHasMore) item { OutlinedButton(vm::moreNotices, enabled = !busy) { Text("Load more") } }
            }
        }
    }
}
