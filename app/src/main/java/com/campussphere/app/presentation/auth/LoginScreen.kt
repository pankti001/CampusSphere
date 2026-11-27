package com.campussphere.app.presentation.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    val primaryBlue = com.campussphere.app.ui.theme.Brand.Blue

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(com.campussphere.app.ui.theme.Brand.Sky, Color.White)))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 25.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Spacer(modifier = Modifier.height(28.dp))

        com.campussphere.app.ui.brand.CampusLogo(size = 112.dp, animate = true)
        com.campussphere.app.ui.brand.CampusWordmark(fontSize = 26.sp)

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Welcome back!",
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            color = com.campussphere.app.ui.theme.Brand.Navy
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Sign in to continue to CampusSphere",
            color = Color.Gray,
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Email
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {

            Text(
                text = "Email Address",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Enter your email")
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email
                )
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Password
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {

            Text(
                text = "Password",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Enter your password")
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(
                        onClick = {
                            passwordVisible = !passwordVisible
                        }
                    ) {
                        Text(
                            if (passwordVisible) "Hide" else "Show"
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Remember Me and Forgot Password
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = rememberMe,
                    onCheckedChange = {
                        rememberMe = it
                    }
                )

                Text(
                    text = "Remember me",
                    fontSize = 12.sp
                )
            }

            TextButton(
                onClick = onForgotPassword,
                enabled = !isLoading
            ) {
                Text(
                    text = "Forgot Password?",
                    fontSize = 12.sp,
                    color = primaryBlue
                )
            }
        }

        Spacer(modifier = Modifier.height(25.dp))

        // Login Button
        Button(
            onClick = {
                onLogin(email.trim(), password, rememberMe)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = primaryBlue
            ),
            enabled = !isLoading && email.isNotBlank() && password.isNotBlank()
        ) {
            Text(
                text = if (isLoading) "Signing in..." else "Login",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(25.dp))

        // Register
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

            Text(
                text = "Don't have an account?",
                color = Color.Gray,
                fontSize = 13.sp
            )

            TextButton(
                onClick = onRegister,
                enabled = !isLoading
            ) {
                Text(
                    text = "Create Account",
                    color = primaryBlue,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        TextButton(onClick = onRegisterCollege, enabled = !isLoading) { Text("Register Your College") }
        TextButton(onClick = onAcceptInvitation, enabled = !isLoading) {
            Text("Accept College Admin invitation")
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "CAMPUSSPHERE • LEARN • CONNECT • GROW",
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))
    }
}
