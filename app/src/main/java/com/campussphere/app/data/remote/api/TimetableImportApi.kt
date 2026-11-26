package com.campussphere.app.data.remote.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/** College Admin: Excel template, upload preview, confirmed import (always drafts) and copying a timetable. */
data class TimetableImportRow(
    val row: Int, val division: String, val day: String, val start: String, val end: String, val type: String,
    val subjectCode: String, val facultyEmail: String, val classroomCode: String, val label: String,
    val errors: List<String>)

data class TimetableImportDivision(val divisionId: Long, val division: String, val periods: Int, val lessons: Int,
                                   val newVersion: Int, val publishedVersion: Int?)

data class TimetableImportPreview(
    val fileName: String, val sha256: String, val rows: Int, val validRows: Int, val errorRows: Int,
    val fileErrors: List<String>, val divisions: List<TimetableImportDivision>, val details: List<TimetableImportRow>,
    val canImport: Boolean)

data class TimetableImported(val drafts: List<TimetableSummary>, val periods: Int)
data class TimetableCopyRequest(val sourceTimetableId: Long, val semesterId: Long, val divisionId: Long)
data class TimetableCopyResult(val timetable: TimetableDetail, val copied: Int, val skipped: List<String>)

interface TimetableImportApi {
    @Streaming
    @GET("api/college/timetables/import/template")
    suspend fun template(@Header("Authorization") auth: String, @Query("semesterId") semesterId: Long?): Response<ResponseBody>

    @Multipart
    @POST("api/college/timetables/import/preview")
    suspend fun preview(@Header("Authorization") auth: String, @Part("semesterId") semesterId: RequestBody,
                        @Part file: MultipartBody.Part): Response<TimetableImportPreview>

    @Multipart
    @POST("api/college/timetables/import")
    suspend fun importFile(@Header("Authorization") auth: String, @Part("semesterId") semesterId: RequestBody,
                           @Part("sha256") sha256: RequestBody, @Part file: MultipartBody.Part): Response<TimetableImported>

    @POST("api/college/timetables/copy")
    suspend fun copy(@Header("Authorization") auth: String, @Body request: TimetableCopyRequest): Response<TimetableCopyResult>
}

/** A personal notice about the faculty member's own lesson. kind: ASSIGNED, CHANGED, REASSIGNED or CANCELLED. */
data class FacultyNotice(
    val id: Long, val kind: String, val title: String, val body: String, val subjectCode: String?, val subject: String?,
    val program: String, val semester: String, val division: String, val academicYear: String, val day: Int,
    val start: String, val end: String, val classroomCode: String?, val startsOn: String, val createdAt: String,
    val readAt: String?)

data class UnreadCount(val count: Long)

/** The signed-in faculty member's notifications; the server takes the faculty member from the token. */
interface FacultyNotificationApi {
    @GET("api/faculty/notifications")
    suspend fun list(@Header("Authorization") auth: String, @Query("unread") unread: Boolean,
                     @Query("page") page: Int): Response<List<FacultyNotice>>

    @GET("api/faculty/notifications/unread-count")
    suspend fun unread(@Header("Authorization") auth: String): Response<UnreadCount>

    @POST("api/faculty/notifications/{id}/read")
    suspend fun read(@Header("Authorization") auth: String, @Path("id") id: Long): Response<Unit>

    @POST("api/faculty/notifications/read-all")
    suspend fun readAll(@Header("Authorization") auth: String): Response<Map<String, Int>>
}
