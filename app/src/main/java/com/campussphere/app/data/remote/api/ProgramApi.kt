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

interface ProgramApi {
    @GET("api/college/departments/{departmentId}/programs")
    suspend fun list(@Header("Authorization") authorization: String,
        @Path("departmentId") departmentId: Long): Response<List<AcademicCatalogItem>>

    @POST("api/college/departments/{departmentId}/programs")
    suspend fun create(@Header("Authorization") authorization: String,
        @Path("departmentId") departmentId: Long, @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>

    @PATCH("api/college/departments/{departmentId}/programs/{programId}")
    suspend fun update(@Header("Authorization") authorization: String,
        @Path("departmentId") departmentId: Long, @Path("programId") id: Long,
        @Body input: AcademicCatalogInput): Response<AcademicCatalogItem>
}
