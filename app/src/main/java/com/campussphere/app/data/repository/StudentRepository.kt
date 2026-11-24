package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.*

class StudentRepository(
    private val api: StudentApi,
    private val departments: DepartmentRepository,
    private val programs: ProgramApi,
    private val periods: AcademicPeriodApi,
    private val offerings: AcademicOfferingApi
) {
    suspend fun students(auth: String) = api.students(auth).catalogBody("student")
    suspend fun profile(auth: String, id: Long) = api.profile(auth, id).catalogBody("student")
    suspend fun history(auth: String, id: Long) = api.history(auth, id).catalogBody("enrollment")
    suspend fun current(auth: String) = api.current(auth).catalogBody("enrollment")
    suspend fun department(auth: String, id: Long, department: Long) =
        api.department(auth, id, StudentDepartmentInput(department)).catalogBody(
            "department", "End the current enrollment before changing the department.")

    suspend fun enroll(auth: String, id: Long, input: StudentEnrollmentInput) =
        api.enroll(auth, id, input).catalogBody("enrollment", "This student already has a current enrollment.")

    suspend fun end(auth: String, id: Long, enrollment: Long) {
        val response = api.end(auth, id, enrollment)
        if (!response.isSuccessful) response.catalogBody("enrollment")
    }

    suspend fun myRequest(auth: String) = api.myRequest(auth).catalogBody("affiliation").singleOrNull()
    suspend fun pending(auth: String) = api.pending(auth).catalogBody("affiliation")
    suspend fun request(auth: String, code: String) = try {
        api.request(auth, StudentCollegeInput(code)).catalogBody("college", "You already have a pending request.")
    } catch (failure: AcademicCatalogFailure) {
        if (failure.status != 404) throw failure
        throw AcademicCatalogFailure(404, "No active college uses that code.")
    }

    suspend fun decide(auth: String, id: Long, approved: Boolean, confirmed: Boolean) =
        api.decide(auth, id, StudentDecisionInput(approved, confirmed)).catalogBody(
            "request", "This request has already changed. Refresh the queue.")

    suspend fun departments(auth: String) = departments.list(auth)
    suspend fun programs(auth: String, department: Long) = ProgramRepository(programs, department).list(auth)
    suspend fun years(auth: String) = AcademicYearRepository(periods).list(auth)
    suspend fun semesters(auth: String, program: Long, year: Long) = SemesterRepository(periods, program, year).list(auth)
    suspend fun divisions(auth: String, program: Long, year: Long) = DivisionRepository(offerings, program, year).list(auth)
}
