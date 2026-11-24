package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import com.campussphere.app.data.repository.AcademicCatalogFailure
import com.campussphere.app.data.repository.DepartmentRepository
import com.campussphere.app.data.repository.StudentRepository
import com.campussphere.app.presentation.college.EnrollmentSelection
import com.campussphere.app.presentation.college.normalizedCollegeCode
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

class StudentApiContractTest {
    @Test fun enrollmentEncodesAcademicIdsWithoutTenantAndSelfReadsWithoutIds() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val enrollment = """{"id":24,"college":"Alpha","department":"Science","program":"Biology","academicYear":"2026","semester":"First","division":"A","current":true}"""
        val repo = repository { request ->
            assertEquals("Bearer fixture", request.header("Authorization"))
            assertNull(request.url.query)
            calls += request.method to request.url.encodedPath
            when (request.method) {
                "POST" -> {
                    assertEquals(setOf("departmentId", "programId", "academicYearId", "semesterId", "divisionId"),
                        payload(request).keySet())
                    201 to enrollment
                }
                "DELETE" -> 204 to ""
                else -> 200 to "[$enrollment]"
            }
        }
        val auth = "Bearer fixture"
        val root = "/api/college/students/12/enrollments"
        assertEquals(24L, repo.enroll(auth, 12, StudentEnrollmentInput(2, 3, 4, 5, 6)).id)
        assertTrue(repo.history(auth, 12).single().current)
        assertEquals("Biology", repo.current(auth).single().program)
        repo.end(auth, 12, 24)
        assertEquals(listOf("POST" to root, "GET" to root, "GET" to "/api/student/enrollment",
            "DELETE" to "$root/24"), calls)
    }

    @Test fun affiliationSendsCodeAndExplicitAdmissionDecisionOnly() = runBlocking {
        val requestPath = "/api/auth/student-affiliation"
        val queuePath = "/api/college/student-affiliation-requests"
        val pending = """{"id":90,"status":"PENDING","college":"Alpha","collegeCode":"ALPHA","name":"Student Sample","email":"student@example.invalid"}"""
        val repo = repository { request ->
            assertNull(request.url.query)
            when (request.method to request.url.encodedPath) {
                "GET" to requestPath, "GET" to queuePath -> 200 to "[$pending]"
                "POST" to requestPath -> {
                    assertEquals(setOf("collegeCode"), payload(request).keySet())
                    assertEquals("ALPHA", payload(request).get("collegeCode").asString)
                    201 to pending
                }
                "POST" to "$queuePath/90/decision" -> {
                    assertEquals(setOf("approved", "admissionConfirmed"), payload(request).keySet())
                    assertTrue(payload(request).get("admissionConfirmed").asBoolean)
                    200 to pending.replace("PENDING", "APPROVED")
                }
                else -> error("Unexpected request")
            }
        }
        assertEquals("PENDING", repo.request("Bearer fixture", "ALPHA").status)
        assertEquals(90L, repo.myRequest("Bearer fixture")?.id)
        assertEquals(90L, repo.pending("Bearer admin").single().id)
        assertEquals("APPROVED", repo.decide("Bearer admin", 90, true, true).status)
    }

    @Test fun incompleteSelectionAndMalformedCollegeCodesAreRejected() {
        fun option(id: Long) = AcademicCatalogItem(id, "A", "Example")
        val selection = EnrollmentSelection(option(2), option(3), option(4), option(5))
        assertNull(selection.input())
        assertEquals(StudentEnrollmentInput(2, 3, 4, 5, 6), selection.copy(division = option(6)).input())
        assertEquals("ALPHA-1", normalizedCollegeCode(" alpha-1 "))
        assertNull(normalizedCollegeCode("_BAD"))
        assertNull(normalizedCollegeCode("a".repeat(31)))
        assertNull(normalizedCollegeCode(" "))
    }

    @Test fun duplicateEnrollmentHidesServerDetails() = runBlocking {
        val repo = repository { 409 to "private server details" }
        val failure = runCatching {
            repo.enroll("Bearer admin", 12, StudentEnrollmentInput(2, 3, 4, 5, 6))
        }.exceptionOrNull() as AcademicCatalogFailure
        assertEquals(409, failure.status)
        assertEquals("This student already has a current enrollment.", failure.message)
    }

    private fun payload(request: Request) =
        JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject

    private fun repository(handler: (Request) -> Pair<Int, String>): StudentRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (status, body) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        return StudentRepository(retrofit.create(StudentApi::class.java),
            DepartmentRepository(retrofit.create(AuthApi::class.java)), retrofit.create(ProgramApi::class.java),
            retrofit.create(AcademicPeriodApi::class.java), retrofit.create(AcademicOfferingApi::class.java))
    }
}
