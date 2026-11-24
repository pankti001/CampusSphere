package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.AuthApi
import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.CollegeAdminInvitationAcceptance
import com.campussphere.app.data.remote.dto.RegisterRequest
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException

class AuthRepository(private val authApi: AuthApi) {
    suspend fun register(request: RegisterRequest): Result<Unit> = request {
        authApi.register(request).requireBody()
        Unit
    }

    suspend fun login(request: LoginRequest): Result<String> = request {
        val token = authApi.login(request).requireBody().token
        token.takeIf { !it.isNullOrBlank() } ?: throw AuthFailure(AuthError.INVALID_RESPONSE)
    }

    suspend fun account(token: String): Result<AuthorizedAccount> = request {
        val authorization = "Bearer $token"
        val status = authApi.status(authorization).requireBody()
        val roles = status.roles ?: throw AuthFailure(AuthError.INVALID_RESPONSE)
        val role = DashboardRole.entries.firstOrNull { it.name in roles }
        val dashboard = if (role == DashboardRole.COLLEGE_ADMIN) {
            val departments = authApi.departments(authorization).requireBody()
            if (departments.isEmpty()) "No departments have been added to your college yet."
            else "Departments in your college: ${departments.size}"
        } else role?.path?.let { authApi.dashboard(it, authorization).requireBody() }
        AuthorizedAccount(status.verified, status.facultyRequest?.status, role, dashboard, status.studentAffiliationEligible)
    }

    suspend fun acceptCollegeInvitation(input: CollegeAdminInvitationAcceptance): Result<Unit> = request {
        val response = authApi.acceptCollegeInvitation(input)
        if (!response.isSuccessful) response.requireBody()
    }

    suspend fun reapply(token: String): Result<Unit> = request {
        authApi.reapply("Bearer $token").requireBody()
        Unit
    }

    private suspend fun <T> request(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuthFailure) {
        Result.failure(e)
    } catch (_: IOException) {
        Result.failure(AuthFailure(AuthError.NETWORK))
    } catch (_: JsonParseException) {
        Result.failure(AuthFailure(AuthError.INVALID_RESPONSE))
    } catch (_: IllegalStateException) {
        Result.failure(AuthFailure(AuthError.INVALID_RESPONSE))
    }

    private fun <T> Response<T>.requireBody(): T {
        if (isSuccessful) return body() ?: throw AuthFailure(AuthError.INVALID_RESPONSE)

        val code = try {
            errorBody()?.use { body ->
                val json = JsonParser.parseString(body.string())
                if (json.isJsonObject) {
                    json.asJsonObject.get("code")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                } else null
            }
        } catch (_: JsonParseException) {
            null
        } catch (_: IOException) {
            null
        }

        val error = when (code()) {
            401 -> AuthError.INVALID_AUTHENTICATION
            403 -> if (code == "VERIFICATION_REQUIRED") AuthError.VERIFICATION_REQUIRED else AuthError.ACCESS_DENIED
            503 -> AuthError.DATABASE_UNAVAILABLE
            else -> when (code) {
                "INVALID_REQUEST" -> AuthError.INVALID_REQUEST
                "INVITATION_INVALID" -> AuthError.INVITATION_INVALID
                "REGISTRATION_NOT_ALLOWED" -> AuthError.REGISTRATION_NOT_ALLOWED
                "ACCOUNT_ALREADY_EXISTS" -> AuthError.ACCOUNT_ALREADY_EXISTS
                "FACULTY_REQUEST_CONFLICT" -> AuthError.FACULTY_REQUEST_CONFLICT
                else -> AuthError.REQUEST_FAILED
            }
        }
        throw AuthFailure(error)
    }
}

data class AuthorizedAccount(
    val verified: Boolean,
    val facultyStatus: String?,
    val dashboardRole: DashboardRole?,
    val dashboardMessage: String?,
    val studentAffiliationEligible: Boolean = false
)

enum class DashboardRole(val path: String?, val title: String) {
    SUPER_ADMIN(null, "Super Admin"),
    COLLEGE_ADMIN(null, "College Admin dashboard"),
    ADMIN("admin", "Admin dashboard"),
    FACULTY("faculty", "Faculty dashboard"),
    STUDENT("student", "Student dashboard")
}

class AuthFailure(val error: AuthError) : Exception(error.message)

enum class AuthError(val message: String) {
    INVALID_AUTHENTICATION("Sign in with valid credentials to continue."),
    ACCESS_DENIED("Your account does not have access to this area."),
    VERIFICATION_REQUIRED("Your account needs verification before you can access this area."),
    DATABASE_UNAVAILABLE("The campus service is temporarily unavailable. Try again shortly."),
    INVALID_REQUEST("Check the information you entered and try again."),
    INVITATION_INVALID("This invitation cannot be accepted. Ask your administrator to check it."),
    REGISTRATION_NOT_ALLOWED("Choose Student or Faculty to register."),
    ACCOUNT_ALREADY_EXISTS("An account already uses that email or phone."),
    FACULTY_REQUEST_CONFLICT("Your Faculty request has changed. Refresh your account status."),
    NETWORK("Could not reach CampusSphere. Check your connection and try again."),
    INVALID_RESPONSE("CampusSphere returned an unexpected response."),
    REQUEST_FAILED("The request could not be completed. Try again."),
    SESSION_STORAGE("Could not access saved sign-in data. Try again.")
}
