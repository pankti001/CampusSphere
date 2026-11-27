package com.campussphere.backend.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.AssignmentSubmissions.Evaluation;
import com.campussphere.backend.service.AssignmentSubmissions.ReturnRequest;
import com.campussphere.backend.service.Assignments.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Business rules of the Assignments module that need no database. */
class AssignmentRulesTest {
    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 12, 1, 10, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

    static Input input(OffsetDateTime issue, OffsetDateTime due, String marks, List<String> formats,
                       String late, OffsetDateTime lateUntil, String resubmission) {
        return new Input(4L, " Essay ", " About ", " Read ", issue, due, marks == null ? null : new BigDecimal(marks),
                formats, null, late, lateUntil, resubmission);
    }

    static Input valid() {
        return input(NOW, NOW.plusDays(7), "20", List.of("PDF", "docx"), null, null, null);
    }

    static State state(String status, String late, OffsetDateTime lateUntil, String resubmission) {
        return new State(5, status, NOW.minusDays(1), NOW.plusDays(2), new BigDecimal("20.00"), List.of("pdf"),
                10_485_760, late, lateUntil, resubmission, 4, (UUID) null, "Essay");
    }

    static void assertError(ApiError expected, Runnable action) {
        var failure = assertThrows(ApiError.Failure.class, action::run);
        assertEquals(expected, failure.error());
    }

    @Test void inputIsTrimmedAndDefaultsAreFilledIn() {
        Input clean = Assignments.clean(input(null, NOW.plusDays(7), "20.5", List.of("JPEG", "pdf", "pdf"), null, null, null), NOW);
        assertEquals("Essay", clean.title());
        assertEquals("About", clean.description());
        assertEquals("Read", clean.instructions());
        assertEquals(NOW, clean.issueAt());
        assertEquals(new BigDecimal("20.50"), clean.maxMarks());
        assertEquals(List.of("pdf", "jpg"), clean.allowedFormats());
        assertEquals(10_485_760, clean.maxFileBytes());
        assertEquals("NONE", clean.latePolicy());
        assertEquals("SINGLE", clean.resubmission());
    }

    @Test void invalidAssignmentsAreRefused() {
        OffsetDateTime due = NOW.plusDays(7);
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(null, NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(new Input(4L, "  ", "", "", NOW, due,
                BigDecimal.TEN, List.of("pdf"), null, null, null, null), NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(new Input(4L, "t".repeat(161), "", "", NOW, due,
                BigDecimal.TEN, List.of("pdf"), null, null, null, null), NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(new Input(4L, "t", "d".repeat(4001), "", NOW, due,
                BigDecimal.TEN, List.of("pdf"), null, null, null, null), NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(input(NOW, due, null, List.of("pdf"), null, null, null), NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(input(NOW, null, "10", List.of("pdf"), null, null, null), NOW));
        for (String marks : List.of("0", "-1", "1000.01", "10.005")) {
            assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(input(NOW, due, marks, List.of("pdf"), null, null, null), NOW));
        }
        assertEquals(new BigDecimal("1000.00"), Assignments.clean(input(NOW, due, "1000", List.of("pdf"), null, null, null), NOW).maxMarks());
        // Executables, old binary Office formats and unknown types are never allowed.
        for (List<String> formats : List.of(List.<String>of(), List.of("exe"), List.of("doc"), List.of("pdf", "ppt"), List.of("xls"))) {
            assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(input(NOW, due, "10", formats, null, null, null), NOW));
        }
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(new Input(4L, "t", "", "", NOW, due,
                BigDecimal.TEN, List.of("pdf"), 50_000, null, null, null), NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(new Input(4L, "t", "", "", NOW, due,
                BigDecimal.TEN, List.of("pdf"), 20_000_000, null, null, null), NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(input(NOW, due, "10", List.of("pdf"), null, null, "ALWAYS"), NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(input(NOW, due, "10", List.of("pdf"), "SOMETIMES", null, null), NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(new Input(0L, "t", "", "", NOW, due,
                BigDecimal.TEN, List.of("pdf"), null, null, null, null), NOW));
    }

    @Test void deadlinesAndLateWindowsAreChecked() {
        assertError(ApiError.ASSIGNMENT_DEADLINE_INVALID, () -> Assignments.clean(input(NOW, NOW, "10", List.of("pdf"), null, null, null), NOW));
        assertError(ApiError.ASSIGNMENT_DEADLINE_INVALID, () -> Assignments.clean(input(NOW, NOW.minusHours(1), "10", List.of("pdf"), null, null, null), NOW));
        assertError(ApiError.ASSIGNMENT_DEADLINE_INVALID, () -> Assignments.clean(input(NOW, NOW.plusDays(400), "10", List.of("pdf"), null, null, null), NOW));
        OffsetDateTime due = NOW.plusDays(7);
        assertError(ApiError.ASSIGNMENT_DEADLINE_INVALID, () -> Assignments.clean(input(NOW, due, "10", List.of("pdf"), "ALLOWED", null, null), NOW));
        assertError(ApiError.ASSIGNMENT_DEADLINE_INVALID, () -> Assignments.clean(input(NOW, due, "10", List.of("pdf"), "ALLOWED", due, null), NOW));
        assertError(ApiError.ASSIGNMENT_DEADLINE_INVALID, () -> Assignments.clean(input(NOW, due, "10", List.of("pdf"), "ALLOWED", due.plusDays(61), null), NOW));
        assertError(ApiError.ASSIGNMENT_INVALID, () -> Assignments.clean(input(NOW, due, "10", List.of("pdf"), "NONE", due.plusDays(1), null), NOW));
        Input late = Assignments.clean(input(NOW, due, "10", List.of("pdf"), "ALLOWED", due.plusDays(2), "UNTIL_DEADLINE"), NOW);
        assertEquals(due.plusDays(2), late.lateUntil());
        assertEquals("UNTIL_DEADLINE", late.resubmission());
    }

    @Test void draftsCanChangeAnythingButPublishedWorkFollowsSafeRules() {
        Input next = Assignments.clean(input(NOW.minusDays(1), NOW.plusDays(2), "20", List.of("pdf"), null, null, null), NOW);
        assertFalse(Assignments.plan(state("DRAFT", "NONE", null, "SINGLE"), next, NOW, false).deadlineMoved());
        Input otherClass = new Input(9L, next.title(), "", "", next.issueAt(), next.dueAt(), next.maxMarks(),
                next.allowedFormats(), next.maxFileBytes(), "NONE", null, "SINGLE");
        assertTrue(Assignments.plan(state("DRAFT", "NONE", null, "SINGLE"), otherClass, NOW, false).classChanged());
        State published = state("PUBLISHED", "NONE", null, "SINGLE");
        // Class and issue date are fixed once students can see the assignment.
        assertError(ApiError.ASSIGNMENT_STATE_CONFLICT, () -> Assignments.plan(published, otherClass, NOW, false));
        Input moved = new Input(null, "Essay", "", "", NOW, NOW.plusDays(2), new BigDecimal("20.00"), List.of("pdf"),
                10_485_760, "NONE", null, "SINGLE");
        assertError(ApiError.ASSIGNMENT_STATE_CONFLICT, () -> Assignments.plan(published, moved, NOW, false));
        // The deadline only moves later, never earlier or into the past.
        Input earlier = withDue(next, NOW.plusDays(1));
        assertError(ApiError.ASSIGNMENT_DEADLINE_INVALID, () -> Assignments.plan(published, earlier, NOW, false));
        assertTrue(Assignments.plan(published, withDue(next, NOW.plusDays(5)), NOW, false).deadlineMoved());
        State overdue = new State(5, "PUBLISHED", NOW.minusDays(9), NOW.minusDays(3), new BigDecimal("20.00"),
                List.of("pdf"), 10_485_760, "NONE", null, "SINGLE", 4, null, "Essay");
        Input stillPast = new Input(null, "Essay", "", "", NOW.minusDays(9), NOW.minusDays(1), new BigDecimal("20.00"),
                List.of("pdf"), 10_485_760, "NONE", null, "SINGLE");
        assertError(ApiError.ASSIGNMENT_DEADLINE_INVALID, () -> Assignments.plan(overdue, stillPast, NOW, false));
        // Maximum marks are locked once any work is marked.
        Input marks = new Input(null, "Essay", "", "", next.issueAt(), next.dueAt(), new BigDecimal("25.00"),
                List.of("pdf"), 10_485_760, "NONE", null, "SINGLE");
        assertTrue(Assignments.plan(published, marks, NOW, false).marksChanged());
        assertError(ApiError.ASSIGNMENT_MARKS_LOCKED, () -> Assignments.plan(published, marks, NOW, true));
        for (String status : List.of("CLOSED", "ARCHIVED")) {
            assertError(ApiError.ASSIGNMENT_STATE_CONFLICT, () -> Assignments.plan(state(status, "NONE", null, "SINGLE"), next, NOW, false));
        }
    }

    private static Input withDue(Input in, OffsetDateTime due) {
        return new Input(null, in.title(), in.description(), in.instructions(), in.issueAt(), due, in.maxMarks(),
                in.allowedFormats(), in.maxFileBytes(), in.latePolicy(), in.lateUntil(), in.resubmission());
    }

    @Test void submissionsAreAcceptedOnlyInsideTheWindow() {
        OffsetDateTime issue = NOW.minusDays(1), due = NOW.plusDays(1);
        assertTrue(Assignments.accepting("PUBLISHED", issue, due, "NONE", null, NOW));
        assertFalse(Assignments.accepting("PUBLISHED", NOW.plusHours(1), due, "NONE", null, NOW));
        assertFalse(Assignments.accepting("PUBLISHED", issue, NOW.minusMinutes(1), "NONE", null, NOW));
        assertTrue(Assignments.accepting("PUBLISHED", issue, NOW.minusMinutes(1), "ALLOWED", NOW.plusDays(1), NOW));
        for (String status : List.of("DRAFT", "CLOSED", "ARCHIVED")) assertFalse(Assignments.accepting(status, issue, due, "NONE", null, NOW));
    }

    @Test void firstSubmissionLateWorkAndResubmissionFollowTheAssignmentPolicy() {
        State single = state("PUBLISHED", "NONE", null, "SINGLE");
        var first = AssignmentSubmissions.decide(single, null, NOW);
        assertNull(first.refusal());
        assertFalse(first.late());
        assertFalse(first.supersede());
        // One submission only: a second attempt is refused, even before the deadline.
        assertEquals(ApiError.SUBMISSION_CONFLICT, AssignmentSubmissions.decide(single, "SUBMITTED", NOW).refusal());
        assertEquals(ApiError.SUBMISSION_CONFLICT, AssignmentSubmissions.decide(single, "EVALUATED", NOW).refusal());
        // After the deadline without a late window nothing is accepted.
        assertEquals(ApiError.SUBMISSION_CLOSED, AssignmentSubmissions.decide(single, null, NOW.plusDays(3)).refusal());
        // Not yet issued, a draft, closed or archived: closed.
        assertEquals(ApiError.SUBMISSION_CLOSED, AssignmentSubmissions.decide(single, null, NOW.minusDays(2)).refusal());
        for (String status : List.of("DRAFT", "CLOSED", "ARCHIVED")) {
            assertEquals(ApiError.SUBMISSION_CLOSED, AssignmentSubmissions.decide(state(status, "NONE", null, "SINGLE"), null, NOW).refusal());
        }
        // Late window: accepted and marked late until the cut-off, refused after it.
        State late = state("PUBLISHED", "ALLOWED", NOW.plusDays(4), "UNTIL_DEADLINE");
        var lateWork = AssignmentSubmissions.decide(late, null, NOW.plusDays(3));
        assertNull(lateWork.refusal());
        assertTrue(lateWork.late());
        assertEquals(ApiError.SUBMISSION_CLOSED, AssignmentSubmissions.decide(late, null, NOW.plusDays(5)).refusal());
        // Resubmission until the deadline replaces the current attempt, but not in the late window.
        var again = AssignmentSubmissions.decide(late, "SUBMITTED", NOW);
        assertNull(again.refusal());
        assertTrue(again.supersede());
        assertEquals(ApiError.SUBMISSION_CONFLICT, AssignmentSubmissions.decide(late, "SUBMITTED", NOW.plusDays(3)).refusal());
        // Work returned for correction can be handed in again even after the deadline or once closed.
        var corrected = AssignmentSubmissions.decide(state("CLOSED", "NONE", null, "SINGLE"), "RETURNED", NOW.plusDays(9));
        assertNull(corrected.refusal());
        assertTrue(corrected.supersede());
        assertTrue(corrected.late());
        assertEquals(ApiError.SUBMISSION_CLOSED,
                AssignmentSubmissions.decide(state("ARCHIVED", "NONE", null, "SINGLE"), "RETURNED", NOW).refusal());
    }

    @Test void marksMustBeWithinZeroAndTheMaximum() {
        BigDecimal max = new BigDecimal("20.00");
        assertEquals(new BigDecimal("0.00"), AssignmentSubmissions.cleanEvaluation(new Evaluation(BigDecimal.ZERO, " "), max).marks());
        assertNull(AssignmentSubmissions.cleanEvaluation(new Evaluation(BigDecimal.ONE, "   "), max).feedback());
        assertEquals("Good", AssignmentSubmissions.cleanEvaluation(new Evaluation(new BigDecimal("20"), " Good "), max).feedback());
        for (String marks : List.of("-0.5", "20.01", "1.234")) {
            assertError(ApiError.EVALUATION_INVALID, () -> AssignmentSubmissions.cleanEvaluation(new Evaluation(new BigDecimal(marks), null), max));
        }
        assertError(ApiError.EVALUATION_INVALID, () -> AssignmentSubmissions.cleanEvaluation(new Evaluation(null, "x"), max));
        assertError(ApiError.EVALUATION_INVALID, () -> AssignmentSubmissions.cleanEvaluation(null, max));
        assertError(ApiError.EVALUATION_INVALID, () -> AssignmentSubmissions.cleanEvaluation(new Evaluation(BigDecimal.ONE, "f".repeat(4001)), max));
        assertError(ApiError.EVALUATION_INVALID, () -> AssignmentSubmissions.cleanReturn(new ReturnRequest(" ok ")));
        assertEquals("Add references", AssignmentSubmissions.cleanReturn(new ReturnRequest(" Add references ")));
    }

    @Test void notificationKeysMakeRetriesHarmless() {
        assertEquals(AssignmentNotices.publishedKey(5), AssignmentNotices.publishedKey(5));
        assertNotEquals(AssignmentNotices.deadlineKey(5, NOW), AssignmentNotices.deadlineKey(5, NOW.plusDays(1)));
        assertEquals(AssignmentNotices.deadlineKey(5, NOW), AssignmentNotices.deadlineKey(5, NOW.withOffsetSameInstant(ZoneOffset.UTC)));
        assertNotEquals(AssignmentNotices.evaluatedKey(8, 1), AssignmentNotices.evaluatedKey(8, 2));
        assertTrue(AssignmentNotices.returnedKey(8).length() <= 100);
    }

    @Test void filtersRejectUnknownValues() {
        assertError(ApiError.INVALID_REQUEST, () -> new Filter(null, null, "DELETED", null, 0));
        assertError(ApiError.INVALID_REQUEST, () -> new Filter(null, null, null, null, -1));
        assertError(ApiError.INVALID_REQUEST, () -> new Filter(null, null, null, "q".repeat(101), 0));
        assertNull(new Filter(null, null, null, "  ", 0).q());
        // College Admins never list drafts.
        assertError(ApiError.INVALID_REQUEST, () -> new AdminFilter(null, null, null, null, null, null, null, null, "DRAFT", null, 0));
        assertError(ApiError.INVALID_REQUEST, () -> new AdminFilter(null, null, null, null, null, null,
                LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 1), null, null, 0));
    }
}
