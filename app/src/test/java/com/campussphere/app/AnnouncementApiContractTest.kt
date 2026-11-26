package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AnnouncementApiContractTest {
    private val item = """
        {"id":4,"title":"Exam timetable","body":"Read it","category":"EXAMINATION","priority":"HIGH",
        "audience":"STUDENTS","scope":"DIVISION","targetId":9,"target":"BSc · 2026-2027 · Division A","status":"DRAFT",
        "publishAt":null,"expiresAt":null,"createdAt":"2026-11-26T10:00:00+05:30","updatedAt":"2026-11-26T10:00:00+05:30",
        "author":"Synthetic Faculty","authorRole":"FACULTY","attachmentName":null,"attachmentFormat":null,
        "attachmentSize":null,"read":null,"canEdit":true,"canPublish":true,"canArchive":true,"canDelete":true}
    """.trimIndent()

    private val files = MaterialRepository(Retrofit.Builder().baseUrl("https://example.invalid/")
        .addConverterFactory(GsonConverterFactory.create()).build().create(MaterialApi::class.java))

    @Test fun createSendsOnlyTheFormAndNeverCollegeOrAuthor() = runBlocking {
        val repository = repo { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/faculty/announcements", request.url.encodedPath)
            assertEquals("Bearer synthetic", request.header("Authorization"))
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            assertTrue(body.contains("\"scope\":\"DIVISION\""))
            assertTrue(body.contains("\"targetId\":9"))
            assertFalse(body.contains("collegeId"))
            assertFalse(body.contains("authorId"))
            201 to item
        }
        val input = AnnouncementInput("Exam timetable", "Read it", "EXAMINATION", "HIGH", "STUDENTS", "DIVISION", 9, null)
        val created = repository.create("Bearer synthetic", "faculty", input)
        assertEquals(4L, created.id)
        assertEquals("DRAFT", created.status)
    }

    @Test fun eachRoleUsesItsOwnAreaAndFiltersAreQueryParameters() = runBlocking {
        val urls = mutableListOf<String>()
        val repository = repo { request -> urls += request.url.encodedPath + "?" + request.url.encodedQuery; 200 to "[$item]" }
        assertEquals(1, repository.list("Bearer s", "student", "FEES", null, null, "  fee ", true).size)
        repository.list("Bearer s", "college", null, "URGENT", "SCHEDULED", "", null)
        repository.mine("Bearer s", "DRAFT")
        assertEquals("/api/student/announcements?category=FEES&q=fee&unread=true&page=0", urls[0])
        assertEquals("/api/college/announcements?priority=URGENT&status=SCHEDULED&page=0", urls[1])
        assertEquals("/api/faculty/announcements/mine?status=DRAFT&page=0", urls[2])
    }

    @Test fun markReadPostsWithoutBodyAndPublishSendsTheTime() = runBlocking {
        val seen = mutableListOf<String>()
        val repository = repo { request ->
            val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            seen += "${request.method} ${request.url.encodedPath} $body"
            if (request.url.encodedPath.endsWith("/read")) 204 to "" else 200 to item
        }
        repository.markRead("Bearer s", "student", 4)
        repository.publish("Bearer s", "college", 4, "2026-12-01T09:30+05:30")
        repository.publish("Bearer s", "college", 4, null)
        assertEquals("POST /api/student/announcements/4/read ", seen[0])
        assertEquals("POST /api/college/announcements/4/publish {\"publishAt\":\"2026-12-01T09:30+05:30\"}", seen[1])
        assertEquals("POST /api/college/announcements/4/publish {}", seen[2])
    }

    @Test fun serverErrorsBecomeFriendlyMessagesWithStatus() = runBlocking {
        val cases = listOf(
            400 to """{"code":"ANNOUNCEMENT_TARGET_INVALID","message":"internal detail"}""",
            409 to """{"code":"ANNOUNCEMENT_STATE_CONFLICT","message":"internal detail"}""",
            403 to """{"code":"ACCESS_DENIED","message":"internal detail"}""",
            500 to "not json")
        val messages = cases.map { (status, body) ->
            val failure = runCatching { repo { status to body }.detail("Bearer s", "student", 4) }.exceptionOrNull()
            assertTrue(failure is AnnouncementFailure)
            assertEquals(status, (failure as AnnouncementFailure).status)
            assertFalse(failure.message.orEmpty().contains("internal detail"))
            failure.message
        }
        assertEquals("Choose an audience you are allowed to address.", messages[0])
        assertTrue(messages[1]!!.startsWith("This announcement changed"))
        assertEquals("You do not have permission for this action.", messages[2])
        assertEquals("CampusSphere could not complete the request. Try again shortly.", messages[3])
    }

    @Test fun adminOnlyCallsUseTheCollegeArea() = runBlocking {
        val paths = mutableListOf<String>()
        val repository = repo { request ->
            paths += request.url.encodedPath
            200 to if (request.url.encodedPath.endsWith("/4/stats")) """{"audienceSize":40,"readCount":12}"""
                    else """{"drafts":1,"scheduled":2,"published":3,"expired":0,"archived":1}"""
        }
        assertEquals(12L, repository.stats("Bearer s", 4).readCount)
        assertEquals(2L, repository.overview("Bearer s").scheduled)
        assertEquals(listOf("/api/college/announcements/4/stats", "/api/college/announcements/stats"), paths)
    }

    private fun repo(answer: (okhttp3.Request) -> Pair<Int, String>): AnnouncementRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = answer(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        return AnnouncementRepository(retrofit.create(AnnouncementApi::class.java), files)
    }
}
