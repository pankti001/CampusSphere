
package com.campussphere.app.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    val primaryBlue = com.campussphere.app.ui.theme.Brand.Blue

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(com.campussphere.app.ui.theme.Brand.Sky, Color.White)))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Spacer(modifier = Modifier.height(20.dp))

        com.campussphere.app.ui.brand.CampusLogo(size = 88.dp, animate = true)

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Create account",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = com.campussphere.app.ui.theme.Brand.Navy
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Join the CampusSphere community",
            color = Color.Gray,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(30.dp))

        // Full Name
        RegisterField(
            label = "Full Name",
            value = fullName,
            onValueChange = { fullName = it },
            placeholder = "Enter your full name"
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Email
        RegisterField(
            label = "Email Address",
            value = email,
            onValueChange = { email = it },
            placeholder = "Enter your email",
            keyboardType = KeyboardType.Email
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Phone
        RegisterField(
            label = "Phone Number",
            value = phone,
            onValueChange = {
                if (it.length <= 10 && it.all(Char::isDigit)) {
                    phone = it
                }
            },
            placeholder = "Enter your phone number",
            keyboardType = KeyboardType.Phone
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Password
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
            trailingText = if (passwordVisible) "Hide" else "Show",
            onTrailingClick = {
                passwordVisible = !passwordVisible
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Confirm Password
        RegisterField(
            label = "Confirm Password",
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            placeholder = "Confirm your password",
            keyboardType = KeyboardType.Password,
            visualTransformation = if (confirmVisible)
                VisualTransformation.None
            else
                PasswordVisualTransformation(),
            trailingText = if (confirmVisible) "Hide" else "Show",
            onTrailingClick = {
                confirmVisible = !confirmVisible
            }
        )

        Spacer(modifier = Modifier.height(22.dp))

        // Role Selection
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {

            Text(
                text = "Register As",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    RadioButton(
                        selected = selectedRole == "STUDENT",
                        onClick = { selectedRole = "STUDENT" },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = primaryBlue
                        )
                    )

                    Text("Student", fontSize = 14.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    RadioButton(
                        selected = selectedRole == "FACULTY",
                        onClick = { selectedRole = "FACULTY" },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = primaryBlue
                        )
                    )

                    Text("Faculty", fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(15.dp))

        if (selectedRole == "FACULTY") {
            OutlinedTextField(
                value = collegeCode,
                onValueChange = { collegeCode = it },
                label = { Text("College code") },
                supportingText = { Text("Enter the code provided by your college.") },
                enabled = !isLoading,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Error Message
        if (errorMessage.isNotEmpty()) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(15.dp))

        // Create Account
        Button(
            enabled = !isLoading,
            onClick = {

                errorMessage = when {
                    fullName.isBlank() ->
                        "Please enter your full name"

                    email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS
                        .matcher(email.trim()).matches() ->
                        "Please enter a valid email"

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
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryBlue
            )
        ) {
            Text(
                text = if (isLoading) "Creating account..." else "Create Account",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Login Navigation
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = "Already have an account?",
                color = Color.Gray,
                fontSize = 13.sp
            )

            TextButton(onClick = onLogin, enabled = !isLoading) {
                Text(
                    text = "Login",
                    color = primaryBlue,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}


// Reusable Registration Field

@Composable
private fun RegisterField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingText: String? = null,
    onTrailingClick: (() -> Unit)? = null
) {

    Column(modifier = Modifier.fillMaxWidth()) {

        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(7.dp))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(placeholder, fontSize = 13.sp)
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType
            ),
            visualTransformation = visualTransformation,
            trailingIcon = {
                if (trailingText != null && onTrailingClick != null) {
                    TextButton(onClick = onTrailingClick) {
                        Text(
                            text = trailingText,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        )
    }
}
