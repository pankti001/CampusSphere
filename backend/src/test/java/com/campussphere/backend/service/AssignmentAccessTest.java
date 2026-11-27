package com.campussphere.backend.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.AssignmentSubmissions.Current;
import com.campussphere.backend.service.AssignmentSubmissions.Evaluation;
import com.campussphere.backend.service.AssignmentSubmissions.Submission;
import com.campussphere.backend.service.Assignments.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.campussphere.backend.service.AssignmentRulesTest.NOW;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Who may do what with assignments, checked against the SQL the services run. A scripted JdbcTemplate stands in
 * for PostgreSQL, so these tests show scoping and refusals, not the database itself.
 */
class AssignmentAccessTest {
    private static final long COLLEGE = 3L, ACTOR = 7L;
    private static final byte[] PDF = "%PDF-1.4\n1 0 obj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path storage;
    private final ScriptedJdbc db = new ScriptedJdbc();
    private final AssignmentNotices notices = mock(AssignmentNotices.class);
    private Assignments assignments;
    private AssignmentSubmissions submissions;

    @BeforeEach void setUp() {
        var files = new MaterialFiles(storage.toString());
        assignments = new Assignments(db, files, notices, Clock.fixed(NOW.toInstant(), NOW.getOffset()), ZoneId.of("Asia/Kolkata"));
        submissions = new AssignmentSubmissions(db, assignments, notices, files);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private static Account account(String... roles) {
        return new Account(ACTOR, true, false, Set.of(roles), new CollegeScope(COLLEGE, true));
    }

    private static void assertError(ApiError expected, Executable action) {
        var failure = assertThrows(ApiError.Failure.class, action::run);
        assertEquals(expected, failure.error());
    }

    interface Executable { void run() throws Exception; }

    private static Assignment assignment(String status) {
        return new Assignment(5, "Essay", "", "", NOW.minusDays(1), NOW.plusDays(2), new BigDecimal("20.00"),
                List.of("pdf"), 10_485_760, "NONE", null, "SINGLE", status, null, null, 4, 11, "JAVA101", "Java", 21,
                "Class A", 31, "Semester 1", 41, "BSc CS", 51, "2026-2027", null, "Krishna", null, null, null, NOW, NOW,
                true, false, true, null, null);
    }

    private static State state(String status, String resubmission) {
        return new State(5, status, NOW.minusDays(1), NOW.plusDays(2), new BigDecimal("20.00"), List.of("pdf"),
                10_485_760, "NONE", null, resubmission, 4, null, "Essay");
    }

    private static Submission submission(long id, String status) {
        return new Submission(id, 5, "Asha", "asha@example.invalid", 1, status, false, NOW, "essay.pdf", "pdf", PDF.length,
                "", null, null, null, null);
    }

    @Test void everyRoleIsLimitedToItsOwnOperationsAndCollege() {
        // A student, a College Admin, a Super Admin without a college and a deactivated college are refused faculty work.
        for (Account outsider : List.of(account("STUDENT"), account("COLLEGE_ADMIN"), account("SUPER_ADMIN"),
                new Account(ACTOR, true, false, Set.of("FACULTY"), null),
                new Account(ACTOR, true, false, Set.of("FACULTY"), new CollegeScope(COLLEGE, false)))) {
            assertError(ApiError.ACCESS_DENIED, () -> assignments.facultyList(outsider, new Filter(null, null, null, null, 0)));
            assertError(ApiError.ACCESS_DENIED, () -> assignments.create(outsider, AssignmentRulesTest.valid()));
        }
        assertError(ApiError.ACCESS_DENIED, () -> assignments.studentList(account("FACULTY"), StudentTab.ALL, 0));
        assertError(ApiError.ACCESS_DENIED, () -> assignments.adminList(account("FACULTY"),
                new AdminFilter(null, null, null, null, null, null, null, null, null, null, 0)));
        assertError(ApiError.ACCESS_DENIED, () -> assignments.adminStats(account("SUPER_ADMIN", "ADMIN"),
                new AdminFilter(null, null, null, null, null, null, null, null, null, null, 0)));
        // An unverified student account (AccountAccessPolicy removes its STUDENT role) is asked to verify first.
        assertError(ApiError.VERIFICATION_REQUIRED, () -> assignments.studentList(
                new Account(ACTOR, false, true, Set.of(), new CollegeScope(COLLEGE, true)), StudentTab.ALL, 0));
        assertTrue(db.calls.isEmpty(), "refused requests never reach the database");
    }

    @Test void listsAreScopedFromTheSignedInAccountOnly() {
        assignments.facultyList(account("FACULTY"), new Filter(null, null, null, null, 0));
        var faculty = db.calls("FROM public.assignments a").get(0);
        assertTrue(faculty.sql().contains("a.college_id = ?") && faculty.sql().contains("m.faculty_id = ?")
                && faculty.sql().contains("m.ended_at IS NULL"));
        assertTrue(faculty.args().containsAll(List.of(COLLEGE, ACTOR)));

        db.calls.clear();
        assignments.studentList(account("STUDENT"), StudentTab.PENDING, 0);
        var student = db.calls("FROM public.assignments a").get(0);
        assertTrue(student.sql().contains("a.status <> 'DRAFT'") && student.sql().contains("a.issue_at <= ?")
                && student.sql().contains("e.student_id = ?") && student.sql().contains("e.ended_at IS NULL"));
        // Joined SQL pieces keep a space between them (a live check found "division_idAND" once).
        for (var call : db.calls) assertFalse(call.sql().matches("(?s).*[a-z_]AND .*"), call.sql());
        assertEquals(List.of(ACTOR, COLLEGE, NOW, ACTOR, 30, 0), student.args());

        db.calls.clear();
        assignments.adminList(account("COLLEGE_ADMIN"), new AdminFilter(41L, null, null, null, null, null, null, null, null, null, 0));
        var admin = db.calls("FROM public.assignments a").get(0);
        assertTrue(admin.sql().contains("a.status <> 'DRAFT'") && admin.sql().contains("t.program_id = ?"));
        assertEquals(List.of(COLLEGE, 41L, 30, 0), admin.args());
    }

    @Test void hiddenOrForeignAssignmentsAreNotFound() {
        // The scoped query finds nothing for another college's, another class's or a draft assignment.
        assertError(ApiError.ASSIGNMENT_NOT_FOUND, () -> assignments.detail(account("STUDENT"), CollegeRole.STUDENT, 5));
        assertError(ApiError.ASSIGNMENT_NOT_FOUND, () -> assignments.detail(account("COLLEGE_ADMIN"), CollegeRole.COLLEGE_ADMIN, 5));
        assertError(ApiError.ASSIGNMENT_NOT_FOUND, () -> assignments.publish(account("FACULTY"), 5));
        assertError(ApiError.ASSIGNMENT_NOT_FOUND, () -> assignments.attachment(account("STUDENT"), CollegeRole.STUDENT, 5));
        assertFalse(db.ran("UPDATE"));
    }

    @Test void facultyCanCreateOnlyForTheirOwnActiveClass() {
        assertError(ApiError.ASSIGNMENT_INVALID, () -> assignments.create(account("FACULTY"), AssignmentRulesTest.valid()));
        var lookup = db.calls("faculty_subject_assignments").get(0);
        assertTrue(lookup.sql().contains("faculty_id = ? AND ended_at IS NULL"));
        assertEquals(List.of(COLLEGE, 4L, ACTOR), lookup.args());
        assertFalse(db.ran("INSERT"));

        db.on("WHERE college_id = ? AND id = ? AND faculty_id = ? AND ended_at IS NULL FOR SHARE", List.of(new long[]{21, 11}))
                .on("INSERT INTO public.assignments", 5L)
                .on("FROM public.assignments a", List.of(assignment("DRAFT")));
        Assignment created = assignments.create(account("FACULTY"), AssignmentRulesTest.valid());
        assertEquals("DRAFT", created.status());
        var insert = db.calls("INSERT INTO public.assignments").get(0);
        // College, faculty, division and subject come from the account and the database, not the request.
        assertEquals(List.of(COLLEGE, 4L, ACTOR, 21L, 11L), insert.args().subList(0, 5));
        assertTrue(db.ran("INSERT INTO public.assignment_events"));
        verifyNoInteractions(notices);
    }

    @Test void publishingNotifiesTheClassOnceAndOnlyFromADraft() {
        db.on("FROM public.assignments a", List.of(assignment("DRAFT")))
                .on("FOR UPDATE", List.of(state("DRAFT", "SINGLE")));
        assignments.publish(account("FACULTY"), 5);
        verify(notices).published(COLLEGE, 5);
        assertTrue(db.ran("SET status = 'PUBLISHED'"));

        var again = new ScriptedJdbc().on("FROM public.assignments a", List.of(assignment("PUBLISHED")))
                .on("FOR UPDATE", List.of(state("PUBLISHED", "SINGLE")));
        var second = new Assignments(again, new MaterialFiles(storage.toString()), notices,
                Clock.fixed(NOW.toInstant(), NOW.getOffset()), ZoneId.of("Asia/Kolkata"));
        assertError(ApiError.ASSIGNMENT_STATE_CONFLICT, () -> second.publish(account("FACULTY"), 5));
        verifyNoMoreInteractions(notices);
    }

    @Test void notificationInsertsCannotDuplicate() {
        var sql = new ScriptedJdbc();
        var real = new AssignmentNotices(sql);
        real.published(COLLEGE, 5);
        real.deadlineChanged(COLLEGE, 5, NOW);
        real.evaluated(COLLEGE, 5, 8, 12, 99);
        for (var call : sql.calls) {
            assertTrue(call.sql().contains("ON CONFLICT ON CONSTRAINT uq_assignment_notification_event DO NOTHING"));
        }
        assertEquals("published:5", sql.calls.get(0).args().get(0));
        // Only students enrolled in the assignment's class now receive it.
        assertTrue(sql.calls.get(0).sql().contains("e.ended_at IS NULL") && sql.calls.get(0).sql().contains("e.division_id = t.division_id"));
        // A student may only mark their own notification read.
        assertError(ApiError.NOTIFICATION_NOT_FOUND, () -> new AssignmentNotices(new ScriptedJdbc()
                .on("UPDATE public.assignment_notifications", 0).on("SELECT EXISTS", false)).markRead(account("STUDENT"), 40));
    }

    @Test void studentsOutsideTheClassCannotSubmit() {
        db.on("SELECT EXISTS", false);
        var file = new MockMultipartFile("file", "essay.pdf", "application/pdf", PDF);
        assertError(ApiError.ASSIGNMENT_NOT_FOUND, () -> submissions.submit(account("STUDENT"), 5, file, null));
        var check = db.calls("SELECT EXISTS").get(0);
        assertTrue(check.sql().contains("e.student_id = ?") && check.sql().contains("a.status <> 'DRAFT'"));
        assertEquals(List.of(ACTOR, COLLEGE, 5L), check.args());
        assertFalse(db.ran("INSERT"));
    }

    private void enrolled(State state, Current current) {
        db.on("SELECT EXISTS", true)
                .on("FOR SHARE", List.of(state))
                .on("AND status <> 'SUPERSEDED' FOR UPDATE", current == null ? List.of() : List.of(current))
                .on("coalesce(max(attempt), 0)", current == null ? 0 : current.attempt())
                .on("INSERT INTO public.assignment_submissions", 8L)
                .on("INSERT INTO public.assignment_events", 1L)
                .on("AND x.id = ?", List.of(submission(8, "SUBMITTED")));
    }

    @Test void submissionsAreCheckedForTypeContentAndPolicy() throws Exception {
        enrolled(state("PUBLISHED", "SINGLE"), null);
        // Only the assignment's allowed types, and the content must match the name.
        assertError(ApiError.SUBMISSION_FILE_INVALID, () -> submissions.submit(account("STUDENT"), 5,
                new MockMultipartFile("file", "photo.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'}), null));
        assertError(ApiError.SUBMISSION_FILE_INVALID, () -> submissions.submit(account("STUDENT"), 5,
                new MockMultipartFile("file", "essay.pdf", "application/pdf", "MZ not a pdf".getBytes()), null));
        assertError(ApiError.SUBMISSION_FILE_INVALID, () -> submissions.submit(account("STUDENT"), 5,
                new MockMultipartFile("file", "run.exe", "application/pdf", PDF), null));
        assertFalse(db.ran("INSERT INTO public.assignment_submissions"));

        Submission saved = submissions.submit(account("STUDENT"), 5,
                new MockMultipartFile("file", "../../essay.pdf", "application/pdf", PDF), " done ");
        assertEquals(8, saved.id());
        var insert = db.calls("INSERT INTO public.assignment_submissions").get(0);
        // Student from the account, first attempt, safe file name, server-side key, on time.
        assertEquals(List.of(COLLEGE, 5L, ACTOR, 1, "essay.pdf"), insert.args().subList(0, 5));
        assertInstanceOf(UUID.class, insert.args().get(5));
        assertEquals("done", insert.args().get(10));
        assertEquals(false, insert.args().get(12));
        try (var stored = Files.list(storage)) {
            assertEquals(1, stored.count(), "the file is kept in private storage under a random name");
        }
    }

    @Test void singleSubmissionPolicyRefusesASecondFileButAcceptsARetryOfTheSameFile() throws Exception {
        String sha = MaterialFiles.sha256(PDF);
        enrolled(state("PUBLISHED", "SINGLE"), new Current(8, "SUBMITTED", 1, sha, ACTOR, null, null));
        Submission retry = submissions.submit(account("STUDENT"), 5, new MockMultipartFile("file", "essay.pdf", "application/pdf", PDF), null);
        assertEquals(8, retry.id());
        byte[] other = "%PDF-1.4\n2 0 obj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        assertError(ApiError.SUBMISSION_CONFLICT, () -> submissions.submit(account("STUDENT"), 5,
                new MockMultipartFile("file", "essay2.pdf", "application/pdf", other), null));
        assertFalse(db.ran("INSERT INTO public.assignment_submissions"));
        assertFalse(db.ran("SET status = 'SUPERSEDED'"));
    }

    @Test void resubmissionKeepsTheEarlierAttempt() throws Exception {
        enrolled(state("PUBLISHED", "UNTIL_DEADLINE"), new Current(8, "SUBMITTED", 1, "0".repeat(64), ACTOR, null, null));
        submissions.submit(account("STUDENT"), 5, new MockMultipartFile("file", "essay.pdf", "application/pdf", PDF), null);
        var superseded = db.calls("SET status = 'SUPERSEDED'").get(0);
        assertEquals(List.of(COLLEGE, 8L), superseded.args());
        assertEquals(2, db.calls("INSERT INTO public.assignment_submissions").get(0).args().get(3));
        assertFalse(db.ran("DELETE"));
    }

    @Test void marksAreValidatedRecordedOnceAndNotifyTheRightStudent() {
        db.on("FROM public.assignments a", List.of(assignment("PUBLISHED")))
                .on("FROM public.assignments WHERE college_id = ? AND id = ?", List.of(state("PUBLISHED", "SINGLE")))
                .on("AND id = ? FOR UPDATE", List.of(new Current(8, "SUBMITTED", 1, "0".repeat(64), 12L, null, null)))
                .on("INSERT INTO public.assignment_events", 99L)
                .on("AND x.id = ?", List.of(submission(8, "EVALUATED")));
        assertError(ApiError.EVALUATION_INVALID, () -> submissions.evaluate(account("FACULTY"), 5, 8, new Evaluation(new BigDecimal("21"), null)));
        assertError(ApiError.EVALUATION_INVALID, () -> submissions.evaluate(account("FACULTY"), 5, 8, new Evaluation(new BigDecimal("-1"), null)));
        assertFalse(db.ran("SET status = 'EVALUATED'"));
        submissions.evaluate(account("FACULTY"), 5, 8, new Evaluation(new BigDecimal("18.5"), "Well argued"));
        var update = db.calls("SET status = 'EVALUATED'").get(0);
        assertEquals(List.of(new BigDecimal("18.50"), "Well argued", ACTOR, COLLEGE, 8L), update.args());
        // The student to notify comes from the stored submission (12), never from the request.
        verify(notices).evaluated(COLLEGE, 5, 8, 12, 99);
        // Students and College Admins cannot mark.
        assertError(ApiError.ACCESS_DENIED, () -> submissions.evaluate(account("STUDENT"), 5, 8, new Evaluation(BigDecimal.ONE, null)));
        assertError(ApiError.ACCESS_DENIED, () -> submissions.evaluate(account("COLLEGE_ADMIN"), 5, 8, new Evaluation(BigDecimal.ONE, null)));
    }

    @Test void markingTheSameValuesAgainChangesNothing() {
        db.on("FROM public.assignments a", List.of(assignment("PUBLISHED")))
                .on("FROM public.assignments WHERE college_id = ? AND id = ?", List.of(state("PUBLISHED", "SINGLE")))
                .on("AND id = ? FOR UPDATE", List.of(new Current(8, "EVALUATED", 1, "0".repeat(64), 12L,
                        new BigDecimal("18.50"), "Well argued")))
                .on("AND x.id = ?", List.of(submission(8, "EVALUATED")));
        submissions.evaluate(account("FACULTY"), 5, 8, new Evaluation(new BigDecimal("18.5"), " Well argued "));
        assertFalse(db.ran("UPDATE public.assignment_submissions"));
        verify(notices, never()).evaluated(anyLong(), anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test void filesAreOnlyForTheirOwnerOrTheClassFaculty() {
        // A student asking for another student's file finds nothing: the query requires their own id.
        assertError(ApiError.SUBMISSION_NOT_FOUND, () -> submissions.file(account("STUDENT"), CollegeRole.STUDENT, 5, 8));
        var lookup = db.calls("SELECT storage_key").get(0);
        assertTrue(lookup.sql().endsWith("AND student_id = ?"));
        assertEquals(List.of(COLLEGE, 5L, 8L, ACTOR), lookup.args());
        // College Admins monitor statistics only: no submitted files.
        assertError(ApiError.ACCESS_DENIED, () -> submissions.file(account("COLLEGE_ADMIN"), CollegeRole.COLLEGE_ADMIN, 5, 8));
        // Faculty outside the class: the scoped assignment lookup fails first.
        assertError(ApiError.ASSIGNMENT_NOT_FOUND, () -> submissions.file(account("FACULTY"), CollegeRole.FACULTY, 5, 8));
    }

    @Test void collegeAdminHistoryLeavesOutSubmissionsAndMarks() {
        db.on("FROM public.assignments a", List.of(assignment("PUBLISHED")));
        assignments.history(account("COLLEGE_ADMIN"), CollegeRole.COLLEGE_ADMIN, 5);
        assertTrue(db.calls("FROM public.assignment_events").get(0).sql().contains("e.submission_id IS NULL"));
        db.calls.clear();
        assignments.history(account("FACULTY"), CollegeRole.FACULTY, 5);
        assertFalse(db.calls("FROM public.assignment_events").get(0).sql().contains("submission_id IS NULL"));
        assertError(ApiError.ACCESS_DENIED, () -> assignments.history(account("STUDENT"), CollegeRole.STUDENT, 5));
    }

    @Test void publishedDeadlinesOnlyMoveLaterAndTellStudents() {
        db.on("FROM public.assignments a", List.of(assignment("PUBLISHED")))
                .on("FOR UPDATE", List.of(state("PUBLISHED", "SINGLE")))
                .on("marks IS NOT NULL", false)
                .on("SET is_late = false", 2);
        Input later = new Input(null, "Essay", "", "", NOW.minusDays(1), NOW.plusDays(5), new BigDecimal("20"),
                List.of("pdf"), null, null, null, null);
        assignments.update(account("FACULTY"), 5, later);
        verify(notices).deadlineChanged(COLLEGE, 5, NOW.plusDays(5));
        var history = db.calls("INSERT INTO public.assignment_events");
        assertEquals("DEADLINE", history.get(1).args().get(4));
        assertTrue(((String) history.get(1).args().get(5)).contains("2 submission(s) no longer late"));

        Input earlier = new Input(null, "Essay", "", "", NOW.minusDays(1), NOW.plusDays(1), new BigDecimal("20"),
                List.of("pdf"), null, null, null, null);
        assertError(ApiError.ASSIGNMENT_DEADLINE_INVALID, () -> assignments.update(account("FACULTY"), 5, earlier));
        verify(notices, times(1)).deadlineChanged(eq(COLLEGE), eq(5L), org.mockito.ArgumentMatchers.any());
    }
}
