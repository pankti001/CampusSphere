package com.campussphere.app.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.dto.CollegeAdminInvitationAcceptance

@Composable
fun AcceptInvitationScreen(busy: Boolean, error: String?, onBack: () -> Unit,
                           onAccept: (CollegeAdminInvitationAcceptance) -> Unit) {
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val code = token.trim()
    val validCode = code.matches(Regex("[A-Za-z0-9_-]{43}"))
    val validPassword = !password.isBlank() && password.length >= 12 &&
        password.encodeToByteArray().let { bytes -> (bytes.size <= 72).also { bytes.fill(0) } }
    val matches = password == confirmation
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TextButton(onClick = onBack, enabled = !busy) { Text("Back to login") }
                Text("Accept College Admin invitation", style = MaterialTheme.typography.headlineMedium)
                Text("Enter the code shared by CampusSphere and set a password for your invited account.")
                OutlinedTextField(token, { token = it }, label = { Text("Invitation code") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("Full name") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
                OutlinedTextField(password, { password = it }, label = { Text("New password") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("At least 12 characters, up to 72 UTF-8 bytes.") })
                OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Confirm password") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = confirmation.isNotEmpty() && !matches)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { onAccept(CollegeAdminInvitationAcceptance(code, name.trim(), password)) },
                    enabled = !busy && validCode && name.isNotBlank() && validPassword && matches,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "Accepting…" else "Accept invitation")
                }
            }
        }
    }
}
