package com.campussphere.app

import com.campussphere.app.data.remote.api.AuthApi
import com.campussphere.app.data.remote.dto.AccountStatusDto
import com.campussphere.app.data.remote.dto.CollegeAdminInvitationAcceptance
import com.campussphere.app.data.remote.dto.DepartmentSummary
import com.campussphere.app.data.remote.dto.DepartmentInput
import com.campussphere.app.data.remote.dto.FacultyRequestDto
import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.LoginResponse
import com.campussphere.app.data.remote.dto.RegisterRequest
import com.campussphere.app.data.repository.AuthError
import com.campussphere.app.data.repository.AuthFailure
import com.campussphere.app.data.repository.AuthRepository
import com.campussphere.app.data.repository.DashboardRole
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AuthRepositoryTest {
    @Test
    fun restrictedAccountsNeverCallADashboard() = runBlocking {
        val api = FakeApi()
        val repository = AuthRepository(api)
        api.accountStatus = Response.success(AccountStatusDto(false, emptySet(), null))
        assertNull(repository.account("test-token").getOrThrow().dashboardRole)
        api.accountStatus = Response.success(AccountStatusDto(false, emptySet(), null, studentAffiliationEligible = true))
        val applicant = repository.account("test-token").getOrThrow()
        assertTrue(applicant.studentAffiliationEligible)
        assertNull(applicant.dashboardRole)
        api.accountStatus = Response.success(AccountStatusDto(false, emptySet(), FacultyRequestDto("PENDING")))
        assertEquals("PENDING", repository.account("test-token").getOrThrow().facultyStatus)
        assertTrue(api.dashboardCalls.isEmpty())
    }

    @Test
    fun roleRoutesRequireTheCorrespondingProtectedResponse() = runBlocking {
        val api = FakeApi()
        val repository = AuthRepository(api)
        for (role in DashboardRole.entries.filter { it.path != null }) {
            api.accountStatus = Response.success(AccountStatusDto(true, setOf(role.name), null))
            val account = repository.account("test-token").getOrThrow()
            assertEquals(role, account.dashboardRole)
            assertEquals("Server dashboard", account.dashboardMessage)
            assertEquals(role.path to "Bearer test-token", api.dashboardCalls.last())
        }

        api.dashboardResponse = errorResponse(403, "ACCESS_DENIED")
        val denied = repository.account("test-token").exceptionOrNull() as AuthFailure
        assertEquals(AuthError.ACCESS_DENIED, denied.error)
    }

    @Test
    fun superAdminRoutingUsesEffectiveStatusWithoutCallingLegacyDashboard() = runBlocking {
        val api = FakeApi()
        api.accountStatus = Response.success(AccountStatusDto(true, setOf("SUPER_ADMIN"), null))
        val account = AuthRepository(api).account("test-token").getOrThrow()
        assertEquals(DashboardRole.SUPER_ADMIN, account.dashboardRole)
        assertNull(account.dashboardMessage)
        assertTrue(api.dashboardCalls.isEmpty())
    }

    @Test
    fun decodesAuthenticationVerificationAndOutageErrors() = runBlocking {
        val api = FakeApi()
        val repository = AuthRepository(api)
        val cases = listOf(
            Triple(401, "INVALID_AUTHENTICATION", AuthError.INVALID_AUTHENTICATION),
            Triple(403, "ACCESS_DENIED", AuthError.ACCESS_DENIED),
            Triple(403, "VERIFICATION_REQUIRED", AuthError.VERIFICATION_REQUIRED),
            Triple(503, "DATABASE_UNAVAILABLE", AuthError.DATABASE_UNAVAILABLE)
        )
        for ((status, code, expected) in cases) {
            api.accountStatus = errorResponse(status, code)
            val failure = repository.account("test-token").exceptionOrNull() as AuthFailure
            assertEquals(expected, failure.error)
        }
        assertTrue(api.dashboardCalls.isEmpty())
    }

    @Test
    fun invitationAcceptanceHandlesEmptySuccessAndInvalidCode() = runBlocking {
        val api = FakeApi()
        val repo = AuthRepository(api)
        val input = CollegeAdminInvitationAcceptance("fixture", "Synthetic", "fixture-password")
        assertTrue(repo.acceptCollegeInvitation(input).isSuccess)
        assertEquals(1, api.acceptCalls)
        api.acceptance = errorResponse(400, "INVITATION_INVALID")
        assertEquals(AuthError.INVITATION_INVALID,
            (repo.acceptCollegeInvitation(input).exceptionOrNull() as AuthFailure).error)
    }

    @Test
    fun collegeAdminUsesProtectedDepartmentResponseAndFailsClosed() = runBlocking {
        val api = FakeApi()
        val repo = AuthRepository(api)
        api.accountStatus = Response.success(AccountStatusDto(true, setOf("COLLEGE_ADMIN"), null))
        val account = repo.account("test-token").getOrThrow()
        assertEquals(DashboardRole.COLLEGE_ADMIN, account.dashboardRole)
        assertEquals("No departments have been added to your college yet.", account.dashboardMessage)
        assertTrue(api.dashboardCalls.isEmpty())
        api.departmentResponse = errorResponse(403, "ACCESS_DENIED")
        assertEquals(AuthError.ACCESS_DENIED, (repo.account("test-token").exceptionOrNull() as AuthFailure).error)
    }

    private class FakeApi : AuthApi {
        var acceptCalls = 0
        var acceptance: Response<Void> = Response.success<Void>(204, null)
        var departmentResponse: Response<List<DepartmentSummary>> = Response.success(emptyList())
        override suspend fun acceptCollegeInvitation(request: CollegeAdminInvitationAcceptance): Response<Void> {
            acceptCalls++
            return acceptance
        }
        override suspend fun departments(authorization: String): Response<List<DepartmentSummary>> {
            assertEquals("Bearer test-token", authorization)
            return departmentResponse
        }
        override suspend fun createDepartment(authorization: String, input: DepartmentInput): Response<DepartmentSummary> =
            error("Unexpected department creation")
        override suspend fun updateDepartment(authorization: String, id: Long, input: DepartmentInput): Response<DepartmentSummary> =
            error("Unexpected department update")
        var accountStatus: Response<AccountStatusDto> = Response.success(AccountStatusDto(false, emptySet(), null))
        var dashboardResponse: Response<String> = Response.success("Server dashboard")
        val dashboardCalls = mutableListOf<Pair<String, String>>()

        override suspend fun status(authorization: String): Response<AccountStatusDto> {
            assertEquals("Bearer test-token", authorization)
            return accountStatus
        }

        override suspend fun dashboard(role: String, authorization: String): Response<String> {
            dashboardCalls.add(role to authorization)
            return dashboardResponse
        }

        override suspend fun register(request: RegisterRequest): Response<String> = error("Unexpected registration")
        override suspend fun login(request: LoginRequest): Response<LoginResponse> = error("Unexpected login")
        override suspend fun reapply(authorization: String): Response<FacultyRequestDto> = error("Unexpected reapplication")
    }

    private companion object {
        fun <T> errorResponse(status: Int, code: String): Response<T> =
            Response.error(status, """{"code":"$code","message":"Backend error"}""".toResponseBody())
    }
}
