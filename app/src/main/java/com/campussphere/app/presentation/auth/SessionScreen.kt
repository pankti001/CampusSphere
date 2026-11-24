package com.campussphere.app.presentation.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.campussphere.app.presentation.auth.viewmodel.AuthRoute
import com.campussphere.app.presentation.auth.viewmodel.AuthUiState

@Composable
fun SessionScreen(state: AuthUiState, onRefresh: () -> Unit, onReapply: () -> Unit, onLogout: () -> Unit,
                  onAcademicYears: (() -> Unit)? = null,
                  onDepartments: (() -> Unit)? = null,
                  onFaculty: (() -> Unit)? = null,
                  onStudents: (() -> Unit)? = null,
                  onStudentAffiliation: (() -> Unit)? = null) {
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

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(message, textAlign = TextAlign.Center)
        state.error?.let { Text(it.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        if (state.route == AuthRoute.CLEAR_SESSION) {
            Button(onClick = onLogout, enabled = !state.isLoading) { Text("Retry sign out") }
        } else {
            onDepartments?.let { open ->
                Button(onClick = open, enabled = !state.isLoading) { Text("Manage departments") }
            }
            onAcademicYears?.let { open ->
                Button(onClick = open, enabled = !state.isLoading) { Text("Academic years") }
            }
            onFaculty?.let { open ->
                Button(onClick = open, enabled = !state.isLoading) { Text("Manage faculty") }
            }
            onStudents?.let { open ->
                Button(onClick = open, enabled = !state.isLoading) { Text("Manage students") }
            }
            onStudentAffiliation?.let { open ->
                Button(onClick = open, enabled = !state.isLoading) { Text("Request college affiliation") }
            }
            if (role == null && account?.facultyStatus == "REJECTED") {
                Button(onClick = onReapply, enabled = !state.isLoading) { Text("Reapply for Faculty") }
            }
            OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) {
                Text(if (state.route == AuthRoute.RETRY_SESSION) "Retry" else "Refresh access")
            }
            TextButton(onClick = onLogout, enabled = !state.isLoading) { Text("Sign out") }
        }
    }
}
