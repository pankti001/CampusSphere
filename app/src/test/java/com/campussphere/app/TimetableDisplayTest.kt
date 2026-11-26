package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.presentation.timetable.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TimetableDisplayTest {
    private fun slot(id: Long, kind: String, day: Int, start: String, end: String, code: String? = "CS101") = TimetableSlot(
        id, kind, day, start, end, if (kind == "LESSON") code else null, if (kind == "LESSON") "Programming" else null,
        "Prof A", if (kind == "LESSON") "A101" else null, null, null, "A", "BSc", "Semester 1", 2, "2026-11-25T10:00:00+05:30")

    @Test fun dayRowsAreSortedAndShowBreaksAndFreePeriods() {
        val slots = listOf(
            slot(3, "LESSON", 1, "11:00:00", "12:00:00"),
            slot(1, "LESSON", 1, "09:00", "10:00"),
            slot(2, "BREAK", 1, "10:00", "10:15"),
            slot(4, "LUNCH", 1, "13:00", "13:45"),
            slot(5, "LESSON", 2, "09:00", "10:00"))
        val rows = dayRows(slots, 1)
        assertEquals(listOf(RowKind.LESSON, RowKind.BREAK, RowKind.FREE, RowKind.LESSON, RowKind.FREE, RowKind.LUNCH), rows.map { it.kind })
        assertEquals("10:15", rows[2].start)
        assertEquals("11:00", rows[2].end)
        assertEquals("11:00", rows[3].start) // seconds are dropped
        assertTrue(dayRows(slots, 3).isEmpty())
    }

    @Test fun shortGapsAreNotShownAsFreePeriods() {
        val rows = dayRows(listOf(slot(1, "LESSON", 1, "09:00", "10:00"), slot(2, "LESSON", 1, "10:05", "11:00")), 1)
        assertEquals(2, rows.size)
    }

    @Test fun weekendsAppearOnlyWhenUsedAndNowIsDetected() {
        assertEquals(listOf(1, 2, 3, 4, 5), shownDays(listOf(1, 3)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), shownDays(listOf(6)))
        val row = DayRow(RowKind.LESSON, "09:00", "10:00", null)
        assertTrue(isNow(row, 9 * 60))
        assertTrue(isNow(row, 9 * 60 + 59))
        assertFalse(isNow(row, 10 * 60))
    }

    @Test fun updateIndicatorOnlyForLaterVersionsWithinAWeek() {
        val now = Instant.parse("2026-11-27T06:00:00Z")
        assertTrue(recentlyUpdated("2026-11-25T10:00:00+05:30", 2, now))
        assertFalse(recentlyUpdated("2026-11-25T10:00:00+05:30", 1, now))
        assertFalse(recentlyUpdated("2026-11-01T10:00:00+05:30", 3, now))
        assertFalse(recentlyUpdated(null, 3, now))
        assertFalse(recentlyUpdated("not a date", 3, now))
    }

    @Test fun conflictMessagesNameWhatClashes() {
        assertEquals("Mon 09:30–10:30: the faculty member already teaches BSc · B · CS301.",
            clashMessage(TimetableClash("FACULTY", 1, "09:30:00", "10:30:00", "BSc · B · CS301")))
        assertEquals("Tue 09:00–10:00: the classroom is already used by BSc · C · MA101.",
            clashMessage(TimetableClash("CLASSROOM", 2, "09:00", "10:00", "BSc · C · MA101")))
        assertEquals("Wed 13:00–13:45: this class already has Lunch.",
            clashMessage(TimetableClash("DIVISION", 3, "13:00", "13:45", "Lunch")))
    }

    @Test fun entryFormIsCheckedBeforeSending() {
        val ok = entryInput(EntryForm("LESSON", 1, "09:00", "10:00", 5, 8, " Lab ")).getOrThrow()
        assertEquals(TimetableEntryInput("LESSON", 1, "09:00", "10:00", 5, 8, "Lab"), ok)
        val lunch = entryInput(EntryForm("LUNCH", 1, "13:00", "13:45", assignmentId = 5, classroomId = 8)).getOrThrow()
        assertNull(lunch.assignmentId)   // a break never sends lesson details
        assertNull(lunch.classroomId)
        fun problem(form: EntryForm) = entryInput(form).exceptionOrNull()?.message
        assertEquals("Use HH:MM for times, for example 09:30.", problem(EntryForm(start = "9am", end = "10:00", assignmentId = 1, classroomId = 1)))
        assertEquals("The end time must be after the start time.", problem(EntryForm(start = "10:00", end = "09:00", assignmentId = 1, classroomId = 1)))
        assertEquals("Use 5-minute steps, for example 09:00 or 09:05.", problem(EntryForm(start = "09:02", end = "10:00", assignmentId = 1, classroomId = 1)))
        assertEquals("A lesson can be at most 4 hours.", problem(EntryForm(start = "08:00", end = "12:05", assignmentId = 1, classroomId = 1)))
        assertEquals("Choose the subject and faculty member.", problem(EntryForm(start = "09:00", end = "10:00", classroomId = 1)))
        assertEquals("Choose a classroom.", problem(EntryForm(start = "09:00", end = "10:00", assignmentId = 1)))
        assertEquals("A break can be at most 2 hours.", problem(EntryForm("BREAK", 1, "09:00", "11:05")))
    }

    @Test fun newPeriodsStartWhereTheDayEnds() {
        val entries = listOf(TimetableEntry(1, "LESSON", 2, "09:00:00", "10:00:00", 5, "CS101", "Programming", 3, "Prof A", 8, "A101", "Room", null))
        assertEquals(EntryForm("LESSON", 2, "10:00", "11:00"), suggestedForm(entries, 2, "LESSON"))
        assertEquals(EntryForm("BREAK", 3, "09:00", "09:15"), suggestedForm(entries, 3, "BREAK"))
    }

    @Test fun academicScopeIsChosenLevelByLevel() {
        val scopes = listOf(
            TimetableScope(21, 31, 1, "2026-2027", 2, "Science", 3, "BSc", 1, "Semester 1", "A"),
            TimetableScope(21, 32, 1, "2026-2027", 2, "Science", 3, "BSc", 1, "Semester 1", "B"),
            TimetableScope(22, 31, 1, "2026-2027", 2, "Science", 3, "BSc", 2, "Semester 2", "A"),
            TimetableScope(23, 33, 1, "2026-2027", 4, "Commerce", 5, "BCom", 1, "Semester 1", "A"))
        var choice = chooseScope(ScopeChoice(), 0, 1)
        assertEquals(listOf(2L to "Science", 4L to "Commerce"), scopeLists(scopes, choice).departments)
        choice = chooseScope(chooseScope(choice, 1, 2), 2, 3)
        assertEquals(listOf(21L to "Semester 1", 22L to "Semester 2"), scopeLists(scopes, choice).semesters)
        choice = chooseScope(choice, 3, 21)
        assertEquals(listOf(31L to "A", 32L to "B"), scopeLists(scopes, choice).divisions)
        choice = chooseScope(choice, 4, 32)
        assertTrue(choice.complete)
        // Changing the department clears everything below it.
        assertEquals(ScopeChoice(yearId = 1, departmentId = 4), chooseScope(choice, 1, 4))
    }

    @Test fun classroomFormIsValidated() {
        assertEquals(TimetableClassroomInput("A-101", "Main hall", 60, true), roomInput(RoomForm(" a-101 ", " Main hall ", "60")).getOrThrow())
        assertNull(roomInput(RoomForm("B2", "Lab", "")).getOrThrow().capacity)
        assertTrue(roomInput(RoomForm("A 101", "Hall")).isFailure)
        assertTrue(roomInput(RoomForm("A101", " ")).isFailure)
        assertTrue(roomInput(RoomForm("A101", "Hall", "3000")).isFailure)
    }
}
