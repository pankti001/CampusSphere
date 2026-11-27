package com.campussphere.app.presentation.homework

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.campussphere.app.data.remote.api.FacultyHomeworkSummary
import com.campussphere.app.data.remote.api.StudentHomeworkSummary
import com.campussphere.app.data.repository.HomeworkFailure
import com.campussphere.app.data.repository.HomeworkRepository
import kotlinx.coroutines.CancellationException

/** Loads a summary each time the dashboard is shown; only real server numbers are displayed. */
@Composable
private fun <T> rememberSummary(lost: (Int) -> Unit, load: suspend () -> T): Pair<T?, String?> {
    var value by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(tick) {
        try {
            value = load()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = e as? HomeworkFailure
            if (failure?.status == 401) lost(401)
            value = null
            error = "Assignment numbers are not available right now."
        }
    }
    return value to error
}

/** Faculty dashboard card: assignment counts and the way in to create or mark work. */
@Composable
fun FacultyHomeworkCard(repo: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit,
                        onOpen: () -> Unit, onCreate: () -> Unit) {
    val (summary, error) = rememberSummary<FacultyHomeworkSummary>(lost) { repo.facultySummary(auth()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("📝 Assignments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            summary?.let {
                Text("${it.published} published · ${it.drafts} drafts · ${it.closed} closed")
                Text("${it.toMark} submissions waiting to be marked · ${it.evaluated} marked",
                    color = if (it.toMark > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onOpen) { Text("My assignments") }
                OutlinedButton(onCreate) { Text("Create") }
            }
        }
    }
}

/** Student dashboard card: pending work, the next deadlines and results. */
@Composable
fun StudentHomeworkCard(repo: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit,
                        onOpen: (tab: String) -> Unit) {
    val (summary, error) = rememberSummary<StudentHomeworkSummary>(lost) { repo.studentSummary(auth()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("📝 My assignments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            summary?.let { s ->
                Text("${s.pending} pending · ${s.dueThisWeek} due this week · ${s.submitted} submitted · ${s.evaluated} marked")
                if (s.unreadNotices > 0) Text("🔔 ${s.unreadNotices} new assignment notification" + if (s.unreadNotices == 1L) "" else "s",
                    color = MaterialTheme.colorScheme.primary)
                if (s.upcoming.isNotEmpty()) {
                    Text("Upcoming deadlines", style = MaterialTheme.typography.labelLarge)
                    s.upcoming.forEach { Text("• ${it.title} (${it.subjectCode}) — ${homeworkRemaining(it.dueAt).lowercase()}",
                        style = MaterialTheme.typography.bodySmall) }
                }
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ onOpen("PENDING") }) { Text("Pending work") }
                OutlinedButton({ onOpen("EVALUATED") }) { Text("Results") }
            }
            TextButton({ onOpen("SUBMITTED") }) { Text("Submitted work") }
        }
    }
}
