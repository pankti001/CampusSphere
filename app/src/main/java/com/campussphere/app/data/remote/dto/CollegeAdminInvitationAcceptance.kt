package com.campussphere.app.data.remote.dto

class CollegeAdminInvitationAcceptance(val token: String, val fullName: String, val password: String) {
    override fun toString() = "CollegeAdminInvitationAcceptance[redacted]"
}

data class AcademicCatalogItem(val id: Long, val code: String, val name: String)
data class AcademicCatalogInput(val code: String, val name: String)
typealias DepartmentSummary = AcademicCatalogItem
typealias DepartmentInput = AcademicCatalogInput
