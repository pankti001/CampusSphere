package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.*
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

class CollegeAdminFailure(val status: Int, val code: String?, message: String) : Exception(message)

fun collegeAdminErrorMessage(status: Int, code: String?): String = when (code) {
    "ACCOUNT_ALREADY_EXISTS" -> "Another account already uses this phone number."
    "FACULTY_NOT_FOUND" -> "This faculty member is no longer in your college."
    "STUDENT_NOT_FOUND" -> "This student is no longer in your college."
    "INVALID_REQUEST" -> "Enter a name (up to 100 characters) and, optionally, a 10-digit phone number."
    "DATA_CONFLICT" -> "This change conflicts with existing records. Refresh and try again."
    else -> when (status) {
        401 -> "Your session expired. Sign in again."
        403 -> "You do not have permission for this action."
        404 -> "This record is no longer available."
        in 500..599 -> "CampusSphere could not complete the request. Try again shortly."
        else -> "Request failed. Check your connection and try again."
    }
}

class CollegeAdminRepository(private val api: CollegeAdminApi) {
    suspend fun profile(auth: String) = call { api.profile(auth) }
    suspend fun overview(auth: String) = call { api.overview(auth) }

    /** kind is "faculty" or "students". */
    suspend fun people(auth: String, kind: String, active: Boolean? = null, q: String? = null) =
        call { api.people(auth, kind, active, q?.trim()?.ifEmpty { null }) }
    suspend fun update(auth: String, kind: String, id: Long, fullName: String, phone: String?) =
        call { api.update(auth, kind, id, CollegePersonDetails(fullName.trim(), phone?.trim()?.ifEmpty { null })) }
    suspend fun setActive(auth: String, kind: String, id: Long, active: Boolean) =
        call { api.setActive(auth, kind, id, CollegePersonStatus(active)) }

    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw CollegeAdminFailure(0, null, collegeAdminErrorMessage(0, null))
        }
        if (response.isSuccessful) {
            return response.body() ?: throw CollegeAdminFailure(response.code(), null, collegeAdminErrorMessage(500, null))
        }
        val code = runCatching { JsonParser.parseString(response.errorBody()?.string().orEmpty()).asJsonObject["code"]?.asString }.getOrNull()
        throw CollegeAdminFailure(response.code(), code, collegeAdminErrorMessage(response.code(), code))
    }
}
