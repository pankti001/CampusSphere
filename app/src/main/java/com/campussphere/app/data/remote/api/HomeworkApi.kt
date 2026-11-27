package com.campussphere.app.data.remote.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Assignments (homework). The server derives college, faculty and student identity from the token; ids sent here
 * only pick among records the signed-in account may already see. Times are ISO-8601 with an offset.
 */
data class HomeworkClass(val classId: Long, val subjectId: Long, val subjectCode: String, val subject: String,
                         val divisionId: Long, val division: String, val semesterId: Long, val semester: String,
                         val programId: Long, val program: String, val academicYearId: Long, val academicYear: String)

data class HomeworkCounts(val roster: Long, val submitted: Long, val pending: Long, val late: Long, val toMark: Long,
                          val evaluated: Long, val returned: Long)

data class HomeworkMine(val submissionId: Long, val attempt: Int, val status: String, val late: Boolean,
                        val marks: Double?, val submittedAt: String?)

data class HomeworkItem(val id: Long, val title: String, val description: String, val instructions: String,
                        val issueAt: String, val dueAt: String, val maxMarks: Double, val allowedFormats: List<String>,
                        val maxFileBytes: Int, val latePolicy: String, val lateUntil: String?, val resubmission: String,
                        val status: String, val publishedAt: String?, val closedAt: String?, val classId: Long,
                        val subjectId: Long, val subjectCode: String, val subject: String, val divisionId: Long,
                        val division: String, val semesterId: Long, val semester: String, val programId: Long,
                        val program: String, val academicYearId: Long, val academicYear: String, val facultyId: Long?,
                        val faculty: String, val attachmentName: String?, val attachmentFormat: String?,
                        val attachmentSize: Long?, val createdAt: String, val updatedAt: String,
                        val acceptingSubmissions: Boolean, val overdue: Boolean, val canEdit: Boolean,
                        val counts: HomeworkCounts?, val mine: HomeworkMine?)

data class HomeworkInput(val classId: Long?, val title: String, val description: String, val instructions: String,
                         val issueAt: String?, val dueAt: String, val maxMarks: Double, val allowedFormats: List<String>,
                         val maxFileBytes: Int?, val latePolicy: String, val lateUntil: String?, val resubmission: String)

data class FacultyHomeworkSummary(val published: Long, val drafts: Long, val closed: Long, val toMark: Long,
                                  val evaluated: Long)

data class StudentHomeworkSummary(val pending: Long, val dueThisWeek: Long, val submitted: Long, val evaluated: Long,
                                  val unreadNotices: Long, val upcoming: List<HomeworkItem>)

data class HomeworkAdminStats(val published: Long, val closed: Long, val archived: Long, val drafts: Long,
                              val submissions: Long, val pending: Long, val toMark: Long, val evaluated: Long,
                              val late: Long)

data class HomeworkOption(val id: Long, val label: String)

data class HomeworkFilters(val programs: List<HomeworkOption>, val academicYears: List<HomeworkOption>,
                           val semesters: List<HomeworkOption>, val divisions: List<HomeworkOption>,
                           val subjects: List<HomeworkOption>, val faculty: List<HomeworkOption>)

data class HomeworkSubmission(val id: Long, val assignmentId: Long, val student: String, val studentEmail: String?,
                              val attempt: Int, val status: String, val late: Boolean, val submittedAt: String,
                              val fileName: String, val format: String, val sizeBytes: Long, val comment: String?,
                              val marks: Double?, val feedback: String?, val evaluatedBy: String?,
                              val evaluatedAt: String?)

data class HomeworkRosterRow(val studentId: Long, val student: String, val email: String?, val submissionId: Long?,
                             val attempt: Int?, val status: String, val late: Boolean, val submittedAt: String?,
                             val marks: Double?)

data class HomeworkReview(val submission: HomeworkSubmission, val attempts: List<HomeworkSubmission>, val maxMarks: Double)
data class HomeworkStudentView(val assignment: HomeworkItem, val attempts: List<HomeworkSubmission>)
data class HomeworkEvaluation(val marks: Double, val feedback: String?)
data class HomeworkReturn(val feedback: String)
data class HomeworkNotice(val id: Long, val kind: String, val title: String, val body: String, val assignmentId: Long,
                          val dueAt: String?, val createdAt: String, val readAt: String?)
data class HomeworkCount(val count: Long)
data class HomeworkEvent(val action: String, val actor: String, val note: String?, val time: String)

interface HomeworkApi {
    // ---- Faculty ----
    @GET("api/faculty/homework/classes")
    suspend fun classes(@Header("Authorization") auth: String): Response<List<HomeworkClass>>

    @GET("api/faculty/homework/summary")
    suspend fun facultySummary(@Header("Authorization") auth: String): Response<FacultyHomeworkSummary>

    @GET("api/faculty/homework")
    suspend fun facultyList(@Header("Authorization") auth: String, @Query("subjectId") subjectId: Long?,
                            @Query("divisionId") divisionId: Long?, @Query("status") status: String?,
                            @Query("q") q: String?, @Query("page") page: Int): Response<List<HomeworkItem>>

    @POST("api/faculty/homework")
    suspend fun create(@Header("Authorization") auth: String, @Body input: HomeworkInput): Response<HomeworkItem>

    @GET("api/{area}/homework/{id}")
    suspend fun detail(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<HomeworkItem>

    @PUT("api/faculty/homework/{id}")
    suspend fun update(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: HomeworkInput): Response<HomeworkItem>

    /** action is "publish", "close" or "archive". */
    @POST("api/faculty/homework/{id}/{action}")
    suspend fun lifecycle(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("action") action: String): Response<HomeworkItem>

    @Multipart
    @POST("api/faculty/homework/{id}/attachment")
    suspend fun attach(@Header("Authorization") auth: String, @Path("id") id: Long, @Part file: MultipartBody.Part): Response<HomeworkItem>

    @DELETE("api/faculty/homework/{id}/attachment")
    suspend fun detach(@Header("Authorization") auth: String, @Path("id") id: Long): Response<HomeworkItem>

    @Streaming
    @GET("api/{area}/homework/{id}/attachment")
    suspend fun attachment(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<ResponseBody>

    @GET("api/{area}/homework/{id}/history")
    suspend fun history(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long): Response<List<HomeworkEvent>>

    @GET("api/faculty/homework/{id}/submissions")
    suspend fun roster(@Header("Authorization") auth: String, @Path("id") id: Long, @Query("filter") filter: String,
                       @Query("page") page: Int): Response<List<HomeworkRosterRow>>

    @GET("api/faculty/homework/{id}/submissions/{submissionId}")
    suspend fun review(@Header("Authorization") auth: String, @Path("id") id: Long,
                       @Path("submissionId") submissionId: Long): Response<HomeworkReview>

    @Streaming
    @GET("api/{area}/homework/{id}/submissions/{submissionId}/file")
    suspend fun submissionFile(@Header("Authorization") auth: String, @Path("area") area: String, @Path("id") id: Long,
                               @Path("submissionId") submissionId: Long): Response<ResponseBody>

    @PUT("api/faculty/homework/{id}/submissions/{submissionId}/evaluation")
    suspend fun evaluate(@Header("Authorization") auth: String, @Path("id") id: Long,
                         @Path("submissionId") submissionId: Long, @Body evaluation: HomeworkEvaluation): Response<HomeworkSubmission>

    @POST("api/faculty/homework/{id}/submissions/{submissionId}/return")
    suspend fun returnWork(@Header("Authorization") auth: String, @Path("id") id: Long,
                           @Path("submissionId") submissionId: Long, @Body request: HomeworkReturn): Response<HomeworkSubmission>

    // ---- Students ----
    @GET("api/student/homework/summary")
    suspend fun studentSummary(@Header("Authorization") auth: String): Response<StudentHomeworkSummary>

    @GET("api/student/homework")
    suspend fun studentList(@Header("Authorization") auth: String, @Query("tab") tab: String,
                            @Query("page") page: Int): Response<List<HomeworkItem>>

    @GET("api/student/homework/{id}")
    suspend fun studentDetail(@Header("Authorization") auth: String, @Path("id") id: Long): Response<HomeworkStudentView>

    @Multipart
    @POST("api/student/homework/{id}/submissions")
    suspend fun submit(@Header("Authorization") auth: String, @Path("id") id: Long, @Part file: MultipartBody.Part,
                       @Part("comment") comment: RequestBody?): Response<HomeworkSubmission>

    @GET("api/student/homework-notifications")
    suspend fun notices(@Header("Authorization") auth: String, @Query("unreadOnly") unreadOnly: Boolean,
                        @Query("page") page: Int): Response<List<HomeworkNotice>>

    @POST("api/student/homework-notifications/{id}/read")
    suspend fun markRead(@Header("Authorization") auth: String, @Path("id") id: Long): Response<Unit>

    // ---- College Admin (read-only) ----
    @GET("api/college/homework/filters")
    suspend fun adminFilters(@Header("Authorization") auth: String): Response<HomeworkFilters>

    @GET("api/college/homework/stats")
    suspend fun adminStats(@Header("Authorization") auth: String, @QueryMap filters: Map<String, String>): Response<HomeworkAdminStats>

    @GET("api/college/homework")
    suspend fun adminList(@Header("Authorization") auth: String, @QueryMap filters: Map<String, String>): Response<List<HomeworkItem>>
}
