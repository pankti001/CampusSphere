package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.*
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

/**
 * A failed timetable request. [status] is the HTTP status (0 for network problems). A rejected save carries the
 * [clashes] it overlaps; a rejected publication carries the [problems] to fix. The server's own text is never shown.
 */
class TimetableFailure(val status: Int, val code: String?, message: String,
                       val clashes: List<TimetableClash> = emptyList(),
                       val problems: List<TimetableProblem> = emptyList()) : Exception(message)

fun timetableErrorMessage(status: Int, code: String?): String = when (code) {
    "TIMETABLE_CONFLICT" -> "This time clashes with another booking."
    "TIMETABLE_NOT_READY" -> "Fix the problems listed below before publishing."
    "TIMETABLE_DRAFT_EXISTS" -> "A draft already exists for this class. Open it instead."
    "TIMETABLE_STATE_CONFLICT" -> "The timetable changed or can no longer be changed this way. Refresh and try again."
    "TIMETABLE_NOT_FOUND" -> "This timetable is no longer available."
    "TIMETABLE_SCOPE_INVALID" -> "Choose a semester and division from your college."
    "TIMETABLE_ENTRY_INVALID" -> "Use a valid day and times in 5-minute steps (lessons up to 4 hours, breaks up to 2)."
    "TIMETABLE_ASSIGNMENT_INVALID" -> "Choose a subject whose faculty member currently teaches this division."
    "TIMETABLE_CLASSROOM_INVALID" -> "Choose an active classroom."
    "CLASSROOM_INVALID" -> "Enter a classroom code (letters, digits, - or _), a name and an optional capacity up to 2000."
    "CLASSROOM_CODE_TAKEN" -> "Another classroom already uses this code."
    "CLASSROOM_NOT_FOUND" -> "This classroom is no longer available."
    "TIMETABLE_IMPORT_INVALID" -> "Fix the problems shown in the preview, then upload the corrected file."
    "TIMETABLE_IMPORT_CHANGED" -> "The file changed since the preview. Preview it again before importing."
    "NOTIFICATION_NOT_FOUND" -> "This notification is no longer available."
    "VERIFICATION_REQUIRED" -> "Your account must be verified first."
    else -> when (status) {
        401 -> "Your session expired. Sign in again."
        403 -> "You do not have permission for this action."
        404 -> "This timetable is no longer available."
        in 500..599 -> "CampusSphere could not complete the request. Try again shortly."
        else -> "Request failed. Check your connection and try again."
    }
}

internal data class TimetableErrorBody(val code: String?, val clashes: List<TimetableClash>?, val problems: List<TimetableProblem>?)

class TimetableRepository(private val api: TimetableApi) {
    private val gson = Gson()

    suspend fun scopes(auth: String) = call { api.scopes(auth) }
    suspend fun versions(auth: String, semesterId: Long, divisionId: Long) = call { api.list(auth, semesterId, divisionId, null, 0) }
    /** Every timetable of the college (newest academic year first), for choosing one to copy. */
    suspend fun all(auth: String, page: Int = 0) = call { api.list(auth, null, null, null, page) }
    suspend fun create(auth: String, semesterId: Long, divisionId: Long) = call { api.create(auth, TimetableCreate(semesterId, divisionId)) }
    suspend fun detail(auth: String, id: Long) = call { api.detail(auth, id) }
    suspend fun preview(auth: String, id: Long) = call { api.preview(auth, id) }
    suspend fun options(auth: String, id: Long) = call { api.options(auth, id) }
    suspend fun history(auth: String, id: Long, page: Int = 0) = call { api.history(auth, id, page) }
    suspend fun addEntry(auth: String, id: Long, input: TimetableEntryInput) = call { api.addEntry(auth, id, input) }
    suspend fun updateEntry(auth: String, id: Long, entryId: Long, input: TimetableEntryInput) =
        call { api.updateEntry(auth, id, entryId, input) }
    suspend fun deleteEntry(auth: String, id: Long, entryId: Long) = call { api.deleteEntry(auth, id, entryId) }
    suspend fun publish(auth: String, id: Long, revision: Long) = call { api.publish(auth, id, TimetablePublish(revision)) }
    suspend fun archive(auth: String, id: Long) = call { api.archive(auth, id) }
    suspend fun classrooms(auth: String) = call { api.classrooms(auth) }
    suspend fun saveClassroom(auth: String, id: Long?, input: TimetableClassroomInput) =
        if (id == null) call { api.createClassroom(auth, input) } else call { api.updateClassroom(auth, id, input) }

    /** area is "faculty" or "student". */
    suspend fun week(auth: String, area: String) = call { api.week(auth, area) }
    suspend fun day(auth: String, area: String, date: String?) = call { api.day(auth, area, date) }

    private suspend fun <T> call(block: suspend () -> Response<T>): T = timetableRequest(gson, block)
}

/** Runs a timetable request: friendly errors, with clashes and problems from the server's error body. */
internal suspend fun <T> timetableRequest(gson: Gson, block: suspend () -> Response<T>): T {
    val response = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw TimetableFailure(0, null, timetableErrorMessage(0, null))
    }
    if (response.isSuccessful) {
        @Suppress("UNCHECKED_CAST")
        return (response.body() ?: Unit) as T
    }
    val body = runCatching { gson.fromJson(response.errorBody()?.string().orEmpty(), TimetableErrorBody::class.java) }.getOrNull()
    throw TimetableFailure(response.code(), body?.code, timetableErrorMessage(response.code(), body?.code),
        body?.clashes.orEmpty(), body?.problems.orEmpty())
}
