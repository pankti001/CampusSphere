package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.ProgramApi
import com.campussphere.app.data.remote.dto.AcademicCatalogInput
import com.campussphere.app.data.remote.dto.AcademicCatalogItem

class ProgramRepository(private val api: ProgramApi, private val departmentId: Long) : AcademicCatalogRepository {
    override suspend fun list(authorization: String) = api.list(authorization, departmentId).catalogBody("program")

    override suspend fun save(authorization: String, id: Long?, input: AcademicCatalogInput): AcademicCatalogItem =
        (if (id == null) api.create(authorization, departmentId, input)
        else api.update(authorization, departmentId, id, input)).catalogBody("program")
}
