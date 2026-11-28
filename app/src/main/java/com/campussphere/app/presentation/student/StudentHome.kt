package com.campussphere.app.presentation.student

import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.campussphere.app.ui.brand.IconBadge
import com.campussphere.app.ui.brand.appear
import com.campussphere.app.ui.brand.*
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
    val skills: Section<SkillSummary> = Section(),
    val resources: Section<List<ResourceSubject>> = Section(),
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
    is SkillFailure -> e.status
    is ResourceFailure -> e.status
    else -> null
}

class StudentHomeViewModel(
    private val chats: ChatRepository, private val attendance: AttendanceRepository,
    private val timetable: TimetableRepository, private val homework: HomeworkRepository,
    private val materials: MaterialRepository, private val announcements: AnnouncementRepository,
    private val skills: SkillRepository, private val auth: () -> String, private val lost: (Int) -> Unit,
    private val resources: ResourceRepository? = null,
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
        section({ it.skills }, { copy(skills = it) }, "Skill Exchange is not available right now.") { skills.summary(auth()) }
        resources?.let { repo ->
            section({ it.resources }, { copy(resources = it) }, "Syllabus and question papers are not available right now.") {
                repo.subjects(auth())
            }
        }
    }
}

/** Where a dashboard button leads. */
enum class StudentArea { TIMETABLE, ATTENDANCE, MATERIALS, ANNOUNCEMENTS, CHATS, SKILLS, RESOURCES, HOMEWORK }

/** Badge numbers for the shortcut grid, worked out from what has loaded (0 when unknown). */
internal data class StudentBadges(val assignments: Long, val chats: Long, val skills: Long, val notices: Long)

internal fun studentBadges(ui: StudentHomeUi) = StudentBadges(
    assignments = ui.homework.value?.let { it.pending + it.unreadNotices } ?: 0,
    chats = ui.chats.value?.sumOf { it.unread } ?: 0,
    skills = ui.skills.value?.let { it.incomingRequests + it.unreadNotices } ?: 0,
    notices = ui.announcements.value?.count { it.read == false }?.toLong() ?: 0,
)

@Composable
fun StudentHomeScreen(vmFactory: () -> StudentHomeViewModel, open: (StudentArea, Long?) -> Unit,
                      onRefreshAccount: () -> Unit, onLogout: () -> Unit, keep: ViewModelStoreOwner? = null) {
    // With [keep] (owned by the caller), the dashboard's data stays in memory while another page is open. Coming
    // back, the cards are already full size, so the list returns to exactly where it was and then refreshes.
    val local = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(local) { onDispose { local.viewModelStore.clear() } }
    val vm = viewModel<StudentHomeViewModel>(viewModelStoreOwner = keep ?: local) { vmFactory() }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    val now = ZonedDateTime.now()
    val badges = studentBadges(ui)

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "hello") { Greeting(ui.profile) }

        item(key = "numbers") {
            Row(Modifier.appear(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val attendance = ui.attendance.value?.overall
                NumberTile("Attendance",
                    value = attendance?.percentage?.let { "${it.stripTrailingZeros().toPlainString()}%" } ?: if (ui.attendance.error != null) "–" else "…",
                    caption = attendance?.let { "${it.attended} of ${it.eligible} lectures" } ?: ui.attendance.error?.let { "Not available" } ?: "Loading",
                    tone = when (attendance?.severity) {
                        AttendanceSeverity.CRITICAL -> Tone.Danger
                        AttendanceSeverity.WARNING -> Tone.Warning
                        else -> Tone.Accent
                    }, modifier = Modifier.weight(1f)) { open(StudentArea.ATTENDANCE, null) }
                val work = ui.homework.value
                NumberTile("Assignments",
                    value = work?.pending?.toString() ?: if (ui.homework.error != null) "–" else "…",
                    caption = work?.let { "pending · ${it.dueThisWeek} due this week" } ?: ui.homework.error?.let { "Not available" } ?: "Loading",
                    tone = if ((work?.dueThisWeek ?: 0) > 0) Tone.Warning else Tone.Accent,
                    modifier = Modifier.weight(1f)) { open(StudentArea.HOMEWORK, null) }
            }
        }

        item(key = "today") {
            HomeCard("Today", action = "Week", onAction = { open(StudentArea.TIMETABLE, null) }, modifier = Modifier.appear(2)) {
                val day = ui.today.value
                when {
                    day == null -> SectionStatus(ui.today, "Today's timetable isn't available right now.")
                    !day.schedule.enrolled -> Muted("You're not enrolled in a class yet.")
                    day.schedule.slots.isEmpty() -> Muted("No classes today.")
                    else -> day.schedule.slots.forEach { slot ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${slot.start}–${slot.end}", Modifier.width(96.dp), style = MaterialTheme.typography.labelLarge,
                                color = if (slot.kind == "LESSON") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Text(if (slot.kind == "LESSON") listOfNotNull(slot.subject, slot.classroomCode?.let { "room $it" }).joinToString(" · ")
                                else slot.label ?: "Break", style = MaterialTheme.typography.bodyMedium,
                                color = if (slot.kind == "LESSON") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        val activities = upcomingActivities(ui.today.value, ui.homework.value, ui.announcements.value, now).take(4)
        if (activities.isNotEmpty()) item(key = "upcoming") {
            HomeCard("Coming up", modifier = Modifier.appear(3)) {
                activities.forEach {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(it.kind, when (it.kind) { "Deadline" -> Tone.Warning; "Lesson" -> Tone.Accent; else -> Tone.Neutral })
                        Column(Modifier.weight(1f)) {
                            Text(it.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(it.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        item(key = "shortcuts") {
            Column(Modifier.appear(4), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("Everything")
                ShortcutGrid(listOf(
                    Shortcut("Timetable", CampusIcons.Calendar, 0) { open(StudentArea.TIMETABLE, null) },
                    Shortcut("Attendance", CampusIcons.Checklist, 0) { open(StudentArea.ATTENDANCE, null) },
                    Shortcut("Assignments", CampusIcons.Homework, badges.assignments) { open(StudentArea.HOMEWORK, null) },
                    Shortcut("Materials", CampusIcons.Books, 0) { open(StudentArea.MATERIALS, null) },
                    Shortcut("Syllabus", CampusIcons.Book, 0) { open(StudentArea.RESOURCES, null) },
                    Shortcut("Chats", CampusIcons.Chat, badges.chats) { open(StudentArea.CHATS, null) },
                    Shortcut("Skill Exchange", CampusIcons.Handshake, badges.skills) { open(StudentArea.SKILLS, null) },
                    Shortcut("Notices", CampusIcons.Megaphone, badges.notices) { open(StudentArea.ANNOUNCEMENTS, null) },
                ))
            }
        }

        item(key = "footer") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ vm.load(); onRefreshAccount() }) {
                    Icon(CampusIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh")
                }
                TextButton(onLogout, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(CampusIcons.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sign out")
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun <T> SectionStatus(section: Section<T>, unavailable: String) {
    if (section.error != null) Muted(unavailable) else LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun Greeting(profile: Section<StudentMe>) {
    com.campussphere.app.ui.brand.BrandHero(Modifier.appear(0)) {
        val me = profile.value
        com.campussphere.app.ui.brand.CampusBrandRow(logoSize = 30.dp, fontSize = 15.sp, onDark = true)
        Spacer(Modifier.height(8.dp))
        Text(if (me != null) "Hello, ${me.name.substringBefore(' ')}" else "Welcome back",
            style = MaterialTheme.typography.headlineSmall, color = Color.White)
        me?.let {
            Text(it.college, style = MaterialTheme.typography.titleSmall, color = com.campussphere.app.ui.theme.Brand.AmberBright)
            Text(if (it.division != null) "${it.program} · ${it.semester} · ${it.division}" else "Not enrolled in a class yet",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (profile.loading && me == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color.White)
        profile.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White) }
    }
}
