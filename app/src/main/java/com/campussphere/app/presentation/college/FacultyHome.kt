package com.campussphere.app.presentation.college

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.FacultyAssignment
import com.campussphere.app.data.remote.api.FacultyHomeworkSummary
import com.campussphere.app.data.remote.api.TimetableDay
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.student.Section
import com.campussphere.app.ui.brand.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Student learning requests for the faculty member: how many wait for a reply, and how many notices are new. */
data class LearningCounts(val waiting: Int, val unread: Long)

data class FacultyHomeUi(
    val classes: Section<List<FacultyAssignment>> = Section(),
    val homework: Section<FacultyHomeworkSummary> = Section(),
    val learning: Section<LearningCounts> = Section(),
    val today: Section<TimetableDay> = Section(),
)

private fun statusOf(e: Throwable): Int? = when (e) {
    is AcademicCatalogFailure -> e.status
    is HomeworkFailure -> e.status
    is SkillFailure -> e.status
    is TimetableFailure -> e.status
    else -> null
}

/** Loads each dashboard section on its own, keeping the last value while it refreshes (so the page keeps its size). */
class FacultyHomeViewModel(
    private val faculty: FacultyRepository, private val homework: HomeworkRepository,
    private val learning: FacultyLearningRepository, private val timetable: TimetableRepository,
    private val auth: () -> String, private val lost: (Int) -> Unit,
) : ViewModel() {
    private val state = MutableStateFlow(FacultyHomeUi())
    val ui = state.asStateFlow()

    private fun <T> section(get: (FacultyHomeUi) -> Section<T>, set: FacultyHomeUi.(Section<T>) -> FacultyHomeUi,
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
                if (statusOf(e) == 401) lost(401)
                state.update { it.set(Section(error = unavailable)) }
            }
        }
    }

    fun load() {
        section({ it.classes }, { copy(classes = it) }, "Your classes could not be loaded.") { faculty.mine(auth()) }
        section({ it.homework }, { copy(homework = it) }, "Assignment numbers are not available right now.") {
            homework.facultySummary(auth())
        }
        section({ it.learning }, { copy(learning = it) }, "Student requests are not available right now.") {
            LearningCounts(learning.list(auth()).count { it.status == "OPEN" }, learning.unread(auth()))
        }
        section({ it.today }, { copy(today = it) }, "Today's timetable is not available right now.") {
            timetable.day(auth(), "faculty", null)
        }
    }
}

/** Where a faculty dashboard shortcut leads. */
enum class FacultyArea { NOTIFICATIONS, TIMETABLE, ATTENDANCE, HOMEWORK, CREATE_HOMEWORK, MATERIALS, RESOURCES, ANNOUNCEMENTS, LEARNING }

@Composable
fun FacultyHomeScreen(vmFactory: () -> FacultyHomeViewModel, unreadNotifications: Int, open: (FacultyArea) -> Unit,
                      onLogout: () -> Unit, keep: ViewModelStoreOwner? = null) {
    // With [keep] (owned by the caller) the data stays in memory while another page is open, so Back returns to the
    // same place on a page that is already full size; it then refreshes.
    val local = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(local) { onDispose { local.viewModelStore.clear() } }
    val vm = viewModel<FacultyHomeViewModel>(viewModelStoreOwner = keep ?: local) { vmFactory() }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "hello") {
            BrandHero(Modifier.appear(0)) {
                CampusBrandRow(logoSize = 30.dp, fontSize = 15.sp, onDark = true)
                Spacer(Modifier.height(8.dp))
                Text("Faculty dashboard", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Teach, mark and share with your classes.", style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f))
            }
        }

        item(key = "numbers") {
            Row(Modifier.appear(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val work = ui.homework.value
                NumberTile("To mark", value = work?.toMark?.toString() ?: if (ui.homework.error != null) "–" else "…",
                    caption = work?.let { "waiting to be marked" } ?: ui.homework.error?.let { "Not available" } ?: "Loading",
                    tone = if ((work?.toMark ?: 0) > 0) Tone.Warning else Tone.Accent, modifier = Modifier.weight(1f)) {
                    open(FacultyArea.HOMEWORK)
                }
                val asks = ui.learning.value
                NumberTile("Student requests", value = asks?.waiting?.toString() ?: if (ui.learning.error != null) "–" else "…",
                    caption = asks?.let { "waiting for your reply" } ?: ui.learning.error?.let { "Not available" } ?: "Loading",
                    tone = if ((asks?.waiting ?: 0) > 0) Tone.Warning else Tone.Accent, modifier = Modifier.weight(1f)) {
                    open(FacultyArea.LEARNING)
                }
            }
        }

        item(key = "today") {
            HomeCard("Today", modifier = Modifier.appear(2), action = "Week", onAction = { open(FacultyArea.TIMETABLE) }) {
                val day = ui.today.value
                val lessons = day?.schedule?.slots.orEmpty().filter { it.kind == "LESSON" }
                when {
                    day == null -> if (ui.today.error != null) Muted("Today's timetable isn't available right now.")
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                    lessons.isEmpty() -> Muted("No lessons today.")
                    else -> lessons.forEach { slot ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${slot.start}–${slot.end}", Modifier.width(96.dp), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                            Text(listOfNotNull(slot.subject, slot.classroomCode?.let { "room $it" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        item(key = "shortcuts") {
            Column(Modifier.appear(3), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("Everything")
                ShortcutGrid(listOf(
                    Shortcut("Notifications", CampusIcons.Bell, unreadNotifications.toLong()) { open(FacultyArea.NOTIFICATIONS) },
                    Shortcut("Timetable", CampusIcons.Calendar, 0) { open(FacultyArea.TIMETABLE) },
                    Shortcut("Attendance", CampusIcons.Checklist, 0) { open(FacultyArea.ATTENDANCE) },
                    Shortcut("Assignments", CampusIcons.Homework, ui.homework.value?.toMark ?: 0) { open(FacultyArea.HOMEWORK) },
                    Shortcut("Materials", CampusIcons.Books, 0) { open(FacultyArea.MATERIALS) },
                    Shortcut("Syllabus", CampusIcons.Book, 0) { open(FacultyArea.RESOURCES) },
                    Shortcut("Notices", CampusIcons.Megaphone, 0) { open(FacultyArea.ANNOUNCEMENTS) },
                    Shortcut("Requests", CampusIcons.Handshake, ui.learning.value?.let { it.waiting + it.unread } ?: 0) { open(FacultyArea.LEARNING) },
                ))
                SecondaryButton("Create an assignment", { open(FacultyArea.CREATE_HOMEWORK) }, icon = CampusIcons.Plus)
            }
        }

        item(key = "classes-title") { SectionHeader("My subjects and classes") }
        val classes = ui.classes.value
        when {
            classes == null -> item(key = "classes-status") {
                if (ui.classes.error != null) MessageBanner(ui.classes.error!!, MessageKind.Error, actionLabel = "Retry", onAction = vm::load)
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            classes.isEmpty() -> item(key = "classes-empty") { Muted("No classes have been assigned to you yet.") }
            else -> items(classes, key = { "class-${it.id}" }) {
                ListRow("${it.subjectCode} · ${it.subject}", subtitle = "${it.program} · ${it.semester} · ${it.division}",
                    meta = "${it.department} · ${it.academicYear}", icon = CampusIcons.Book)
            }
        }

        item(key = "footer") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(vm::load) {
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
