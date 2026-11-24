package com.campussphere.app.data.remote.api

import retrofit2.http.*

data class PlatformOverview(val totalColleges: Long?, val activeColleges: Long?,
    val inactiveColleges: Long?, val pendingInvitations: Long?)
data class College(val id: Long, val name: String, val code: String, val active: Boolean)
data class CollegeInput(val name: String, val code: String)
data class CollegeStatus(val active: Boolean)
data class InvitationInput(val email: String)
data class CollegeInvitation(val id: Long, val email: String, val status: String, val expiresAt: String)
class IssuedInvitation(val invitation: CollegeInvitation, val token: String) {
    override fun toString() = "IssuedInvitation[redacted]"
}

interface PlatformApi {
    @GET("api/platform/overview")
    suspend fun overview(@Header("Authorization") auth: String): PlatformOverview

    @GET("api/platform/colleges")
    suspend fun colleges(@Header("Authorization") auth: String, @Query("search") search: String?,
        @Query("after") after: Long, @Query("limit") limit: Int): List<College>

    @GET("api/platform/colleges/{id}")
    suspend fun college(@Header("Authorization") auth: String, @Path("id") id: Long): College

    @POST("api/platform/colleges")
    suspend fun create(@Header("Authorization") auth: String, @Body body: CollegeInput): College

    @PATCH("api/platform/colleges/{id}")
    suspend fun edit(@Header("Authorization") auth: String, @Path("id") id: Long,
        @Body body: CollegeInput): College

    @PUT("api/platform/colleges/{id}/status")
    suspend fun status(@Header("Authorization") auth: String, @Path("id") id: Long,
        @Body body: CollegeStatus): College

    @GET("api/platform/colleges/{id}/admin-invitations")
    suspend fun invitations(@Header("Authorization") auth: String, @Path("id") id: Long,
        @Query("before") before: Long, @Query("limit") limit: Int): List<CollegeInvitation>

    @POST("api/platform/colleges/{id}/admin-invitations/{invitationId}/revoke")
    suspend fun revoke(@Header("Authorization") auth: String, @Path("id") collegeId: Long,
        @Path("invitationId") invitationId: Long)

    @POST("api/platform/colleges/{id}/admin-invitations")
    suspend fun invite(@Header("Authorization") auth: String, @Path("id") id: Long,
        @Body body: InvitationInput): IssuedInvitation
}
