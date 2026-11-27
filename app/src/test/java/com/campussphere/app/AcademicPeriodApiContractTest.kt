package com.campussphere.app

import com.campussphere.app.data.remote.api.AcademicPeriodApi
import com.campussphere.app.data.remote.dto.AcademicCatalogInput
import com.campussphere.app.data.repository.AcademicCatalogFailure
import com.campussphere.app.data.repository.AcademicYearRepository
import com.campussphere.app.data.repository.SemesterRepository
import com.campussphere.app.presentation.college.validatedAcademicYear
import com.campussphere.app.presentation.college.validatedSemester
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class AcademicPeriodApiContractTest {
    @Test fun rejectsInvalidRangesAndSemesterNumbers() {
        for (range in listOf("2027-2026", "2026-2028", "0001-0002", "abcd", "2026-2027-2028", "")) {
            assertNull(validatedAcademicYear(range, "Example"))
        }
        // Common ways of writing a year are accepted and stored as YYYY-YYYY.
        for (range in listOf(" 2026-2027 ", "2026/2027", "2026-27", "2026 2027", "2026")) {
            assertEquals("2026-2027", validatedAcademicYear(range, "Example")?.code)
        }
        assertEquals("2099-2100", validatedAcademicYear("2099-00", "Example")?.code)
        assertEquals("Academic Year 2026-27", validatedAcademicYear("2026-27", " ")?.name)
        for (number in listOf("0", "100", "-1", "１", "3 4", "")) assertNull(validatedSemester(number, "Example"))
        assertEquals("1", validatedSemester("01", "Example")?.code)
        assertEquals("3", validatedSemester("Sem 3", "Example")?.code)
        assertEquals("Semester 1", validatedSemester("1", " ")?.name)   // an empty name is filled in
        assertEquals("99", validatedSemester("99", "Example")?.code)
    }

    @Test fun bothRepositoriesSendTheScopedRoutesAndOnlyEditableFields() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val api = intercepted { request ->
            assertEquals("Bearer fixture", request.header("Authorization"))
            calls += request.method to request.url.encodedPath
            if (request.method != "GET") {
                val payload = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
                assertEquals(setOf("code", "name"), JsonParser.parseString(payload).asJsonObject.keySet())
            }
            val row = """{"id":9,"code":"1","name":"Example"}"""
            when (request.method) { "GET" -> 200 to "[$row]"; "POST" -> 201 to row; else -> 200 to row }
        }
        val years = AcademicYearRepository(api)
        val semesters = SemesterRepository(api, 42, 57)
        val year = AcademicCatalogInput("2026-2027", "Example")
        val term = AcademicCatalogInput("1", "Example")
        years.list("Bearer fixture")
        years.save("Bearer fixture", null, year)
        years.save("Bearer fixture", 57, year)
        semesters.list("Bearer fixture")
        semesters.save("Bearer fixture", null, term)
        semesters.save("Bearer fixture", 9, term)
        val yearRoute = "/api/college/academic-years"
        val termRoute = "/api/college/programs/42/academic-years/57/semesters"
        assertEquals(listOf("GET" to yearRoute, "POST" to yearRoute, "PATCH" to "$yearRoute/57",
            "GET" to termRoute, "POST" to termRoute, "PATCH" to "$termRoute/9"), calls)
    }

    @Test fun semesterConflictExplainsTheExactScope() = runBlocking {
        val repo = SemesterRepository(intercepted { 409 to "private server detail" }, 42, 57)
        val failure = runCatching { repo.save("Bearer fixture", null, AcademicCatalogInput("1", "Term")) }
            .exceptionOrNull() as AcademicCatalogFailure
        assertEquals(409, failure.status)
        assertEquals("This program already has that semester number in this academic year.", failure.message)
    }

    private fun intercepted(handler: (Request) -> Pair<Int, String>): AcademicPeriodApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (status, body) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(AcademicPeriodApi::class.java)
    }
}
