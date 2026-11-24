package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.PlatformFailure
import com.campussphere.app.data.repository.PlatformRepository
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PlatformRepositoryTest {
    @Test
    fun preservesEmptyCountsAndReturnsEmptyCollegeList() = runBlocking {
        val api = FakeApi()
        val repo = PlatformRepository(api)
        assertEquals(PlatformOverview(0L, 0L, 0L, 0L), repo.overview("Bearer fixture"))
        assertTrue(repo.colleges("Bearer fixture", "").isEmpty())
        assertNull(api.lastSearch)
        assertTrue(repo.colleges("Bearer fixture", "  science  ", 7L).isEmpty())
        assertEquals("science", api.lastSearch)
        assertEquals(7L, api.lastCursor)
    }

    @Test
    fun propagatesAuthorizationAndAvailabilityFailuresWithoutData() = runBlocking {
        val api = FakeApi()
        val repo = PlatformRepository(api)
        for (status in listOf(401, 403, 503)) {
            api.failure = status
            val result = runCatching { repo.overview("Bearer fixture") }
            assertEquals(status, (result.exceptionOrNull() as PlatformFailure).status)
        }
    }

    @Test
    fun sendsWriteContractsToMockApiOnly() = runBlocking {
        val api = FakeApi()
        val repository = PlatformRepository(api)
        val input = CollegeInput("Synthetic", "SYN")
        val created = repository.save("Bearer fixture", null, input)
        assertFalse(created.active)
        assertEquals("Updated", repository.save("Bearer fixture", created.id, input.copy(name = "Updated")).name)
        assertTrue(repository.status("Bearer fixture", created.id, true).active)
        repository.invite("Bearer fixture", created.id, "  fixture@example.invalid ")
        assertEquals("fixture@example.invalid", api.sentEmail)
        repository.revoke("Bearer fixture", created.id, 17)
        assertEquals(created.id to 17L, api.revoked)
        assertEquals(listOf("create", "edit", "status", "invite", "revoke"), api.writes)
    }

    private class FakeApi : PlatformApi {
        val writes = mutableListOf<String>()
        var sentEmail: String? = null
        var revoked: Pair<Long, Long>? = null
        var lastSearch: String? = null
        var lastCursor = 0L
        var failure: Int? = null
        override suspend fun overview(auth: String): PlatformOverview {
            failure?.let { throw HttpException(Response.error<PlatformOverview>(it, "{}".toResponseBody())) }
            return PlatformOverview(0L, 0L, 0L, 0L)
        }
        override suspend fun colleges(auth: String, search: String?, after: Long, limit: Int): List<College> {
            lastSearch = search
            lastCursor = after
            return emptyList()
        }
        override suspend fun college(auth: String, id: Long) = College(id, "Synthetic", "SYN", false)
        override suspend fun create(auth: String, body: CollegeInput): College {
            writes += "create"
            return College(1, body.name, body.code, false)
        }
        override suspend fun edit(auth: String, id: Long, body: CollegeInput): College {
            writes += "edit"
            return College(id, body.name, body.code, false)
        }
        override suspend fun status(auth: String, id: Long, body: CollegeStatus): College {
            writes += "status"
            return college(auth, id).copy(active = body.active)
        }
        override suspend fun invitations(auth: String, id: Long, before: Long, limit: Int) = emptyList<CollegeInvitation>()
        override suspend fun revoke(auth: String, collegeId: Long, invitationId: Long) {
            writes += "revoke"
            revoked = collegeId to invitationId
        }
        override suspend fun invite(auth: String, id: Long, body: InvitationInput): IssuedInvitation {
            writes += "invite"
            sentEmail = body.email
            return IssuedInvitation(CollegeInvitation(1, body.email, "PENDING", ""), "")
        }
    }
}
