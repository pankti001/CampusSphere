package com.campussphere.app

import com.campussphere.app.data.remote.api.AcademicOfferingApi
import com.campussphere.app.data.remote.dto.AcademicCatalogInput
import com.campussphere.app.data.repository.AcademicCatalogFailure
import com.campussphere.app.data.repository.DivisionRepository
import com.campussphere.app.data.repository.SubjectRepository
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

class AcademicOfferingApiContractTest {
    @Test fun sendsProgramAndParentInThePathAndOnlyEditableFieldsInTheBody() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val api = intercepted { request ->
            assertEquals("Bearer fixture", request.header("Authorization"))
            assertNull(request.url.query)
            calls += request.method to request.url.encodedPath
            request.body?.let { body ->
                val payload = Buffer().apply { body.writeTo(this) }.readUtf8()
                assertEquals(setOf("code", "name"), JsonParser.parseString(payload).asJsonObject.keySet())
            }
            val row = """{"id":21,"code":"A","name":"Example"}"""
            when (request.method) { "GET" -> 200 to "[$row]"; "POST" -> 201 to row; else -> 200 to row }
        }
        val input = AcademicCatalogInput("A", "Example")
        val paths = listOf("/api/college/programs/8/academic-years/57/divisions",
            "/api/college/programs/8/semesters/9/subjects")
        for (repository in listOf(DivisionRepository(api, 8, 57), SubjectRepository(api, 8, 9))) {
            assertEquals(21L, repository.list("Bearer fixture").single().id)
            assertEquals(21L, repository.save("Bearer fixture", null, input).id)
            assertEquals(21L, repository.save("Bearer fixture", 21, input).id)
        }
        assertEquals(paths.flatMap { listOf("GET" to it, "POST" to it, "PATCH" to "$it/21") }, calls)
    }

    @Test fun duplicateErrorsExplainTheCorrectScopeAndHideRawServerText() = runBlocking {
        val api = intercepted { 409 to "private server details" }
        val input = AcademicCatalogInput("A", "Example")
        val division = runCatching { DivisionRepository(api, 8, 57).save("Bearer fixture", null, input) }
            .exceptionOrNull() as AcademicCatalogFailure
        val subject = runCatching { SubjectRepository(api, 8, 9).save("Bearer fixture", 21, input) }
            .exceptionOrNull() as AcademicCatalogFailure
        assertEquals("This program already uses that division code in this academic year.", division.message)
        assertEquals("This program already uses that subject code in this semester.", subject.message)
        assertEquals(409, subject.status)
    }

    private fun intercepted(handler: (Request) -> Pair<Int, String>): AcademicOfferingApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (status, body) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(AcademicOfferingApi::class.java)
    }
}
