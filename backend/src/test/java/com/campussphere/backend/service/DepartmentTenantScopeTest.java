package com.campussphere.backend.service;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DepartmentTenantScopeTest {
    @Test
    void sameDepartmentIdIsVisibleOnlyInItsOwnCollegeScope() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var department = new DepartmentService.Department(42L, "D42", "Synthetic department");
        when(jdbc.query(contains("WHERE college_id = ? AND id = ?"),
                org.mockito.ArgumentMatchers.<RowMapper<DepartmentService.Department>>any(),
                eq(10L), eq(42L))).thenReturn(List.of(department));
        when(jdbc.query(contains("WHERE college_id = ? AND id = ?"),
                org.mockito.ArgumentMatchers.<RowMapper<DepartmentService.Department>>any(),
                eq(20L), eq(42L))).thenReturn(List.of());
        var service = new DepartmentService(jdbc);
        assertEquals(department, service.get(admin(10L, true), 42L));
        var error = assertThrows(ApiError.Failure.class, () -> service.get(admin(20L, true), 42L));
        assertEquals(ApiError.DEPARTMENT_NOT_FOUND, error.error());
        verify(jdbc).query(contains("WHERE college_id = ? AND id = ?"),
                org.mockito.ArgumentMatchers.<RowMapper<DepartmentService.Department>>any(),
                eq(20L), eq(42L));
    }

    @Test
    void inactiveCollegeCannotReadDepartmentData() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var service = new DepartmentService(jdbc);
        var error = assertThrows(ApiError.Failure.class, () -> service.list(admin(10L, false)));
        assertEquals(ApiError.ACCESS_DENIED, error.error());
        verifyNoInteractions(jdbc);
    }

    private Account admin(long college, boolean active) {
        return new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(college, active));
    }
}
