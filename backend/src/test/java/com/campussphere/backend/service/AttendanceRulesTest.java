package com.campussphere.backend.service;

import static com.campussphere.backend.dto.AttendanceModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import com.campussphere.backend.security.ApiError;
import org.junit.jupiter.api.Test;

class AttendanceRulesTest {
    @Test void countsLateAndExcludesExcusedByDefault() {
        var result = AttendanceReports.summarize(2, 1, 1, 7, Policy.DEFAULT);
        assertEquals(4, result.eligible());
        assertEquals(3, result.attended());
        assertEquals(new BigDecimal("75.00"), result.percentage());
        assertEquals(Severity.NORMAL, result.severity());
    }

    @Test void thresholdUsesTotalsAndUnroundedPercentages() {
        assertEquals(Severity.WARNING, AttendanceReports.summarize(65, 35, 0, 0, Policy.DEFAULT).severity());
        assertEquals(Severity.CRITICAL, AttendanceReports.summarize(64, 36, 0, 0, Policy.DEFAULT).severity());
        var rounded = AttendanceReports.summarize(74996, 25004, 0, 0, Policy.DEFAULT);
        assertEquals(new BigDecimal("75.00"), rounded.percentage());
        assertEquals(Severity.WARNING, rounded.severity());
    }

    @Test void countsExcusedAsEligibleAndLateAsMissedWhenConfigured() {
        var policy = new Policy(new BigDecimal("75"), new BigDecimal("65"), false, false, 24);
        assertEquals(new BigDecimal("25.00"), AttendanceReports.summarize(1, 1, 1, 1, policy).percentage());
        var excusedOnly = AttendanceReports.summarize(0, 0, 0, 6, Policy.DEFAULT);
        assertNull(excusedOnly.percentage());
        assertEquals(Severity.NORMAL, excusedOnly.severity());
    }

    @Test void rejectsDuplicateForeignAndIncompleteSubmissionMarksButAllowsPartialDraft() {
        var roster = List.of(new RosterRow(1, "One", null), new RosterRow(2, "Two", null));
        var one = new MarkInput(1, Mark.PRESENT);
        assertEquals(1, AttendanceService.checkedMarks(roster, List.of(one), false).size());
        for (var invalid : List.of(List.of(one), List.of(one, one), List.of(one, new MarkInput(3, Mark.LATE)))) {
            var failure = assertThrows(ApiError.Failure.class, () -> AttendanceService.checkedMarks(roster, invalid, true));
            assertEquals(ApiError.ATTENDANCE_ROSTER_INVALID, failure.error());
        }
        assertEquals(2, AttendanceService.checkedMarks(roster, List.of(one, new MarkInput(2, Mark.ABSENT)), true).size());
    }

    @Test void derivesRosterInstantFromLectureTimeAndRejectsFutureLectures() {
        var now = OffsetDateTime.parse("2026-11-25T06:00:00Z");
        assertEquals(now, AttendanceService.rosterAt(new Create(1, LocalDate.of(2026,11,25), 1, null, null), now));
        assertEquals(Instant.parse("2026-11-25T04:30:00Z"), AttendanceService.rosterAt(
                new Create(1, LocalDate.of(2026,11,25), 2, LocalTime.of(10, 0), LocalTime.of(11, 0)), now).toInstant());
        assertThrows(ApiError.Failure.class, () -> AttendanceService.rosterAt(
                new Create(1, LocalDate.of(2026,11,25), 3, LocalTime.of(13, 0), null), now));
        assertThrows(ApiError.Failure.class, () -> AttendanceService.rosterAt(
                new Create(1, LocalDate.of(2026,11,25), 3, null, LocalTime.of(10, 0)), now));
    }
}
