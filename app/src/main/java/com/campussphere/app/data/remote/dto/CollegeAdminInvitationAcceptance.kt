package com.campussphere.app.data.remote.dto

class CollegeAdminInvitationAcceptance(val token: String, val fullName: String, val password: String) {
    override fun toString() = "CollegeAdminInvitationAcceptance[redacted]"
}

/** active is sent for departments; startDate/endDate (YYYY-MM-DD) for academic years and semesters. */
data class AcademicCatalogItem(val id: Long, val code: String, val name: String, val active: Boolean? = null,
                               val startDate: String? = null, val endDate: String? = null)

/** Null fields are left out of the JSON, so departments, programs, divisions and subjects send only code and name. */
data class AcademicCatalogInput(val code: String, val name: String, val startDate: String? = null,
                                val endDate: String? = null, val clearDates: Boolean? = null)
typealias DepartmentSummary = AcademicCatalogItem
typealias DepartmentInput = AcademicCatalogInput
