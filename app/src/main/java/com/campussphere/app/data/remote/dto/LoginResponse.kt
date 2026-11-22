package com.campussphere.app.data.remote.dto

data class LoginResponse(
    val token: String,
    val userId: Long,
    val fullName: String,
    val email: String,
    val roles: Set<String>,
    val tokenType: String
)