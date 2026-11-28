package com.campussphere.app.presentation.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.campussphere.app.presentation.auth.viewmodel.AuthRoute
import com.campussphere.app.presentation.auth.viewmodel.AuthUiState
import com.campussphere.app.ui.brand.CampusIcons
import com.campussphere.app.ui.brand.MessageBanner
import com.campussphere.app.ui.brand.MessageKind
import com.campussphere.app.ui.brand.PrimaryButton
import com.campussphere.app.ui.brand.SecondaryButton

@Composable
fun SessionScreen(state: AuthUiState, onRefresh: () -> Unit, onReapply: () -> Unit, onLogout: () -> Unit,
                  onAcademicYears: (() -> Unit)? = null,
                  onDepartments: (() -> Unit)? = null,
                  onFaculty: (() -> Unit)? = null,
                  onStudents: (() -> Unit)? = null,
                  onStudentAffiliation: (() -> Unit)? = null,
                  onAttendance: (() -> Unit)? = null, onMaterials: (() -> Unit)? = null,
                  onAnnouncements: (() -> Unit)? = null, onTimetable: (() -> Unit)? = null) {
    val account = state.account
    val role = account?.dashboardRole
    val title = role?.title ?: when {
        state.route == AuthRoute.CLEAR_SESSION -> "Sign-out incomplete"
        state.route == AuthRoute.RETRY_SESSION -> "Could not verify your session"
        account?.facultyStatus == "PENDING" -> "Faculty request pending"
        account?.facultyStatus == "REJECTED" -> "Faculty request rejected"
        account?.verified == false -> "Verification pending"
        else -> "Access unavailable"
    }
    val message = account?.dashboardMessage ?: when {
        state.route == AuthRoute.CLEAR_SESSION -> "Try signing out again to remove saved sign-in data."
        state.route == AuthRoute.RETRY_SESSION -> "Refresh your session to check your current access."
        account?.facultyStatus == "PENDING" -> "An administrator must approve your request before you can open the Faculty dashboard."
        account?.facultyStatus == "REJECTED" -> "You can submit a new Faculty request for review."
        account?.verified == false -> "Your account must be verified before you can open the Student dashboard."
        else -> "Your account has no dashboard access."
    }
    // Same order of checks as the title, so the picture always matches the heading.
    val symbol = when {
        role != null -> CampusIcons.Building
        state.route == AuthRoute.CLEAR_SESSION -> CampusIcons.Logout
        state.route == AuthRoute.RETRY_SESSION -> CampusIcons.Refresh
        account?.facultyStatus == "PENDING" -> CampusIcons.Clock
        account?.facultyStatus == "REJECTED" -> CampusIcons.Warning
        account?.verified == false -> CampusIcons.Clock
        else -> CampusIcons.Info
    }
    val enabled = !state.isLoading
    val reapply = role == null && account?.facultyStatus == "REJECTED"
    val hasLinks = listOf(onDepartments, onAcademicYears, onFaculty, onStudents, onStudentAffiliation,
        onAnnouncements, onTimetable, onMaterials, onAttendance).any { it != null }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SoftIcon(symbol)
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            state.error?.let { MessageBanner(it.message, MessageKind.Error) }
            if (state.route == AuthRoute.CLEAR_SESSION) {
                PrimaryButton("Retry sign out", onClick = onLogout, enabled = enabled, icon = CampusIcons.Logout)
            } else {
                onDepartments?.let { SecondaryButton("Manage departments", it, enabled = enabled, icon = CampusIcons.Building) }
                onAcademicYears?.let { SecondaryButton("Academic years", it, enabled = enabled, icon = CampusIcons.Calendar) }
                onFaculty?.let { SecondaryButton("Manage faculty", it, enabled = enabled, icon = CampusIcons.Users) }
                onStudents?.let { SecondaryButton("Manage students", it, enabled = enabled, icon = CampusIcons.Users) }
                onStudentAffiliation?.let {
                    SecondaryButton("Request college affiliation", it, enabled = enabled, icon = CampusIcons.Handshake)
                }
                onAnnouncements?.let { SecondaryButton("Announcements", it, enabled = enabled, icon = CampusIcons.Megaphone) }
                onTimetable?.let {
                    SecondaryButton("Timetables: create, upload or copy", it, enabled = enabled, icon = CampusIcons.Calendar)
                }
                onMaterials?.let { SecondaryButton("Study materials", it, enabled = enabled, icon = CampusIcons.Book) }
                onAttendance?.let { SecondaryButton("Attendance reports", it, enabled = enabled, icon = CampusIcons.Checklist) }
                if (reapply) {
                    PrimaryButton("Reapply for Faculty", onClick = onReapply, enabled = enabled)
                }
                val refreshText = if (state.route == AuthRoute.RETRY_SESSION) "Retry" else "Refresh access"
                // Refresh is the main action only when nothing else on the page is.
                if (!reapply && !hasLinks) {
                    PrimaryButton(refreshText, onClick = onRefresh, enabled = enabled, icon = CampusIcons.Refresh)
                } else {
                    SecondaryButton(refreshText, onClick = onRefresh, enabled = enabled, icon = CampusIcons.Refresh)
                }
                TextButton(onClick = onLogout, enabled = enabled) { Text("Sign out") }
            }
        }
    }
}

/** A line icon in a soft tinted circle, as on the dashboards. */
@Composable
private fun SoftIcon(icon: ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(16.dp).size(28.dp))
    }
}
