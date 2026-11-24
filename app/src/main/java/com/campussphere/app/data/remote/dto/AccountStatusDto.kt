package com.campussphere.app.data.remote.dto

import com.google.gson.annotations.SerializedName

data class AccountStatusDto(
    @SerializedName("verified") val verified: Boolean,
    @SerializedName("roles") val roles: Set<String>?,
    @SerializedName("facultyRequest") val facultyRequest: FacultyRequestDto?,
    @SerializedName("studentAffiliationEligible") val studentAffiliationEligible: Boolean = false
)

data class FacultyRequestDto(
    @SerializedName("status") val status: String?
)
