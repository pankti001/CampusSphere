package com.campussphere.app.presentation.homework

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.repository.HomeworkFailure
import com.campussphere.app.data.repository.HomeworkRepository
import com.campussphere.app.data.repository.homeworkPickerTypes
import com.campussphere.app.data.repository.homeworkSize
import com.campussphere.app.ui.brand.*
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

    val view = ui.view
    CampusPage(
        title = when (ui.page) {
            StudentHomeworkPage.LIST -> "My assignments"
            StudentHomeworkPage.DETAIL -> "Assignment"
            StudentHomeworkPage.NOTICES -> "Assignment notifications"
        },
        subtitle = if (ui.page == StudentHomeworkPage.DETAIL) view?.assignment?.let { "${it.subjectCode} · ${it.subject}" } else null,
        onBack = { if (!vm.back()) exit() },
        backEnabled = !busy,
        busy = busy,
        // The list (per tab), each assignment and the notifications keep their own scroll position.
        stateKey = when (ui.page) {
            StudentHomeworkPage.LIST -> "list-${ui.tab}"
            StudentHomeworkPage.DETAIL -> "detail-${view?.assignment?.id}"
            StudentHomeworkPage.NOTICES -> "notices"
        },
    ) {
        ui.progress?.let { p ->
            item(key = "progress") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Uploading ${(p * 100).toInt().coerceIn(0, 100)}%", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                }
            }
        }
        if (ui.error != null || ui.notice != null) item(key = "messages") {
            HomeworkMessages(ui.error, ui.notice, vm::clearMessages)
        }
        when (ui.page) {
            StudentHomeworkPage.LIST -> {
                ui.summary?.let { s ->
                    item(key = "stats") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeworkStat("Pending", s.pending, Modifier.weight(1f))
                            HomeworkStat("Due this week", s.dueThisWeek, Modifier.weight(1f))
                            HomeworkStat("Marked", s.evaluated, Modifier.weight(1f))
                        }
                    }
                    item(key = "notices-button") {
                        SecondaryButton("Notifications" + if (s.unreadNotices > 0) " (${s.unreadNotices} new)" else "",
                            vm::notices, enabled = !busy, icon = CampusIcons.Bell)
                    }
                }
                item(key = "tabs") {
                    val selected = STUDENT_TABS.indexOfFirst { it.first == ui.tab }.coerceAtLeast(0)
                    HomeworkTabs(STUDENT_TABS.map { it.second }, selected) { index ->
                        // Tabs do nothing while a request is running, as before.
                        if (!busy) vm.setTab(STUDENT_TABS[index].first)
                    }
                }
                if (ui.loaded && ui.items.isEmpty() && !busy) item(key = "empty") {
                    val (title, body) = when (ui.tab) {
                        "PENDING" -> "All caught up" to "Nothing to hand in right now."
                        "SUBMITTED" -> "Nothing waiting" to "No submitted work is waiting for marks."
                        "LATE" -> "No late work" to "No late submissions."
                        "EVALUATED" -> "No results yet" to "No marked work yet."
                        else -> "No assignments" to "No assignments for your class yet."
                    }
                    EmptyState(title, body)
                }
                items(ui.items, key = { "hw-${it.id}" }) { hw -> HomeworkCard(hw, !busy) { vm.open(hw.id) } }
                if (ui.hasMore) item(key = "more") { SecondaryButton("Load more", vm::loadMore, enabled = !busy) }
            }
            StudentHomeworkPage.DETAIL -> view?.let { v ->
                val hw = v.assignment
                val current = v.attempts.firstOrNull { it.status != "SUPERSEDED" }
                val canSubmit = when {
                    current == null -> hw.acceptingSubmissions
                    current.status == "RETURNED" -> hw.status == "PUBLISHED" || hw.status == "CLOSED"
                    current.status == "SUBMITTED" -> hw.resubmission == "UNTIL_DEADLINE" && hw.acceptingSubmissions && !hw.overdue
                    else -> false
                }
                item(key = "details") {
                    InfoCard {
                        HomeworkDetails(hw) {
                            download(hw.attachmentName ?: "attachment") { uri ->
                                repo.saveAttachment(auth(), "student", hw.id, context.contentResolver, uri)
                            }
                        }
                    }
                }
                current?.let { s ->
                    item(key = "work-header") { SectionHeader("Your work") }
                    item(key = "work") {
                        InfoCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                StatusChip(homeworkStatusLabel(s.status), homeworkTone(s.status))
                                StatusChip(if (s.late) "Late" else "On time", if (s.late) Tone.Warning else Tone.Positive)
                            }
                            if (s.status == "EVALUATED") {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Marks", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("${homeworkMarks(s.marks)} / ${homeworkMarks(hw.maxMarks)}",
                                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                }
                                InfoRow("Marked by", "${s.evaluatedBy.orEmpty()} on ${homeworkTime(s.evaluatedAt)}")
                            }
                            s.feedback?.let { InfoRow("Feedback", it) }
                            if (s.status == "RETURNED") MessageBanner("Correct your work and submit it again.", MessageKind.Error)
                            InfoRow("File", "${s.fileName} (${homeworkSize(s.sizeBytes)}) · attempt ${s.attempt}")
                            InfoRow("Submitted", homeworkTime(s.submittedAt))
                            SecondaryButton("Download my file", {
                                download(s.fileName) { uri ->
                                    repo.saveSubmission(auth(), "student", hw.id, s.id, context.contentResolver, uri)
                                }
                            }, enabled = !busy, icon = CampusIcons.Download)
                        }
                    }
                }
                if (canSubmit) {
                    item(key = "submit-header") { SectionHeader(if (current == null) "Hand in your work" else "Submit again") }
                    item(key = "submit") {
                        InfoCard {
                            if (hw.overdue) MessageBanner("The deadline has passed, so this will be marked late.", MessageKind.Error)
                            SecondaryButton(ui.file?.let { "Chosen: ${it.fileName} (${homeworkSize(it.bytes.size.toLong())})" } ?: "Choose file",
                                { pick.launch(homeworkPickerTypes(hw.allowedFormats)) }, enabled = !busy,
                                icon = if (ui.file == null) CampusIcons.Upload else CampusIcons.File)
                            pickError?.let { MessageBanner(it, MessageKind.Error) }
                            OutlinedTextField(ui.comment, vm::setComment, label = { Text("Comment for your teacher (optional)") },
                                enabled = !busy, minLines = 2, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small)
                            PrimaryButton("Submit", vm::submit, enabled = !busy && ui.file != null, icon = CampusIcons.Upload)
                        }
                    }
                } else if (current == null) item(key = "closed") {
                    MessageBanner(if (hw.overdue || hw.status != "PUBLISHED") "This assignment is no longer accepting submissions."
                        else "This assignment is not open for submissions yet.", MessageKind.Info)
                }
                val earlier = v.attempts.filter { it.status == "SUPERSEDED" }
                if (earlier.isNotEmpty()) {
                    item(key = "earlier-header") { SectionHeader("Earlier attempts") }
                    items(earlier, key = { "attempt-${it.id}" }) { a ->
                        ListRow("Attempt ${a.attempt}", subtitle = "${a.fileName} · ${homeworkTime(a.submittedAt)}",
                            meta = a.feedback?.let { "Feedback: $it" }, icon = CampusIcons.File)
                    }
                }
            }
            StudentHomeworkPage.NOTICES -> {
                if (ui.notices.isEmpty() && !busy) item(key = "empty") {
                    EmptyState("No notifications yet", "Assignment notifications will appear here.")
                }
                items(ui.notices, key = { "notice-${it.id}" }) { n ->
                    ListRow(n.title, subtitle = n.body,
                        meta = homeworkTime(n.createdAt) + (n.dueAt?.let { " · due ${homeworkTime(it)}" } ?: ""),
                        icon = CampusIcons.Bell,
                        chip = if (n.readAt == null) ({ StatusChip("New", Tone.Accent) }) else null,
                        onClick = { if (!busy) vm.openNotice(n) })
                }
                if (ui.noticesHasMore) item(key = "more-notices") {
                    SecondaryButton("Load more", vm::moreNotices, enabled = !busy)
                }
            }
        }
    }
}

/** Five tabs do not fit a fixed tab row on a phone, so these scroll sideways; otherwise they look like CampusTabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeworkTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    PrimaryScrollableTabRow(selectedTabIndex = selected, containerColor = Color.Transparent, edgePadding = 0.dp) {
        titles.forEachIndexed { index, title ->
            Tab(selected == index, { onSelect(index) }, text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}
