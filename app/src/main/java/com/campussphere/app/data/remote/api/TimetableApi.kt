package com.campussphere.app.data.remote.api

import retrofit2.Response
import retrofit2.http.*

/**
 * Timetables. College Admins manage /api/college/timetables and /api/college/classrooms; faculty and students read
 * /api/{faculty|student}/timetable. The server takes the college, faculty member and student enrollment from the
 * token, so no college, faculty or division id is ever sent for personal views. Times are "HH:mm" (or "HH:mm:ss").
 * Days are 1 = Monday … 7 = Sunday.
 */
data class TimetableScope(
    val semesterId: Long, val divisionId: Long, val academicYearId: Long, val academicYear: String,
    val departmentId: Long, val department: String, val programId: Long, val program: String,
    val semesterNumber: Int, val semester: String, val division: String)

data class TimetableSummary(
    val id: Long, val version: Int, val status: String, val revision: Long, val semesterId: Long, val divisionId: Long,
    val academicYear: String, val department: String, val program: String, val semester: String, val division: String,
    val lessons: Int, val breaks: Int, val createdAt: String, val updatedAt: String, val publishedAt: String?,
    val archivedAt: String?)

data class TimetableEntry(
    val id: Long, val kind: String, val day: Int, val start: String, val end: String, val assignmentId: Long?,
    val subjectCode: String?, val subject: String?, val facultyId: Long?, val faculty: String?, val classroomId: Long?,
    val classroomCode: String?, val classroom: String?, val label: String?)

data class TimetableProblem(val type: String, val entryId: Long?, val detail: String)
/** notified: faculty notifications created by the publish or unpublish that returned this (otherwise 0). */
data class TimetableDetail(val timetable: TimetableSummary, val entries: List<TimetableEntry>,
                           val problems: List<TimetableProblem>, val ready: Boolean, val notified: Int = 0)
data class TimetableAssignment(val id: Long, val subjectCode: String, val subject: String, val facultyId: Long, val faculty: String)
data class TimetableClassroom(val id: Long, val code: String, val name: String, val capacity: Int?, val active: Boolean)
data class TimetableOptions(val assignments: List<TimetableAssignment>, val classrooms: List<TimetableClassroom>)
data class TimetableClash(val type: String, val day: Int, val start: String, val end: String, val with: String)
data class TimetableEvent(val id: Long, val timetableId: Long, val version: Int, val action: String, val detail: String,
                          val actor: String, val at: String)

data class TimetableSlot(
    val id: Long, val kind: String, val day: Int, val start: String, val end: String, val subjectCode: String?,
    val subject: String?, val faculty: String?, val classroomCode: String?, val classroom: String?, val label: String?,
    val division: String, val program: String, val semester: String, val version: Int, val publishedAt: String?)

/** enrolled is false for a student without a current enrollment; always true for faculty. */
data class TimetableSchedule(val enrolled: Boolean, val title: String?, val version: Int?, val publishedAt: String?,
                             val slots: List<TimetableSlot>)
data class TimetableDay(val date: String, val day: Int, val schedule: TimetableSchedule)

data class TimetableCreate(val semesterId: Long, val divisionId: Long)
data class TimetableEntryInput(val kind: String, val day: Int, val start: String, val end: String,
                               val assignmentId: Long?, val classroomId: Long?, val label: String?)
data class TimetablePublish(val revision: Long)
data class TimetableClassroomInput(val code: String, val name: String, val capacity: Int?, val active: Boolean)

interface TimetableApi {
    @GET("api/college/timetables/scopes")
    suspend fun scopes(@Header("Authorization") auth: String): Response<List<TimetableScope>>

    @GET("api/college/timetables")
    suspend fun list(@Header("Authorization") auth: String, @Query("semesterId") semesterId: Long?,
                     @Query("divisionId") divisionId: Long?, @Query("status") status: String?,
                     @Query("page") page: Int): Response<List<TimetableSummary>>

    @POST("api/college/timetables")
    suspend fun create(@Header("Authorization") auth: String, @Body input: TimetableCreate): Response<TimetableDetail>

    @GET("api/college/timetables/{id}")
    suspend fun detail(@Header("Authorization") auth: String, @Path("id") id: Long): Response<TimetableDetail>

    @GET("api/college/timetables/{id}/preview")
    suspend fun preview(@Header("Authorization") auth: String, @Path("id") id: Long): Response<TimetableDetail>

    @GET("api/college/timetables/{id}/options")
    suspend fun options(@Header("Authorization") auth: String, @Path("id") id: Long): Response<TimetableOptions>

    @GET("api/college/timetables/{id}/history")
    suspend fun history(@Header("Authorization") auth: String, @Path("id") id: Long,
                        @Query("page") page: Int): Response<List<TimetableEvent>>

    @POST("api/college/timetables/{id}/entries")
    suspend fun addEntry(@Header("Authorization") auth: String, @Path("id") id: Long,
                         @Body input: TimetableEntryInput): Response<TimetableDetail>

    @PUT("api/college/timetables/{id}/entries/{entryId}")
    suspend fun updateEntry(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("entryId") entryId: Long,
                            @Body input: TimetableEntryInput): Response<TimetableDetail>

    @DELETE("api/college/timetables/{id}/entries/{entryId}")
    suspend fun deleteEntry(@Header("Authorization") auth: String, @Path("id") id: Long,
                            @Path("entryId") entryId: Long): Response<TimetableDetail>

    @POST("api/college/timetables/{id}/publish")
    suspend fun publish(@Header("Authorization") auth: String, @Path("id") id: Long,
                        @Body input: TimetablePublish): Response<TimetableDetail>

    @POST("api/college/timetables/{id}/archive")
    suspend fun archive(@Header("Authorization") auth: String, @Path("id") id: Long): Response<TimetableDetail>

    @GET("api/college/classrooms")
    suspend fun classrooms(@Header("Authorization") auth: String): Response<List<TimetableClassroom>>

    @POST("api/college/classrooms")
    suspend fun createClassroom(@Header("Authorization") auth: String,
                                @Body input: TimetableClassroomInput): Response<TimetableClassroom>

    @PUT("api/college/classrooms/{id}")
    suspend fun updateClassroom(@Header("Authorization") auth: String, @Path("id") id: Long,
                                @Body input: TimetableClassroomInput): Response<TimetableClassroom>

    /** area is "faculty" or "student". */
    @GET("api/{area}/timetable")
    suspend fun week(@Header("Authorization") auth: String, @Path("area") area: String): Response<TimetableSchedule>

    @GET("api/{area}/timetable/day")
    suspend fun day(@Header("Authorization") auth: String, @Path("area") area: String,
                    @Query("date") date: String?): Response<TimetableDay>
}
