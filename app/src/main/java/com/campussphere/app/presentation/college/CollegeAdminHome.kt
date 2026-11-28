package com.campussphere.app.presentation.college

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campussphere.app.ui.brand.*
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.CollegeCounts
import com.campussphere.app.data.remote.api.CollegeProfile
import com.campussphere.app.data.repository.CollegeAdminFailure
import com.campussphere.app.data.repository.CollegeAdminRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a home-screen tile or setup step leads. */
enum class AdminArea { DEPARTMENTS, YEARS, FACULTY, STUDENTS, ACCOUNTS, TIMETABLES, ATTENDANCE, MATERIALS, ANNOUNCEMENTS, ASSIGNMENTS, RESOURCES }

data class SetupStep(val title: String, val done: Boolean, val area: AdminArea)

/** The order a college is set up in, with each step ticked from the real counts. */
fun setupSteps(c: CollegeCounts) = listOf(
    SetupStep("Add departments and their programs", c.departments > 0 && c.programs > 0, AdminArea.DEPARTMENTS),
    SetupStep("Add an academic year", c.academicYears > 0, AdminArea.YEARS),
    SetupStep("Inside a program: add semesters and subjects", c.semesters > 0 && c.subjects > 0, AdminArea.DEPARTMENTS),
    SetupStep("Inside a program and year: add divisions (classes)", c.divisions > 0, AdminArea.DEPARTMENTS),
    SetupStep("Approve faculty and assign their subjects", c.faculty > 0 && c.activeAssignments > 0, AdminArea.FACULTY),
    SetupStep("Approve students and enroll them in a class", c.students > 0 && c.currentEnrollments > 0, AdminArea.STUDENTS),
    SetupStep("Add classrooms and publish timetables", c.classrooms > 0 && c.publishedTimetables > 0, AdminArea.TIMETABLES),
)

data class AdminHomeUi(val profile: CollegeProfile? = null, val counts: CollegeCounts? = null,
                       val loading: Boolean = false, val error: String? = null)

class CollegeAdminHomeViewModel(private val repo: CollegeAdminRepository, private val auth: () -> String,
                                private val lost: (Int) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(AdminHomeUi())
    val ui = state.asStateFlow()

    /** Called whenever the home screen is shown, so numbers are fresh after changes made in other screens. */
    fun load() {
        if (state.value.loading) return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val profile = repo.profile(auth())
                val counts = repo.overview(auth())
                state.update { it.copy(profile = profile, counts = counts) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? CollegeAdminFailure
                if (failure != null && (failure.status == 401 || failure.status == 403)) lost(failure.status)
                // Old numbers are dropped on failure so a stale or missing value is never shown as current.
                state.update { it.copy(counts = null, error = failure?.message ?: "Could not load your college's numbers.") }
            } finally {
                state.update { it.copy(loading = false) }
            }
        }
    }
}



/** The College Admin home: the college, approvals, setup progress and every management area. */
@Composable
fun CollegeAdminHome(repo: CollegeAdminRepository, auth: () -> String, lost: (Int) -> Unit, signingIn: Boolean,
                     authError: String?, onRefresh: () -> Unit, onLogout: () -> Unit, keep: ViewModelStoreOwner? = null,
                     open: (AdminArea) -> Unit) {
    // With [keep] (owned by the caller) the numbers stay in memory while another page is open, so Back returns to the
    // same place on a page that is already full size; it then refreshes.
    val local = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(local) { onDispose { local.viewModelStore.clear() } }
    val vm = viewModel<CollegeAdminHomeViewModel>(viewModelStoreOwner = keep ?: local) { CollegeAdminHomeViewModel(repo, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    val c = ui.counts
    fun count(value: Long?) = if (c != null && value != null) value.toString() else if (ui.error != null) "–" else "…"

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "hello") {
            BrandHero(Modifier.appear(0)) {
                CampusBrandRow(logoSize = 30.dp, fontSize = 15.sp, onDark = true)
                Spacer(Modifier.height(8.dp))
                Text(ui.profile?.name ?: "College Admin", style = MaterialTheme.typography.headlineSmall, color = Color.White,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(ui.profile?.let { "College Admin · code ${it.code} · " + (if (it.active) "active" else "inactive") } ?: "College Admin",
                    style = MaterialTheme.typography.bodyMedium, color = com.campussphere.app.ui.theme.Brand.AmberBright)
            }
        }
        if (ui.loading || signingIn) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        (ui.error ?: authError)?.let { message ->
            item(key = "error") {
                MessageBanner(message, MessageKind.Error, actionLabel = "Retry", onAction = vm::load, enabled = !ui.loading)
            }
        }

        item(key = "numbers") {
            Row(Modifier.appear(1), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberTile("Faculty", count(c?.faculty),
                    caption = c?.let { if (it.pendingFacultyRequests > 0) "${it.pendingFacultyRequests} waiting for approval"
                        else "${it.activeAssignments} teaching assignments" } ?: "Loading",
                    tone = if ((c?.pendingFacultyRequests ?: 0) > 0) Tone.Warning else Tone.Accent, modifier = Modifier.weight(1f)) {
                    open(AdminArea.FACULTY)
                }
                NumberTile("Students", count(c?.students),
                    caption = c?.let { if (it.pendingStudentRequests > 0) "${it.pendingStudentRequests} waiting for approval"
                        else "${it.currentEnrollments} enrolled in a class" } ?: "Loading",
                    tone = if ((c?.pendingStudentRequests ?: 0) > 0) Tone.Warning else Tone.Accent, modifier = Modifier.weight(1f)) {
                    open(AdminArea.STUDENTS)
                }
            }
        }

        c?.let { counts ->
            val steps = setupSteps(counts)
            val done = steps.count { it.done }
            if (done < steps.size) item(key = "setup") {
                HomeCard("Set up your college", Modifier.appear(2)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LinearProgressIndicator(progress = { done.toFloat() / steps.size }, modifier = Modifier.weight(1f))
                        Text("$done of ${steps.size}", style = MaterialTheme.typography.labelLarge)
                    }
                    val next = steps.firstOrNull { !it.done }
                    steps.forEachIndexed { i, step ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (step.done) Icon(CampusIcons.Check, "Done", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                            else Text("${i + 1}", Modifier.width(18.dp), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(step.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (step == next) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    next?.let { PrimaryButton("Next: ${it.title.substringBefore(':').substringBefore(" and ")}", { open(it.area) }) }
                }
            }
            if (counts.faculty == 0L && counts.students == 0L) item(key = "join") {
                HomeCard("How faculty and students join") {
                    val code = ui.profile?.code ?: "your college code"
                    InfoRow("Faculty", "Register as Faculty with college code $code. Approve them under Faculty, then set their department and subjects.")
                    InfoRow("Students", "Register as Student and request affiliation with code $code. Approve them under Students and enroll them in a class.")
                }
            }
        }

        item(key = "manage") {
            Column(Modifier.appear(3), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("Manage")
                ShortcutGrid(listOf(
                    Shortcut("Faculty", CampusIcons.Users, c?.pendingFacultyRequests ?: 0) { open(AdminArea.FACULTY) },
                    Shortcut("Students", CampusIcons.Users, c?.pendingStudentRequests ?: 0) { open(AdminArea.STUDENTS) },
                    Shortcut("Accounts", CampusIcons.Settings, 0) { open(AdminArea.ACCOUNTS) },
                    Shortcut("Departments", CampusIcons.Building, 0) { open(AdminArea.DEPARTMENTS) },
                    Shortcut("Academic years", CampusIcons.Calendar, 0) { open(AdminArea.YEARS) },
                    Shortcut("Timetables", CampusIcons.Clock, 0) { open(AdminArea.TIMETABLES) },
                    Shortcut("Attendance", CampusIcons.Checklist, 0) { open(AdminArea.ATTENDANCE) },
                    Shortcut("Assignments", CampusIcons.Homework, 0) { open(AdminArea.ASSIGNMENTS) },
                    Shortcut("Materials", CampusIcons.Books, 0) { open(AdminArea.MATERIALS) },
                    Shortcut("Syllabus", CampusIcons.Book, 0) { open(AdminArea.RESOURCES) },
                    Shortcut("Notices", CampusIcons.Megaphone, 0) { open(AdminArea.ANNOUNCEMENTS) },
                ))
            }
        }

        if (c != null) item(key = "glance") {
            HomeCard("College at a glance", Modifier.appear(4)) {
                GlanceRow("Departments", c.departments, "Programs", c.programs)
                GlanceRow("Classes", c.divisions, "Subjects", c.subjects)
                GlanceRow("Published timetables", c.publishedTimetables, "Classes without one", c.divisionsWithoutTimetable)
                GlanceRow("Lectures recorded", c.attendanceSessions, "Study materials", c.materials)
                GlanceRow("Live notices", c.liveAnnouncements, "Classrooms", c.classrooms)
            }
        }

        item(key = "footer") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onRefresh(); vm.load() }, enabled = !signingIn && !ui.loading) {
                    Icon(CampusIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh")
                }
                TextButton(onLogout, enabled = !signingIn, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(CampusIcons.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sign out")
                }
            }
        }
    }
}

@Composable
private fun GlanceRow(leftLabel: String, left: Long, rightLabel: String, right: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { InfoRow(leftLabel, left.toString()) }
        Column(Modifier.weight(1f)) { InfoRow(rightLabel, right.toString()) }
    }
}
