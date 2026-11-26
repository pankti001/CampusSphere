package com.campussphere.app.data.remote.api

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/** Area is "college", "faculty" or "student". The server takes college, author and reader from the token. */
data class AnnouncementItem(
    val id: Long, val title: String, val body: String, val category: String, val priority: String,
    val audience: String, val scope: String, val targetId: Long?, val target: String, val status: String,
    val publishAt: String?, val expiresAt: String?, val createdAt: String, val updatedAt: String,
    val author: String, val authorRole: String, val attachmentName: String?, val attachmentFormat: String?,
    val attachmentSize: Long?, val read: Boolean?, val canEdit: Boolean, val canPublish: Boolean,
    val canArchive: Boolean, val canDelete: Boolean)

/** Sent when creating or editing. expiresAt is an ISO-8601 instant with offset, or null. */
data class AnnouncementInput(val title: String, val body: String, val category: String, val priority: String,
                             val audience: String, val scope: String, val targetId: Long?, val expiresAt: String?)
data class AnnouncementPublish(val publishAt: String?)
data class AnnouncementTarget(val scope: String, val id: Long, val label: String)
data class AnnouncementStats(val audienceSize: Long, val readCount: Long)
data class AnnouncementOverview(val drafts: Long, val scheduled: Long, val published: Long, val expired: Long, val archived: Long)

interface AnnouncementApi {
    @GET("api/{area}/announcements")
    suspend fun list(@Header("Authorization") auth: String, @Path("area") area: String,
                     @Query("category") category: String?, @Query("priority") priority: String?,
                     @Query("status") status: String?, @Query("q") q: String?, @Query("unread") unread: Boolean?,
                     @Query("page") page: Int): Response<List<AnnouncementItem>>

    @GET("api/faculty/announcements/mine")
    suspend fun mine(@Header("Authorization") auth: String, @Query("status") status: String?,
                     @Query("page") page: Int): Response<List<AnnouncementItem>>

    @GET("api/{area}/announcements/targets")
    suspend fun targets(@Header("Authorization") auth: String, @Path("area") area: String): Response<List<AnnouncementTarget>>

    @GET("api/college/announcements/stats")
    suspend fun overview(@Header("Authorization") auth: String): Response<AnnouncementOverview>

    @GET("api/{area}/announcements/{id}")
    suspend fun detail(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<AnnouncementItem>

    @GET("api/college/announcements/{id}/stats")
    suspend fun stats(@Header("Authorization") auth: String, @Path("id") id: Long): Response<AnnouncementStats>

    @POST("api/{area}/announcements")
    suspend fun create(@Header("Authorization") auth: String, @Path("area") area: String,
                       @Body input: AnnouncementInput): Response<AnnouncementItem>

    @PUT("api/{area}/announcements/{id}")
    suspend fun update(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long,
                       @Body input: AnnouncementInput): Response<AnnouncementItem>

    @POST("api/{area}/announcements/{id}/publish")
    suspend fun publish(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long,
                        @Body request: AnnouncementPublish): Response<AnnouncementItem>

    @POST("api/{area}/announcements/{id}/archive")
    suspend fun archive(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<AnnouncementItem>

    @DELETE("api/{area}/announcements/{id}")
    suspend fun delete(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<Unit>

    @POST("api/{area}/announcements/{id}/read")
    suspend fun read(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<Unit>

    @Multipart
    @POST("api/{area}/announcements/{id}/attachment")
    suspend fun attach(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long,
                       @Part file: MultipartBody.Part): Response<AnnouncementItem>

    @DELETE("api/{area}/announcements/{id}/attachment")
    suspend fun detach(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<AnnouncementItem>

    @Streaming
    @GET("api/{area}/announcements/{id}/attachment")
    suspend fun attachment(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<ResponseBody>
}
