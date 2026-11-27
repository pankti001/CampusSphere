package com.campussphere.backend.service;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.dto.AcademicPeriodInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AcademicPeriodServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AcademicPeriodService service = new AcademicPeriodService(jdbc);
    private final Account admin = new Account(1L, true, false, Set.of("COLLEGE_ADMIN"), new CollegeScope(10L, true));

    @Test void yearUsesPrincipalCollegeAndConsecutiveRange() {
        var year = new AcademicPeriodService.Period(4,"2026-2027","Year");
        when(jdbc.query(contains("ON CONFLICT (college_id, code)"), rowMapper(),
                eq(10L), eq("2026-2027"), eq("Year"), isNull(), isNull())).thenReturn(List.of(year), List.of());
        assertEquals(year, service.createYear(admin,new AcademicPeriodInput("2026-2027"," Year ")));
        assertError(ApiError.ACADEMIC_YEAR_CONFLICT, () -> service.createYear(admin,new AcademicPeriodInput("2026-2027","Year")));
        assertError(ApiError.INVALID_REQUEST, () -> service.createYear(admin,new AcademicPeriodInput("2026-2028","Year")));
        verify(jdbc,times(2)).query(contains("ON CONFLICT (college_id, code)"), rowMapper(),
                eq(10L), eq("2026-2027"), eq("Year"), isNull(), isNull());
        verifyNoMoreInteractions(jdbc);
    }

    @Test void updatingYearIsScopedAndDistinguishesMissingAndDuplicate() {
        // The year is loaded (and locked) in the admin's college first; a missing one is reported before writing.
        var current = new AcademicPeriodService.Period(4, "2026-2027", "Year");
        when(jdbc.query(contains("FOR UPDATE"), rowMapper(), eq(10L), eq(4L))).thenReturn(List.of(), List.of(current));
        when(jdbc.query(contains("UPDATE public.academic_years"), rowMapper(),
                eq("2026-2027"), eq("Updated"), isNull(), isNull(), eq(10L), eq(4L)))
                .thenThrow(new DuplicateKeyException("fixture"));
        var input = new AcademicPeriodInput(null,"Updated");
        assertError(ApiError.ACADEMIC_YEAR_NOT_FOUND, () -> service.updateYear(admin,4,input));
        assertError(ApiError.ACADEMIC_YEAR_CONFLICT, () -> service.updateYear(admin,4,input));
    }

    @Test void rejectsForeignProgramBeforeCheckingYearOrWriting() {
        when(jdbc.queryForObject(contains("FROM public.academic_programs"),eq(Boolean.class),eq(10L),eq(2L))).thenReturn(false);
        assertError(ApiError.PROGRAM_NOT_FOUND, () -> service.semesters(admin,2,4));
        verify(jdbc).queryForObject(contains("FROM public.academic_programs"),eq(Boolean.class),eq(10L),eq(2L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void rejectsForeignYearBeforeSemesterWrites() {
        parents(false);
        assertError(ApiError.ACADEMIC_YEAR_NOT_FOUND, () -> service.createSemester(admin,2,4,new AcademicPeriodInput("1","One")));
        verify(jdbc).queryForObject(contains("FROM public.academic_programs"),eq(Boolean.class),eq(10L),eq(2L));
        verify(jdbc).queryForObject(contains("FROM public.academic_years"),eq(Boolean.class),eq(10L),eq(4L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void semesterListAndPatchUseAllThreeScopeKeys() {
        parents(true);
        var term = new AcademicPeriodService.Period(9,"2","Term Two");
        when(jdbc.query(contains("ORDER BY semester_number"),rowMapper(),eq(10L),eq(2L),eq(4L))).thenReturn(List.of(term));
        assertEquals(List.of(term),service.semesters(admin,2,4));
        when(jdbc.query(contains("FOR UPDATE"),rowMapper(),eq(10L),eq(2L),eq(4L),eq(9L))).thenReturn(List.of(term));
        when(jdbc.query(contains("UPDATE public.academic_semesters"),rowMapper(),
                eq((short)2),eq("Term Two"),isNull(),isNull(),eq(10L),eq(2L),eq(4L),eq(9L)))
                .thenReturn(List.of(term),List.of()).thenThrow(new DuplicateKeyException("fixture"));
        var input = new AcademicPeriodInput("2","Term Two");
        assertEquals(term,service.updateSemester(admin,2,4,9,input));
        assertError(ApiError.SEMESTER_NOT_FOUND,() -> service.updateSemester(admin,2,4,9,input));
        assertError(ApiError.SEMESTER_CONFLICT,() -> service.updateSemester(admin,2,4,9,input));
    }

    @Test void semesterNumberValidationAndScopedDuplicate() {
        parents(true);
        var term = new AcademicPeriodService.Period(9,"1","Term One");
        when(jdbc.query(contains("ON CONFLICT (college_id, program_id, academic_year_id, semester_number)"),
                rowMapper(),eq(10L),eq(2L),eq(4L),eq((short)1),eq("Term One"),isNull(),isNull()))
                .thenReturn(List.of(term),List.of());
        var input = new AcademicPeriodInput("1","Term One");
        assertEquals(term,service.createSemester(admin,2,4,input));
        assertError(ApiError.SEMESTER_CONFLICT,() -> service.createSemester(admin,2,4,input));
        assertError(ApiError.INVALID_REQUEST,() -> service.createSemester(admin,2,4,new AcademicPeriodInput("01","Term One")));
    }

    @Test void datesComeInPairsWithinAboutAYear() {
        var none = AcademicPeriodService.Dates.NONE;
        var start = java.time.LocalDate.of(2026, 6, 1);
        var end = java.time.LocalDate.of(2027, 5, 31);
        assertEquals(new AcademicPeriodService.Dates(start, end), AcademicPeriodService.requested(
                new AcademicPeriodInput("2026-2027", "Y", start, end, null), none));
        var kept = new AcademicPeriodService.Dates(start, end);
        assertEquals(kept, AcademicPeriodService.requested(new AcademicPeriodInput(null, "Y"), kept));   // unchanged when not sent
        assertEquals(none, AcademicPeriodService.requested(new AcademicPeriodInput(null, null, null, null, true), kept));
        assertError(ApiError.ACADEMIC_DATES_INVALID, () -> AcademicPeriodService.requested(
                new AcademicPeriodInput("2026-2027", "Y", start, null, null), none));
        assertError(ApiError.ACADEMIC_DATES_INVALID, () -> AcademicPeriodService.requested(
                new AcademicPeriodInput("2026-2027", "Y", end, start, null), none));
        assertError(ApiError.ACADEMIC_DATES_INVALID, () -> AcademicPeriodService.requested(
                new AcademicPeriodInput("2026-2027", "Y", start, start.plusDays(401), null), none));
        assertError(ApiError.ACADEMIC_DATES_INVALID, () -> AcademicPeriodService.requested(
                new AcademicPeriodInput(null, null, start, end, true), none));
    }

    @Test void yearDatesSitInTheYearsOfItsCode() {
        var ok = new AcademicPeriodService.Dates(java.time.LocalDate.of(2026, 6, 1), java.time.LocalDate.of(2027, 5, 31));
        assertEquals(ok, AcademicPeriodService.yearDates("2026-2027", ok));
        assertError(ApiError.ACADEMIC_DATES_INVALID, () -> AcademicPeriodService.yearDates("2025-2026", ok));
        assertError(ApiError.ACADEMIC_DATES_INVALID, () -> AcademicPeriodService.yearDates("2026-2027",
                new AcademicPeriodService.Dates(java.time.LocalDate.of(2026, 6, 1), java.time.LocalDate.of(2028, 1, 1))));
    }

    @Test void semesterDatesMustLieInsideTheYear() {
        parents(true);
        when(jdbc.query(contains("SELECT start_date, end_date FROM public.academic_years"), org.mockito.ArgumentMatchers.<RowMapper<AcademicPeriodService.Dates>>any(), eq(10L), eq(4L)))
                .thenReturn(List.of(new AcademicPeriodService.Dates(java.time.LocalDate.of(2026, 6, 1), java.time.LocalDate.of(2027, 5, 31))));
        assertError(ApiError.ACADEMIC_DATES_INVALID, () -> service.createSemester(admin, 2, 4, new AcademicPeriodInput("1", "One",
                java.time.LocalDate.of(2026, 5, 1), java.time.LocalDate.of(2026, 11, 30), null)));
        verify(jdbc, never()).query(contains("INSERT"), rowMapper(), any(Object[].class));
    }

    @Test void inactiveCollegeCannotReadAcademicPeriods() {
        var inactive = new Account(1L,true,false,Set.of("COLLEGE_ADMIN"),new CollegeScope(10L,false));
        assertError(ApiError.ACCESS_DENIED,() -> service.years(inactive));
        assertError(ApiError.ACCESS_DENIED,() -> service.semesters(inactive,2,4));
        verifyNoInteractions(jdbc);
    }

    private void parents(boolean ownYear) {
        when(jdbc.queryForObject(contains("FROM public.academic_programs"),eq(Boolean.class),eq(10L),eq(2L))).thenReturn(true);
        when(jdbc.queryForObject(contains("FROM public.academic_years"),eq(Boolean.class),eq(10L),eq(4L))).thenReturn(ownYear);
    }

    private RowMapper<AcademicPeriodService.Period> rowMapper() {
        return org.mockito.ArgumentMatchers.<RowMapper<AcademicPeriodService.Period>>any();
    }

    private void assertError(ApiError expected,Runnable operation) {
        assertEquals(expected,assertThrows(ApiError.Failure.class,operation::run).error());
    }
}
