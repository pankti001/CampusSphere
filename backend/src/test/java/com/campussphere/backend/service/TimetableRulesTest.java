package com.campussphere.backend.service;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.Timetables.*;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TimetableRulesTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final FacultyNotifications notifications = mock(FacultyNotifications.class);
    private final Timetables service = new Timetables(db, notifications);
    private static final LocalTime NINE = LocalTime.of(9, 0), TEN = LocalTime.of(10, 0), ELEVEN = LocalTime.of(11, 0);

    private static Account account(String role) {
        return new Account(7L, true, false, Set.of(role), new CollegeScope(3L, true));
    }

    private static EntryInput lesson(int day, LocalTime start, LocalTime end) {
        return new EntryInput("LESSON", day, start, end, 51L, 81L, null);
    }

    private static void assertError(ApiError expected, Runnable action) {
        var failure = assertThrows(ApiError.Failure.class, action::run);
        assertEquals(expected, failure.error());
    }

    // ---- Input validation ----

    @Test void entriesNeedValidKindDayAndFiveMinuteTimes() {
        var clean = new EntryInput(" lesson ", 1, NINE, TEN, 51L, 81L, "  Lab ").clean();
        assertEquals("LESSON", clean.kind());
        assertEquals("Lab", clean.label());
        assertNull(new EntryInput("BREAK", 2, LocalTime.of(11, 0), LocalTime.of(11, 15), null, null, " ").clean().label());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> new EntryInput("PARTY", 1, NINE, TEN, 51L, 81L, null).clean());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> lesson(0, NINE, TEN).clean());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> lesson(8, NINE, TEN).clean());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> lesson(1, null, TEN).clean());
    }

    @Test void invalidTimeRangesAreRejected() {
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> lesson(1, TEN, NINE).clean());       // ends before it starts
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> lesson(1, NINE, NINE).clean());      // zero length
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> lesson(1, LocalTime.of(9, 2), TEN).clean()); // not a 5-minute step
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> lesson(1, LocalTime.of(9, 0, 30), TEN).clean());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> lesson(1, NINE, LocalTime.of(13, 5)).clean()); // over 4 hours
        assertError(ApiError.TIMETABLE_ENTRY_INVALID,
                () -> new EntryInput("LUNCH", 1, LocalTime.of(12, 0), LocalTime.of(14, 5), null, null, null).clean()); // break over 2 hours
        assertDoesNotThrow(() -> lesson(1, NINE, LocalTime.of(13, 0)).clean());
    }

    @Test void lessonsNeedAssignmentAndClassroomButBreaksMustNotHaveThem() {
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> new EntryInput("LESSON", 1, NINE, TEN, null, 81L, null).clean());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> new EntryInput("LESSON", 1, NINE, TEN, 51L, null, null).clean());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> new EntryInput("LESSON", 1, NINE, TEN, -5L, 81L, null).clean());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> new EntryInput("BREAK", 1, NINE, TEN, 51L, null, null).clean());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> new EntryInput("LUNCH", 1, NINE, TEN, null, 81L, null).clean());
        assertError(ApiError.TIMETABLE_ENTRY_INVALID, () -> new EntryInput("BREAK", 1, NINE, TEN, null, null, "x".repeat(61)).clean());
    }

    @Test void requestRecordsValidateIdsStatusesAndClassrooms() {
        assertError(ApiError.TIMETABLE_SCOPE_INVALID, () -> new Create(null, 3L));
        assertError(ApiError.TIMETABLE_SCOPE_INVALID, () -> new Create(2L, 0L));
        assertError(ApiError.INVALID_REQUEST, () -> new Publish(null));
        assertError(ApiError.INVALID_REQUEST, () -> new Filter(null, null, null, null, null, "DELETED", 0));
        assertError(ApiError.INVALID_REQUEST, () -> new Filter(-1L, null, null, null, null, null, 0));
        assertError(ApiError.INVALID_REQUEST, () -> new Filter(null, null, null, null, null, null, -1));
        assertEquals("PUBLISHED", new Filter(null, null, null, null, null, " published ", 0).status());
        var room = new ClassroomInput(" a-101 ", " Main hall ", 60, null).clean();
        assertEquals("A-101", room.code());
        assertEquals("Main hall", room.name());
        assertTrue(room.active());
        assertError(ApiError.CLASSROOM_INVALID, () -> new ClassroomInput("A 101", "Hall", null, true).clean());
        assertError(ApiError.CLASSROOM_INVALID, () -> new ClassroomInput("A101", " ", null, true).clean());
        assertError(ApiError.CLASSROOM_INVALID, () -> new ClassroomInput("A101", "Hall", 0, true).clean());
        assertError(ApiError.CLASSROOM_INVALID, () -> new ClassroomInput("A101", "Hall", 2001, true).clean());
    }

    // ---- Conflict detection ----

    private static Booked other(Long faculty, Long room, LocalTime start, LocalTime end) {
        return new Booked(500, false, "LESSON", 1, start, end, faculty, room, "BSc · B · CS301");
    }

    @Test void facultyCannotTeachTwoClassesAtOverlappingTimes() {
        var found = Timetables.clashes(new Candidate(1, NINE, TEN, 61L, 81L),
                List.of(other(61L, 99L, LocalTime.of(9, 30), LocalTime.of(10, 30))));
        assertEquals(List.of(new Clash("FACULTY", 1, LocalTime.of(9, 30), LocalTime.of(10, 30), "BSc · B · CS301")), found);
    }

    @Test void classroomCannotHostTwoClassesAtOnce() {
        var found = Timetables.clashes(new Candidate(1, NINE, TEN, 61L, 81L), List.of(other(62L, 81L, NINE, TEN)));
        assertEquals("CLASSROOM", found.getFirst().type());
        assertEquals(2, Timetables.clashes(new Candidate(1, NINE, TEN, 61L, 81L), List.of(other(61L, 81L, NINE, TEN))).size());
    }

    @Test void divisionCannotHaveTwoPeriodsOrALessonInsideABreak() {
        var subject = new Booked(400, true, "LESSON", 1, LocalTime.of(9, 55), ELEVEN, 62L, 82L, "BSc · A · CS101");
        var lunch = new Booked(401, true, "LUNCH", 1, LocalTime.of(9, 30), LocalTime.of(9, 45), null, null, "Lunch");
        var found = Timetables.clashes(new Candidate(1, NINE, TEN, 61L, 81L), List.of(subject, lunch));
        assertEquals(List.of("DIVISION", "DIVISION"), found.stream().map(Clash::type).toList());
        // A break of this division also refuses to overlap a lesson.
        assertEquals("DIVISION", Timetables.clashes(new Candidate(1, NINE, TEN, null, null), List.of(subject)).getFirst().type());
    }

    @Test void touchingPeriodsOtherDaysAndUnrelatedClassesDoNotClash() {
        var candidate = new Candidate(1, NINE, TEN, 61L, 81L);
        assertTrue(Timetables.clashes(candidate, List.of(other(61L, 81L, TEN, ELEVEN))).isEmpty());          // starts when it ends
        assertTrue(Timetables.clashes(candidate, List.of(other(61L, 81L, LocalTime.of(8, 0), NINE))).isEmpty());
        assertTrue(Timetables.clashes(candidate, List.of(new Booked(1, false, "LESSON", 2, NINE, TEN, 61L, 81L, "x"))).isEmpty());
        assertTrue(Timetables.clashes(candidate, List.of(other(62L, 82L, NINE, TEN))).isEmpty());
        assertTrue(Timetables.clashes(candidate, List.of(new Booked(1, false, "BREAK", 1, NINE, TEN, null, null, "Break"))).isEmpty());
        assertTrue(Timetables.overlaps(NINE, TEN, LocalTime.of(9, 59), ELEVEN));
        assertFalse(Timetables.overlaps(NINE, TEN, TEN, ELEVEN));
    }

    @Test void bookedQueryOnlyComparesLiveVersionsOfOtherDivisionsAndActiveAssignments() {
        assertTrue(Timetables.BOOKED.contains("e.start_time<? AND e.end_time>?"));
        assertTrue(Timetables.BOOKED.contains("t.status IN ('DRAFT','PUBLISHED')"));
        assertTrue(Timetables.BOOKED.contains("t.division_id<>?"));
        assertTrue(Timetables.BOOKED.contains("a.ended_at IS NULL"));
        assertTrue(Timetables.BOOKED.contains("e.college_id=?"));
        assertEquals(10, Timetables.BOOKED.chars().filter(c -> c == '?').count());
    }

    // ---- Roles and tenant isolation ----

    @Test void studentsFacultyAndPlatformAdminsCannotWrite() {
        for (var role : List.of("STUDENT", "FACULTY", "SUPER_ADMIN")) {
            var actor = account(role);
            assertError(ApiError.ACCESS_DENIED, () -> service.create(actor, new Create(2L, 3L)));
            assertError(ApiError.ACCESS_DENIED, () -> service.addEntry(actor, 1L, lesson(1, NINE, TEN)));
            assertError(ApiError.ACCESS_DENIED, () -> service.deleteEntry(actor, 1L, 2L));
            assertError(ApiError.ACCESS_DENIED, () -> service.publish(actor, 1L, new Publish(0L)));
            assertError(ApiError.ACCESS_DENIED, () -> service.archive(actor, 1L));
            assertError(ApiError.ACCESS_DENIED, () -> service.createClassroom(actor, new ClassroomInput("A1", "Hall", null, true)));
        }
        var noCollege = new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), null);
        assertError(ApiError.ACCESS_DENIED, () -> service.list(noCollege, new Filter(null, null, null, null, null, null, 0)));
        var inactive = new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(3L, false));
        assertError(ApiError.ACCESS_DENIED, () -> service.scopes(inactive));
        verifyNoInteractions(db);
    }

    /** Sets up an admin in college 3 editing draft 11 (semester 21, division 31, revision 4). */
    private void draft(String status) {
        when(db.queryForList(contains("FROM public.colleges"), eq(Long.class), eq(3L))).thenReturn(List.of(3L));
        when(db.query(contains("SELECT id, version, revision"), any(RowMapper.class), eq(3L), eq(11L)))
                .thenReturn(List.<Object[]>of(new Object[]{new Locked(11, 1, 4, 21, 31), status}));
    }

    @Test void facultyConflictIsRejectedAfterTheCollegeLockAndNothingIsSaved() {
        draft("DRAFT");
        when(db.query(contains("FOR SHARE OF a"), any(RowMapper.class), eq(3L), eq(51L), eq(21L), eq(31L)))
                .thenReturn(List.<Object[]>of(new Object[]{61L, 71L, "CS201", "Data Structures", "Prof A"}));
        when(db.queryForList(contains("FROM public.classrooms"), eq(String.class), eq(3L), eq(81L))).thenReturn(List.of("A101"));
        when(db.query(eq(Timetables.BOOKED), any(RowMapper.class), eq(11L), eq(3L), eq(1), eq(TEN), eq(NINE), eq(-1L),
                eq(11L), eq(31L), eq(61L), eq(81L)))
                .thenReturn(List.of(other(61L, 99L, LocalTime.of(9, 30), LocalTime.of(10, 30))));

        var rejected = assertThrows(Rejected.class, () -> service.addEntry(account("COLLEGE_ADMIN"), 11L, lesson(1, NINE, TEN)));
        assertEquals(ApiError.TIMETABLE_CONFLICT, rejected.error());
        assertEquals("FACULTY", rejected.clashes().getFirst().type());

        InOrder order = inOrder(db);
        order.verify(db).query(contains("pg_advisory_xact_lock(671540"), any(ResultSetExtractor.class), eq(3L));
        order.verify(db).query(contains("SELECT id, version, revision"), any(RowMapper.class), eq(3L), eq(11L));
        order.verify(db).query(eq(Timetables.BOOKED), any(RowMapper.class), any(Object[].class));
        verify(db, never()).queryForObject(contains("INSERT"), eq(Long.class), any(Object[].class));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void assignmentMustBelongToThisDivisionAndSemesterAndClassroomToThisCollege() {
        draft("DRAFT");
        when(db.query(contains("FOR SHARE OF a"), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        assertError(ApiError.TIMETABLE_ASSIGNMENT_INVALID, () -> service.addEntry(account("COLLEGE_ADMIN"), 11L, lesson(1, NINE, TEN)));
        verify(db).query(contains("a.semester_id=? AND a.division_id=? AND a.ended_at IS NULL"), any(RowMapper.class),
                eq(3L), eq(51L), eq(21L), eq(31L));

        when(db.query(contains("FOR SHARE OF a"), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.<Object[]>of(new Object[]{61L, 71L, "CS201", "Data Structures", "Prof A"}));
        when(db.queryForList(contains("FROM public.classrooms"), eq(String.class), any(Object[].class))).thenReturn(List.of());
        assertError(ApiError.TIMETABLE_CLASSROOM_INVALID, () -> service.addEntry(account("COLLEGE_ADMIN"), 11L, lesson(1, NINE, TEN)));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void publishedTimetablesAreNotEditedAndStalePublishIsRefused() {
        draft("PUBLISHED");
        assertError(ApiError.TIMETABLE_STATE_CONFLICT, () -> service.addEntry(account("COLLEGE_ADMIN"), 11L, lesson(1, NINE, TEN)));
        assertError(ApiError.TIMETABLE_STATE_CONFLICT, () -> service.deleteEntry(account("COLLEGE_ADMIN"), 11L, 5L));

        draft("DRAFT");
        assertError(ApiError.TIMETABLE_STATE_CONFLICT, () -> service.publish(account("COLLEGE_ADMIN"), 11L, new Publish(3L)));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void missingTimetableInTheAdminsCollegeIsNotFound() {
        when(db.queryForList(contains("FROM public.colleges"), eq(Long.class), eq(3L))).thenReturn(List.of(3L));
        when(db.query(contains("SELECT id, version, revision"), any(RowMapper.class), eq(3L), eq(99L))).thenReturn(List.of());
        assertError(ApiError.TIMETABLE_NOT_FOUND, () -> service.addEntry(account("COLLEGE_ADMIN"), 99L, lesson(1, NINE, TEN)));
    }

    @Test void studentTimetableComesFromTheirOwnCurrentEnrollment() {
        when(db.query(contains("FROM public.student_enrollments en"), any(RowMapper.class), eq(3L), eq(7L))).thenReturn(List.of());
        var week = service.studentWeek(account("STUDENT"));
        assertFalse(week.enrolled());
        assertTrue(week.slots().isEmpty());
        verify(db).query(contains("en.ended_at IS NULL"), any(RowMapper.class), eq(3L), eq(7L));
        // Another college's student id is never used: the query takes the signed-in account's own id and college.
        assertError(ApiError.ACCESS_DENIED, () -> service.studentWeek(account("FACULTY")));
    }

    @Test void facultySeeOnlyTheirOwnLessonsInPublishedTimetables() {
        when(db.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        service.facultyDay(account("FACULTY"), java.time.LocalDate.of(2026, 11, 30)); // a Monday
        verify(db).query(argThat((String sql) -> sql.contains("t.status='PUBLISHED'") && sql.contains("a.ended_at IS NULL")
                && sql.contains("e.faculty_id=?") && sql.contains("e.day_of_week=?")), any(RowMapper.class), eq(3L), eq(7L), eq(1));
        assertError(ApiError.ACCESS_DENIED, () -> service.facultyWeek(account("STUDENT")));
    }
}
