package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.AcademicPeriodApi
import com.campussphere.app.data.remote.dto.AcademicCatalogInput

class AcademicYearRepository(private val api: AcademicPeriodApi) : AcademicCatalogRepository {
    override suspend fun list(authorization: String) = api.years(authorization).catalogBody("academic year")

    override suspend fun save(authorization: String, id: Long?, input: AcademicCatalogInput) =
        (if (id == null) api.createYear(authorization, input)
        else api.updateYear(authorization, id, input)).catalogBody(
            "academic year", "This college already has that academic year.")
}

class SemesterRepository(private val api: AcademicPeriodApi, private val program: Long, private val year: Long) :
    AcademicCatalogRepository {
    override suspend fun list(authorization: String) = api.semesters(authorization, program, year).catalogBody("semester")

    override suspend fun save(authorization: String, id: Long?, input: AcademicCatalogInput) =
        (if (id == null) api.createSemester(authorization, program, year, input)
        else api.updateSemester(authorization, program, year, id, input)).catalogBody(
            "semester", "This program already has that semester number in this academic year.")
}
