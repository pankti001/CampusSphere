package com.campussphere.app.presentation.student

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.homework.homeworkRemaining
import com.campussphere.app.presentation.homework.homeworkTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** One dashboard section: its data, or why it could not be loaded. Sections load independently. */
data class Section<T>(val value: T? = null, val error: String? = null, val loading: Boolean = false)

data class StudentHomeUi(
    val profile: Section<StudentMe> = Section(),
    val attendance: Section<AttendanceStudent> = Section(),
    val today: Section<TimetableDay> = Section(),
    val homework: Section<StudentHomeworkSummary> = Section(),
    val materials: Section<List<MaterialItem>> = Section(),
    val announcements: Section<List<AnnouncementItem>> = Section(),
    val chats: Section<List<ChatConversation>> = Section(),
)

/** Something coming up, for the "Upcoming" list: a lesson later today, a deadline or an event notice. */
data class Activity(val kind: String, val title: String, val detail: String, val sortKey: String)

/** Lessons still to come today, the next assignment deadlines and current event or exam notices, soonest first. */
fun upcomingActivities(today: TimetableDay?, homework: StudentHomeworkSummary?, notices: List<AnnouncementItem>?,
                       now: ZonedDateTime): List<Activity> {
    val list = mutableListOf<Activity>()
    today?.schedule?.slots.orEmpty().filter { it.kind == "LESSON" }.forEach { slot ->
        val start = runCatching { LocalTime.parse(slot.start) }.getOrNull() ?: return@forEach
        if (start.isAfter(now.toLocalTime())) list += Activity("Lesson",
            listOfNotNull(slot.subjectCode, slot.subject).joinToString(" · ").ifEmpty { slot.label ?: "Lesson" },
            "Today ${slot.start}–${slot.end}" + (slot.classroomCode?.let { " · room $it" } ?: ""),
            "${today?.date}T${slot.start}")
    }
    homework?.upcoming.orEmpty().forEach {
        list += Activity("Deadline", it.title, "${it.subjectCode} · ${homeworkRemaining(it.dueAt, now)}", it.dueAt)
    }
    notices.orEmpty().filter { it.category == "EVENTS" || it.category == "EXAMINATION" }.forEach {
        list += Activity(if (it.category == "EVENTS") "Event" else "Exam notice", it.title,
            "Posted ${homeworkTime(it.publishAt ?: it.createdAt)}", it.publishAt ?: it.createdAt)
    }
    return list.sortedBy { runCatching { OffsetDateTime.parse(it.sortKey).toInstant().toString() }.getOrDefault(it.sortKey) }.take(6)
}

/** The HTTP status inside any repository failure, so a 401 anywhere signs the student out consistently. */
internal fun failureStatus(e: Throwable): Int? = when (e) {
    is ChatFailure -> e.status
    is HomeworkFailure -> e.status
    is AttendanceFailure -> e.status
    is TimetableFailure -> e.status
    is AnnouncementFailure -> e.status
    is MaterialFailure -> e.status
    else -> null
}

class StudentHomeViewModel(
    private val chats: ChatRepository, private val attendance: AttendanceRepository,
    private val timetable: TimetableRepository, private val homework: HomeworkRepository,
    private val materials: MaterialRepository, private val announcements: AnnouncementRepository,
    private val auth: () -> String, private val lost: (Int) -> Unit,
) : ViewModel() {
    private val state = MutableStateFlow(StudentHomeUi())
    val ui = state.asStateFlow()

    private fun <T> section(get: (StudentHomeUi) -> Section<T>, set: StudentHomeUi.(Section<T>) -> StudentHomeUi,
                            unavailable: String, load: suspend () -> T) {
        if (get(state.value).loading) return
        state.update { it.set(get(it).copy(loading = true, error = null)) }
        viewModelScope.launch {
            try {
                val value = load()
                state.update { it.set(Section(value)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failureStatus(e) == 401) lost(401)
                // A stale value is dropped so old numbers are never shown as current.
                state.update { it.set(Section(error = unavailable)) }
            }
        }
    }

    fun load() {
        section({ it.profile }, { copy(profile = it) }, "Your profile could not be loaded.") { chats.me(auth()) }
        section({ it.attendance }, { copy(attendance = it) }, "Attendance is not available right now.") {
            attendance.student(auth(), emptyMap())
        }
        section({ it.today }, { copy(today = it) }, "Today's timetable is not available right now.") {
            timetable.day(auth(), "student", null)
        }
        section({ it.homework }, { copy(homework = it) }, "Assignments are not available right now.") {
            homework.studentSummary(auth())
        }
        section({ it.materials }, { copy(materials = it) }, "Study materials are not available right now.") {
            materials.list(auth(), "student").sortedByDescending { it.uploadedAt }.take(3)
        }
        section({ it.announcements }, { copy(announcements = it) }, "Announcements are not available right now.") {
            announcements.list(auth(), "student", null, null, null, null, null, 0)
        }
        section({ it.chats }, { copy(chats = it) }, "Chats are not available right now.") { chats.conversations(auth()) }
    }
}

/** Where a dashboard button leads. */
enum class StudentArea { TIMETABLE, ATTENDANCE, MATERIALS, ANNOUNCEMENTS, CHATS }

@Composable
fun StudentHomeScreen(vmFactory: () -> StudentHomeViewModel, homeworkCard: @Composable () -> Unit,
                      open: (StudentArea, Long?) -> Unit, onRefreshAccount: () -> Unit, onLogout: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<StudentHomeViewModel>(viewModelStoreOwner = owner) { vmFactory() }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    val now = ZonedDateTime.now()

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ProfileCard(ui.profile) }
        item {
            Panel("✅ Attendance", ui.attendance, "Open", { open(StudentArea.ATTENDANCE, null) }) { a ->
                val overall = a.overall
                Text(overall.percentage?.let { "${it.stripTrailingZeros().toPlainString()}% overall" } ?: "No sessions yet",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = when (overall.severity) {
                        AttendanceSeverity.CRITICAL -> MaterialTheme.colorScheme.error
                        AttendanceSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
                        AttendanceSeverity.NORMAL -> MaterialTheme.colorScheme.primary
                    })
                Text("${overall.attended} of ${overall.eligible} counted lectures · ${overall.absent} absent")
                a.subjects.filter { it.summary.percentage != null }.sortedBy { it.summary.percentage }.take(2).forEach {
                    Text("${it.subject}: ${it.summary.percentage!!.stripTrailingZeros().toPlainString()}%",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Panel("🗓 Today", ui.today, "Week", { open(StudentArea.TIMETABLE, null) }) { day ->
                val lessons = day.schedule.slots
                when {
                    !day.schedule.enrolled -> Text("You are not enrolled in a class yet.")
                    lessons.isEmpty() -> Text("No classes today.")
                    else -> lessons.forEach { slot ->
                        Text("${slot.start}–${slot.end}  " + if (slot.kind == "LESSON")
                            listOfNotNull(slot.subject, slot.classroomCode?.let { "room $it" }).joinToString(" · ")
                        else slot.label ?: "Break", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item { homeworkCard() }
        item {
            val activities = upcomingActivities(ui.today.value, ui.homework.value, ui.announcements.value, now)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("⏭ Upcoming", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (activities.isEmpty()) Text("Nothing coming up.", style = MaterialTheme.typography.bodySmall)
                    activities.forEach {
                        Text("${it.kind}: ${it.title}", fontWeight = FontWeight.Medium)
                        Text(it.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Panel("💬 Groups and chats", ui.chats, "All chats", { open(StudentArea.CHATS, null) }) { list ->
                val unread = list.sumOf { it.unread }
                Text(if (unread > 0) "$unread unread message" + (if (unread == 1L) "" else "s") else "No unread messages",
                    color = if (unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                list.filter { it.kind == "CLASS" }.forEach { group ->
                    OutlinedButton({ open(StudentArea.CHATS, group.id) }) {
                        Text("Class group: ${group.name}" + if (group.unread > 0) " (${group.unread})" else "",
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                list.filter { it.kind != "CLASS" && it.unread > 0 }.take(3).forEach { c ->
                    TextButton({ open(StudentArea.CHATS, c.id) }) { Text("${c.name ?: "Chat"} (${c.unread})") }
                }
            }
        }
        item {
            Panel("📚 Study materials", ui.materials, "Open", { open(StudentArea.MATERIALS, null) }) { list ->
                if (list.isEmpty()) Text("Nothing shared yet.")
                list.forEach { Text("• ${it.title} (${it.subject})", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        item {
            Panel("📣 Announcements", ui.announcements, "Open", { open(StudentArea.ANNOUNCEMENTS, null) }) { list ->
                if (list.isEmpty()) Text("No announcements.")
                list.take(3).forEach {
                    Text("• ${it.title}" + if (it.read == false) "  (new)" else "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (it.read == false) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ vm.load(); onRefreshAccount() }) { Text("Refresh") }
                TextButton(onLogout) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: Section<StudentMe>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val me = profile.value
            Text(if (me != null) "Hello, ${me.name.substringBefore(' ')}" else "Student dashboard",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            me?.let {
                Text(it.college, style = MaterialTheme.typography.titleSmall)
                Text(if (it.division != null) "${it.program} · ${it.academicYear} · ${it.semester} · ${it.division}"
                    else "Not enrolled in a class yet", style = MaterialTheme.typography.bodyMedium)
                Text(it.email, style = MaterialTheme.typography.bodySmall)
            }
            if (profile.loading && me == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            profile.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun <T> Panel(title: String, section: Section<T>, action: String, onAction: () -> Unit,
                      content: @Composable ColumnScope.(T) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                TextButton(onAction) { Text(action) }
            }
            val value = section.value
            when {
                value != null -> content(value)
                section.error != null -> Text(section.error, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}
