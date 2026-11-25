package com.campussphere.backend.service;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.FacultyAccessService.CollegeRequest;
import com.campussphere.backend.service.FacultyAccessService.Status;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FacultyAccessServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FacultyAccessService service = new FacultyAccessService(jdbc);

    @Test
    void unscopedOrInactiveReviewersCannotQueryTheQueue() {
        for (Account account : List.of(
                new Account(7L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(10L, false)),
                new Account(7L, true, false, Set.of("COLLEGE_ADMIN"), null),
                new Account(7L, true, false, Set.of("ADMIN"), new CollegeScope(10L, true)))) {
            assertEquals(ApiError.ACCESS_DENIED,
                    assertThrows(ApiError.Failure.class, () -> service.pending(account)).error());
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void foreignRequestIsLookedUpOnlyWithinTheReviewersCollege() {
        when(jdbc.query(argThat(sql -> sql.contains("WHERE f.requested_college_id = ? AND f.id = ?")),
                org.mockito.ArgumentMatchers.<RowMapper<CollegeRequest>>any(), eq(20L), eq(15L)))
                .thenReturn(List.of());
        assertEquals(ApiError.FACULTY_REQUEST_NOT_FOUND, assertThrows(ApiError.Failure.class,
                () -> service.decideForCollege(admin(20L), 15L, Status.APPROVED, true)).error());
        verify(jdbc).query(argThat(sql -> sql.contains("WHERE f.requested_college_id = ? AND f.id = ?")),
                org.mockito.ArgumentMatchers.<RowMapper<CollegeRequest>>any(), eq(20L), eq(15L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void approvalRequiresEmploymentConfirmationBeforeAnyWrite() {
        when(jdbc.query(argThat(sql -> sql.contains("WHERE f.requested_college_id = ? AND f.id = ?")),
                org.mockito.ArgumentMatchers.<RowMapper<CollegeRequest>>any(), eq(10L), eq(15L)))
                .thenReturn(List.of(new CollegeRequest(15, 12, Status.PENDING, "Test Applicant", "faculty@example.invalid")));
        assertEquals(ApiError.FACULTY_EMPLOYMENT_UNCONFIRMED, assertThrows(ApiError.Failure.class,
                () -> service.decideForCollege(admin(10L), 15L, Status.APPROVED, false)).error());
        verify(jdbc).query(argThat(sql -> sql.contains("WHERE f.requested_college_id = ? AND f.id = ?")),
                org.mockito.ArgumentMatchers.<RowMapper<CollegeRequest>>any(), eq(10L), eq(15L));
        verifyNoMoreInteractions(jdbc);
    }

    private static Account admin(Long college) {
        return new Account(7L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(college, true));
    }
}
