package com.campussphere.app.data.remote.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/** Area is "faculty", "student" or "college"; the server derives college and identity from the token. */
data class MaterialAssignment(val id: Long, val subjectId: Long, val subject: String, val divisionId: Long,
                              val division: String, val semester: String, val program: String, val academicYear: String)

data class MaterialItem(val id: Long, val title: String, val description: String, val topic: String?, val type: String,
                        val subjectId: Long, val subject: String, val divisionId: Long, val division: String,
                        val semester: String, val program: String, val academicYear: String, val facultyId: Long?,
                        val faculty: String, val assignmentId: Long, val fileName: String, val format: String,
                        val mimeType: String, val sizeBytes: Long, val status: String, val moderationNote: String?,
                        val uploadedAt: String, val updatedAt: String, val canEdit: Boolean, val canModerate: Boolean)

data class MaterialFields(val title: String, val description: String, val topic: String?, val type: String)
data class MaterialModeration(val action: String, val note: String?)
data class MaterialEvent(val action: String, val actor: String, val note: String?, val time: String)

interface MaterialApi {
    @GET("api/faculty/materials/assignments")
    suspend fun assignments(@Header("Authorization") auth: String): Response<List<MaterialAssignment>>

    @GET("api/{area}/materials")
    suspend fun list(@Header("Authorization") auth: String, @Path("area") area: String): Response<List<MaterialItem>>

    @GET("api/{area}/materials/{id}")
    suspend fun detail(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<MaterialItem>

    @Multipart
    @POST("api/faculty/materials")
    suspend fun upload(@Header("Authorization") auth: String,
                       @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
                       @Part file: MultipartBody.Part): Response<MaterialItem>

    @PATCH("api/faculty/materials/{id}")
    suspend fun update(@Header("Authorization") auth: String, @Path("id") id: Long, @Body fields: MaterialFields): Response<MaterialItem>

    @DELETE("api/faculty/materials/{id}")
    suspend fun delete(@Header("Authorization") auth: String, @Path("id") id: Long): Response<Unit>

    @Streaming
    @GET("api/{area}/materials/{id}/file")
    suspend fun file(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<ResponseBody>

    @GET("api/college/materials/{id}/events")
    suspend fun events(@Header("Authorization") auth: String, @Path("id") id: Long): Response<List<MaterialEvent>>

    @POST("api/college/materials/{id}/moderation")
    suspend fun moderate(@Header("Authorization") auth: String, @Path("id") id: Long, @Body request: MaterialModeration): Response<MaterialItem>
}
