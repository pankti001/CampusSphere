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
import com.campussphere.app.ui.brand.CampusTopBar
import com.campussphere.app.ui.brand.InfoCard
import com.campussphere.app.ui.brand.MessageBanner
import com.campussphere.app.ui.brand.MessageKind
import com.campussphere.app.ui.brand.PrimaryButton

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
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            CampusTopBar("Accept invitation", onBack = onBack, subtitle = "College Admin", backEnabled = !busy)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Accept College Admin invitation", style = MaterialTheme.typography.titleLarge)
                    Text("Enter the code shared by CampusSphere and set a password for your invited account.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InfoCard {
                        OutlinedTextField(token, { token = it }, label = { Text("Invitation code") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                            shape = MaterialTheme.shapes.small,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        OutlinedTextField(name, { name = it.take(100) }, label = { Text("Full name") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                            shape = MaterialTheme.shapes.small)
                        OutlinedTextField(password, { password = it }, label = { Text("New password") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                            shape = MaterialTheme.shapes.small,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            supportingText = { Text("At least 12 characters, up to 72 UTF-8 bytes.") })
                        OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Confirm password") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                            shape = MaterialTheme.shapes.small,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError = confirmation.isNotEmpty() && !matches)
                    }
                    error?.let { MessageBanner(it, MessageKind.Error) }
                    PrimaryButton(if (busy) "Accepting…" else "Accept invitation",
                        onClick = { onAccept(CollegeAdminInvitationAcceptance(code, name.trim(), password)) },
                        enabled = !busy && validCode && name.isNotBlank() && validPassword && matches)
                }
            }
        }
    }
}
