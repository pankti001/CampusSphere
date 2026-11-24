package com.campussphere.app.presentation.auth.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.campussphere.app.data.local.SessionStore
import com.campussphere.app.data.repository.AuthRepository

class AuthViewModelFactory(
    private val authRepository: AuthRepository,
    private val sessionStore: SessionStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass == AuthViewModel::class.java) {
            return AuthViewModel(authRepository, sessionStore) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
