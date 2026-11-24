package com.campussphere.app.data.remote.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

private const val STUDENTS = "api/college/students"
private const val STUDENT = "$STUDENTS/{studentId}"
private const val ENROLLMENTS = "$STUDENT/enrollments"
private const val AFFILIATION = "api/auth/student-affiliation"
private const val QUEUE = "api/college/student-affiliation-requests"

interface StudentApi {
    @GET(STUDENTS)
    suspend fun students(@Header("Authorization") auth: String): Response<List<StudentMember>>

    @GET(STUDENT)
    suspend fun profile(@Header("Authorization") auth: String,
        @Path("studentId") id: Long): Response<StudentMember>

    @PUT("$STUDENT/department")
    suspend fun department(@Header("Authorization") auth: String, @Path("studentId") id: Long,
        @Body input: StudentDepartmentInput): Response<StudentMember>

    @GET(ENROLLMENTS)
    suspend fun history(@Header("Authorization") auth: String,
        @Path("studentId") id: Long): Response<List<StudentEnrollment>>

    @POST(ENROLLMENTS)
    suspend fun enroll(@Header("Authorization") auth: String, @Path("studentId") id: Long,
        @Body input: StudentEnrollmentInput): Response<StudentEnrollment>

    @DELETE("$ENROLLMENTS/{enrollmentId}")
    suspend fun end(@Header("Authorization") auth: String, @Path("studentId") id: Long,
        @Path("enrollmentId") enrollmentId: Long): Response<Unit>

    @GET("api/student/enrollment")
    suspend fun current(@Header("Authorization") auth: String): Response<List<StudentEnrollment>>

    @GET(AFFILIATION)
    suspend fun myRequest(@Header("Authorization") auth: String): Response<List<StudentAffiliation>>

    @POST(AFFILIATION)
    suspend fun request(@Header("Authorization") auth: String,
        @Body input: StudentCollegeInput): Response<StudentAffiliation>

    @GET(QUEUE)
    suspend fun pending(@Header("Authorization") auth: String): Response<List<StudentAffiliation>>

    @POST("$QUEUE/{requestId}/decision")
    suspend fun decide(@Header("Authorization") auth: String, @Path("requestId") id: Long,
        @Body input: StudentDecisionInput): Response<StudentAffiliation>
}

data class StudentMember(val id: Long, val name: String, val email: String, val departmentId: Long?,
                         val department: String?)
data class StudentDepartmentInput(val departmentId: Long)
data class StudentEnrollmentInput(val departmentId: Long, val programId: Long, val academicYearId: Long,
                                  val semesterId: Long, val divisionId: Long)
data class StudentEnrollment(val id: Long, val college: String, val department: String, val program: String,
                             val academicYear: String, val semester: String, val division: String,
                             val current: Boolean)
data class StudentCollegeInput(val collegeCode: String)
data class StudentDecisionInput(val approved: Boolean, val admissionConfirmed: Boolean)
data class StudentAffiliation(val id: Long, val status: String, val college: String, val collegeCode: String,
                              val name: String, val email: String)
