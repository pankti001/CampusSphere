package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.AuthApi
import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.LoginResponse
import com.campussphere.app.data.remote.dto.RegisterRequest

class AuthRepository(
    private val authApi: AuthApi
) {

    suspend fun register(
        request: RegisterRequest
    ): Result<String> {

        return try {
            val response = authApi.register(request)

            if (response.isSuccessful) {
                Result.success(response.body() ?: "Registration successful")
            } else {
                Result.failure(
                    Exception(
                        response.errorBody()?.string()
                            ?: "Registration failed"
                    )
                )
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(
        request: LoginRequest
    ): Result<LoginResponse> {

        return try {
            val response = authApi.login(request)

            if (response.isSuccessful) {
                val body = response.body()

                if (body != null) {
                    Result.success(body)
                } else {
                    Result.failure(Exception("Empty login response"))
                }

            } else {
                Result.failure(
                    Exception(
                        response.errorBody()?.string()
                            ?: "Login failed"
                    )
                )
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}