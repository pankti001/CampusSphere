package com.campussphere.app.presentation.skills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.campussphere.app.ui.brand.EmptyState
import com.campussphere.app.ui.brand.IconBadge
import com.campussphere.app.ui.brand.appear
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
        AlertDialog(onDismissRequest = { vm.back() },
            title = { Text(if (draft.cancel) "Cancel this session?" else "Decline this request?") },
            text = {
                OutlinedTextField(draft.reason, { vm.editReason(draft.copy(reason = it)) }, Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text("Reason for the students (optional)") })
            },
            confirmButton = { TextButton(vm::finishReason, enabled = !ui.busy) { Text(if (draft.cancel) "Cancel session" else "Decline") } },
            dismissButton = { TextButton({ vm.back() }) { Text("Back") } })
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (!vm.back()) exit() }, enabled = !ui.busy) { Text("Back") }
            Text(when (ui.page) { FacultyLearningPage.LIST -> "Students want to learn"; FacultyLearningPage.NOTICES -> "Notifications"
                FacultyLearningPage.DETAIL -> ui.detail?.request?.topic ?: "Request" },
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            if (ui.page == FacultyLearningPage.LIST) BadgedBox(badge = { if (ui.unread > 0) Badge { Text("${ui.unread}") } }) {
                TextButton(vm::openNotices, enabled = !ui.busy) { Text("🔔") }
            }
        }
        if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        listOfNotNull(ui.error?.let { it to true }, ui.notice?.let { it to false }).forEach { (text, bad) ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(
                containerColor = if (bad) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text, Modifier.weight(1f)); TextButton(vm::clearMessages) { Text("OK") }
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (ui.page) {
                FacultyLearningPage.LIST -> {
                    if (ui.loaded && ui.requests.isEmpty()) item {
                        EmptyState("No requests yet", "When students ask you to teach a topic, it appears here with how many want it.")
                    }
                    items(ui.requests, key = { it.id }) { r -> FacultyCard(r, !ui.busy) { vm.open(r.id) } }
                }
                FacultyLearningPage.NOTICES -> {
                    if (ui.notices.isEmpty()) item { EmptyState("No notifications", "New requests, students joining and reminders appear here.") }
                    items(ui.notices, key = { it.id }) { n ->
                        Card(onClick = { vm.openNotice(n) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().appear(),
                            colors = CardDefaults.cardColors(containerColor = if (n.readAt == null) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLowest)) {
                            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                IconBadge(when (n.kind) { "NEW" -> "📨"; "JOINED" -> "🙋"; "REMINDER" -> "⏰"; else -> "✖" }, SkillTint)
                                Column(Modifier.weight(1f)) {
                                    Text(n.title, fontWeight = if (n.readAt == null) FontWeight.SemiBold else FontWeight.Normal)
                                    Text(n.body, style = MaterialTheme.typography.bodyMedium)
                                    Text(chatTime(n.createdAt), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                FacultyLearningPage.DETAIL -> ui.detail?.let { d ->
                    val r = d.request
                    item {
                        Card(Modifier.fillMaxWidth().appear(0), colors = CardDefaults.cardColors(containerColor = SkillTint)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(r.topic, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                Text("${r.students} student" + (if (r.students == 1L) "" else "s") + " want this · asked by ${r.createdBy}",
                                    color = Color(0xFF334155))
                                Text(facultyStatusLabel(r.status), fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                if (r.details.isNotBlank()) Text(r.details, color = Color(0xFF0F172A))
                                if (r.startsAt != null && r.endsAt != null && r.status != "OPEN") Text("🕒 " + skillSessionTime(r.startsAt, r.endsAt) +
                                    (r.place?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), color = Color(0xFF0F172A))
                                r.reason?.let { Text("Reason: $it", color = Color(0xFF334155)) }
                            }
                        }
                    }
                    if (r.status == "OPEN") item {
                        val form = ui.acceptForm
                        if (form == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(vm::startAccept, enabled = !ui.busy) { Text("Accept and set a time") }
                            OutlinedButton({ vm.startReason(false) }, enabled = !ui.busy) { Text("Decline") }
                        } else TimeForm("When will you teach it?", form, ui.acceptError, ui.busy, "Accept", vm::editAccept, vm::accept)
                    }
                    if (r.status == "ACCEPTED") item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (r.canComplete) Button(vm::complete, enabled = !ui.busy) { Text("Mark completed") }
                            TextButton({ vm.startReason(true) }, enabled = !ui.busy) { Text("Cancel session", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    item { Text("Students (${d.students.size})", style = MaterialTheme.typography.titleMedium) }
                    items(d.students, key = { "s" + it.id }) { s ->
                        Column {
                            Text(s.name)
                            s.classLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }
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
            IconBadge("🧑‍🏫", SkillTint)
            Column(Modifier.weight(1f)) {
                Text("Students want to learn", style = MaterialTheme.typography.titleMedium)
                Text(when (val n = waiting) {
                    null -> "Loading…"; -1 -> "Not available right now."; 0 -> "No requests waiting for you."
                    1 -> "1 request is waiting for your reply."; else -> "$n requests are waiting for your reply."
                } + if (unread > 0) " · $unread new" else "", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(open) { Text("Open") }
        }
    }
}
