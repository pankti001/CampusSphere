package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.*
import com.google.gson.JsonParser
import retrofit2.Response
import java.io.IOException

class AttendanceFailure(val status: Int, message: String) : Exception(message)

class AttendanceRepository(private val api: AttendanceApi) {
    suspend fun assignments(auth: String) = api.assignments(auth).data()
    suspend fun sessions(auth: String, page: Int) = api.sessions(auth, page).data()
    suspend fun create(auth: String, input: AttendanceCreate) = api.create(auth, input).data()
    suspend fun session(auth: String, id: Long, admin: Boolean) =
        (if (admin) api.adminSession(auth, id) else api.facultySession(auth, id)).data()
    suspend fun save(auth: String, id: Long, input: AttendanceMarks, submit: Boolean) =
        (if (submit) api.submit(auth, id, input) else api.draft(auth, id, input)).data()
    suspend fun correct(auth: String, id: Long, student: Long, input: AttendanceCorrectionInput, admin: Boolean) =
        (if (admin) api.adminCorrection(auth, id, student, input) else api.facultyCorrection(auth, id, student, input)).data()
    suspend fun student(auth: String, filters: Map<String, String>) = api.student(auth, filters).data()
    suspend fun studentSubjects(auth: String) = api.studentSubjects(auth).data()
    suspend fun report(auth: String, filters: Map<String, String>) = api.report(auth, filters).data()
    suspend fun options(auth: String) = api.options(auth).data()
    suspend fun alerts(auth: String, page: Int) = api.alerts(auth, page).data()
    suspend fun policy(auth: String) = api.policy(auth).data()
    suspend fun policy(auth: String, input: AttendancePolicy) = api.updatePolicy(auth, input).data()
    suspend fun lock(auth: String, id: Long, revision: Long) = api.lock(auth, id, AttendanceRevision(revision)).data()
    suspend fun audit(auth: String, id: Long, page: Int) = api.audit(auth, id, page).data()

    private fun <T> Response<T>.data(): T {
        if (isSuccessful) return body() ?: throw IOException("CampusSphere returned no attendance data.")
        val code = runCatching {
            errorBody()?.use { JsonParser.parseString(it.string()).asJsonObject.get("code")?.asString }
        }.getOrNull()
        throw AttendanceFailure(code(), when (code) {
            "ATTENDANCE_NOT_FOUND" -> "This session or assignment is no longer available."
            "ATTENDANCE_SESSION_CONFLICT" -> "This lecture already has a session. Open it from history."
            "ATTENDANCE_STATE_CONFLICT" -> "The session changed or can no longer be edited. Refresh to check it."
            "ATTENDANCE_ROSTER_INVALID" -> "Check the enrolled roster and mark every student before submitting."
            "ATTENDANCE_SESSION_INVALID" -> "Check the lecture date, number and times."
            "ATTENDANCE_POLICY_INVALID" -> "Check the attendance thresholds and correction hours."
            else -> when (code()) {
                400 -> "Check the attendance information and try again."
                401 -> "Sign in again to view attendance."
                403 -> "Your account does not have access to this attendance area."
                503 -> "The campus service is temporarily unavailable."
                else -> "CampusSphere could not complete the attendance request."
            }
        })
    }
}
