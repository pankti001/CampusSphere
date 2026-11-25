package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.AttendanceFailure
import com.campussphere.app.data.repository.AttendanceRepository
import com.campussphere.app.presentation.attendance.attendanceCreate
import com.google.gson.JsonParser
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

class AttendanceApiContractTest {
    @Test fun createInputRejectsInvalidDatesAndTimesButAllowsOmittedTimes() {
        assertEquals(AttendanceCreate(5, "2026-11-25", 2, null, null),
            attendanceCreate(5, "2026-11-25", "2", "", ""))
        assertNull(attendanceCreate(5, "2026-02-30", "2", "", ""))
        assertNull(attendanceCreate(5, "2026-11-25", "0", "", ""))
        assertNull(attendanceCreate(5, "2026-11-25", "2", "10:00", "09:00"))
        assertNull(attendanceCreate(5, "2026-11-25", "2", "", "09:00"))
    }

    @Test fun draftAndSubmissionCarryTheRevisionAndRosterButNoFacultyOrCollegeId() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = repo { request ->
            assertEquals("Bearer synthetic", request.header("Authorization"))
            assertNull(request.url.query)
            val body = requireNotNull(request.body)
            val input = JsonParser.parseString(Buffer().also { body.writeTo(it) }.readUtf8()).asJsonObject
            assertEquals(setOf("revision", "records"), input.keySet())
            assertEquals(3, input["revision"].asInt)
            val mark = input["records"].asJsonArray[0].asJsonObject
            assertEquals(setOf("studentId", "status"), mark.keySet())
            assertEquals("EXCUSED", mark["status"].asString)
            calls += "${request.method} ${request.url.encodedPath}"
            200 to """
                {"session":{"id":7,"assignmentId":5,"status":"SUBMITTED","revision":4,"date":"2026-11-25",
                "lectureNumber":2,"subject":"Biology","division":"A","faculty":"Synthetic Teacher",
                "department":"Science","program":"Biology","semester":"First"},
                 "roster":[{"studentId":12,"name":"Synthetic Student","status":"EXCUSED"}]}
            """.trimIndent()
        }
        val input = AttendanceMarks(3, listOf(AttendanceMarkInput(12, AttendanceMark.EXCUSED)))
        repository.save("Bearer synthetic", 7, input, false)
        val detail = repository.save("Bearer synthetic", 7, input, true)
        assertEquals(4L, detail.session.revision)
        assertEquals(AttendanceMark.EXCUSED, detail.roster.single().status)
        assertEquals(listOf("PUT /api/faculty/attendance/sessions/7/draft",
            "POST /api/faculty/attendance/sessions/7/submit"), calls)
    }

    @Test fun conflictHasARefreshMessageAndDoesNotExposeResponseDetails() = runBlocking {
        val repository = repo { 409 to """{"code":"ATTENDANCE_STATE_CONFLICT","message":"private body"}""" }
        val failure = runCatching { repository.lock("Bearer synthetic", 7, 3) }.exceptionOrNull() as AttendanceFailure
        assertEquals(409, failure.status)
        assertTrue(failure.message.orEmpty().contains("Refresh"))
        assertFalse(failure.message.orEmpty().contains("private body"))
    }

    private fun repo(options: (okhttp3.Request) -> Pair<Int, String>): AttendanceRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = options(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        return AttendanceRepository(retrofit.create(AttendanceApi::class.java))
    }
}
