package com.campussphere.backend.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.IntStream;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.AcademicResources.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Academic Resources rules and access. The fake database answers the resource lookup only for college A (and, where a
 * test says so, only for the enrolled student or the uploading faculty member), as the real WHERE clauses do. The
 * negative tests prove another college, an unenrolled student or another faculty member gets "not found" and that
 * nothing is written or served for them.
 */
class AcademicResourcesTest {
    static final long A = 10L, B = 20L;
    static final long PROF = 50L, PROF_B = 60L, STUDENT = 7L, OTHER_STUDENT = 8L, ADMIN = 1L, ADMIN_B = 2L;
    static final long SUBJECT = 300L, ASSIGNMENT = 900L, DIVISION = 31L, ID = 5000L;
    static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-28T10:00:00+05:30");
    static final byte[] PDF = "%PDF-1.4\n1 0 obj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final String SYLLABUS_SELECT = "FROM public.syllabus_versions r";
    static final String PAPER_SELECT = "FROM public.question_papers r";
    static final String TEACHING = "SELECT id, division_id FROM public.faculty_subject_assignments";

    @TempDir Path storage;
    private final ScriptedJdbc db = new ScriptedJdbc();
    private AcademicResources resources;

    @BeforeEach void setUp() {
        resources = new AcademicResources(db, new MaterialFiles(storage.toString()));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    static Account account(long id, long college, String role) {
        return new Account(id, true, false, Set.of(role), new CollegeScope(college, true));
    }

    static long n(Object v) { return ((Number) v).longValue(); }

    static Entry entry(String kind, String status, boolean canEdit) {
        return new Entry(ID, kind, SUBJECT, "CS101", "Data Structures", "BSc CS", "Semester 1", "2026-2027",
                "Synthetic Faculty", "Syllabus", "", kind.equals("SYLLABUS") ? "2026" : null, null, true,
                kind.equals("PAPER") ? "FINAL" : null, null, kind.equals("PAPER") ? 2025 : null, "",
                "file.pdf", PDF.length, status, null, NOW, NOW, canEdit, false, List.of());
    }

    static MockMultipartFile pdf(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/pdf", bytes);
    }

    static void assertError(ApiError expected, Runnable action) {
        var failure = assertThrows(ApiError.Failure.class, action::run);
        assertEquals(expected, failure.error());
    }

    interface IoAction { void run() throws Exception; }

    static void assertIoError(ApiError expected, IoAction action) {
        var failure = assertThrows(ApiError.Failure.class, action::run);
        assertEquals(expected, failure.error());
    }

    /** The resource exists only in college A. */
    private void onlyInCollegeA(String select, Entry found) {
        db.answer(select, args -> n(args.get(0)) == A ? List.of(found) : List.of());
    }

    private void finish(int status) {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) s.afterCompletion(status);
    }

    // ---- Input rules ----

    @Test void syllabusAndPaperDetailsAreChecked() {
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(new SyllabusFields(" ", "", "2026", null)));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(new SyllabusFields("Syllabus", "", " ", null)));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(new SyllabusFields("Syllabus", "", "x".repeat(41), null)));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(new SyllabusFields("Syllabus", "", "2026", LocalDate.of(1980, 1, 1))));
        assertEquals(new SyllabusFields("Syllabus", "", "2026", null), AcademicResources.clean(new SyllabusFields(" Syllabus ", null, " 2026 ", null)));

        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(new PaperFields("OTHER", " ", 2025, "", "Paper", "")));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(new PaperFields("MID1", "Surprise test", 2025, "", "Paper", "")));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(new PaperFields("FINAL", null, 1989, "", "Paper", "")));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(new PaperFields("QUIZ", null, 2025, "", "Paper", "")));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(new PaperFields("FINAL", null, null, "", "Paper", "")));
        assertEquals("Class test", AcademicResources.clean(new PaperFields("OTHER", " Class test ", 2025, "", "Paper", "")).examTypeLabel());
        assertNull(AcademicResources.clean(new PaperFields("FINAL", "", 2025, "May", "Paper", "")).examTypeLabel());
    }

    @Test void unitsMustBeNumberedOnceAndAtMostThirty() {
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean((List<Unit>) null));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(List.of(
                new Unit(1, "Arrays", "", "", "", ""), new Unit(1, "Lists", "", "", "", ""))));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(List.of(new Unit(0, "Arrays", "", "", "", ""))));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(List.of(new Unit(1, " ", "", "", "", ""))));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.clean(
                IntStream.rangeClosed(1, 31).mapToObj(i -> new Unit(i % 30 + 1, "U", "", "", "", "")).toList()));
        var sorted = AcademicResources.clean(List.of(new Unit(2, "Lists", null, null, null, null), new Unit(1, "Arrays", "", "", "", "")));
        assertEquals(List.of(1, 2), sorted.stream().map(Unit::number).toList());
        assertEquals("", sorted.get(1).topics());
    }

    @Test void filtersRejectUnknownValues() {
        assertError(ApiError.RESOURCE_INVALID, () -> new Filter(null, null, null, "QUIZ", null, null, null, 0));
        assertError(ApiError.RESOURCE_INVALID, () -> new Filter(null, null, null, null, "DELETED", null, null, 0));
        assertError(ApiError.RESOURCE_INVALID, () -> new Filter(null, null, 1900, null, null, null, null, 0));
        assertError(ApiError.RESOURCE_INVALID, () -> new Filter(null, null, null, null, null, null, "x".repeat(81), 0));
        assertError(ApiError.RESOURCE_INVALID, () -> new Filter(null, null, null, null, null, null, null, -1));
    }

    @Test void statusChangesFollowTheAllowedPaths() {
        assertEquals("ARCHIVED", AcademicResources.next("ARCHIVE", "PUBLISHED"));
        assertNull(AcademicResources.next("ARCHIVE", "HIDDEN"));
        assertEquals("PUBLISHED", AcademicResources.next("UNARCHIVE", "ARCHIVED"));
        assertEquals("HIDDEN", AcademicResources.next("HIDE", "ARCHIVED"));
        assertNull(AcademicResources.next("HIDE", "REMOVED"));
        assertEquals("PUBLISHED", AcademicResources.next("RESTORE", "HIDDEN"));
        assertNull(AcademicResources.next("RESTORE", "REMOVED"));
        assertEquals("REMOVED", AcademicResources.next("REMOVE", "HIDDEN"));
        assertNull(AcademicResources.next("REMOVE", "REMOVED"));
        assertError(ApiError.RESOURCE_INVALID, () -> AcademicResources.next("DELETE", "PUBLISHED"));
    }

    // ---- Scoping ----

    @Test void everyListIsLimitedToTheAccountsOwnCollegeAndRole() {
        resources.list(account(ADMIN_B, B, "COLLEGE_ADMIN"), CollegeRole.COLLEGE_ADMIN, Kind.PAPER, Filter.none());
        var admin = db.calls(PAPER_SELECT).getLast();
        assertEquals(B, n(admin.args().getFirst()));
        assertFalse(admin.sql().contains("student_enrollments e"));

        resources.list(account(PROF, A, "FACULTY"), CollegeRole.FACULTY, Kind.SYLLABUS, Filter.none());
        var faculty = db.calls(SYLLABUS_SELECT).getLast();
        assertTrue(faculty.sql().contains("r.faculty_id = ?"));
        assertEquals(List.of(A, PROF), faculty.args().subList(0, 2).stream().map(AcademicResourcesTest::n).toList());

        // A student's own filters cannot widen the scope: status and faculty filters are ignored for students.
        resources.list(account(STUDENT, A, "STUDENT"), CollegeRole.STUDENT, Kind.PAPER,
                new Filter(SUBJECT, null, 2025, "FINAL", "HIDDEN", PROF_B, null, 0));
        var student = db.calls(PAPER_SELECT).getLast();
        assertTrue(student.sql().contains("r.status = 'PUBLISHED'"));
        assertTrue(student.sql().contains("e.student_id = ? AND e.program_id = s.program_id AND e.semester_id = s.semester_id"));
        assertFalse(student.sql().contains("r.status = ?"));
        assertFalse(student.sql().contains("r.faculty_id = ?"));
        assertEquals(List.of(A, STUDENT, SUBJECT), student.args().subList(0, 3).stream().map(AcademicResourcesTest::n).toList());
        assertFalse(student.args().contains(PROF_B));
    }

    @Test void anotherCollegeCannotReadDownloadModerateOrSeeHistory() {
        onlyInCollegeA(SYLLABUS_SELECT, entry("SYLLABUS", "PUBLISHED", false));
        var adminB = account(ADMIN_B, B, "COLLEGE_ADMIN");
        var studentB = account(21L, B, "STUDENT");
        assertError(ApiError.RESOURCE_NOT_FOUND, () -> resources.detail(adminB, CollegeRole.COLLEGE_ADMIN, Kind.SYLLABUS, ID));
        assertError(ApiError.RESOURCE_NOT_FOUND, () -> resources.download(adminB, CollegeRole.COLLEGE_ADMIN, Kind.SYLLABUS, ID));
        assertError(ApiError.RESOURCE_NOT_FOUND, () -> resources.download(studentB, CollegeRole.STUDENT, Kind.SYLLABUS, ID));
        assertError(ApiError.RESOURCE_NOT_FOUND, () -> resources.events(adminB, Kind.SYLLABUS, ID));
        assertError(ApiError.RESOURCE_NOT_FOUND, () -> resources.moderate(adminB, Kind.SYLLABUS, ID, new Moderation("HIDE", "Wrong file")));
        assertFalse(db.ran("SELECT storage_key"));
        assertFalse(db.ran("syllabus_units"));
        assertFalse(db.ran("UPDATE public."));
        assertFalse(db.ran("academic_resource_events"));
    }

    @Test void aStudentOnlyGetsResourcesOfSemestersTheyAreOrWereEnrolledIn() throws Exception {
        db.answer(PAPER_SELECT, args -> n(args.get(0)) == A && n(args.get(1)) == STUDENT
                ? List.of(entry("PAPER", "PUBLISHED", false)) : List.of());
        UUID key = UUID.randomUUID();
        Files.write(storage.resolve(key.toString()), PDF);
        db.on("SELECT storage_key", key);

        assertError(ApiError.RESOURCE_NOT_FOUND, () -> resources.detail(account(OTHER_STUDENT, A, "STUDENT"), CollegeRole.STUDENT, Kind.PAPER, ID));
        assertError(ApiError.RESOURCE_NOT_FOUND, () -> resources.download(account(OTHER_STUDENT, A, "STUDENT"), CollegeRole.STUDENT, Kind.PAPER, ID));
        assertFalse(db.ran("SELECT storage_key"));

        var download = resources.download(account(STUDENT, A, "STUDENT"), CollegeRole.STUDENT, Kind.PAPER, ID);
        assertEquals("application/pdf", download.mimeType());
        assertEquals(storage.resolve(key.toString()), download.path());
        assertEquals(A, n(db.calls("SELECT storage_key").getFirst().args().getFirst()));
    }

    @Test void theWrongRoleIsRefusedBeforeAnyQuery() {
        var student = account(STUDENT, A, "STUDENT");
        assertError(ApiError.ACCESS_DENIED, () -> resources.moderate(student, Kind.PAPER, ID, new Moderation("HIDE", "Nope")));
        assertError(ApiError.ACCESS_DENIED, () -> resources.teachableSubjects(student));
        assertError(ApiError.ACCESS_DENIED, () -> resources.coverage(account(PROF, A, "FACULTY"), null));
        assertError(ApiError.ACCESS_DENIED, () -> resources.semesters(account(PROF, A, "FACULTY")));
        assertIoError(ApiError.ACCESS_DENIED, () -> resources.uploadPaper(student, SUBJECT,
                new PaperFields("FINAL", null, 2025, "", "Paper", ""), pdf("p.pdf", PDF)));
        assertTrue(db.calls.isEmpty());
    }

    // ---- Faculty uploads ----

    @Test void facultyCanOnlyUploadForASubjectTheyCurrentlyTeach() {
        var prof = account(PROF, A, "FACULTY");
        var fields = new SyllabusFields("Syllabus", "", "2026", null);
        assertIoError(ApiError.RESOURCE_INVALID, () -> resources.uploadSyllabus(prof, null, fields, pdf("s.pdf", PDF)));
        assertIoError(ApiError.RESOURCE_NOT_FOUND, () -> resources.uploadSyllabus(prof, SUBJECT, fields, pdf("s.pdf", PDF)));
        var lookup = db.calls(TEACHING).getFirst();
        assertTrue(lookup.sql().contains("ended_at IS NULL"));
        assertEquals(List.of(A, PROF, SUBJECT), lookup.args().stream().map(AcademicResourcesTest::n).toList());
        assertFalse(db.ran("INSERT"));
        assertEquals(0, storage.toFile().list().length);
    }

    @Test void onlyARealPdfUpToTenMegabytesIsAccepted() {
        db.on(TEACHING, List.of(new long[]{ASSIGNMENT, DIVISION}));
        var prof = account(PROF, A, "FACULTY");
        var fields = new PaperFields("MID1", null, 2025, "", "Mid-term 1", "");
        assertIoError(ApiError.RESOURCE_FILE_INVALID, () -> resources.uploadPaper(prof, SUBJECT, fields,
                pdf("paper.pdf", "MZ this is a program".getBytes(StandardCharsets.US_ASCII))));
        assertIoError(ApiError.RESOURCE_FILE_INVALID, () -> resources.uploadPaper(prof, SUBJECT, fields,
                new MockMultipartFile("file", "paper.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", PDF)));
        byte[] big = Arrays.copyOf(PDF, MaterialFiles.LIMIT + 1);
        assertIoError(ApiError.RESOURCE_FILE_INVALID, () -> resources.uploadPaper(prof, SUBJECT, fields, pdf("paper.pdf", big)));
        assertIoError(ApiError.RESOURCE_FILE_INVALID, () -> resources.uploadPaper(prof, SUBJECT, fields, pdf("paper.pdf", new byte[0])));
        assertFalse(db.ran("INSERT"));
        assertEquals(0, storage.toFile().list().length);
    }

    @Test void anUploadUsesTheServersCollegeAndAssignmentAndKeepsNoFileIfItFails() throws Exception {
        db.on(TEACHING, List.of(new long[]{ASSIGNMENT, DIVISION}))
          .on("INSERT INTO public.syllabus_versions", ID);
        onlyInCollegeA(SYLLABUS_SELECT, entry("SYLLABUS", "PUBLISHED", true));
        var prof = account(PROF, A, "FACULTY");

        var saved = resources.uploadSyllabus(prof, SUBJECT, new SyllabusFields("BSc CS Data Structures", "", "2026-27", null),
                pdf("syllabus.pdf", PDF));
        assertEquals(ID, saved.id());
        var insert = db.calls("INSERT INTO public.syllabus_versions").getFirst();
        assertEquals(List.of(A, ASSIGNMENT, PROF, DIVISION, SUBJECT),
                insert.args().subList(0, 5).stream().map(AcademicResourcesTest::n).toList());
        var event = db.calls("INSERT INTO public.academic_resource_events").getFirst();
        assertEquals(List.of(A, ID, PROF), event.args().subList(0, 3).stream().map(AcademicResourcesTest::n).toList());
        assertEquals("UPLOAD", event.args().get(3));
        assertEquals(1, storage.toFile().list().length);

        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertEquals(0, storage.toFile().list().length, "a failed transaction leaves no stray file");
    }

    @Test void theSameFileOrVersionLabelTwiceIsADuplicate() {
        db.on(TEACHING, List.of(new long[]{ASSIGNMENT, DIVISION}))
          .answer("INSERT INTO public.syllabus_versions", args -> { throw new DuplicateKeyException("uq_syllabus_version_file"); });
        assertIoError(ApiError.RESOURCE_DUPLICATE, () -> resources.uploadSyllabus(account(PROF, A, "FACULTY"), SUBJECT,
                new SyllabusFields("Syllabus", "", "2026", null), pdf("s.pdf", PDF)));
        assertFalse(db.ran("academic_resource_events"));
        assertEquals(0, storage.toFile().list().length);
    }

    @Test void facultyCannotChangeAnotherUploadOrOneTheyNoLongerTeach() {
        var prof = account(PROF, A, "FACULTY");
        // Someone else's upload is outside the faculty scope: not found.
        assertError(ApiError.RESOURCE_NOT_FOUND, () -> resources.editPaper(prof, ID, new PaperFields("FINAL", null, 2025, "", "Paper", "")));
        // Their own upload after the teaching assignment ended (or hidden by the admin) cannot be edited.
        db.on(PAPER_SELECT, List.of(entry("PAPER", "HIDDEN", false)));
        assertError(ApiError.RESOURCE_STATE_CONFLICT, () -> resources.editPaper(prof, ID, new PaperFields("FINAL", null, 2025, "", "Paper", "")));
        assertError(ApiError.RESOURCE_STATE_CONFLICT, () -> resources.archive(prof, Kind.PAPER, ID, true));
        assertFalse(db.ran("UPDATE public."));
        assertTrue(db.calls(PAPER_SELECT).stream().allMatch(c -> c.sql().contains("r.faculty_id = ?") && c.sql().contains("FOR UPDATE OF r")));
    }

    @Test void replacingTheFileDeletesTheOldOneOnlyAfterCommit() throws Exception {
        UUID old = UUID.randomUUID();
        Files.write(storage.resolve(old.toString()), PDF);
        db.on(SYLLABUS_SELECT, List.of(entry("SYLLABUS", "PUBLISHED", true))).on("SELECT storage_key", old);
        resources.replaceFile(account(PROF, A, "FACULTY"), Kind.SYLLABUS, ID, pdf("syllabus v2.pdf", "%PDF-1.5\n2 0 obj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(2, storage.toFile().list().length);
        assertEquals("REPLACE", db.calls("INSERT INTO public.academic_resource_events").getFirst().args().get(3));
        finish(TransactionSynchronization.STATUS_COMMITTED);
        assertFalse(Files.exists(storage.resolve(old.toString())));
        assertEquals(1, storage.toFile().list().length);
    }

    @Test void unitsAreReplacedForTheOwnCollegeOnly() {
        db.on(SYLLABUS_SELECT, List.of(entry("SYLLABUS", "PUBLISHED", true)));
        resources.saveUnits(account(PROF, A, "FACULTY"), ID, List.of(new Unit(2, "Trees", "", "BST", "", ""),
                new Unit(1, "Arrays", "", "", "", "")));
        assertEquals(List.of(A, ID), db.calls("DELETE FROM public.syllabus_units").getFirst().args().stream().map(AcademicResourcesTest::n).toList());
        var inserts = db.calls("INSERT INTO public.syllabus_units");
        assertEquals(2, inserts.size());
        assertEquals(1, n(inserts.get(0).args().get(2)));
        assertTrue(inserts.stream().allMatch(c -> n(c.args().getFirst()) == A));
        assertEquals("2 unit(s)", db.calls("INSERT INTO public.academic_resource_events").getFirst().args().get(4));
    }

    // ---- College Admin moderation ----

    @Test void moderationNeedsANoteAndAValidState() {
        var admin = account(ADMIN, A, "COLLEGE_ADMIN");
        db.on(PAPER_SELECT, List.of(entry("PAPER", "PUBLISHED", false)));
        assertError(ApiError.RESOURCE_INVALID, () -> resources.moderate(admin, Kind.PAPER, ID, new Moderation("HIDE", " ")));
        assertError(ApiError.RESOURCE_INVALID, () -> resources.moderate(admin, Kind.PAPER, ID, new Moderation("PUBLISH", null)));
        assertError(ApiError.RESOURCE_STATE_CONFLICT, () -> resources.moderate(admin, Kind.PAPER, ID, new Moderation("RESTORE", null)));
        assertFalse(db.ran("UPDATE public."));

        resources.moderate(admin, Kind.PAPER, ID, new Moderation("HIDE", " Wrong subject "));
        var update = db.calls("UPDATE public.question_papers").getFirst();
        assertEquals(List.of("HIDDEN", "Wrong subject"), update.args().subList(0, 2));
        assertEquals(List.of(A, ID), update.args().subList(2, 4).stream().map(AcademicResourcesTest::n).toList());
        var event = db.calls("INSERT INTO public.academic_resource_events").getFirst();
        assertTrue(event.sql().contains("question_paper_id"));
        assertEquals("HIDE", event.args().get(3));
    }
}
