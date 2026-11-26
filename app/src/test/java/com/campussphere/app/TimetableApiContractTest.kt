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

class TimetableApiContractTest {
    private val summary = """{"id":4,"version":2,"status":"DRAFT","revision":7,"semesterId":21,"divisionId":31,
        "academicYear":"2026-2027","department":"Science","program":"BSc","semester":"Semester 1","division":"A",
        "lessons":1,"breaks":0,"createdAt":"2026-11-26T10:00:00+05:30","updatedAt":"2026-11-26T10:00:00+05:30",
        "publishedAt":null,"archivedAt":null}"""
    private val detail = """{"timetable":$summary,"entries":[{"id":9,"kind":"LESSON","day":1,"start":"09:00:00",
        "end":"10:00:00","assignmentId":5,"subjectCode":"CS101","subject":"Programming","facultyId":3,"faculty":"Prof A",
        "classroomId":8,"classroomCode":"A101","classroom":"Room","label":null}],"problems":[],"ready":true}"""
    private val schedule = """{"enrolled":true,"title":"BSc · Semester 1 · A","version":2,
        "publishedAt":"2026-11-26T10:00:00+05:30","slots":[]}"""

    @Test fun addingAPeriodSendsOnlyThePeriodNeverACollege() = runBlocking {
        val repository = repo { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/college/timetables/4/entries", request.url.encodedPath)
            assertEquals("Bearer synthetic", request.header("Authorization"))
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            assertEquals("""{"kind":"LESSON","day":1,"start":"09:00","end":"10:00","assignmentId":5,"classroomId":8}""", body)
            201 to detail
        }
        val result = repository.addEntry("Bearer synthetic", 4, TimetableEntryInput("LESSON", 1, "09:00", "10:00", 5, 8, null))
        assertEquals("Prof A", result.entries.single().faculty)
        assertTrue(result.ready)
    }

    @Test fun personalViewsUseTheRoleAreaAndNoClassIds() = runBlocking {
        val urls = mutableListOf<String>()
        val repository = repo { request ->
            urls += request.url.toString().removePrefix("https://example.invalid")
            200 to if (request.url.encodedPath.endsWith("/day")) """{"date":"2026-11-27","day":5,"schedule":$schedule}""" else schedule
        }
        assertEquals("BSc · Semester 1 · A", repository.week("Bearer s", "student").title)
        assertEquals(5, repository.day("Bearer s", "faculty", "2026-11-27").day)
        assertEquals(listOf("/api/student/timetable", "/api/faculty/timetable/day?date=2026-11-27"), urls)
    }

    @Test fun conflictsArriveWithTheClashingBookings() = runBlocking {
        val repository = repo {
            409 to """{"code":"TIMETABLE_CONFLICT","message":"server text","clashes":[{"type":"CLASSROOM","day":2,
                "start":"09:00:00","end":"10:00:00","with":"BSc · B · CS301"}],"problems":[]}"""
        }
        val failure = runCatching { repository.addEntry("Bearer s", 4, TimetableEntryInput("LESSON", 2, "09:00", "10:00", 5, 8, null)) }
            .exceptionOrNull() as TimetableFailure
        assertEquals(409, failure.status)
        assertEquals("TIMETABLE_CONFLICT", failure.code)
        assertEquals("This time clashes with another booking.", failure.message)
        assertEquals("CLASSROOM", failure.clashes.single().type)
        assertEquals("BSc · B · CS301", failure.clashes.single().with)
    }

    @Test fun publishSendsThePreviewedRevisionAndReportsProblems() = runBlocking {
        var sent = ""
        val repository = repo { request ->
            sent = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            409 to """{"code":"TIMETABLE_NOT_READY","message":"x","clashes":[],"problems":[{"type":"EMPTY","entryId":null,"detail":"Add at least one lesson."}]}"""
        }
        val failure = runCatching { repository.publish("Bearer s", 4, 7) }.exceptionOrNull() as TimetableFailure
        assertEquals("""{"revision":7}""", sent)
        assertEquals("EMPTY", failure.problems.single().type)
        assertEquals("Fix the problems listed below before publishing.", failure.message)
    }

    @Test fun unknownErrorsAndNetworkProblemsHaveFriendlyMessages() = runBlocking {
        val server = runCatching { repo { 500 to "not json" }.week("Bearer s", "student") }.exceptionOrNull() as TimetableFailure
        assertEquals("CampusSphere could not complete the request. Try again shortly.", server.message)
        assertTrue(server.clashes.isEmpty())
        val offline = runCatching { repo { throw java.io.IOException("down") }.week("Bearer s", "student") }.exceptionOrNull() as TimetableFailure
        assertEquals(0, offline.status)
    }

    private fun repo(answer: (okhttp3.Request) -> Pair<Int, String>): TimetableRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = answer(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        return TimetableRepository(retrofit.create(TimetableApi::class.java))
    }
}
