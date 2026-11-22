package com.campussphere.app.data.remote.dto

data class RegisterRequest(
    val fullName: String,
    val email: String,
    val phone: String,
    val password: String,
    val role: String
)