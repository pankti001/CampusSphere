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
import com.campussphere.app.ui.brand.appear
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
enum class AdminArea { DEPARTMENTS, YEARS, FACULTY, STUDENTS, ACCOUNTS, TIMETABLES, ATTENDANCE, MATERIALS, ANNOUNCEMENTS, ASSIGNMENTS }

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

private data class AdminTile(val icon: String, val title: String, val number: Long?, val lines: List<String>,
                             val area: AdminArea)

/** The College Admin home: the college, setup progress and every management area with its real numbers. */
@Composable
fun CollegeAdminHome(repo: CollegeAdminRepository, auth: () -> String, lost: (Int) -> Unit, signingIn: Boolean,
                     authError: String?, onRefresh: () -> Unit, onLogout: () -> Unit, open: (AdminArea) -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<CollegeAdminHomeViewModel>(viewModelStoreOwner = owner) { CollegeAdminHomeViewModel(repo, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    val c = ui.counts

    val sections = listOf(
        "Academic setup" to listOf(
            AdminTile("🏛", "Departments & programs", c?.departments,
                listOfNotNull(c?.let { "${it.programs} programs · ${it.divisions} divisions · ${it.subjects} subjects" }),
                AdminArea.DEPARTMENTS),
            AdminTile("📅", "Academic years", c?.academicYears,
                listOfNotNull(c?.let { "${it.semesters} semesters" }), AdminArea.YEARS)),
        "People" to listOf(
            AdminTile("👩‍🏫", "Faculty", c?.faculty, listOfNotNull(
                c?.let { "${it.activeAssignments} teaching assignments" },
                c?.pendingFacultyRequests?.takeIf { it > 0 }?.let { "$it waiting for approval" }), AdminArea.FACULTY),
            AdminTile("🎓", "Students", c?.students, listOfNotNull(
                c?.let { "${it.currentEnrollments} enrolled in a class" },
                c?.pendingStudentRequests?.takeIf { it > 0 }?.let { "$it waiting for approval" }), AdminArea.STUDENTS),
            AdminTile("🪪", "Accounts", null, listOf("Edit names and phones, deactivate or reactivate"), AdminArea.ACCOUNTS)),
        "Teaching" to listOf(
            AdminTile("🗓", "Timetables", c?.publishedTimetables, listOfNotNull(
                c?.let { "published · ${it.draftTimetables} drafts · ${it.classrooms} classrooms" },
                c?.divisionsWithoutTimetable?.takeIf { it > 0 }?.let { "$it classes without a timetable" }), AdminArea.TIMETABLES),
            AdminTile("✅", "Attendance", c?.attendanceSessions, listOf("submitted sessions; reports and alerts"), AdminArea.ATTENDANCE),
            AdminTile("📚", "Study materials", c?.materials, listOf("published; review and moderate"), AdminArea.MATERIALS),
            AdminTile("📝", "Assignments", null, listOf("Published work, submissions and marking progress"), AdminArea.ASSIGNMENTS)),
        "Communication" to listOf(
            AdminTile("📣", "Announcements", c?.liveAnnouncements, listOf("live now"), AdminArea.ANNOUNCEMENTS)),
    )

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            com.campussphere.app.ui.brand.BrandHero(Modifier.appear(0)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.campussphere.app.ui.brand.CampusBrandRow(Modifier.weight(1f), logoSize = 34.dp,
                        fontSize = 16.sp, onDark = true)
                    TextButton(onLogout, enabled = !signingIn) { Text("Sign out", color = androidx.compose.ui.graphics.Color.White) }
                }
                Spacer(Modifier.height(8.dp))
                Text(ui.profile?.name ?: "College Admin", style = MaterialTheme.typography.headlineSmall,
                    color = androidx.compose.ui.graphics.Color.White)
                Text(ui.profile?.let { "College Admin · code ${it.code} · " + (if (it.active) "active" else "inactive") }
                    ?: "College Admin", style = MaterialTheme.typography.bodyMedium,
                    color = com.campussphere.app.ui.theme.Brand.AmberBright)
            }
            if (ui.loading || signingIn) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            (ui.error ?: authError)?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(vm::load, enabled = !ui.loading) { Text("Retry") }
                    }
                }
            }
        }
        c?.let { counts ->
            val waiting = counts.pendingFacultyRequests + counts.pendingStudentRequests
            if (waiting > 0) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("$waiting waiting for your approval", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (counts.pendingFacultyRequests > 0) Button({ open(AdminArea.FACULTY) }) {
                                Text("${counts.pendingFacultyRequests} faculty")
                            }
                            if (counts.pendingStudentRequests > 0) Button({ open(AdminArea.STUDENTS) }) {
                                Text("${counts.pendingStudentRequests} students")
                            }
                        }
                    }
                }
            }
            if (counts.faculty == 0L && counts.students == 0L) item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("How faculty and students join", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        val code = ui.profile?.code ?: "your college code"
                        Text("Faculty: register in the app as Faculty and enter college code $code. Then approve them under Faculty → Approve faculty requests, set their department and add their subjects.",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Students: register as Student, sign in and tap Request college affiliation with code $code. Then approve them under Students → Approve student requests and enroll them in a class.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            val steps = setupSteps(counts)
            val done = steps.count { it.done }
            if (done < steps.size) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Setup progress: $done of ${steps.size}", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold)
                        LinearProgressIndicator(progress = { done.toFloat() / steps.size }, modifier = Modifier.fillMaxWidth())
                        val next = steps.firstOrNull { !it.done }
                        steps.forEachIndexed { i, step ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text((if (step.done) "✓ " else "${i + 1}. ") + step.title, style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (step == next) FontWeight.SemiBold else FontWeight.Normal,
                                    modifier = Modifier.weight(1f))
                                if (step == next) TextButton({ open(step.area) }) { Text("Do this") }
                            }
                        }
                    }
                }
            }
        }
        sections.forEach { (title, tiles) ->
            item(key = title) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { tile ->
                                Tile(tile, showNumber = tile.area != AdminArea.ACCOUNTS && tile.area != AdminArea.ASSIGNMENTS, loaded = c != null,
                                    modifier = Modifier.weight(1f).appear(1)) { open(tile.area) }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ onRefresh(); vm.load() }, enabled = !signingIn && !ui.loading) { Text("Refresh") }
                TextButton(onLogout, enabled = !signingIn) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun Tile(tile: AdminTile, showNumber: Boolean, loaded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = modifier.heightIn(min = 128.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tile.icon, style = MaterialTheme.typography.titleLarge)
                // A dash while loading or after an error: a number is shown only when it really came from the server.
                if (showNumber) Text(if (loaded && tile.number != null) tile.number.toString() else "—",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(tile.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            tile.lines.forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
