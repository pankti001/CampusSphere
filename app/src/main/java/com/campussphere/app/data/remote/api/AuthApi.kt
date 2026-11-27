package com.campussphere.app.data.remote.api

import com.campussphere.app.data.remote.dto.AccountStatusDto
import com.campussphere.app.data.remote.dto.CollegeAdminInvitationAcceptance
import com.campussphere.app.data.remote.dto.DepartmentSummary
import com.campussphere.app.data.remote.dto.DepartmentInput
import com.campussphere.app.data.remote.dto.FacultyRequestDto
import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.LoginResponse
import com.campussphere.app.data.remote.dto.RegisterRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Path

interface AuthApi {
    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<String>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("api/auth/status")
    suspend fun status(@Header("Authorization") authorization: String): Response<AccountStatusDto>

    @GET("api/{role}/dashboard")
    suspend fun dashboard(
        @Path("role") role: String,
        @Header("Authorization") authorization: String
    ): Response<String>

    @POST("api/auth/college-admin-invitations/accept")
    suspend fun acceptCollegeInvitation(@Body request: CollegeAdminInvitationAcceptance): Response<Void>

    @GET("api/college/departments")
    suspend fun departments(@Header("Authorization") authorization: String): Response<List<DepartmentSummary>>

    @POST("api/college/departments")
    suspend fun createDepartment(@Header("Authorization") authorization: String,
        @Body input: DepartmentInput): Response<DepartmentSummary>

    @PUT("api/college/departments/{id}/active")
    suspend fun setDepartmentActive(@Header("Authorization") authorization: String, @Path("id") id: Long,
        @Body body: Map<String, Boolean>): Response<com.campussphere.app.data.remote.dto.AcademicCatalogItem>

    @PATCH("api/college/departments/{id}")
    suspend fun updateDepartment(@Header("Authorization") authorization: String, @Path("id") id: Long,
        @Body input: DepartmentInput): Response<DepartmentSummary>

    @POST("api/auth/faculty/reapply")
    suspend fun reapply(@Header("Authorization") authorization: String): Response<FacultyRequestDto>
}
