package com.campussphere.app.presentation.homework

import com.campussphere.app.data.remote.api.HomeworkInput
import com.campussphere.app.data.remote.api.HomeworkItem
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The assignment form as typed. Dates are YYYY-MM-DD and times HH:MM in the phone's time zone. */
data class HomeworkForm(
    val id: Long? = null,
    val status: String = "DRAFT",
    val classId: Long? = null,
    val title: String = "",
    val description: String = "",
    val instructions: String = "",
    val issueDate: String = "",
    val issueTime: String = "",
    val dueDate: String = "",
    val dueTime: String = "23:59",
    val maxMarks: String = "",
    val formats: Set<String> = setOf("pdf", "docx"),
    val maxSizeMb: String = "10",
    val lateAllowed: Boolean = false,
    val lateDate: String = "",
    val lateTime: String = "23:59",
    val resubmit: Boolean = false,
    /** Saved server values and how they were shown, so an untouched date is sent back exactly (to the second). */
    val savedIssueText: String? = null,
    val savedDueAt: String? = null,
    val savedDueText: String? = null,
    val savedLateUntil: String? = null,
    val savedLateText: String? = null,
) {
    val published get() = status == "PUBLISHED"
}

private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
private val TIME = Regex("\\d{2}:\\d{2}")

private fun at(date: String, time: String, zone: ZoneId): ZonedDateTime? {
    if (!DATE.matches(date.trim()) || !TIME.matches(time.trim())) return null
    return runCatching { ZonedDateTime.of(LocalDate.parse(date.trim()), LocalTime.parse(time.trim()), zone) }.getOrNull()
}

/** Issue time: blank means "now" (the server fills it in). */
private fun issue(form: HomeworkForm, zone: ZoneId): ZonedDateTime? =
    if (form.issueDate.isBlank()) null else at(form.issueDate, form.issueTime.ifBlank { "00:00" }, zone)

/**
 * Checks the form before it is sent, with a message a person can act on. The server repeats every rule and also
 * decides what may change once an assignment is published.
 */
fun homeworkFormProblem(form: HomeworkForm, now: ZonedDateTime): String? {
    val zone = now.zone
    val title = form.title.trim()
    if (form.id == null && form.classId == null) return "Choose one of your classes."
    if (title.isEmpty()) return "Enter a title."
    if (title.length > 160) return "Keep the title to 160 characters."
    if (form.description.trim().length > 4000 || form.instructions.trim().length > 4000) {
        return "Keep the description and instructions to 4000 characters each."
    }
    if (form.issueDate.isNotBlank() && issue(form, zone) == null) return "Use YYYY-MM-DD and HH:MM for the issue date."
    val due = at(form.dueDate, form.dueTime, zone) ?: return "Enter the deadline as YYYY-MM-DD and HH:MM."
    val start = issue(form, zone) ?: now
    if (!due.isAfter(start)) return "The deadline must be after the issue date."
    if (Duration.between(start, due).toDays() > 366) return "The deadline must be within a year of the issue date."
    if (form.id == null && !due.isAfter(now)) return "The deadline must be in the future."
    val marks = form.maxMarks.trim().toBigDecimalOrNull() ?: return "Enter the maximum marks as a number."
    if (marks.signum() <= 0 || marks > BigDecimal(1000) || marks.stripTrailingZeros().scale() > 2) {
        return "Maximum marks must be more than 0 and at most 1000, with up to two decimals."
    }
    if (form.formats.isEmpty()) return "Choose at least one file type students may upload."
    val size = form.maxSizeMb.trim().toDoubleOrNull() ?: return "Enter the file size limit in MB."
    if (size < 0.1 || size > 10) return "The file size limit must be between 0.1 and 10 MB."
    if (form.lateAllowed) {
        val late = at(form.lateDate, form.lateTime, zone) ?: return "Enter the late cut-off as YYYY-MM-DD and HH:MM."
        if (!late.isAfter(due)) return "The late cut-off must be after the deadline."
        if (Duration.between(due, late).toDays() > 60) return "The late cut-off must be within 60 days of the deadline."
    }
    return null
}

private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

/** The request body. Call only after [homeworkFormProblem] returned null. */
fun HomeworkForm.toInput(zone: ZoneId): HomeworkInput {
    val due = at(dueDate, dueTime, zone)!!
    // Unchanged saved dates go back exactly as stored; an unchanged issue date is left to the server.
    val issueText = "$issueDate $issueTime"
    val issueAt = if (id != null && issueText == savedIssueText) null else issue(this, zone)?.toOffsetDateTime()?.format(ISO)
    val dueAt = if (savedDueAt != null && "$dueDate $dueTime" == savedDueText) savedDueAt else due.toOffsetDateTime().format(ISO)
    val lateUntil = when {
        !lateAllowed -> null
        savedLateUntil != null && "$lateDate $lateTime" == savedLateText -> savedLateUntil
        else -> at(lateDate, lateTime, zone)!!.toOffsetDateTime().format(ISO)
    }
    return HomeworkInput(
        classId = if (id != null && published) null else classId, title = title.trim(), description = description.trim(),
        instructions = instructions.trim(), issueAt = issueAt, dueAt = dueAt,
        maxMarks = maxMarks.trim().toDouble(),
        allowedFormats = HOMEWORK_FORMAT_ORDER.filter { it in formats },
        maxFileBytes = (maxSizeMb.trim().toDouble() * 1024 * 1024).toInt().coerceIn(102_400, 10 * 1024 * 1024),
        latePolicy = if (lateAllowed) "ALLOWED" else "NONE", lateUntil = lateUntil,
        resubmission = if (resubmit) "UNTIL_DEADLINE" else "SINGLE")
}

private val HOMEWORK_FORMAT_ORDER = listOf("pdf", "docx", "pptx", "xlsx", "txt", "jpg", "png")

/** Fills the form from a saved assignment, shown in the phone's time zone. */
fun homeworkFormOf(item: HomeworkItem, zone: ZoneId): HomeworkForm {
    fun local(iso: String?) = iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }
    val issue = local(item.issueAt)
    val due = local(item.dueAt)
    val late = local(item.lateUntil)
    val time = DateTimeFormatter.ofPattern("HH:mm")
    val issueDate = issue?.toLocalDate()?.toString().orEmpty()
    val issueTime = issue?.format(time).orEmpty()
    val dueDate = due?.toLocalDate()?.toString().orEmpty()
    val dueTime = due?.format(time) ?: "23:59"
    val lateDate = late?.toLocalDate()?.toString().orEmpty()
    val lateTime = late?.format(time) ?: "23:59"
    return HomeworkForm(
        savedIssueText = "$issueDate $issueTime", savedDueAt = item.dueAt, savedDueText = "$dueDate $dueTime",
        savedLateUntil = item.lateUntil, savedLateText = if (item.lateUntil == null) null else "$lateDate $lateTime",
        id = item.id, status = item.status, classId = item.classId, title = item.title, description = item.description,
        instructions = item.instructions, issueDate = issue?.toLocalDate()?.toString().orEmpty(),
        issueTime = issue?.format(time).orEmpty(), dueDate = due?.toLocalDate()?.toString().orEmpty(),
        dueTime = due?.format(time) ?: "23:59", maxMarks = BigDecimal.valueOf(item.maxMarks).stripTrailingZeros().toPlainString(),
        formats = item.allowedFormats.toSet(),
        maxSizeMb = BigDecimal.valueOf(item.maxFileBytes / (1024.0 * 1024.0)).setScale(1, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString(),
        lateAllowed = item.latePolicy == "ALLOWED", lateDate = late?.toLocalDate()?.toString().orEmpty(),
        lateTime = late?.format(time) ?: "23:59", resubmit = item.resubmission == "UNTIL_DEADLINE")
}

private val SHOWN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

/** "3 Dec 2026, 23:59" in the phone's time zone. */
fun homeworkTime(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).format(SHOWN) }.getOrNull() ?: it } ?: "—"

/** "Due in 2 days", "Due in 5 hours", "Overdue by 1 day". */
fun homeworkRemaining(dueIso: String, now: ZonedDateTime = ZonedDateTime.now()): String {
    val due = runCatching { OffsetDateTime.parse(dueIso).toZonedDateTime() }.getOrNull() ?: return ""
    val gap = Duration.between(now, due)
    val abs = gap.abs()
    val text = when {
        abs.toDays() >= 1 -> "${abs.toDays()} day" + if (abs.toDays() == 1L) "" else "s"
        abs.toHours() >= 1 -> "${abs.toHours()} hour" + if (abs.toHours() == 1L) "" else "s"
        else -> "${maxOf(1, abs.toMinutes())} min"
    }
    return if (gap.isNegative) "Overdue by $text" else "Due in $text"
}

/** Checks typed marks against the assignment's maximum before they are sent. */
fun homeworkMarksProblem(text: String, max: Double): String? {
    val marks = text.trim().toBigDecimalOrNull() ?: return "Enter the marks as a number."
    return when {
        marks.signum() < 0 -> "Marks cannot be negative."
        marks > BigDecimal.valueOf(max) -> "Marks cannot be more than ${homeworkMarks(max)}."
        marks.stripTrailingZeros().scale() > 2 -> "Use at most two decimals."
        else -> null
    }
}

fun homeworkMarks(value: Double?): String =
    value?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "—"

fun homeworkStatusLabel(status: String) = when (status) {
    "DRAFT" -> "Draft"
    "PUBLISHED" -> "Published"
    "CLOSED" -> "Closed"
    "ARCHIVED" -> "Archived"
    "SUBMITTED" -> "Submitted"
    "EVALUATED" -> "Marked"
    "RETURNED" -> "Returned for correction"
    "SUPERSEDED" -> "Replaced by a later attempt"
    "NOT_SUBMITTED" -> "Not submitted"
    else -> status
}

/** What a student should see as the state of their work on one assignment. */
fun studentWorkLabel(item: HomeworkItem): String {
    val mine = item.mine
    return when {
        mine == null && item.acceptingSubmissions -> if (item.overdue) "Late submission allowed" else "Not submitted"
        mine == null -> if (item.overdue) "Missed" else "Not open yet"
        mine.status == "EVALUATED" -> "Marked: ${homeworkMarks(mine.marks)} / ${homeworkMarks(item.maxMarks)}"
        mine.status == "RETURNED" -> "Returned for correction"
        mine.late -> "Submitted late"
        else -> "Submitted"
    }
}
