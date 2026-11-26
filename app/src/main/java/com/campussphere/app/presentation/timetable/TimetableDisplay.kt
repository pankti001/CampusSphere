package com.campussphere.app.presentation.timetable

import com.campussphere.app.data.remote.api.*
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Display and form rules shared by the admin editor and the personal timetables. Pure functions; unit-tested. */

val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
fun dayName(day: Int) = DAY_NAMES.getOrElse(day - 1) { "Day $day" }
fun dayShort(day: Int) = dayName(day).take(3)

/** "09:30:00" or "09:30" → "09:30". */
fun hhmm(time: String) = time.take(5)

private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

/** Minutes after midnight, or null when the text is not a valid HH:MM time. */
fun minutesOf(time: String): Int? {
    val t = hhmm(time.trim())
    if (!TIME.matches(t)) return null
    return t.substring(0, 2).toInt() * 60 + t.substring(3, 5).toInt()
}

fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

enum class RowKind { LESSON, BREAK, LUNCH, FREE }

/** One line of a day: a lesson, a break, lunch, or a free gap between them. */
data class DayRow(val kind: RowKind, val start: String, val end: String, val slot: TimetableSlot?)

/** A day's periods in time order, with FREE rows for gaps of 10 minutes or more between periods. */
fun dayRows(slots: List<TimetableSlot>, day: Int): List<DayRow> {
    val rows = mutableListOf<DayRow>()
    var lastEnd: Int? = null
    for (slot in slots.filter { it.day == day }.sortedBy { minutesOf(it.start) ?: 0 }) {
        val start = minutesOf(slot.start) ?: continue
        val end = minutesOf(slot.end) ?: continue
        val previous = lastEnd
        if (previous != null && start - previous >= 10) rows += DayRow(RowKind.FREE, clock(previous), clock(start), null)
        val kind = runCatching { RowKind.valueOf(slot.kind) }.getOrDefault(RowKind.LESSON)
        rows += DayRow(kind, clock(start), clock(end), slot)
        lastEnd = maxOf(previous ?: end, end)
    }
    return rows
}

/** Monday to Friday always; Saturday and Sunday only when something is scheduled then. */
fun shownDays(days: Collection<Int>) = (1..7).filter { it <= 5 || it in days }

fun isNow(row: DayRow, nowMinutes: Int): Boolean {
    val start = minutesOf(row.start) ?: return false
    val end = minutesOf(row.end) ?: return false
    return nowMinutes in start until end
}

/** A timetable changed recently when a version after the first was published within the last 7 days. */
fun recentlyUpdated(publishedAt: String?, version: Int?, now: Instant = Instant.now()): Boolean {
    if (publishedAt == null || version == null || version < 2) return false
    val published = runCatching { OffsetDateTime.parse(publishedAt).toInstant() }.getOrNull() ?: return false
    return !published.isAfter(now) && Duration.between(published, now) <= Duration.ofDays(7)
}

fun clashMessage(clash: TimetableClash): String =
    "${dayShort(clash.day)} ${hhmm(clash.start)}–${hhmm(clash.end)}: " + when (clash.type) {
        "FACULTY" -> "the faculty member already teaches ${clash.with}."
        "CLASSROOM" -> "the classroom is already used by ${clash.with}."
        else -> "this class already has ${clash.with}."
    }

fun slotTitle(slot: TimetableSlot) = when (slot.kind) {
    "LESSON" -> listOfNotNull(slot.subjectCode, slot.subject).joinToString(" ")
    "LUNCH" -> slot.label ?: "Lunch"
    else -> slot.label ?: "Break"
}

fun entryTitle(entry: TimetableEntry) = when (entry.kind) {
    "LESSON" -> listOfNotNull(entry.subjectCode, entry.subject).joinToString(" ")
    "LUNCH" -> entry.label ?: "Lunch"
    else -> entry.label ?: "Break"
}

// ---- Entry form ----

data class EntryForm(val kind: String = "LESSON", val day: Int = 1, val start: String = "", val end: String = "",
                     val assignmentId: Long? = null, val classroomId: Long? = null, val label: String = "")

fun formFrom(entry: TimetableEntry) = EntryForm(entry.kind, entry.day, hhmm(entry.start), hhmm(entry.end),
    entry.assignmentId, entry.classroomId, entry.label.orEmpty())

/** A new period starts where the day's last period ends (or 09:00) and lasts an hour, or 15 minutes for a break. */
fun suggestedForm(entries: List<TimetableEntry>, day: Int, kind: String): EntryForm {
    val start = entries.filter { it.day == day }.mapNotNull { minutesOf(it.end) }.maxOrNull() ?: (9 * 60)
    val length = when (kind) { "LESSON" -> 60; "LUNCH" -> 45; else -> 15 }
    val end = minOf(start + length, 23 * 60 + 55)
    return EntryForm(kind = kind, day = day, start = clock(start), end = clock(maxOf(end, start)))
}

/** Checks the form like the server does, so most mistakes are explained before anything is sent. */
fun entryInput(form: EntryForm): Result<TimetableEntryInput> {
    val start = minutesOf(form.start)
    val end = minutesOf(form.end)
    val lesson = form.kind == "LESSON"
    val label = form.label.trim()
    val problem = when {
        form.kind !in setOf("LESSON", "BREAK", "LUNCH") -> "Choose lesson, break or lunch."
        form.day !in 1..7 -> "Choose a day."
        start == null || end == null -> "Use HH:MM for times, for example 09:30."
        end <= start -> "The end time must be after the start time."
        start % 5 != 0 || end % 5 != 0 -> "Use 5-minute steps, for example 09:00 or 09:05."
        lesson && end - start > 240 -> "A lesson can be at most 4 hours."
        !lesson && end - start > 120 -> "A break can be at most 2 hours."
        lesson && form.assignmentId == null -> "Choose the subject and faculty member."
        lesson && form.classroomId == null -> "Choose a classroom."
        label.length > 60 -> "The label can be at most 60 characters."
        else -> null
    }
    if (problem != null) return Result.failure(IllegalArgumentException(problem))
    return Result.success(TimetableEntryInput(form.kind, form.day, clock(start!!), clock(end!!),
        form.assignmentId.takeIf { lesson }, form.classroomId.takeIf { lesson }, label.ifEmpty { null }))
}

// ---- Academic scope selection (year → department → program → semester → division) ----

data class ScopeChoice(val yearId: Long? = null, val departmentId: Long? = null, val programId: Long? = null,
                       val semesterId: Long? = null, val divisionId: Long? = null) {
    val complete get() = semesterId != null && divisionId != null
}

data class ScopeLists(val years: List<Pair<Long, String>>, val departments: List<Pair<Long, String>>,
                      val programs: List<Pair<Long, String>>, val semesters: List<Pair<Long, String>>,
                      val divisions: List<Pair<Long, String>>)

fun scopeLists(scopes: List<TimetableScope>, c: ScopeChoice): ScopeLists {
    val inYear = scopes.filter { it.academicYearId == c.yearId }
    val inDepartment = inYear.filter { it.departmentId == c.departmentId }
    val inProgram = inDepartment.filter { it.programId == c.programId }
    val inSemester = inProgram.filter { it.semesterId == c.semesterId }
    return ScopeLists(
        scopes.distinctBy { it.academicYearId }.map { it.academicYearId to it.academicYear },
        inYear.distinctBy { it.departmentId }.map { it.departmentId to it.department },
        inDepartment.distinctBy { it.programId }.map { it.programId to it.program },
        inProgram.distinctBy { it.semesterId }.sortedBy { it.semesterNumber }.map { it.semesterId to it.semester },
        inSemester.distinctBy { it.divisionId }.map { it.divisionId to it.division })
}

/** Changing a level clears the levels below it. */
fun chooseScope(c: ScopeChoice, level: Int, id: Long?): ScopeChoice = when (level) {
    0 -> ScopeChoice(yearId = id)
    1 -> c.copy(departmentId = id, programId = null, semesterId = null, divisionId = null)
    2 -> c.copy(programId = id, semesterId = null, divisionId = null)
    3 -> c.copy(semesterId = id, divisionId = null)
    else -> c.copy(divisionId = id)
}

// ---- Classroom form ----

data class RoomForm(val code: String = "", val name: String = "", val capacity: String = "", val active: Boolean = true)

fun roomInput(form: RoomForm): Result<TimetableClassroomInput> {
    val code = form.code.trim().uppercase()
    val name = form.name.trim()
    val capacity = form.capacity.trim().ifEmpty { null }?.toIntOrNull()
    val problem = when {
        !Regex("^[A-Z0-9][A-Z0-9_-]{0,29}$").matches(code) -> "Use a code of capital letters, digits, - or _, for example A-101."
        name.isEmpty() || name.length > 150 -> "Enter a classroom name."
        form.capacity.isNotBlank() && (capacity == null || capacity !in 1..2000) -> "Capacity must be a number from 1 to 2000."
        else -> null
    }
    return if (problem != null) Result.failure(IllegalArgumentException(problem))
    else Result.success(TimetableClassroomInput(code, name, capacity, form.active))
}
