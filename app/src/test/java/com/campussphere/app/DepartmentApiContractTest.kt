package com.campussphere.app

import com.campussphere.app.data.remote.api.AuthApi
import com.campussphere.app.data.repository.DepartmentFailure
import com.campussphere.app.data.repository.DepartmentRepository
import com.campussphere.app.presentation.college.validatedDepartment
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

class DepartmentApiContractTest {
    @Test fun validatesDepartmentInputWithoutTenantField() {
        assertNull(validatedDepartment("_invalid", "Department"))
        assertNull(validatedDepartment("CO", " "))
        assertNull(validatedDepartment("C".repeat(31), "Department"))
        assertNull(validatedDepartment("CO", "X".repeat(151)))
        assertEquals("CS-01", validatedDepartment(" CS-01 ", " Computer Science ")?.code)
        assertEquals("Computer Science", validatedDepartment("CS", " Computer Science ")?.name)
    }

    @Test fun usesTenantScopedListCreateAndPatchContracts() = runBlocking {
        val repository = repository { request ->
            assertEquals("Bearer fixture", request.header("Authorization"))
            assertNull(request.url.query)
            val body = Buffer()
            request.body?.writeTo(body)
            val document = body.readUtf8()
            assertFalse(document.contains("collegeId"))
            val row = """{"id":3,"code":"CS","name":"Computer Science"}"""
            when (request.method) {
                "GET" -> { assertEquals("/api/college/departments", request.url.encodedPath); 200 to "[$row]" }
                "POST" -> { assertEquals("/api/college/departments", request.url.encodedPath); 201 to row }
                "PATCH" -> { assertEquals("/api/college/departments/3", request.url.encodedPath); 200 to row }
                else -> error("Unexpected method")
            }
        }
        val input = requireNotNull(validatedDepartment("CS", "Computer Science"))
        assertEquals(3L, repository.list("Bearer fixture").single().id)
        assertEquals(3L, repository.save("Bearer fixture", null, input).id)
        assertEquals(3L, repository.save("Bearer fixture", 3, input).id)
    }

    @Test fun preservesStatusWithoutExposingServerErrorBody() = runBlocking {
        for (status in listOf(401, 403, 404, 409, 503)) {
            val repo = repository { status to "internal fixture details" }
            val failure = runCatching { repo.list("Bearer fixture") }.exceptionOrNull() as DepartmentFailure
            assertEquals(status, failure.status)
            assertFalse(failure.message.orEmpty().contains("internal fixture details"))
        }
    }

    private fun repository(handler: (Request) -> Pair<Int, String>): DepartmentRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (status, body) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("Fixture").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(AuthApi::class.java)
        return DepartmentRepository(api)
    }
}
