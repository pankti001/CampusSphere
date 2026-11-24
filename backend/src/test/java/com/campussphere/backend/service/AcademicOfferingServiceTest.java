package com.campussphere.backend.service;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.dto.AcademicOfferingInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static com.campussphere.backend.service.AcademicOfferingService.Type.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicOfferingServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AcademicOfferingService service = new AcademicOfferingService(jdbc);
    private final Account admin = new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(10L, true));

    @Test void rejectsDivisionWhenYearIsOutsidePrincipalCollege() {
        allowProgram();
        when(jdbc.queryForObject(contains("FROM public.academic_years"), eq(Boolean.class), eq(10L), eq(4L)))
                .thenReturn(false);
        assertError(ApiError.ACADEMIC_YEAR_NOT_FOUND, () -> service.create(admin, DIVISION, 2, 4,
                new AcademicOfferingInput("A", "Division A")));
        verify(jdbc).queryForObject(contains("FROM public.academic_programs"), eq(Boolean.class), eq(10L), eq(2L));
        verify(jdbc).queryForObject(contains("FROM public.academic_years"), eq(Boolean.class), eq(10L), eq(4L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void rejectsForeignSemesterAndProgramMismatchBeforeSubjectWrite() {
        when(jdbc.queryForObject(contains("WHERE college_id = ? AND program_id = ? AND id = ?"),
                eq(Boolean.class), eq(10L), eq(2L), eq(9L))).thenReturn(false);
        assertError(ApiError.SEMESTER_NOT_FOUND, () -> service.update(admin, SUBJECT, 2, 9, 21,
                new AcademicOfferingInput(null, "Changed")));
        verify(jdbc).queryForObject(contains("FROM public.academic_semesters"),
                eq(Boolean.class), eq(10L), eq(2L), eq(9L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void divisionCreateCanonicalizesCodeAndReportsConflictWithinScope() {
        allowProgram();
        when(jdbc.queryForObject(contains("FROM public.academic_years"), eq(Boolean.class), eq(10L), eq(4L)))
                .thenReturn(true);
        var division = new AcademicOfferingService.Offering(7, "A", "Division A");
        when(jdbc.query(contains("ON CONFLICT (college_id, program_id, academic_year_id, code)"),
                rowMapper(), eq(10L), eq(2L), eq(4L), eq("A"), eq("Division A")))
                .thenReturn(List.of(division), List.of());
        var input = new AcademicOfferingInput("a", " Division A ");
        assertEquals(division, service.create(admin, DIVISION, 2, 4, input));
        assertError(ApiError.DIVISION_CONFLICT, () -> service.create(admin, DIVISION, 2, 4, input));
        assertError(ApiError.INVALID_REQUEST, () -> service.create(admin, DIVISION, 2, 4, new AcademicOfferingInput("B", " ")));
    }

    @Test void subjectListAndPatchBindCollegeProgramAndSemester() {
        when(jdbc.queryForObject(contains("FROM public.academic_semesters"), eq(Boolean.class),
                eq(10L), eq(2L), eq(9L))).thenReturn(true);
        var subject = new AcademicOfferingService.Offering(21, "BIO", "Biology");
        when(jdbc.query(contains("AND semester_id = ? ORDER BY code"), rowMapper(), eq(10L), eq(2L), eq(9L)))
                .thenReturn(List.of(subject));
        assertEquals(List.of(subject), service.list(admin, SUBJECT, 2, 9));
        when(jdbc.query(contains("AND semester_id = ? AND id = ?"), rowMapper(),
                eq("BIO"), isNull(), eq(10L), eq(2L), eq(9L), eq(21L)))
                .thenReturn(List.of(subject), List.of()).thenThrow(new DuplicateKeyException("fixture"));
        var input = new AcademicOfferingInput("bio", null);
        assertEquals(subject, service.update(admin, SUBJECT, 2, 9, 21, input));
        assertError(ApiError.SUBJECT_NOT_FOUND, () -> service.update(admin, SUBJECT, 2, 9, 21, input));
        assertError(ApiError.SUBJECT_CONFLICT, () -> service.update(admin, SUBJECT, 2, 9, 21, input));
    }

    @Test void deniesInactiveCollegeBeforeSql() {
        var inactive = new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(10L, false));
        assertError(ApiError.ACCESS_DENIED, () -> service.list(inactive, DIVISION, 2, 4));
        assertError(ApiError.ACCESS_DENIED, () -> service.list(inactive, SUBJECT, 2, 9));
        verifyNoInteractions(jdbc);
    }

    private void allowProgram() {
        when(jdbc.queryForObject(contains("FROM public.academic_programs"), eq(Boolean.class), eq(10L), eq(2L)))
                .thenReturn(true);
    }

    private RowMapper<AcademicOfferingService.Offering> rowMapper() {
        return org.mockito.ArgumentMatchers.<RowMapper<AcademicOfferingService.Offering>>any();
    }

    private void assertError(ApiError expected, Runnable action) {
        assertEquals(expected, assertThrows(ApiError.Failure.class, action::run).error());
    }
}
