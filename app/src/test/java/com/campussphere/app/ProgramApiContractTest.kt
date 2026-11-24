package com.campussphere.app

import com.campussphere.app.data.remote.api.ProgramApi
import com.campussphere.app.data.remote.dto.AcademicCatalogInput
import com.campussphere.app.data.repository.AcademicCatalogFailure
import com.campussphere.app.data.repository.ProgramRepository
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

class ProgramApiContractTest {
    @Test fun listCreateAndEditUseTheSelectedDepartmentAndNoBodyOwnership() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val repository = repository { request ->
            assertEquals("Bearer fixture", request.header("Authorization"))
            assertNull(request.url.query)
            val payload = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
            if (request.method != "GET") {
                val fields = com.google.gson.JsonParser.parseString(payload).asJsonObject.keySet()
                assertEquals(setOf("code", "name"), fields)
            }
            calls += request.method to request.url.encodedPath
            val row = """{"id":8,"code":"BSC-CS","name":"Computer Science"}"""
            when (request.method) {
                "GET" -> 200 to "[$row]"
                "POST" -> 201 to row
                else -> 200 to row
            }
        }
        val input = AcademicCatalogInput("BSC-CS", "Computer Science")
        assertEquals(8L, repository.list("Bearer fixture").single().id)
        assertEquals(8L, repository.save("Bearer fixture", null, input).id)
        assertEquals(8L, repository.save("Bearer fixture", 8L, input).id)
        val base = "/api/college/departments/42/programs"
        assertEquals(listOf("GET" to base, "POST" to base, "PATCH" to "$base/8"), calls)
    }

    @Test fun exposesCollegeWideConflictWithoutRawServerText() = runBlocking {
        val repository = repository { 409 to "private server detail" }
        val failure = runCatching { repository.save("Bearer fixture", null, AcademicCatalogInput("CS", "Computing")) }
            .exceptionOrNull() as AcademicCatalogFailure
        assertEquals(409, failure.status)
        assertEquals("This college already has a program with that code.", failure.message)
    }

    private fun repository(response: (okhttp3.Request) -> Pair<Int, String>): ProgramRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (status, body) = response(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(ProgramApi::class.java)
        return ProgramRepository(api, 42L)
    }
}
