package com.campussphere.app.data.remote.api

import com.campussphere.app.data.remote.dto.AcademicCatalogInput
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

private const val YEARS = "api/college/academic-years"
private const val SEMESTERS = "api/college/programs/{programId}/academic-years/{yearId}/semesters"

interface AcademicPeriodApi {
    @GET(YEARS)
    suspend fun years(@Header("Authorization") authorization: String): Response<List<AcademicCatalogItem>>

    @POST(YEARS)
    suspend fun createYear(@Header("Authorization") authorization: String,
        @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>

    @PATCH("$YEARS/{id}")
    suspend fun updateYear(@Header("Authorization") authorization: String, @Path("id") id: Long,
        @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>

    @GET(SEMESTERS)
    suspend fun semesters(@Header("Authorization") authorization: String,
        @Path("programId") program: Long, @Path("yearId") year: Long): Response<List<AcademicCatalogItem>>

    @POST(SEMESTERS)
    suspend fun createSemester(@Header("Authorization") authorization: String,
        @Path("programId") program: Long, @Path("yearId") year: Long,
        @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>

    @PATCH("$SEMESTERS/{id}")
    suspend fun updateSemester(@Header("Authorization") authorization: String,
        @Path("programId") program: Long, @Path("yearId") year: Long, @Path("id") id: Long,
        @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>
}
