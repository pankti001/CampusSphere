package com.campussphere.app.presentation.skills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.FacultyLearningDetail
import com.campussphere.app.data.remote.api.FacultyLearningNotice
import com.campussphere.app.data.remote.api.FacultyLearningRequest
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.chat.chatTime
import com.campussphere.app.ui.brand.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FacultyLearningPage { LIST, DETAIL, NOTICES }

/** A pending decline (or cancel of an accepted session) with its optional reason. */
data class FacultyReasonDraft(val cancel: Boolean, val reason: String = "")

data class FacultyLearningUi(
    val page: FacultyLearningPage = FacultyLearningPage.LIST, val busy: Boolean = false, val error: String? = null,
    val notice: String? = null, val requests: List<FacultyLearningRequest> = emptyList(), val loaded: Boolean = false,
    val detail: FacultyLearningDetail? = null, val acceptForm: SkillSessionForm? = null, val acceptError: String? = null,
    val reason: FacultyReasonDraft? = null, val notices: List<FacultyLearningNotice> = emptyList(), val unread: Long = 0,
)

class FacultyLearningViewModel(private val repo: FacultyLearningRepository, private val auth: () -> String,
                               private val lost: (Int) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(FacultyLearningUi())
    val ui = state.asStateFlow()

    private fun work(done: String? = null, block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                block()
                if (done != null) state.update { it.copy(notice = done) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val status = (e as? SkillFailure)?.status
                if (status == 401 || status == 403) lost(status)
                state.update { it.copy(error = e.message ?: "Something went wrong. Try again.") }
            } finally { state.update { it.copy(busy = false) } }
        }
    }

    fun clearMessages() = state.update { it.copy(error = null, notice = null) }

    fun load() = work {
        val list = repo.list(auth())
        val unread = runCatching { repo.unread(auth()) }.getOrDefault(0)
        state.update { it.copy(requests = list, loaded = true, unread = unread) }
    }

    fun back(): Boolean {
        val s = state.value
        if (s.reason != null) { state.update { it.copy(reason = null) }; return true }
        if (s.page == FacultyLearningPage.LIST) return false
        state.update { it.copy(page = FacultyLearningPage.LIST, acceptForm = null, acceptError = null) }
        load()
        return true
    }

    fun open(id: Long) = work {
        val detail = repo.one(auth(), id)
        state.update { it.copy(detail = detail, page = FacultyLearningPage.DETAIL, acceptForm = null) }
    }

    fun openNotices() = work {
        val list = repo.notices(auth())
        state.update { it.copy(notices = list, page = FacultyLearningPage.NOTICES) }
    }

    fun openNotice(n: FacultyLearningNotice) {
        if (n.readAt == null) viewModelScope.launch {
            runCatching { repo.read(auth(), n.id) }.onSuccess {
                state.update { s -> s.copy(unread = (s.unread - 1).coerceAtLeast(0),
                    notices = s.notices.map { if (it.id == n.id) it.copy(readAt = "read") else it }) }
            }
        }
        open(n.requestId)
    }

    fun startAccept() = state.update { it.copy(acceptForm = SkillSessionForm(teach = true, mode = "IN_PERSON"), acceptError = null) }
    fun editAccept(form: SkillSessionForm) = state.update { it.copy(acceptForm = form, acceptError = null) }

    fun accept() {
        val id = state.value.detail?.request?.id ?: return
        val form = state.value.acceptForm ?: return
        skillSessionTimes(form, java.time.ZonedDateTime.now()).exceptionOrNull()?.let { p -> state.update { it.copy(acceptError = p.message) }; return }
        work("Accepted. Every student who joined was told the time.") {
            val detail = repo.accept(auth(), id, form)
            state.update { it.copy(detail = detail, acceptForm = null) }
        }
    }

    fun startReason(cancel: Boolean) = state.update { it.copy(reason = FacultyReasonDraft(cancel)) }
    fun editReason(draft: FacultyReasonDraft) = state.update { it.copy(reason = draft.copy(reason = draft.reason.take(300))) }

    fun finishReason() {
        val id = state.value.detail?.request?.id ?: return
        val draft = state.value.reason ?: return
        work(if (draft.cancel) "Session cancelled. The students were told." else "Declined. The students were told.") {
            val detail = if (draft.cancel) repo.cancel(auth(), id, draft.reason) else repo.decline(auth(), id, draft.reason)
            state.update { it.copy(detail = detail, reason = null) }
        }
    }

    fun complete() {
        val id = state.value.detail?.request?.id ?: return
        work("Marked as completed.") { val d = repo.complete(auth(), id); state.update { it.copy(detail = d) } }
    }
}

/** Faculty: students asking to learn a topic from them. Only requests sent to this faculty member appear. */
@Composable
fun FacultyLearningScreen(repo: FacultyLearningRepository, auth: () -> String, lost: (Int) -> Unit, exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<FacultyLearningViewModel>(viewModelStoreOwner = owner) { FacultyLearningViewModel(repo, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    BackHandler { if (!ui.busy && !vm.back()) exit() }

    ui.reason?.let { draft ->
        FacultyReasonDialog(draft, ui.busy, edit = { vm.editReason(it) }, done = vm::finishReason, dismiss = { vm.back() })
    }

    // The list, the notifications and each opened request keep their own scroll position.
    CampusPage(
        title = when (ui.page) {
            FacultyLearningPage.LIST -> "Students want to learn"
            FacultyLearningPage.NOTICES -> "Notifications"
            FacultyLearningPage.DETAIL -> ui.detail?.request?.topic ?: "Request"
        },
        subtitle = when (ui.page) {
            FacultyLearningPage.LIST -> "Topics students asked you to teach"
            FacultyLearningPage.NOTICES -> null
            FacultyLearningPage.DETAIL -> ui.detail?.request?.let { facultyPlural(it.students, "student") + " want this" }
        },
        onBack = { if (!vm.back()) exit() },
        backEnabled = !ui.busy,
        busy = ui.busy,
        stateKey = when (ui.page) {
            FacultyLearningPage.LIST -> "list"
            FacultyLearningPage.NOTICES -> "notices"
            FacultyLearningPage.DETAIL -> "detail-${ui.detail?.request?.id}"
        },
        actions = {
            if (ui.page == FacultyLearningPage.LIST) IconButton(vm::openNotices, enabled = !ui.busy) {
                BadgedBox(badge = { if (ui.unread > 0) Badge { Text("${ui.unread}") } }) {
                    Icon(CampusIcons.Bell, "Notifications")
                }
            }
        },
    ) {
        // Messages keep their dismiss action, as before.
        ui.error?.let { text ->
            item(key = "fl-error") { MessageBanner(text, MessageKind.Error, actionLabel = "Dismiss", onAction = vm::clearMessages) }
        }
        ui.notice?.let { text ->
            item(key = "fl-notice") { MessageBanner(text, MessageKind.Success, actionLabel = "Dismiss", onAction = vm::clearMessages) }
        }
        when (ui.page) {
            FacultyLearningPage.LIST -> {
                if (ui.loaded && ui.requests.isEmpty()) item(key = "empty") {
                    EmptyState("No requests yet", "When students ask you to teach a topic, it appears here with how many want it.")
                }
                items(ui.requests, key = { "r" + it.id }) { r -> FacultyCard(r, !ui.busy) { vm.open(r.id) } }
            }
            FacultyLearningPage.NOTICES -> {
                if (ui.notices.isEmpty()) item(key = "empty") {
                    EmptyState("No notifications", "New requests, students joining and reminders appear here.")
                }
                items(ui.notices, key = { "n" + it.id }) { n ->
                    ListRow(n.title,
                        subtitle = n.body,
                        meta = chatTime(n.createdAt),
                        icon = facultyNoticeIcon(n.kind),
                        chip = { if (n.readAt == null) StatusChip("New", Tone.Accent) },
                        onClick = { if (!ui.busy) vm.openNotice(n) })
                }
            }
            FacultyLearningPage.DETAIL -> ui.detail?.let { d ->
                val r = d.request
                item(key = "detail-summary") {
                    InfoCard {
                        StatusChip(facultyStatusLabel(r.status), facultyLearningTone(r.status))
                        InfoRow("Students", facultyPlural(r.students, "student") + " want this")
                        InfoRow("Asked by", r.createdBy + " · " + chatTime(r.createdAt))
                        if (r.details.isNotBlank()) InfoRow("Details", r.details)
                        if (r.startsAt != null && r.endsAt != null && r.status != "OPEN") {
                            InfoRow("When", skillSessionTime(r.startsAt, r.endsAt))
                            facultyWhere(r.mode, r.place)?.let { InfoRow("Where", it) }
                        }
                        r.reason?.let { InfoRow("Reason", it) }
                    }
                }
                if (r.status == "OPEN") item(key = "detail-open") {
                    val form = ui.acceptForm
                    if (form == null) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Accept and set a time", vm::startAccept, enabled = !ui.busy, icon = CampusIcons.Calendar)
                        SecondaryButton("Decline", { vm.startReason(false) }, enabled = !ui.busy, danger = true, icon = CampusIcons.Close)
                    } else TimeForm("When will you teach it?", form, ui.acceptError, ui.busy, "Accept", vm::editAccept, vm::accept)
                }
                if (r.status == "ACCEPTED") item(key = "detail-accepted") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (r.canComplete) PrimaryButton("Mark completed", vm::complete, enabled = !ui.busy, icon = CampusIcons.Check)
                        SecondaryButton("Cancel session", { vm.startReason(true) }, enabled = !ui.busy, danger = true, icon = CampusIcons.Close)
                    }
                }
                item(key = "students-header") { SectionHeader("Students (${d.students.size})") }
                items(d.students, key = { "s" + it.id }) { s -> ListRow(s.name, subtitle = s.classLabel, icon = CampusIcons.Users) }
            }
        }
    }
}

private fun facultyLearningTone(status: String) = when (status) {
    "OPEN" -> Tone.Warning
    "ACCEPTED", "COMPLETED" -> Tone.Positive
    else -> Tone.Danger
}

private fun facultyNoticeIcon(kind: String): ImageVector = when (kind) {
    "NEW" -> CampusIcons.Book
    "JOINED" -> CampusIcons.Users
    "REMINDER" -> CampusIcons.Clock
    else -> CampusIcons.Close
}

private fun facultyPlural(n: Long, one: String) = "$n " + if (n == 1L) one else one + "s"

/** "Online", "In person · Room 12", or just the place when the mode is not known. */
private fun facultyWhere(mode: String?, place: String?): String? {
    val p = place?.takeIf { it.isNotBlank() }
    return when (mode) {
        null -> p
        "ONLINE" -> "Online" + (p?.let { " · $it" } ?: "")
        else -> "In person" + (p?.let { " · $it" } ?: "")
    }
}

/** Decline a request, or cancel an accepted session, with an optional reason for the students. */
@Composable
private fun FacultyReasonDialog(draft: FacultyReasonDraft, busy: Boolean, edit: (FacultyReasonDraft) -> Unit, done: () -> Unit,
                                dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(if (draft.cancel) "Cancel this session?" else "Decline this request?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (draft.cancel) "Every student who joined will be told." else "The students who asked will be told.")
                OutlinedTextField(draft.reason, { edit(draft.copy(reason = it)) }, Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text("Reason for the students (optional)") })
            }
        },
        confirmButton = {
            TextButton(done, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(if (draft.cancel) "Cancel session" else "Decline")
            }
        },
        dismissButton = { TextButton(dismiss) { Text("Back") } })
}

/** Faculty dashboard card: how many student requests are waiting. */
@Composable
fun FacultyLearningCard(repo: FacultyLearningRepository, auth: () -> String, open: () -> Unit) {
    var waiting by remember { mutableStateOf<Int?>(null) }
    var unread by remember { mutableStateOf(0L) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { waiting = null }
    LaunchedEffect(waiting) {
        if (waiting == null) {
            // Both are fetched before either is shown: setting [waiting] restarts this effect.
            val count = runCatching { repo.list(auth()) }.map { list -> list.count { it.status == "OPEN" } }.getOrDefault(-1)
            unread = runCatching { repo.unread(auth()) }.getOrDefault(0)
            waiting = count
        }
    }
    Card(onClick = open, modifier = Modifier.fillMaxWidth().appear(4), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Icon(CampusIcons.Book, null, Modifier.padding(8.dp).size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Students want to learn", style = MaterialTheme.typography.titleMedium)
                Text(when (val n = waiting) {
                    null -> "Loading…"; -1 -> "Not available right now"; 0 -> "No requests waiting for you"
                    1 -> "1 request is waiting for your reply"; else -> "$n requests are waiting for your reply"
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (unread > 0) StatusChip("$unread new", Tone.Accent)
            TextButton(open) { Text("Open") }
        }
    }
}
