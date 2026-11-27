package com.campussphere.app.data.remote.api

import retrofit2.Response
import retrofit2.http.*

/** College Admin home and account care. The server takes the college from the token; no college id is sent. */
data class CollegeProfile(val id: Long, val code: String, val name: String, val active: Boolean,
                          val createdAt: String?, val updatedAt: String?)

data class CollegeCounts(
    val departments: Long, val programs: Long, val academicYears: Long, val semesters: Long, val divisions: Long,
    val subjects: Long, val classrooms: Long, val faculty: Long, val students: Long, val currentEnrollments: Long,
    val activeAssignments: Long, val pendingFacultyRequests: Long, val pendingStudentRequests: Long,
    val attendanceSessions: Long, val materials: Long, val liveAnnouncements: Long, val publishedTimetables: Long,
    val draftTimetables: Long, val divisionsWithoutTimetable: Long)

data class CollegePerson(val id: Long, val name: String, val email: String, val phone: String?, val active: Boolean,
                         val verified: Boolean, val department: String?, val joinedAt: String?)

data class CollegePersonDetails(val fullName: String, val phone: String?)
data class CollegePersonStatus(val active: Boolean)

interface CollegeAdminApi {
    @GET("api/college/profile")
    suspend fun profile(@Header("Authorization") auth: String): Response<CollegeProfile>

    @GET("api/college/overview")
    suspend fun overview(@Header("Authorization") auth: String): Response<CollegeCounts>

    /** kind is "faculty" or "students"; active null lists everyone. */
    @GET("api/college/accounts/{kind}")
    suspend fun people(@Header("Authorization") auth: String, @Path("kind") kind: String,
                       @Query("active") active: Boolean?, @Query("q") q: String?): Response<List<CollegePerson>>

    @PATCH("api/college/accounts/{kind}/{id}")
    suspend fun update(@Header("Authorization") auth: String, @Path("kind") kind: String, @Path("id") id: Long,
                       @Body details: CollegePersonDetails): Response<CollegePerson>

    @PUT("api/college/accounts/{kind}/{id}/active")
    suspend fun setActive(@Header("Authorization") auth: String, @Path("kind") kind: String, @Path("id") id: Long,
                          @Body status: CollegePersonStatus): Response<CollegePerson>
}
