package com.campussphere.backend.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.Announcements.Filter;
import com.campussphere.backend.service.Announcements.Input;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnnouncementRulesTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final Announcements service = new Announcements(db, new MaterialFiles(System.getProperty("java.io.tmpdir")));

    private static Account account(String role) {
        return new Account(7L, true, false, Set.of(role), new CollegeScope(3L, true));
    }

    private static Input input(String audience, String scope, Long target) {
        return new Input(" Exam timetable ", " Read carefully. ", "EXAMINATION", "HIGH", audience, scope, target, null);
    }

    private static void assertError(ApiError expected, Runnable action) {
        var failure = assertThrows(ApiError.Failure.class, action::run);
        assertEquals(expected, failure.error());
    }

    @Test void inputIsTrimmedAndEveryFieldIsValidated() {
        var clean = input("EVERYONE", "COLLEGE", null).clean();
        assertEquals("Exam timetable", clean.title());
        assertEquals("Read carefully.", clean.body());
        assertError(ApiError.INVALID_REQUEST, () -> new Input(" ", "b", "GENERAL", "LOW", "EVERYONE", "COLLEGE", null, null).clean());
        assertError(ApiError.INVALID_REQUEST, () -> new Input("t", "b", "PARTY", "LOW", "EVERYONE", "COLLEGE", null, null).clean());
        assertError(ApiError.INVALID_REQUEST, () -> new Input("t", "b", "GENERAL", "CRITICAL", "EVERYONE", "COLLEGE", null, null).clean());
        assertError(ApiError.INVALID_REQUEST, () -> new Input("t", "b", "GENERAL", "LOW", "PARENTS", "COLLEGE", null, null).clean());
        assertError(ApiError.INVALID_REQUEST, () -> new Input("t", "b", "GENERAL", "LOW", "EVERYONE", "CAMPUS", null, null).clean());
        assertError(ApiError.INVALID_REQUEST, () -> new Input("t".repeat(201), "b", "GENERAL", "LOW", "EVERYONE", "COLLEGE", null, null).clean());
        // A college-wide announcement has no target; every other scope needs a positive target id.
        assertError(ApiError.INVALID_REQUEST, () -> input("EVERYONE", "COLLEGE", 5L).clean());
        assertError(ApiError.INVALID_REQUEST, () -> input("EVERYONE", "DIVISION", null).clean());
        assertError(ApiError.INVALID_REQUEST, () -> input("EVERYONE", "DIVISION", -1L).clean());
    }

    @Test void filtersRejectUnknownValues() {
        assertError(ApiError.INVALID_REQUEST, () -> new Filter("PARTY", null, null, null, null, 0));
        assertError(ApiError.INVALID_REQUEST, () -> new Filter(null, "CRITICAL", null, null, null, 0));
        assertError(ApiError.INVALID_REQUEST, () -> new Filter(null, null, "DELETED", null, null, 0));
        assertError(ApiError.INVALID_REQUEST, () -> new Filter(null, null, null, null, null, -1));
        assertNull(new Filter(null, null, null, "   ", null, 0).q());
    }

    @Test void expiryMustBeInTheFuture() {
        assertError(ApiError.ANNOUNCEMENT_SCHEDULE_INVALID,
                () -> Announcements.requireFutureExpiry(OffsetDateTime.now().minusMinutes(1)));
        assertDoesNotThrow(() -> Announcements.requireFutureExpiry(OffsetDateTime.now().plusDays(1)));
        assertDoesNotThrow(() -> Announcements.requireFutureExpiry(null));
    }

    @Test void permissionsFollowRoleOwnershipAndStatus() {
        // edit, publish, archive, delete
        assertArrayEquals(new boolean[]{true, true, true, true}, Announcements.permissions(CollegeRole.COLLEGE_ADMIN, false, "DRAFT"));
        assertArrayEquals(new boolean[]{true, false, true, false}, Announcements.permissions(CollegeRole.COLLEGE_ADMIN, false, "PUBLISHED"));
        assertArrayEquals(new boolean[]{true, true, true, false}, Announcements.permissions(CollegeRole.COLLEGE_ADMIN, false, "SCHEDULED"));
        assertArrayEquals(new boolean[]{false, false, false, false}, Announcements.permissions(CollegeRole.COLLEGE_ADMIN, false, "ARCHIVED"));
        assertArrayEquals(new boolean[]{true, true, true, true}, Announcements.permissions(CollegeRole.FACULTY, true, "DRAFT"));
        assertArrayEquals(new boolean[]{false, false, false, false}, Announcements.permissions(CollegeRole.FACULTY, false, "DRAFT"));
        assertArrayEquals(new boolean[]{false, false, false, false}, Announcements.permissions(CollegeRole.STUDENT, false, "PUBLISHED"));
    }

    @Test void studentsCannotWriteAndNothingReachesTheDatabase() {
        var student = account("STUDENT");
        assertError(ApiError.ACCESS_DENIED, () -> service.create(student, CollegeRole.STUDENT, input("STUDENTS", "COLLEGE", null)));
        assertError(ApiError.ACCESS_DENIED, () -> service.publish(student, CollegeRole.STUDENT, 1L, null));
        assertError(ApiError.ACCESS_DENIED, () -> service.archive(student, CollegeRole.STUDENT, 1L));
        assertError(ApiError.ACCESS_DENIED, () -> service.delete(student, CollegeRole.STUDENT, 1L));
        verifyNoInteractions(db);
    }

    @Test void accountsWithoutTheRoleOrCollegeAreRejected() {
        var platform = new Account(1L, true, false, Set.of("SUPER_ADMIN"), null);
        assertError(ApiError.ACCESS_DENIED, () -> service.create(platform, CollegeRole.COLLEGE_ADMIN, input("EVERYONE", "COLLEGE", null)));
        var inactive = new Account(7L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(3L, false));
        assertError(ApiError.ACCESS_DENIED, () -> service.list(inactive, CollegeRole.COLLEGE_ADMIN, new Filter(null, null, null, null, null, 0)));
        verifyNoInteractions(db);
    }

    @Test void facultyMayOnlyAddressStudentsOfADivisionTheyTeach() {
        var faculty = account("FACULTY");
        assertError(ApiError.ANNOUNCEMENT_TARGET_INVALID,
                () -> service.resolve(faculty, CollegeRole.FACULTY, 3L, input("STUDENTS", "COLLEGE", null).clean()));
        assertError(ApiError.ANNOUNCEMENT_TARGET_INVALID,
                () -> service.resolve(faculty, CollegeRole.FACULTY, 3L, input("EVERYONE", "DIVISION", 9L).clean()));
        assertError(ApiError.ANNOUNCEMENT_TARGET_INVALID,
                () -> service.resolve(faculty, CollegeRole.FACULTY, 3L, input("STUDENTS", "DEPARTMENT", 2L).clean()));
        verifyNoInteractions(db);

        // Not an active assignment of this faculty member: no row, so rejected.
        when(db.query(contains("faculty_subject_assignments"), any(RowMapper.class), eq(3L), eq(7L), eq(9L))).thenReturn(List.of());
        assertError(ApiError.ANNOUNCEMENT_TARGET_INVALID,
                () -> service.resolve(faculty, CollegeRole.FACULTY, 3L, input("STUDENTS", "DIVISION", 9L).clean()));
    }

    @Test void adminTargetsAreLookedUpInsideTheAdminsOwnCollege() {
        var admin = account("COLLEGE_ADMIN");
        when(db.query(contains("FROM public.academic_divisions"), any(RowMapper.class), eq(3L), eq(9L))).thenReturn(List.of());
        assertError(ApiError.ANNOUNCEMENT_TARGET_INVALID,
                () -> service.resolve(admin, CollegeRole.COLLEGE_ADMIN, 3L, input("EVERYONE", "DIVISION", 9L).clean()));
        verify(db).query(contains("WHERE v.college_id = ? AND v.id = ?"), any(RowMapper.class), eq(3L), eq(9L));
        assertEquals("COLLEGE", service.resolve(admin, CollegeRole.COLLEGE_ADMIN, 3L, input("EVERYONE", "COLLEGE", null).clean()).scope());
    }

    @Test void audienceSqlOnlyShowsLivePublishedAnnouncementsToReaders() {
        assertTrue(Announcements.LIVE.contains("a.status = 'PUBLISHED'"));
        assertTrue(Announcements.LIVE.contains("a.publish_at <= statement_timestamp()"));
        assertTrue(Announcements.LIVE.contains("a.expires_at > statement_timestamp()"));
        assertTrue(Announcements.EFFECTIVE.contains("THEN 'SCHEDULED'"));
        assertTrue(Announcements.EFFECTIVE.contains("THEN 'EXPIRED'"));
        // Students never match faculty-only announcements and vice versa; both are tied to the announcement's college.
        assertTrue(Announcements.studentMatch("?").contains("a.audience IN ('EVERYONE', 'STUDENTS')"));
        assertTrue(Announcements.facultyMatch("?").contains("a.audience IN ('EVERYONE', 'FACULTY')"));
        assertTrue(Announcements.studentMatch("?").contains("e.college_id = a.college_id"));
        assertTrue(Announcements.studentMatch("?").contains("e.ended_at IS NULL"));
        assertEquals(2, Announcements.studentMatch("?").chars().filter(c -> c == '?').count());
        assertEquals(2, Announcements.facultyMatch("?").chars().filter(c -> c == '?').count());
    }
}
