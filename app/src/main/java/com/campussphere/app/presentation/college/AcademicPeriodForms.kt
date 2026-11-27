package com.campussphere.app.presentation.college

import com.campussphere.app.data.remote.dto.AcademicCatalogInput
import com.campussphere.app.data.remote.dto.DepartmentSummary
import java.time.LocalDate

/**
 * Accepts the ways people write an academic year — 2026-2027, 2026-27, 2026/27, "2026 2027" or just 2026 — and
 * returns the form the server stores, 2026-2027. Null when it is not two consecutive years.
 */
internal fun normalizedAcademicYear(text: String): String? {
    val parts = Regex("\\d+").findAll(text).map { it.value }.toList()
    if (parts.isEmpty() || parts.size > 2) return null
    val first = parts[0]
    if (first.length != 4 || first[0] == '0') return null
    val start = first.toInt()
    val end = when (val second = parts.getOrNull(1)) {
        null -> start + 1
        else -> when (second.length) {
            2 -> (start / 100) * 100 + second.toInt() + if (second.toInt() < start % 100) 100 else 0
            4 -> second.toInt()
            else -> return null
        }
    }
    return if (end == start + 1) "$start-$end" else null
}

/** The academic year in progress (from June) or the one about to start. */
internal fun currentAcademicYear(today: LocalDate = LocalDate.now()): String {
    val start = if (today.monthValue >= 6) today.year else today.year - 1
    return "$start-${start + 1}"
}

/** A new academic year form suggests the current year, or the year after the latest one already added. */
internal fun suggestedAcademicYear(existing: List<DepartmentSummary>, today: LocalDate = LocalDate.now()): DepartmentForm {
    val latest = existing.mapNotNull { normalizedAcademicYear(it.code)?.substring(0, 4)?.toInt() }.maxOrNull()
    val current = currentAcademicYear(today)
    val code = if (latest == null || latest < current.substring(0, 4).toInt()) current else "${latest + 1}-${latest + 2}"
    return DepartmentForm(code = code, name = academicYearName(code))
}

internal fun academicYearName(code: String) = "Academic Year ${code.substring(0, 4)}-${code.substring(7)}"

internal fun validatedAcademicYear(code: String, name: String): AcademicCatalogInput? {
    val range = normalizedAcademicYear(code) ?: return null
    return validatedDepartment(range, name.trim().ifEmpty { academicYearName(range) })
}

/** "3", "Sem 3" or "Semester 3" → "3"; 1 to 99. */
internal fun normalizedSemester(text: String): String? {
    val match = Regex("^(?:sem(?:ester)?\\.?\\s*)?0*([1-9][0-9]?)$", RegexOption.IGNORE_CASE).find(text.trim())
    return match?.groupValues?.get(1)
}

internal fun suggestedSemester(existing: List<DepartmentSummary>): DepartmentForm {
    val next = (existing.mapNotNull { it.code.toIntOrNull() }.maxOrNull() ?: 0) + 1
    return DepartmentForm(code = next.toString(), name = "Semester $next")
}

internal fun validatedSemester(code: String, name: String): AcademicCatalogInput? {
    val number = normalizedSemester(code) ?: return null
    return validatedDepartment(number, name.trim().ifEmpty { "Semester $number" })
}

/** Department, program, division and subject codes: spaces become hyphens and letters are made capital. */
internal fun normalizedCode(text: String) = text.trim().replace(Regex("\\s+"), "-").uppercase()

/** A code suggestion from a name: "Computer Science" → "CS", "Java Programming 1" → "JP1". */
internal fun suggestedCode(name: String): String {
    val words = name.trim().split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""
    val code = if (words.size == 1) words[0].take(6) else words.joinToString("") { w -> if (w.all(Char::isDigit)) w else w.take(1) }
    return code.uppercase().take(30)
}
