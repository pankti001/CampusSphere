package com.campussphere.app.presentation.college

import com.campussphere.app.data.remote.dto.AcademicCatalogInput

internal fun validatedAcademicYear(code: String, name: String): AcademicCatalogInput? {
    val range = code.trim()
    if (!range.matches(Regex("[1-9][0-9]{3}-[1-9][0-9]{3}"))) return null
    val start = range.substring(0, 4).toInt()
    if (range.substring(5).toInt() != start + 1) return null
    return validatedDepartment(range, name)
}

internal fun validatedSemester(code: String, name: String): AcademicCatalogInput? {
    val number = code.trim()
    if (!number.matches(Regex("[1-9][0-9]?"))) return null
    return validatedDepartment(number, name)
}
