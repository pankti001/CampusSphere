package com.campussphere.app.data.remote.api

import retrofit2.http.*

class CollegeApplication(val name: String, val code: String, val website: String?, val fullName: String,
    val email: String, val position: String, val password: String)
data class RegistrationDetails(val id: Long, val name: String, val code: String, val website: String?,
    val fullName: String, val email: String, val position: String, val status: String, val expiresAt: String)
data class RequestPage(val count: Long, val items: List<RegistrationDetails>)
data class Verification(val method: String, val institution: Boolean, val representative: Boolean, val mailbox: Boolean)
data class Rejection(val reason: String)

interface CollegeRegistrationApi {
    @POST("api/college-registration-requests")
    suspend fun submit(@Body application: CollegeApplication)
    @GET("api/platform/college-registration-requests")
    suspend fun pending(@Header("Authorization") auth: String,@Query("after") after: Long): RequestPage
    @GET("api/platform/college-registration-requests/{id}")
    suspend fun detail(@Header("Authorization") auth: String,@Path("id") id: Long): RegistrationDetails
    @POST("api/platform/college-registration-requests/{id}/approve")
    suspend fun approve(@Header("Authorization") auth: String,@Path("id") id: Long,@Body evidence: Verification)
    @POST("api/platform/college-registration-requests/{id}/reject")
    suspend fun reject(@Header("Authorization") auth: String,@Path("id") id: Long,@Body rejection: Rejection)
}