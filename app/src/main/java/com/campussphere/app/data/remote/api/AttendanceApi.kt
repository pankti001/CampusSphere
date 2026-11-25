package com.campussphere.app.data.remote.api

import retrofit2.Response
import retrofit2.http.*

private const val FACULTY = "api/faculty/attendance"
private const val STUDENT = "api/student/attendance"
private const val ADMIN = "api/college/attendance"
private const val FACULTY_SESSION = "$FACULTY/sessions/{id}"
private const val ADMIN_SESSION = "$ADMIN/sessions/{id}"

interface AttendanceApi {
    @GET("$FACULTY/assignments") suspend fun assignments(@Header("Authorization") auth: String): Response<List<AttendanceAssignment>>
    @GET("$FACULTY/sessions") suspend fun sessions(@Header("Authorization") auth: String, @Query("page") page: Int): Response<List<AttendanceSession>>
    @POST("$FACULTY/sessions") suspend fun create(@Header("Authorization") auth: String, @Body input: AttendanceCreate): Response<AttendanceDetail>
    @GET(FACULTY_SESSION) suspend fun facultySession(@Header("Authorization") auth: String, @Path("id") id: Long): Response<AttendanceDetail>
    @PUT("$FACULTY_SESSION/draft") suspend fun draft(@Header("Authorization") auth: String, @Path("id") id: Long,
        @Body input: AttendanceMarks): Response<AttendanceDetail>
    @POST("$FACULTY_SESSION/submit") suspend fun submit(@Header("Authorization") auth: String, @Path("id") id: Long,
        @Body input: AttendanceMarks): Response<AttendanceDetail>
    @POST("$FACULTY_SESSION/records/{student}/corrections")
    suspend fun facultyCorrection(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("student") student: Long,
        @Body input: AttendanceCorrectionInput): Response<AttendanceDetail>

    @GET(STUDENT) suspend fun student(@Header("Authorization") auth: String, @QueryMap filters: Map<String, String>): Response<AttendanceStudent>
    @GET("$STUDENT/subjects") suspend fun studentSubjects(@Header("Authorization") auth: String): Response<List<AttendanceOption>>

    @GET("$ADMIN/reports") suspend fun report(@Header("Authorization") auth: String, @QueryMap filters: Map<String, String>): Response<AttendanceReport>
    @GET("$ADMIN/report-options") suspend fun options(@Header("Authorization") auth: String): Response<AttendanceOptions>
    @GET("$ADMIN/alerts") suspend fun alerts(@Header("Authorization") auth: String, @Query("page") page: Int): Response<List<AttendanceAlert>>
    @GET("$ADMIN/policy") suspend fun policy(@Header("Authorization") auth: String): Response<AttendancePolicy>
    @PUT("$ADMIN/policy") suspend fun updatePolicy(@Header("Authorization") auth: String,
        @Body input: AttendancePolicy): Response<AttendancePolicy>
    @GET(ADMIN_SESSION) suspend fun adminSession(@Header("Authorization") auth: String, @Path("id") id: Long): Response<AttendanceDetail>
    @POST("$ADMIN_SESSION/lock") suspend fun lock(@Header("Authorization") auth: String, @Path("id") id: Long,
        @Body input: AttendanceRevision): Response<AttendanceDetail>
    @POST("$ADMIN_SESSION/records/{student}/corrections")
    suspend fun adminCorrection(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("student") student: Long,
        @Body input: AttendanceCorrectionInput): Response<AttendanceDetail>
    @GET("$ADMIN_SESSION/audit") suspend fun audit(@Header("Authorization") auth: String, @Path("id") id: Long,
        @Query("page") page: Int): Response<List<AttendanceCorrection>>
}

enum class AttendanceMark { PRESENT, ABSENT, LATE, EXCUSED }
enum class AttendanceSessionState { DRAFT, SUBMITTED, LOCKED }
enum class AttendanceSeverity { NORMAL, WARNING, CRITICAL }
data class AttendanceCreate(val assignmentId: Long, val date: String, val lectureNumber: Int,
                            val startTime: String?, val endTime: String?)
data class AttendanceMarkInput(val studentId: Long, val status: AttendanceMark)
data class AttendanceMarks(val revision: Long, val records: List<AttendanceMarkInput>)
data class AttendanceRevision(val revision: Long)
data class AttendanceCorrectionInput(val revision: Long, val status: AttendanceMark, val reason: String)
data class AttendanceAssignment(val id: Long, val subject: String, val division: String, val semester: String, val program: String)
data class AttendanceSession(val id: Long, val assignmentId: Long, val status: AttendanceSessionState, val revision: Long,
                             val date: String, val lectureNumber: Int, val startTime: String?, val endTime: String?,
                             val submittedAt: String?, val subject: String, val division: String, val faculty: String,
                             val department: String, val program: String, val semester: String)
data class AttendanceRosterRow(val studentId: Long, val name: String, val status: AttendanceMark?)
data class AttendanceDetail(val session: AttendanceSession, val roster: List<AttendanceRosterRow>)
data class AttendanceOption(val id: Long, val label: String)
data class AttendanceOptions(val filters: Map<String, List<AttendanceOption>>)
data class AttendancePolicy(val warningThreshold: java.math.BigDecimal, val criticalThreshold: java.math.BigDecimal,
                            val countLate: Boolean, val excludeExcused: Boolean, val correctionHours: Int)
data class AttendanceSummary(val present: Long, val absent: Long, val late: Long, val excused: Long,
                             val attended: Long, val eligible: Long, val percentage: java.math.BigDecimal?,
                             val severity: AttendanceSeverity)
data class AttendanceSubject(val subjectId: Long, val subject: String, val summary: AttendanceSummary)
data class AttendanceStudentSummary(val studentId: Long, val student: String, val summary: AttendanceSummary)
data class AttendanceHistory(val sessionId: Long, val date: String, val lectureNumber: Int, val subject: String,
                             val division: String, val status: AttendanceMark)
data class AttendanceAlert(val id: Long, val studentId: Long, val student: String, val severity: AttendanceSeverity,
                           val percentage: java.math.BigDecimal, val createdAt: String, val resolvedAt: String?)
data class AttendanceStudent(val policy: AttendancePolicy, val overall: AttendanceSummary, val subjects: List<AttendanceSubject>,
                             val history: List<AttendanceHistory>, val alerts: List<AttendanceAlert>)
data class AttendanceReport(val policy: AttendancePolicy, val overall: AttendanceSummary, val subjects: List<AttendanceSubject>,
                            val students: List<AttendanceStudentSummary>, val sessions: List<AttendanceSession>)
data class AttendanceCorrection(val id: Long, val studentId: Long, val actor: String, val previousStatus: AttendanceMark,
                                val newStatus: AttendanceMark, val reason: String, val correctedAt: String)
