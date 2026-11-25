package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.remote.dto.AcademicCatalogItem

class FacultyRepository(
    private val faculty: FacultyApi,
    private val departments: DepartmentRepository,
    private val programs: ProgramApi,
    private val periods: AcademicPeriodApi,
    private val offerings: AcademicOfferingApi
) {
    suspend fun pending(auth: String) = faculty.pending(auth).catalogBody("faculty request")
    suspend fun decide(auth: String, id: Long, approved: Boolean, employmentConfirmed: Boolean) =
        faculty.decide(auth, id, FacultyAccessDecision(if (approved) "APPROVED" else "REJECTED",
            employmentConfirmed)).catalogBody("faculty request", "The request has changed. Refresh the list.")

    suspend fun members(auth: String) = faculty.members(auth).catalogBody("faculty member")
    suspend fun profile(auth: String, id: Long) = faculty.profile(auth, id).catalogBody("faculty member")
    suspend fun assignments(auth: String, id: Long) = faculty.assignments(auth, id).catalogBody("assignment")
    suspend fun mine(auth: String) = faculty.myAssignments(auth).catalogBody("assignment")
    suspend fun department(auth: String, id: Long, dept: Long) =
        faculty.department(auth, id, FacultyDepartmentInput(dept)).catalogBody(
            "department", "End active assignments before changing the department.")

    suspend fun assign(auth: String, id: Long, input: FacultyAssignmentInput) =
        faculty.assign(auth, id, input).catalogBody(
            "assignment", "This faculty member already has that active subject assignment in this division.")

    suspend fun end(auth: String, id: Long, assignment: Long) {
        val response = faculty.end(auth, id, assignment)
        if (!response.isSuccessful) response.catalogBody("assignment")
    }

    suspend fun departments(auth: String): List<AcademicCatalogItem> = departments.list(auth)
    suspend fun programs(auth: String, department: Long) = programs.list(auth, department).catalogBody("program")
    suspend fun years(auth: String) = periods.years(auth).catalogBody("academic year")
    suspend fun semesters(auth: String, program: Long, year: Long) =
        periods.semesters(auth, program, year).catalogBody("semester")
    suspend fun divisions(auth: String, program: Long, year: Long) =
        offerings.divisions(auth, program, year).catalogBody("division")
    suspend fun subjects(auth: String, program: Long, semester: Long) =
        offerings.subjects(auth, program, semester).catalogBody("subject")
}
