package com.campussphere.app.data.remote.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Academic Resources: syllabus versions ("syllabus") and previous-year question papers ("papers").
 * Area is "faculty", "student" or "college"; the server works out the college and the person from the token and never
 * takes them from the app.
 */
data class ResourceUnit(val number: Int, val title: String, val description: String?, val topics: String?,
                        val objectives: String?, val references: String?)

data class ResourceItem(val id: Long, val kind: String, val subjectId: Long, val subjectCode: String, val subject: String,
                        val program: String, val semester: String, val academicYear: String, val faculty: String,
                        val title: String, val description: String?, val versionLabel: String?, val effectiveFrom: String?,
                        val current: Boolean, val examType: String?, val examTypeLabel: String?, val examYear: Int?,
                        val examSession: String?, val fileName: String, val sizeBytes: Long, val status: String,
                        val moderationNote: String?, val createdAt: String, val updatedAt: String, val canEdit: Boolean,
                        val canModerate: Boolean, val units: List<ResourceUnit>?)

data class ResourceSemester(val semesterId: Long, val semester: String, val academicYear: String, val program: String,
                            val current: Boolean)

data class ResourceSubject(val subjectId: Long, val code: String, val name: String, val semesterId: Long,
                           val semester: String, val academicYear: String, val syllabusVersions: Long, val papers: Long)

data class TeachableSubject(val subjectId: Long, val code: String, val name: String, val department: String,
                            val program: String, val semester: String, val academicYear: String)

data class SyllabusEdit(val title: String, val description: String, val versionLabel: String, val effectiveFrom: String?)
data class PaperEdit(val examType: String, val examTypeLabel: String?, val examYear: Int, val examSession: String,
                     val title: String, val description: String)
data class ResourceModeration(val action: String, val note: String?)
data class ResourceEvent(val action: String, val actor: String, val note: String?, val time: String)
data class ResourceCoverage(val subjectId: Long, val code: String, val subject: String, val program: String,
                            val semester: String, val academicYear: String, val syllabusVersions: Long,
                            val hasCurrentSyllabus: Boolean, val papers: Long)
data class ResourceCount(val label: String, val count: Long)
data class ResourceStats(val syllabusByStatus: List<ResourceCount>, val papersByStatus: List<ResourceCount>,
                         val uploadsByMonth: List<ResourceCount>, val uploadsByFaculty: List<ResourceCount>)

interface ResourceApi {
    // ---- Shared reading (kind is "syllabus" or "papers") ----
    @GET("api/{area}/resources/{kind}")
    suspend fun list(@Header("Authorization") auth: String, @Path("area") area: String, @Path("kind") kind: String,
                     @QueryMap filters: Map<String, String>): Response<List<ResourceItem>>

    @GET("api/{area}/resources/{kind}/{id}")
    suspend fun detail(@Header("Authorization") auth: String, @Path("area") area: String, @Path("kind") kind: String,
                       @Path("id") id: Long): Response<ResourceItem>

    @Streaming
    @GET("api/{area}/resources/{kind}/{id}/file")
    suspend fun file(@Header("Authorization") auth: String, @Path("area") area: String, @Path("kind") kind: String,
                     @Path("id") id: Long): Response<ResponseBody>

    // ---- Student ----
    @GET("api/student/resources/semesters")
    suspend fun semesters(@Header("Authorization") auth: String): Response<List<ResourceSemester>>

    @GET("api/student/resources/subjects")
    suspend fun subjects(@Header("Authorization") auth: String, @Query("semesterId") semesterId: Long?): Response<List<ResourceSubject>>

    // ---- Faculty ----
    @GET("api/faculty/resources/subjects")
    suspend fun teachable(@Header("Authorization") auth: String): Response<List<TeachableSubject>>

    @Multipart
    @POST("api/faculty/resources/{kind}")
    suspend fun upload(@Header("Authorization") auth: String, @Path("kind") kind: String,
                       @PartMap fields: Map<String, @JvmSuppressWildcards RequestBody>,
                       @Part file: MultipartBody.Part): Response<ResourceItem>

    @PATCH("api/faculty/resources/syllabus/{id}")
    suspend fun editSyllabus(@Header("Authorization") auth: String, @Path("id") id: Long, @Body fields: SyllabusEdit): Response<ResourceItem>

    @PATCH("api/faculty/resources/papers/{id}")
    suspend fun editPaper(@Header("Authorization") auth: String, @Path("id") id: Long, @Body fields: PaperEdit): Response<ResourceItem>

    @PUT("api/faculty/resources/syllabus/{id}/units")
    suspend fun saveUnits(@Header("Authorization") auth: String, @Path("id") id: Long, @Body units: List<ResourceUnit>): Response<ResourceItem>

    @Multipart
    @POST("api/faculty/resources/{kind}/{id}/file")
    suspend fun replaceFile(@Header("Authorization") auth: String, @Path("kind") kind: String, @Path("id") id: Long,
                            @Part file: MultipartBody.Part): Response<ResourceItem>

    @POST("api/faculty/resources/{kind}/{id}/{action}")
    suspend fun archive(@Header("Authorization") auth: String, @Path("kind") kind: String, @Path("id") id: Long,
                        @Path("action") action: String): Response<ResourceItem>

    // ---- College Admin ----
    @GET("api/college/resources/{kind}/{id}/events")
    suspend fun events(@Header("Authorization") auth: String, @Path("kind") kind: String, @Path("id") id: Long): Response<List<ResourceEvent>>

    @POST("api/college/resources/{kind}/{id}/moderation")
    suspend fun moderate(@Header("Authorization") auth: String, @Path("kind") kind: String, @Path("id") id: Long,
                         @Body request: ResourceModeration): Response<ResourceItem>

    @GET("api/college/resources/coverage")
    suspend fun coverage(@Header("Authorization") auth: String, @Query("semesterId") semesterId: Long?): Response<List<ResourceCoverage>>

    @GET("api/college/resources/stats")
    suspend fun stats(@Header("Authorization") auth: String): Response<ResourceStats>
}
