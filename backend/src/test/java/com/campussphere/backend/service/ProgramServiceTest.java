package com.campussphere.backend.service;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.dto.ProgramInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProgramServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ProgramService service = new ProgramService(jdbc);
    final Account admin = new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(10L, true));

    @Test void scopesListToPrincipalCollegeAndSelectedDepartment() {
        owns(42L, true);
        var program = new ProgramService.Program(8L, "CS", "Computing");
        when(jdbc.query(contains("WHERE college_id = ? AND department_id = ?"), rowMapper(), eq(10L), eq(42L)))
                .thenReturn(List.of(program));
        assertEquals(List.of(program), service.list(admin, 42L));
    }

    @Test void rejectsForeignDepartmentBeforeAnyProgramQueryOrWrite() {
        owns(99L, false);
        assertError(ApiError.DEPARTMENT_NOT_FOUND, () -> service.list(admin, 99L));
        assertError(ApiError.DEPARTMENT_NOT_FOUND, () -> service.create(admin, 99L, new ProgramInput("CS", "Computing")));
        assertError(ApiError.DEPARTMENT_NOT_FOUND, () -> service.update(admin, 99L, 8L, new ProgramInput("CS", null)));
        verify(jdbc, times(3)).queryForObject(contains("FROM public.departments"), eq(Boolean.class), eq(10L), eq(99L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void inactiveCollegeIsDeniedBeforeJdbc() {
        var inactive = new Account(2L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(10L, false));
        assertError(ApiError.ACCESS_DENIED, () -> service.list(inactive, 42L));
        verifyNoInteractions(jdbc);
    }

    @Test void createUsesCollegeWideUniquenessAndCanonicalCode() {
        owns(42L, true);
        var row = new ProgramService.Program(8L, "CS", "Computing");
        when(jdbc.query(contains("ON CONFLICT (college_id, code)"), rowMapper(),
                eq(10L), eq(42L), eq("CS"), eq("Computing"))).thenReturn(List.of(row), List.of());
        var input = new ProgramInput("cs", " Computing ");
        assertEquals(row, service.create(admin, 42L, input));
        assertError(ApiError.PROGRAM_CODE_CONFLICT, () -> service.create(admin, 42L, input));
    }

    @Test void updateIsScopedToBothParentAndCollegeAndMapsDuplicateCode() {
        owns(42L, true);
        var input = new ProgramInput("cs", null);
        when(jdbc.query(contains("WHERE college_id = ? AND department_id = ? AND id = ?"), rowMapper(),
                eq("CS"), isNull(), eq(10L), eq(42L), eq(8L)))
                .thenReturn(List.of()).thenThrow(new DuplicateKeyException("fixture"));
        assertError(ApiError.PROGRAM_NOT_FOUND, () -> service.update(admin, 42L, 8L, input));
        assertError(ApiError.PROGRAM_CODE_CONFLICT, () -> service.update(admin, 42L, 8L, input));
    }

    private void owns(long department, boolean result) {
        when(jdbc.queryForObject(contains("FROM public.departments"), eq(Boolean.class), eq(10L), eq(department)))
                .thenReturn(result);
    }

    private RowMapper<ProgramService.Program> rowMapper() {
        return org.mockito.ArgumentMatchers.<RowMapper<ProgramService.Program>>any();
    }

    private void assertError(ApiError expected, Runnable action) {
        assertEquals(expected, assertThrows(ApiError.Failure.class, action::run).error());
    }
}
