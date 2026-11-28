package com.campussphere.app.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campussphere.app.ui.brand.CampusIcons
import com.campussphere.app.ui.brand.CampusLogo
import com.campussphere.app.ui.brand.CampusWordmark
import com.campussphere.app.ui.brand.PrimaryButton
import com.campussphere.app.ui.theme.Brand

@Composable
fun LoginScreen(
    isLoading: Boolean,
    onLogin: (String, String, Boolean) -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    onRegisterCollege: () -> Unit = {}, onAcceptInvitation: () -> Unit = {}
) {

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberMe by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Sky, Color.White)))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Spacer(modifier = Modifier.height(28.dp))

        CampusLogo(size = 112.dp, animate = true)
        CampusWordmark(fontSize = 26.sp)

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Welcome back",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Brand.Navy
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Sign in to continue to CampusSphere",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email address") },
                placeholder = { Text("Enter your email") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email
                )
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                placeholder = { Text("Enter your password") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) CampusIcons.Hide else CampusIcons.Eye,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Remember me and forgot password
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = rememberMe,
                    onCheckedChange = { rememberMe = it }
                )
                Text(text = "Remember me", style = MaterialTheme.typography.bodyMedium)
            }

            TextButton(
                onClick = onForgotPassword,
                enabled = !isLoading
            ) {
                Text("Forgot password?")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        PrimaryButton(
            text = if (isLoading) "Signing in…" else "Sign in",
            onClick = { onLogin(email.trim(), password, rememberMe) },
            enabled = !isLoading && email.isNotBlank() && password.isNotBlank()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Don't have an account?",
                style = MaterialTheme.typography.bodyMedium,
                color = muted
            )
            TextButton(onClick = onRegister, enabled = !isLoading) {
                Text("Create account", fontWeight = FontWeight.SemiBold)
            }
        }

        HorizontalDivider(
            Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        )

        // "Register Your College" is looked up by the visual tests; keep the wording.
        LinkButton("Register Your College", CampusIcons.Building, onRegisterCollege, enabled = !isLoading)
        LinkButton("Accept College Admin invitation", CampusIcons.Handshake, onAcceptInvitation, enabled = !isLoading)

        Spacer(modifier = Modifier.weight(1f))

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "CAMPUSSPHERE • LEARN • CONNECT • GROW",
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = muted,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}

/** A small text link with a leading line icon, for the less common ways in. */
@Composable
private fun LinkButton(text: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean) {
    TextButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}
