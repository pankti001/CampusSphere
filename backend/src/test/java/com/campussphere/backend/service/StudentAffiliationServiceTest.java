package com.campussphere.backend.service;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.StudentAffiliationService.Affiliation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StudentAffiliationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StudentAffiliationService service = new StudentAffiliationService(jdbc);
    private final Account admin = new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(10L, true));
    private final Account pending = new Account(12L, false, true, Set.of(), null);
    private final Affiliation request = new Affiliation(99, "PENDING", "Alpha", "ALPHA", "Student Sample", "student@example.invalid");

    @Test void onlyUnboundStudentMayRequestAndCodeDoesNotVerifyOrBind() {
        assertError(ApiError.ACCESS_DENIED, () -> service.submit(admin, "ALPHA"));
        assertError(ApiError.ACCESS_DENIED, () -> service.submit(
                new Account(12L, true, false, Set.of("STUDENT"), new CollegeScope(11L, true)), "ALPHA"));
        when(jdbc.queryForList(contains("FOR UPDATE OF u"), eq(Long.class), eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForList(contains("AND is_active IS TRUE"), eq(Long.class), eq("ALPHA"))).thenReturn(List.of(10L));
        when(jdbc.query(contains("WHERE status = 'PENDING'"), idMapper(), eq(12L), eq(10L)))
                .thenReturn(List.of(99L), List.of());
        when(jdbc.query(contains("WHERE r.college_id = ? AND r.id = ?"), affiliationMapper(),
                eq(10L), eq(99L))).thenReturn(List.of(request));
        assertEquals(request, service.submit(pending, "alpha"));
        assertError(ApiError.STUDENT_AFFILIATION_CONFLICT, () -> service.submit(pending, "alpha"));
        verify(jdbc, never()).update(contains("UPDATE public.users"), anyLong(), anyLong());
    }

    @Test void queueAndDecisionsCheckSupervisionScopeAndApprovalRequiresAdmissionConfirmation() {
        when(jdbc.query(contains("r.college_id = ? AND r.status = 'PENDING'"), affiliationMapper(), eq(10L)))
                .thenReturn(List.of(request));
        assertEquals(List.of(request), service.pending(admin));
        assertError(ApiError.STUDENT_ADMISSION_UNCONFIRMED, () -> service.decide(admin, 99, true, false));
        assertError(ApiError.STUDENT_AFFILIATION_NOT_FOUND, () -> service.decide(admin, 100, true, true));
        verify(jdbc).queryForList(contains("college_id = ? AND id = ?"), eq(Long.class), eq(10L), eq(100L));
        verify(jdbc, never()).update(contains("UPDATE"), anyLong(), anyLong());
    }

    @Test void approvalRecordsDecisionAndOnlyBindsABareStudentToReviewerCollege() {
        allowDecisionLock();
        when(jdbc.update(contains("AND status = 'PENDING'"), eq("APPROVED"), eq(1L), eq(10L), eq(99L))).thenReturn(1, 0);
        when(jdbc.update(contains("SET college_id = ?, is_verified = TRUE"), eq(10L), eq(12L))).thenReturn(1);
        var approved = new Affiliation(99, "APPROVED", "Alpha", "ALPHA", "Student Sample", "student@example.invalid");
        when(jdbc.query(contains("WHERE r.college_id = ? AND r.id = ?"), affiliationMapper(),
                eq(10L), eq(99L))).thenReturn(List.of(approved));
        assertEquals(approved, service.decide(admin, 99, true, true));
        assertError(ApiError.STUDENT_AFFILIATION_CONFLICT, () -> service.decide(admin, 99, true, true));
        verify(jdbc).update(contains("r.name <> 'STUDENT'"), eq(10L), eq(12L));
    }

    @Test void rejectionNeverBindsUserAndInactiveCollegeCannotDecide() {
        allowDecisionLock();
        when(jdbc.update(contains("AND status = 'PENDING'"), eq("REJECTED"), eq(1L), eq(10L), eq(99L))).thenReturn(1);
        var rejected = new Affiliation(99, "REJECTED", "Alpha", "ALPHA", "Student Sample", "student@example.invalid");
        when(jdbc.query(contains("WHERE r.college_id = ? AND r.id = ?"), affiliationMapper(),
                eq(10L), eq(99L))).thenReturn(List.of(rejected));
        assertEquals(rejected, service.decide(admin, 99, false, false));
        when(jdbc.queryForList(contains("is_active IS TRUE FOR SHARE"), eq(Long.class), eq(10L))).thenReturn(List.of());
        assertError(ApiError.ACCESS_DENIED, () -> service.decide(admin, 99, true, true));
        verify(jdbc, never()).update(contains("UPDATE public.users"), anyLong(), anyLong());
    }

    private void allowDecisionLock() {
        when(jdbc.queryForList(contains("college_id = ? AND id = ?"), eq(Long.class), eq(10L), eq(99L)))
                .thenReturn(List.of(12L));
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(Long.class), eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForList(contains("is_active IS TRUE FOR SHARE"), eq(Long.class), eq(10L))).thenReturn(List.of(10L));
    }

    private static RowMapper<Affiliation> affiliationMapper() { return any(); }
    private static RowMapper<Long> idMapper() { return any(); }
    private static void assertError(ApiError expected, Runnable action) {
        assertEquals(expected, assertThrows(ApiError.Failure.class, action::run).error());
    }
}
