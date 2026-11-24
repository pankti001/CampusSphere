package com.campussphere.app.data.remote.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

private const val FACULTY = "api/college/faculty"
private const val MEMBER = "$FACULTY/{facultyId}"
private const val ASSIGNMENTS = "$MEMBER/assignments"

interface FacultyApi {
    @GET(FACULTY)
    suspend fun members(@Header("Authorization") auth: String): Response<List<FacultyMember>>

    @GET(MEMBER)
    suspend fun profile(@Header("Authorization") auth: String, @Path("facultyId") id: Long): Response<FacultyMember>

    @PUT("$MEMBER/department")
    suspend fun department(@Header("Authorization") auth: String, @Path("facultyId") id: Long,
        @Body input: FacultyDepartmentInput): Response<FacultyMember>

    @GET(ASSIGNMENTS)
    suspend fun assignments(@Header("Authorization") auth: String,
        @Path("facultyId") id: Long): Response<List<FacultyAssignment>>

    @POST(ASSIGNMENTS)
    suspend fun assign(@Header("Authorization") auth: String, @Path("facultyId") id: Long,
        @Body input: FacultyAssignmentInput): Response<FacultyAssignment>

    @DELETE("$ASSIGNMENTS/{assignmentId}")
    suspend fun end(@Header("Authorization") auth: String, @Path("facultyId") id: Long,
        @Path("assignmentId") assignmentId: Long): Response<Unit>

    @GET("api/faculty/assignments")
    suspend fun myAssignments(@Header("Authorization") auth: String): Response<List<FacultyAssignment>>
}

data class FacultyMember(val id: Long, val name: String, val email: String, val departmentId: Long?,
                         val department: String?)
data class FacultyDepartmentInput(val departmentId: Long)
data class FacultyAssignmentInput(val departmentId: Long, val programId: Long, val academicYearId: Long,
                                  val semesterId: Long, val divisionId: Long, val subjectId: Long)
data class FacultyAssignment(val id: Long, val department: String, val program: String, val academicYear: String,
                             val semester: String, val division: String, val subject: String, val subjectCode: String)
