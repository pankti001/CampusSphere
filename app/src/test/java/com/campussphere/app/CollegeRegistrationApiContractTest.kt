package com.campussphere.app

import com.campussphere.app.data.remote.api.CollegeApplication
import com.campussphere.app.data.remote.api.CollegeRegistrationApi
import com.campussphere.app.data.remote.api.Rejection
import com.campussphere.app.data.remote.api.Verification
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class CollegeRegistrationApiContractTest {
    @Test fun emptyAcceptedSubmissionSucceedsWithoutAuthorization() = runBlocking {
        val api = apiWithResponse(202) { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/college-registration-requests", request.url.encodedPath)
            assertNull(request.header("Authorization"))
        }
        api.submit(CollegeApplication("Fixture College", "FIXTURE", null, "Fixture Applicant",
            "fixture@example.invalid", "Registrar", "synthetic-fixture-only"))
    }

    @Test fun emptyDecisionResponsesSucceedWithAuthorization() = runBlocking {
        val paths = mutableListOf<String>()
        val api = apiWithResponse(204) { request ->
            assertEquals("POST", request.method)
            assertEquals("Bearer fixture", request.header("Authorization"))
            paths += request.url.encodedPath
        }
        api.approve("Bearer fixture", 7, Verification("OFFICIAL_CALLBACK", true, true, true))
        api.reject("Bearer fixture", 7, Rejection("Synthetic reason"))
        assertEquals(listOf("/api/platform/college-registration-requests/7/approve",
            "/api/platform/college-registration-requests/7/reject"), paths)
    }

    private fun apiWithResponse(code: Int, inspect: (Request) -> Unit): CollegeRegistrationApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            inspect(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body("".toResponseBody()).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(CollegeRegistrationApi::class.java)
    }
}
