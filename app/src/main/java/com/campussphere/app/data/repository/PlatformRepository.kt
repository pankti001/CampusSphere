package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.*
import com.google.gson.JsonParser
import retrofit2.HttpException

class PlatformFailure(val status: Int, message: String) : Exception(message)

class PlatformRepository(private val api: PlatformApi) {
    suspend fun overview(auth: String) = call { api.overview(auth) }
    suspend fun colleges(auth: String, search: String, after: Long = 0) =
        call { api.colleges(auth, search.trim().takeIf(String::isNotEmpty), after, PAGE_SIZE) }
    suspend fun college(auth: String, id: Long) = call { api.college(auth, id) }
    suspend fun save(auth: String, id: Long?, input: CollegeInput) =
        call { if (id == null) api.create(auth, input) else api.edit(auth, id, input) }
    suspend fun status(auth: String, id: Long, active: Boolean) =
        call { api.status(auth, id, CollegeStatus(active)) }
    suspend fun invitations(auth: String, id: Long, before: Long = Long.MAX_VALUE) =
        call { api.invitations(auth, id, before, PAGE_SIZE) }
    suspend fun revoke(auth: String, collegeId: Long, invitationId: Long) =
        call { api.revoke(auth, collegeId, invitationId) }
    suspend fun invite(auth: String, id: Long, email: String) =
        call { api.invite(auth, id, InvitationInput(email.trim())) }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        val code = runCatching {
            error.response()?.errorBody()?.use { JsonParser.parseString(it.string()).asJsonObject["code"]?.asString }
        }.getOrNull()
        val message = when (error.code()) {
            401 -> AuthError.INVALID_AUTHENTICATION.message
            403 -> AuthError.ACCESS_DENIED.message
            503 -> AuthError.DATABASE_UNAVAILABLE.message
            else -> when (code) {
                "COLLEGE_CODE_CONFLICT" -> "That college code is already in use."
                "COLLEGE_INACTIVE" -> "Activate the college before inviting an administrator."
                "ACCOUNT_ALREADY_EXISTS" -> "An account already uses this email."
                "INVITATION_CONFLICT" -> "An invitation already exists or cannot be changed."
                "COLLEGE_NOT_FOUND" -> "The college could not be found."
                "INVALID_REQUEST" -> "Check the fields and try again."
                else -> "The service could not complete this request."
            }
        }
        throw PlatformFailure(error.code(), message)
    }

    companion object { const val PAGE_SIZE = 50 }
}
