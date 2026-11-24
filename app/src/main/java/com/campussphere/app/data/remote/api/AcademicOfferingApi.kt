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

private const val DIVISIONS = "api/college/programs/{program}/academic-years/{parent}/divisions"
private const val SUBJECTS = "api/college/programs/{program}/semesters/{parent}/subjects"

interface AcademicOfferingApi {
    @GET(DIVISIONS)
    suspend fun divisions(@Header("Authorization") auth: String, @Path("program") program: Long,
        @Path("parent") year: Long): Response<List<AcademicCatalogItem>>

    @POST(DIVISIONS)
    suspend fun createDivision(@Header("Authorization") auth: String, @Path("program") program: Long,
        @Path("parent") year: Long, @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>

    @PATCH("$DIVISIONS/{id}")
    suspend fun updateDivision(@Header("Authorization") auth: String, @Path("program") program: Long,
        @Path("parent") year: Long, @Path("id") id: Long, @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>

    @GET(SUBJECTS)
    suspend fun subjects(@Header("Authorization") auth: String, @Path("program") program: Long,
        @Path("parent") semester: Long): Response<List<AcademicCatalogItem>>

    @POST(SUBJECTS)
    suspend fun createSubject(@Header("Authorization") auth: String, @Path("program") program: Long,
        @Path("parent") semester: Long, @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>

    @PATCH("$SUBJECTS/{id}")
    suspend fun updateSubject(@Header("Authorization") auth: String, @Path("program") program: Long,
        @Path("parent") semester: Long, @Path("id") id: Long, @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>
}
