package com.campussphere.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel

import com.campussphere.app.data.remote.api.AuthApi
import com.campussphere.app.data.remote.api.RetrofitClient
import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.RegisterRequest
import com.campussphere.app.data.repository.AuthRepository
import com.campussphere.app.presentation.auth.LoginScreen
import com.campussphere.app.presentation.auth.RegisterScreen
import com.campussphere.app.presentation.auth.viewmodel.AuthViewModel
import com.campussphere.app.presentation.auth.viewmodel.AuthViewModelFactory
import com.campussphere.app.presentation.onboarding.OnboardingScreen
import com.campussphere.app.ui.theme.CampusSphereTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // Initialize API and Repository
        val authApi = RetrofitClient.createService(AuthApi::class.java)
        val authRepository = AuthRepository(authApi)
        val authViewModelFactory = AuthViewModelFactory(authRepository)

        setContent {

            CampusSphereTheme {

                // Initialize ViewModel
                val authViewModel: AuthViewModel = viewModel(
                    factory = authViewModelFactory
                )

                // Observe authentication state
                val uiState by authViewModel.uiState.collectAsState()

                var currentScreen by remember {
                    mutableStateOf("ONBOARDING")
                }

                // Display API success and error messages
                LaunchedEffect(
                    uiState.successMessage,
                    uiState.errorMessage
                ) {

                    uiState.successMessage?.let { message ->

                        Toast.makeText(
                            this@MainActivity,
                            message,
                            Toast.LENGTH_LONG
                        ).show()

                        // After successful registration, return to login
                        if (message.contains("Registration", ignoreCase = true)) {
                            currentScreen = "LOGIN"
                        }
                    }

                    uiState.errorMessage?.let { message ->

                        Toast.makeText(
                            this@MainActivity,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                when (currentScreen) {

                    "ONBOARDING" -> {

                        OnboardingScreen(
                            onGetStarted = {
                                currentScreen = "LOGIN"
                            }
                        )
                    }

                    "LOGIN" -> {

                        LoginScreen(

                            onLogin = { email, password ->

                                authViewModel.login(
                                    LoginRequest(
                                        email = email.trim(),
                                        password = password
                                    )
                                )
                            },

                            onRegister = {
                                authViewModel.resetState()
                                currentScreen = "REGISTER"
                            },

                            onForgotPassword = {

                                Toast.makeText(
                                    this@MainActivity,
                                    "Forgot password coming soon",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }

                    "REGISTER" -> {

                        RegisterScreen(

                            onRegister = { name, email, phone, password, role ->

                                authViewModel.register(
                                    RegisterRequest(
                                        fullName = name.trim(),
                                        email = email.trim(),
                                        phone = phone.trim(),
                                        password = password,
                                        role = role
                                    )
                                )
                            },

                            onLogin = {
                                authViewModel.resetState()
                                currentScreen = "LOGIN"
                            }
                        )
                    }
                }
            }
        }
    }
}