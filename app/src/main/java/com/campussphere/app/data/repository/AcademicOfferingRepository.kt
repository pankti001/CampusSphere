package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.AcademicOfferingApi
import com.campussphere.app.data.remote.dto.AcademicCatalogInput

class DivisionRepository(private val api: AcademicOfferingApi, private val program: Long, private val year: Long) :
    AcademicCatalogRepository {
    override suspend fun list(authorization: String) =
        api.divisions(authorization, program, year).catalogBody("division")

    override suspend fun save(authorization: String, id: Long?, input: AcademicCatalogInput) =
        (if (id == null) api.createDivision(authorization, program, year, input)
        else api.updateDivision(authorization, program, year, id, input))
            .catalogBody("division", "This program already uses that division code in this academic year.")
}

class SubjectRepository(private val api: AcademicOfferingApi, private val program: Long, private val semester: Long) :
    AcademicCatalogRepository {
    override suspend fun list(authorization: String) =
        api.subjects(authorization, program, semester).catalogBody("subject")

    override suspend fun save(authorization: String, id: Long?, input: AcademicCatalogInput) =
        (if (id == null) api.createSubject(authorization, program, semester, input)
        else api.updateSubject(authorization, program, semester, id, input))
            .catalogBody("subject", "This program already uses that subject code in this semester.")
}
