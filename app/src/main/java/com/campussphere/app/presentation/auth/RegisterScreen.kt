
package com.campussphere.app.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.campussphere.app.ui.brand.CampusIcons
import com.campussphere.app.ui.brand.CampusLogo
import com.campussphere.app.ui.brand.InfoCard
import com.campussphere.app.ui.brand.MessageBanner
import com.campussphere.app.ui.brand.MessageKind
import com.campussphere.app.ui.brand.PrimaryButton
import com.campussphere.app.ui.brand.SectionHeader
import com.campussphere.app.ui.theme.Brand

@Composable
fun RegisterScreen(
    isLoading: Boolean,
    onRegister: (
        String, String, String, String, String, String?
    ) -> Unit,
    onLogin: () -> Unit
) {

    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    var selectedRole by remember { mutableStateOf("STUDENT") }
    var collegeCode by remember { mutableStateOf("") }

    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Brand.Sky, Color.White)))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Spacer(modifier = Modifier.height(8.dp))

        CampusLogo(size = 88.dp, animate = true)

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Create account",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Brand.Navy
            )
            Text(
                text = "Join the CampusSphere community",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        SectionHeader("Your details")
        InfoCard {
            RegisterField(
                label = "Full name",
                value = fullName,
                onValueChange = { fullName = it },
                placeholder = "Enter your full name"
            )
            RegisterField(
                label = "Email address",
                value = email,
                onValueChange = { email = it },
                placeholder = "Enter your email",
                keyboardType = KeyboardType.Email
            )
            RegisterField(
                label = "Phone number",
                value = phone,
                onValueChange = {
                    if (it.length <= 10 && it.all(Char::isDigit)) {
                        phone = it
                    }
                },
                placeholder = "Enter your phone number",
                keyboardType = KeyboardType.Phone
            )
        }

        SectionHeader("Password")
        InfoCard {
            RegisterField(
                label = "Password",
                value = password,
                onValueChange = { password = it },
                placeholder = "Create password",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                visible = passwordVisible,
                onToggleVisible = { passwordVisible = !passwordVisible }
            )
            RegisterField(
                label = "Confirm password",
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                placeholder = "Confirm your password",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (confirmVisible)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                visible = confirmVisible,
                onToggleVisible = { confirmVisible = !confirmVisible }
            )
        }

        SectionHeader("Register as")
        InfoCard {
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoleOption("Student", selectedRole == "STUDENT", Modifier.weight(1f)) { selectedRole = "STUDENT" }
                RoleOption("Faculty", selectedRole == "FACULTY", Modifier.weight(1f)) { selectedRole = "FACULTY" }
            }

            if (selectedRole == "FACULTY") {
                OutlinedTextField(
                    value = collegeCode,
                    onValueChange = { collegeCode = it },
                    label = { Text("College code") },
                    supportingText = { Text("Enter the code provided by your college.") },
                    enabled = !isLoading,
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (errorMessage.isNotEmpty()) {
            MessageBanner(errorMessage, MessageKind.Error)
        }

        PrimaryButton(
            text = if (isLoading) "Creating account…" else "Create account",
            enabled = !isLoading,
            onClick = {

                errorMessage = when {
                    fullName.isBlank() ->
                        "Enter your full name"

                    email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS
                        .matcher(email.trim()).matches() ->
                        "Enter a valid email"

                    phone.length != 10 ->
                        "Enter a valid 10-digit phone number"

                    password.length < 8 ->
                        "Password must contain at least 8 characters"

                    confirmPassword != password ->
                        "Passwords do not match"

                    selectedRole == "FACULTY" &&
                        !collegeCode.trim().matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,29}")) ->
                        "Enter a valid college code"

                    else -> ""
                }

                if (errorMessage.isEmpty()) {
                    onRegister(
                        fullName.trim(),
                        email.trim(),
                        phone,
                        password,
                        selectedRole,
                        collegeCode.trim().takeIf { selectedRole == "FACULTY" }
                    )
                }
            }
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Already have an account?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onLogin, enabled = !isLoading) {
                Text("Sign in", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}


/** One choice of the "Register as" group; the whole row is tappable. */
@Composable
private fun RoleOption(text: String, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

// Reusable registration field

@Composable
private fun RegisterField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    visible: Boolean = false,
    onToggleVisible: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType
        ),
        visualTransformation = visualTransformation,
        trailingIcon = if (onToggleVisible != null) ({
            IconButton(onClick = onToggleVisible) {
                Icon(
                    if (visible) CampusIcons.Hide else CampusIcons.Eye,
                    contentDescription = if (visible) "Hide password" else "Show password"
                )
            }
        }) else null
    )
}
