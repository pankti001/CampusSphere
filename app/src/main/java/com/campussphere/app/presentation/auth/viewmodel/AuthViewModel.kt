package com.campussphere.app.presentation.auth.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

import com.campussphere.app.data.repository.AuthRepository
import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.LoginResponse
import com.campussphere.app.data.remote.dto.RegisterRequest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null,
    val loginResponse: LoginResponse? = null
)

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun register(request: RegisterRequest) {

        viewModelScope.launch {

            _uiState.value = AuthUiState(isLoading = true)

            val result = authRepository.register(request)

            _uiState.value = result.fold(
                onSuccess = { message ->
                    AuthUiState(successMessage = message)
                },
                onFailure = { error ->
                    AuthUiState(
                        errorMessage = error.message ?: "Registration failed"
                    )
                }
            )
        }
    }

    fun login(request: LoginRequest) {

        viewModelScope.launch {

            _uiState.value = AuthUiState(isLoading = true)

            val result = authRepository.login(request)

            _uiState.value = result.fold(
                onSuccess = { response ->
                    AuthUiState(
                        successMessage = "Login successful",
                        loginResponse = response
                    )
                },
                onFailure = { error ->
                    AuthUiState(
                        errorMessage = error.message ?: "Login failed"
                    )
                }
            )
        }
    }

    fun resetState() {
        _uiState.value = AuthUiState()
    }
}