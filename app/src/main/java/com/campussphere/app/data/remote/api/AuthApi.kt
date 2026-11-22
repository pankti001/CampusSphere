package com.campussphere.app.data.remote.api

import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.LoginResponse
import com.campussphere.app.data.remote.dto.RegisterRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApi {

    @POST("api/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<String>

    @POST("api/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<LoginResponse>
}