package com.campussphere.backend.service;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.StudentEnrollmentService.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentEnrollmentServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StudentEnrollmentService service = new StudentEnrollmentService(jdbc);
    private final Account admin = new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(10L, true));
    private final EnrollmentInput input = new EnrollmentInput(2, 3, 4, 5, 6);
    private final Enrollment row = new Enrollment(99, "Alpha", "Science", "Biology", "2026", "First semester", "Division A", true);

    @Test void dashboardUsesOnlyPrincipalCollegeAndStudentIdAndRequiresVerification() {
        var student = new Account(12L, true, false, Set.of("STUDENT"), new CollegeScope(10L, true));
        var unverified = new Account(12L, false, false, Set.of("STUDENT"), new CollegeScope(10L, true));
        var inactive = new Account(12L, true, false, Set.of("STUDENT"), new CollegeScope(10L, false));
        var unbound = new Account(12L, true, false, Set.of("STUDENT"), null);
        assertError(ApiError.ACCESS_DENIED, () -> service.current(admin));
        assertError(ApiError.ACCESS_DENIED, () -> service.current(inactive));
        assertError(ApiError.ACCESS_DENIED, () -> service.current(unbound));
        assertError(ApiError.VERIFICATION_REQUIRED, () -> service.current(unverified));
        verifyNoInteractions(jdbc);
        allowStudent(2L);
        when(jdbc.query(contains("e.college_id = ? AND e.student_id = ?"), enrollmentMapper(),
                eq(10L), eq(12L))).thenReturn(List.of(row));
        assertEquals(List.of(row), service.current(student));
        verify(jdbc).query(contains("AND e.ended_at IS NULL"), enrollmentMapper(), eq(10L), eq(12L));
    }

    @Test void directoryAndProfileAreScopedAndRequireAdmissionVerification() {
        var member = new Member(12, "Student Example", "student@example.invalid", null, null);
        when(jdbc.query(contains("u.college_id = ? AND u.is_active IS TRUE AND u.is_verified IS TRUE"),
                memberMapper(), eq(10L))).thenReturn(List.of(member));
        assertEquals(List.of(member), service.list(admin));
        assertError(ApiError.STUDENT_NOT_FOUND, () -> service.profile(admin, 30));
        verify(jdbc).query(contains("r.name <> 'STUDENT'"), memberMapper(), eq(10L), eq(30L));
    }

    @Test void foreignStudentAndMismatchedDepartmentOrAcademicContextCannotBeEnrolled() {
        assertError(ApiError.STUDENT_NOT_FOUND, () -> service.enroll(admin, 30, input));
        allowStudent(20L);
        assertError(ApiError.STUDENT_ENROLLMENT_INVALID, () -> service.enroll(admin, 12, input));
        verify(jdbc, never()).queryForObject(contains("academic_programs"), eq(Boolean.class),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        allowStudent(2L);
        assertError(ApiError.STUDENT_ENROLLMENT_INVALID, () -> service.enroll(admin, 12, input));
        verify(jdbc).queryForObject(contains("v.academic_year_id = s.academic_year_id"), eq(Boolean.class),
                eq(10L), eq(2L), eq(3L), eq(4L), eq(5L), eq(6L));
        verify(jdbc, never()).query(contains("INSERT"), idMapper(),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test void onlyOneCurrentEnrollmentEvenWhenSecondEnrollmentUsesAnotherDivision() {
        allowStudent(2L);
        when(jdbc.queryForObject(contains("academic_programs"), eq(Boolean.class),
                eq(10L), eq(2L), eq(3L), eq(4L), eq(5L), anyLong())).thenReturn(true);
        when(jdbc.query(contains("ON CONFLICT (student_id) WHERE ended_at IS NULL"), idMapper(),
                eq(10L), eq(12L), eq(2L), eq(3L), eq(4L), eq(5L), eq(6L))).thenReturn(List.of(99L));
        when(jdbc.query(contains("AND e.id = ?"), enrollmentMapper(), eq(10L), eq(12L), eq(99L)))
                .thenReturn(List.of(row));
        assertEquals(row, service.enroll(admin, 12, input));
        assertError(ApiError.STUDENT_ENROLLMENT_CONFLICT,
                () -> service.enroll(admin, 12, new EnrollmentInput(2, 3, 4, 5, 8)));
        verify(jdbc, times(2)).queryForList(contains("FOR UPDATE"), eq(Long.class), eq(10L), eq(12L));
    }

    @Test void departmentChangesCheckTenantAndRequireEndingEnrollment() {
        allowStudent(null);
        assertError(ApiError.DEPARTMENT_NOT_FOUND, () -> service.changeDepartment(admin, 12, 2));
        when(jdbc.queryForObject(contains("FROM public.departments"), eq(Boolean.class), eq(10L), eq(2L)))
                .thenReturn(true);
        when(jdbc.queryForObject(contains("ended_at IS NULL"), eq(Boolean.class), eq(10L), eq(12L)))
                .thenReturn(true, false);
        assertError(ApiError.STUDENT_DEPARTMENT_CONFLICT, () -> service.changeDepartment(admin, 12, 2));
        verify(jdbc, never()).update(contains("INSERT"), anyLong(), anyLong(), anyLong());
        service.changeDepartment(admin, 12, 2);
        verify(jdbc).update(contains("INSERT INTO public.student_profiles"), eq(10L), eq(12L), eq(2L));
    }

    @Test void endUsesScopedUpdateAndHistoryRemainsVisibleToCollegeAdmin() {
        allowStudent(2L);
        when(jdbc.update(contains("ended_at IS NULL"), eq(10L), eq(12L), eq(99L))).thenReturn(1);
        service.end(admin, 12, 99);
        assertError(ApiError.STUDENT_ENROLLMENT_NOT_FOUND, () -> service.end(admin, 12, 101));
        verify(jdbc).update(contains("SET ended_at"), eq(10L), eq(12L), eq(99L));
        var past = new Enrollment(99, "Alpha", "Science", "Biology", "2026", "First semester", "Division A", false);
        when(jdbc.query(contains("ORDER BY e.id DESC"), enrollmentMapper(), eq(10L), eq(12L)))
                .thenReturn(List.of(past));
        assertEquals(List.of(past), service.history(admin, 12));
    }

    private void allowStudent(Long department) {
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(Long.class), eq(10L), eq(12L))).thenReturn(List.of(12L));
        when(jdbc.query(contains("AND u.id = ?"), memberMapper(), eq(10L), eq(12L)))
                .thenReturn(List.of(new Member(12, "Student Example", "student@example.invalid", department, null)));
    }

    private static RowMapper<Member> memberMapper() { return any(); }
    private static RowMapper<Enrollment> enrollmentMapper() { return any(); }
    private static RowMapper<Long> idMapper() { return any(); }
    private static void assertError(ApiError expected, Runnable action) {
        assertEquals(expected, assertThrows(ApiError.Failure.class, action::run).error());
    }
}
