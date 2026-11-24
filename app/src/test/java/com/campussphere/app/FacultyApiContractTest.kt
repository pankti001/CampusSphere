package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import com.campussphere.app.data.repository.AcademicCatalogFailure
import com.campussphere.app.data.repository.DepartmentRepository
import com.campussphere.app.data.repository.FacultyRepository
import com.campussphere.app.presentation.college.AssignmentSelection
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

class FacultyApiContractTest {
    @Test fun keepsIdentityInTheSessionAndSendsOnlyAcademicFields() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val repo = repository { request ->
            assertEquals("Bearer test", request.header("Authorization"))
            assertNull(request.url.query)
            val path = request.url.encodedPath
            calls += request.method to path
            request.body?.let { body ->
                val payload = JsonParser.parseString(Buffer().apply { body.writeTo(this) }.readUtf8()).asJsonObject
                assertEquals(if (request.method == "PUT") setOf("departmentId") else
                    setOf("departmentId", "programId", "academicYearId", "semesterId", "divisionId", "subjectId"),
                    payload.keySet())
            }
            val assignment = """{"id":99,"department":"Science","program":"Biology","academicYear":"2026","semester":"First","division":"A","subject":"Biology Lab","subjectCode":"BIO"}"""
            val member = """{"id":12,"name":"Faculty Example","email":"faculty@example.invalid","departmentId":2,"department":"Science"}"""
            when {
                request.method == "DELETE" -> 204 to ""
                request.method == "POST" -> 201 to assignment
                path.endsWith("/assignments") -> 200 to "[$assignment]"
                path.endsWith("/faculty") -> 200 to "[$member]"
                else -> 200 to member
            }
        }
        val auth = "Bearer test"
        assertEquals(12L, repo.members(auth).single().id)
        assertEquals(12L, repo.profile(auth, 12).id)
        assertEquals(2L, repo.department(auth, 12, 2).departmentId)
        assertEquals(99L, repo.assignments(auth, 12).single().id)
        assertEquals(99L, repo.assign(auth, 12, FacultyAssignmentInput(2, 3, 4, 5, 6, 7)).id)
        repo.end(auth, 12, 99)
        assertEquals(99L, repo.mine(auth).single().id)
        assertEquals(listOf("GET" to "/api/college/faculty",
            "GET" to "/api/college/faculty/12", "PUT" to "/api/college/faculty/12/department",
            "GET" to "/api/college/faculty/12/assignments", "POST" to "/api/college/faculty/12/assignments",
            "DELETE" to "/api/college/faculty/12/assignments/99", "GET" to "/api/faculty/assignments"), calls)
    }

    @Test fun incompleteSelectionsCannotProduceAnAssignmentPayload() {
        fun option(id: Long) = AcademicCatalogItem(id, "S", "Sample")
        val selection = AssignmentSelection(option(2), option(3), option(4), option(5), option(6))
        assertNull(selection.input())
        assertEquals(FacultyAssignmentInput(2, 3, 4, 5, 6, 7), selection.copy(subject = option(7)).input())
    }

    @Test fun conflictIsSafeAndKeepsTheDuplicateAssignmentMessage() = runBlocking {
        val repo = repository { 409 to "private body" }
        val error = runCatching { repo.assign("Bearer test", 12, FacultyAssignmentInput(2, 3, 4, 5, 6, 7)) }
            .exceptionOrNull() as AcademicCatalogFailure
        assertEquals(409, error.status)
        assertEquals("This faculty member already has that active subject assignment in this division.", error.message)
    }

    private fun repository(handler: (Request) -> Pair<Int, String>): FacultyRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (status, body) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        return FacultyRepository(retrofit.create(FacultyApi::class.java),
            DepartmentRepository(retrofit.create(AuthApi::class.java)), retrofit.create(ProgramApi::class.java),
            retrofit.create(AcademicPeriodApi::class.java), retrofit.create(AcademicOfferingApi::class.java))
    }
}
