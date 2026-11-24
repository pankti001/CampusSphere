package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.AuthApi
import com.campussphere.app.data.remote.dto.AcademicCatalogInput
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import com.campussphere.app.data.remote.dto.DepartmentInput
import com.campussphere.app.data.remote.dto.DepartmentSummary
import java.io.IOException
import retrofit2.Response

class AcademicCatalogFailure(val status: Int, message: String) : Exception(message)
typealias DepartmentFailure = AcademicCatalogFailure

interface AcademicCatalogRepository {
    suspend fun list(authorization: String): List<AcademicCatalogItem>
    suspend fun save(authorization: String, id: Long?, input: AcademicCatalogInput): AcademicCatalogItem
}

class DepartmentRepository(private val api: AuthApi) : AcademicCatalogRepository {
    override suspend fun list(authorization: String) = api.departments(authorization).catalogBody("department")

    override suspend fun save(authorization: String, id: Long?, input: DepartmentInput): DepartmentSummary =
        (if (id == null) api.createDepartment(authorization, input)
        else api.updateDepartment(authorization, id, input)).catalogBody("department")
}

internal fun <T> Response<T>.catalogBody(resource: String,
    conflict: String = "This college already has a $resource with that code."): T {
    if (isSuccessful) return body() ?: throw IOException("CampusSphere returned no $resource data.")
    errorBody()?.close()
    throw AcademicCatalogFailure(code(), when (code()) {
        400 -> "Check the $resource code and name."
        401 -> "Your session expired. Sign in again."
        403 -> "Your account can no longer manage ${resource}s."
        404 -> "This $resource is no longer available. Refresh the list."
        409 -> conflict
        503 -> "CampusSphere is temporarily unavailable. Try again."
        else -> "CampusSphere could not complete the $resource request."
    })
}
