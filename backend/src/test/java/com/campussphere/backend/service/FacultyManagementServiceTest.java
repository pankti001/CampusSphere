package com.campussphere.backend.service;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.FacultyManagementService.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FacultyManagementServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FacultyManagementService service = new FacultyManagementService(jdbc);
    private final Account admin = new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(10L, true));
    private final AssignmentInput input = new AssignmentInput(2, 3, 4, 5, 6, 7);
    private final Assignment row = new Assignment(99, "Science", "Biology", "2026–2027", "First semester",
            "Division A", "Biology Lab", "BIO");

    @Test void dashboardUsesOnlyPrincipalCollegeAndFacultyId() {
        var member = new Account(12L, true, false, Set.of("FACULTY"), new CollegeScope(10L, true));
        when(jdbc.query(contains("a.college_id = ? AND a.faculty_id = ? AND a.ended_at IS NULL"),
                assignmentMapper(), eq(10L), eq(12L))).thenReturn(List.of(row));
        assertEquals(List.of(row), service.dashboard(member));
        assertError(ApiError.ACCESS_DENIED, () -> service.dashboard(admin));
        assertError(ApiError.ACCESS_DENIED, () -> service.dashboard(new Account(12L, true, false,
                Set.of("FACULTY"), null)));
        assertError(ApiError.ACCESS_DENIED, () -> service.dashboard(new Account(12L, true, false,
                Set.of("FACULTY"), new CollegeScope(10L, false))));
        verify(jdbc).query(contains("a.ended_at IS NULL"), assignmentMapper(), eq(10L), eq(12L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void directoryAndProfileAreScopedToEligibleFaculty() {
        var member = new Member(12, "Faculty Example", "faculty@example.invalid", null, null);
        when(jdbc.query(contains("u.college_id = ? AND u.is_active IS TRUE AND u.is_verified IS TRUE"),
                memberMapper(), eq(10L))).thenReturn(List.of(member));
        assertEquals(List.of(member), service.list(admin));
        assertError(ApiError.FACULTY_NOT_FOUND, () -> service.profile(admin, 31));
        verify(jdbc).query(contains("r.name = 'FACULTY'"), memberMapper(), eq(10L), eq(31L));
    }

    @Test void rejectsCrossCollegeMemberAndInvalidHierarchyBeforeWrite() {
        assertError(ApiError.FACULTY_NOT_FOUND, () -> service.assign(admin, 30, input));
        allowMember(8L);
        assertError(ApiError.FACULTY_ASSIGNMENT_INVALID, () -> service.assign(admin, 12, input));
        verify(jdbc, never()).queryForObject(contains("academic_programs"), eq(Boolean.class),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
        allowMember(2L);
        assertError(ApiError.FACULTY_ASSIGNMENT_INVALID, () -> service.assign(admin, 12, input));
        verify(jdbc).queryForObject(contains("v.academic_year_id = s.academic_year_id"), eq(Boolean.class),
                eq(10L), eq(2L), eq(3L), eq(4L), eq(5L), eq(6L), eq(7L));
        verify(jdbc, never()).query(contains("INSERT"), any(RowMapper.class),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test void scopesInsertAndReturnsConflictForDuplicateActiveRow() {
        allowMember(2L);
        when(jdbc.queryForObject(contains("FROM public.academic_programs"), eq(Boolean.class),
                eq(10L), eq(2L), eq(3L), eq(4L), eq(5L), eq(6L), eq(7L))).thenReturn(true);
        when(jdbc.query(contains("WHERE ended_at IS NULL"), idMapper(),
                eq(10L), eq(12L), eq(2L), eq(3L), eq(4L), eq(5L), eq(6L), eq(7L)))
                .thenReturn(List.of(99L), List.of());
        when(jdbc.query(contains("AND a.id = ?"), assignmentMapper(), eq(10L), eq(12L), eq(99L)))
                .thenReturn(List.of(row));
        assertEquals(row, service.assign(admin, 12, input));
        assertError(ApiError.FACULTY_ASSIGNMENT_CONFLICT, () -> service.assign(admin, 12, input));
    }

    @Test void checksDepartmentTenantAndBlocksMovingWithActiveAssignments() {
        allowMember(null);
        assertError(ApiError.DEPARTMENT_NOT_FOUND, () -> service.changeDepartment(admin, 12, 2));
        when(jdbc.queryForObject(contains("FROM public.departments"), eq(Boolean.class), eq(10L), eq(2L)))
                .thenReturn(true);
        when(jdbc.queryForObject(contains("ended_at IS NULL"), eq(Boolean.class), eq(10L), eq(12L)))
                .thenReturn(true, false);
        assertError(ApiError.FACULTY_DEPARTMENT_CONFLICT, () -> service.changeDepartment(admin, 12, 2));
        verify(jdbc, never()).update(contains("INSERT"), anyLong(), anyLong(), anyLong());
        service.changeDepartment(admin, 12, 2);
        verify(jdbc).update(contains("INSERT INTO public.faculty_profiles"), eq(10L), eq(12L), eq(2L));
    }

    @Test void endRetainsHistoryAndNeverMutatesOtherFacultyRows() {
        allowMember(2L);
        when(jdbc.update(contains("ended_at IS NULL"), eq(10L), eq(12L), eq(99L))).thenReturn(1);
        service.end(admin, 12, 99);
        assertError(ApiError.FACULTY_ASSIGNMENT_NOT_FOUND, () -> service.end(admin, 12, 101));
        verify(jdbc).update(contains("SET ended_at"), eq(10L), eq(12L), eq(99L));
    }

    @Test void inactiveDepartmentTakesNoNewAssignments() {
        allowMember(2L);
        when(jdbc.queryForObject(contains("AND is_active"), eq(Boolean.class), eq(10L), eq(2L))).thenReturn(false);
        assertError(ApiError.DEPARTMENT_INACTIVE, () -> service.assign(admin, 12, input));
        verify(jdbc, never()).query(contains("INSERT"), any(RowMapper.class),
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    private void allowMember(Long department) {
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(Long.class), eq(10L), eq(12L)))
                .thenReturn(List.of(12L));
        when(jdbc.query(contains("AND u.id = ?"), memberMapper(), eq(10L), eq(12L)))
                .thenReturn(List.of(new Member(12, "Faculty Example", "faculty@example.invalid", department, null)));
        // The member's department exists and is active (V20), as in normal use.
        if (department != null) when(jdbc.queryForObject(contains("AND is_active"), eq(Boolean.class), eq(10L), eq(department)))
                .thenReturn(true);
    }

    private static RowMapper<Member> memberMapper() { return any(); }
    private static RowMapper<Assignment> assignmentMapper() { return any(); }
    private static RowMapper<Long> idMapper() { return any(); }

    private static void assertError(ApiError expected, Runnable action) {
        assertEquals(expected, assertThrows(ApiError.Failure.class, action::run).error());
    }
}
