package com.campussphere.backend.service;

import java.time.LocalTime;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.TimetableImports.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TimetableImportRulesTest {
    private static final List<String> HEADER = TimetableImports.HEADERS;

    /** College data for "BSc · Semester 1": divisions A and B, Java and Python, Krishna and Meera, rooms 101 and 203. */
    private static Reference reference(Map<Long, Versions> versions, List<Lesson> others) {
        var divisions = new LinkedHashMap<String, Division>();
        divisions.put("A", new Division(31, "A", "Class A"));
        divisions.put("B", new Division(32, "B", "Class B"));
        var faculty = Map.of("krishna@college.test", new Faculty(61, "Krishna", true),
                "meera@college.test", new Faculty(62, "Meera", true), "old@college.test", new Faculty(63, "Old", false));
        var rooms = Map.of("101", new Room(81, "101", true), "203", new Room(82, "203", true), "999", new Room(83, "999", false));
        var assignments = Map.of(
                "31|JAVA|krishna@college.test", new Assignment(501, 61, 71),
                "32|PY|krishna@college.test", new Assignment(502, 61, 72),
                "32|JAVA|meera@college.test", new Assignment(503, 62, 71));
        return new Reference("BSc · Semester 1", divisions, Set.of("JAVA", "PY"), faculty, rooms, assignments, versions, others);
    }

    private static Reference reference() {
        return reference(Map.of(), List.of());
    }

    private static byte[] file(List<String>... rows) {
        var all = new ArrayList<List<String>>();
        all.add(HEADER);
        all.addAll(Arrays.asList(rows));
        return Xlsx.write(List.of(new Xlsx.Sheet("Timetable", all)));
    }

    private static List<String> row(String division, String day, String start, String end, String type, String subject,
                                    String email, String room) {
        return List.of(division, day, start, end, type, subject, email, room, "");
    }

    private static final List<String> KRISHNA_A = row("A", "Monday", "09:00", "10:00", "", "JAVA", "krishna@college.test", "101");
    private static final List<String> KRISHNA_B = row("B", "Mon", "10:00", "11:00", "Lesson", "py", "Krishna@College.test", "203");

    @Test void dayTimeAndTypeAcceptCommonSpellings() {
        assertEquals(1, TimetableImports.day("Monday"));
        assertEquals(3, TimetableImports.day(" wed "));
        assertEquals(7, TimetableImports.day("7"));
        assertNull(TimetableImports.day("Funday"));
        assertEquals(LocalTime.of(9, 0), TimetableImports.time("9:00"));
        assertEquals(LocalTime.of(13, 30), TimetableImports.time("1:30 PM"));
        assertEquals(LocalTime.of(0, 15), TimetableImports.time("12:15 am"));
        assertEquals(LocalTime.of(9, 0), TimetableImports.time("0.375"));  // Excel stores 09:00 as 0.375 of a day
        assertEquals(LocalTime.of(14, 0), TimetableImports.time("14:00:00"));
        assertNull(TimetableImports.time("09:00:30"));
        assertNull(TimetableImports.time("25:00"));
        assertNull(TimetableImports.time("nine"));
        assertEquals("LESSON", TimetableImports.kind(""));
        assertEquals("LUNCH", TimetableImports.kind("lunch"));
        assertNull(TimetableImports.kind("Party"));
    }

    @Test void validFileGivesAPreviewPerDivision() {
        var analysis = TimetableImports.analyze(reference(Map.of(32L, new Versions(3, null, 2)), List.of()), "week.xlsx",
                file(KRISHNA_A, KRISHNA_B, row("A", "Monday", "10:00", "10:15", "Break", "", "", "")));
        var p = analysis.preview();
        assertTrue(p.canImport(), () -> p.toString());
        assertEquals(3, p.validRows());
        assertEquals(0, p.errorRows());
        assertEquals(64, p.sha256().length());
        assertEquals(List.of(new ImportDivision(31, "Class A", 2, 1, 1, null), new ImportDivision(32, "Class B", 1, 1, 3, 2)),
                p.divisions());
        assertEquals(3, analysis.rows().size());
        assertEquals(501, analysis.rows().getFirst().assignment().id());
    }

    @Test void everyFieldIsCheckedAgainstTheCollegeRecords() {
        var p = TimetableImports.analyze(reference(), "week.xlsx", file(
                row("Z", "Monday", "09:00", "10:00", "", "JAVA", "krishna@college.test", "101"),
                row("A", "Moonday", "9", "10:00", "", "JAVA", "krishna@college.test", "101"),
                row("A", "Tuesday", "09:00", "08:00", "", "JAVA", "krishna@college.test", "101"),
                row("A", "Tuesday", "11:00", "12:00", "", "CHEM", "nobody@college.test", "404"),
                row("A", "Tuesday", "12:00", "13:00", "", "JAVA", "old@college.test", "999"),
                row("A", "Tuesday", "13:00", "14:00", "", "JAVA", "meera@college.test", "101"),
                row("A", "Tuesday", "14:00", "14:15", "Break", "JAVA", "", ""))).preview();
        assertFalse(p.canImport());
        assertEquals(7, p.errorRows());
        var errors = p.details().stream().map(ImportRow::errors).toList();
        assertTrue(errors.get(0).contains("Division 'Z' is not a division of BSc · Semester 1."));
        assertTrue(errors.get(1).contains("Day must be Monday to Sunday."));
        assertTrue(errors.get(1).contains("Start must be a time such as 09:00."));
        assertTrue(errors.get(2).contains("End must be after start."));
        assertTrue(errors.get(3).contains("Subject 'CHEM' is not part of BSc · Semester 1."));
        assertTrue(errors.get(3).contains("No faculty member with email nobody@college.test in your college."));
        assertTrue(errors.get(3).contains("Classroom '404' does not exist."));
        assertTrue(errors.get(4).contains("Old's account is inactive."));
        assertTrue(errors.get(4).contains("Classroom '999' is inactive."));
        assertTrue(errors.get(5).contains("Meera is not assigned to teach JAVA to division A."));
        assertTrue(errors.get(6).contains("Leave subject, faculty and classroom empty for a break or lunch."));
        assertEquals(2, p.details().getFirst().row()); // spreadsheet row numbers, header is row 1
    }

    @Test void clashesInsideTheFileAreReportedOnBothRows() {
        var p = TimetableImports.analyze(reference(), "week.xlsx", file(
                KRISHNA_A,
                row("A", "Monday", "09:30", "10:30", "Lunch", "", "", ""),                                    // same division
                row("B", "Monday", "09:00", "10:00", "", "PY", "krishna@college.test", "203"),                // same faculty
                row("B", "Monday", "11:00", "12:00", "", "JAVA", "meera@college.test", "203"),
                row("A", "Monday", "11:00", "12:00", "", "JAVA", "krishna@college.test", "203"))).preview(); // same room
        var errors = p.details().stream().map(ImportRow::errors).toList();
        assertTrue(errors.get(0).contains("Overlaps row 3 of the same division."));
        assertTrue(errors.get(1).contains("Overlaps row 2 of the same division."));
        assertTrue(errors.get(0).contains("The same faculty member also teaches row 4 at this time."));
        assertTrue(errors.get(2).contains("The same faculty member also teaches row 2 at this time."));
        assertTrue(errors.get(3).contains("Classroom also used in row 6 at this time."));
        assertTrue(errors.get(4).contains("Classroom also used in row 5 at this time."));
        assertFalse(p.canImport());
    }

    @Test void clashesWithOtherDivisionsCurrentTimetablesAreReported() {
        var others = List.of(new Lesson(33, 1, LocalTime.of(9, 30), LocalTime.of(10, 30), 61, 90, "BSc · C · JAVA"),
                new Lesson(31, 1, LocalTime.of(9, 0), LocalTime.of(10, 0), 61, 81, "BSc · A · JAVA")); // division A is being replaced
        var p = TimetableImports.analyze(reference(Map.of(), others), "week.xlsx", file(KRISHNA_A)).preview();
        assertEquals(List.of("The faculty member already teaches BSc · C · JAVA (Mon 09:30–10:30)."), p.details().getFirst().errors());
    }

    @Test void fileLevelProblemsStopTheImport() {
        assertFalse(TimetableImports.analyze(reference(), "week.csv", file(KRISHNA_A)).preview().canImport());
        var wrongHeader = Xlsx.write(List.of(new Xlsx.Sheet("Sheet1", List.of(List.of("Class", "Day")))));
        assertTrue(TimetableImports.analyze(reference(), "x.xlsx", wrongHeader).preview().fileErrors().getFirst()
                .startsWith("The first row must contain the template headers"));
        assertTrue(TimetableImports.analyze(reference(), "x.xlsx", file()).preview().fileErrors()
                .contains("The file has no timetable rows below the headers."));
        assertEquals("This is not an Excel .xlsx file. Save it as Excel Workbook (.xlsx) and try again.",
                TimetableImports.analyze(reference(), "x.xlsx", "a,b,c,d".getBytes()).preview().fileErrors().getFirst());
        var many = new ArrayList<List<String>>();
        for (int i = 0; i < 1001; i++) many.add(KRISHNA_A);
        assertTrue(TimetableImports.analyze(reference(), "x.xlsx", file(many.toArray(List[]::new))).preview()
                .fileErrors().contains("The file has 1001 rows; the limit is 1000."));
        var draft = TimetableImports.analyze(reference(Map.of(31L, new Versions(4, 3, 2)), List.of()), "x.xlsx", file(KRISHNA_A)).preview();
        assertFalse(draft.canImport());
        assertTrue(draft.fileErrors().getFirst().startsWith("Division A already has a draft (version 3)."));
        var breaksOnly = TimetableImports.analyze(reference(), "x.xlsx", file(row("A", "Monday", "10:00", "10:15", "Break", "", "", ""))).preview();
        assertTrue(breaksOnly.fileErrors().contains("The file has no lessons."));
    }

    // ---- Service: roles, college lock and the confirmation hash ----

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final Timetables timetables = new Timetables(db, mock(FacultyNotifications.class));
    private final TimetableImports service = new TimetableImports(db, timetables);

    private static Account account(String role) {
        return new Account(7L, true, false, Set.of(role), new CollegeScope(3L, true));
    }

    @Test void onlyCollegeAdminsCanUseImportAndCopy() {
        for (var role : List.of("STUDENT", "FACULTY", "SUPER_ADMIN")) {
            var actor = account(role);
            var failure = assertThrows(ApiError.Failure.class, () -> service.preview(actor, 21, "x.xlsx", file(KRISHNA_A)));
            assertEquals(ApiError.ACCESS_DENIED, failure.error());
            assertThrows(ApiError.Failure.class, () -> service.importFile(actor, 21, "x.xlsx", file(KRISHNA_A), "x"));
            assertThrows(ApiError.Failure.class, () -> service.copy(actor, new CopyRequest(1L, 21L, 31L)));
            assertThrows(ApiError.Failure.class, () -> service.template(actor, null));
        }
        verifyNoInteractions(db);
    }

    @Test void importRefusesAFileThatDiffersFromThePreviewAndSavesNothing() {
        when(db.queryForList(contains("FROM public.colleges"), eq(Long.class), eq(3L))).thenReturn(List.of(3L));
        when(db.query(contains("sem.program_id, sem.academic_year_id"), any(RowMapper.class), eq(3L), eq(21L)))
                .thenReturn(List.<Object[]>of(new Object[]{3L, 1L, "BSc · Semester 1"}));
        var failure = assertThrows(ApiError.Failure.class,
                () -> service.importFile(account("COLLEGE_ADMIN"), 21, "x.xlsx", file(KRISHNA_A), "0".repeat(64)));
        assertEquals(ApiError.TIMETABLE_IMPORT_CHANGED, failure.error());
        verify(db).query(contains("pg_advisory_xact_lock(671540"), any(org.springframework.jdbc.core.ResultSetExtractor.class), eq(3L));
        verify(db, never()).queryForObject(contains("INSERT"), eq(Long.class), any(Object[].class));
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test void importWithErrorsIsRefusedEvenWithTheRightHash() {
        when(db.queryForList(contains("FROM public.colleges"), eq(Long.class), eq(3L))).thenReturn(List.of(3L));
        when(db.query(contains("sem.program_id, sem.academic_year_id"), any(RowMapper.class), eq(3L), eq(21L)))
                .thenReturn(List.<Object[]>of(new Object[]{3L, 1L, "BSc · Semester 1"}));
        byte[] bytes = file(KRISHNA_A); // the mocked college has no divisions, so every row is invalid
        var failure = assertThrows(ApiError.Failure.class,
                () -> service.importFile(account("COLLEGE_ADMIN"), 21, "x.xlsx", bytes, TimetableImports.sha256(bytes)));
        assertEquals(ApiError.TIMETABLE_IMPORT_INVALID, failure.error());
        verify(db, never()).queryForObject(contains("INSERT"), eq(Long.class), any(Object[].class));
    }

    @Test void templateHasTheExactHeaders() throws Exception {
        var template = service.template(account("COLLEGE_ADMIN"), null);
        assertEquals("campussphere-timetable-template.xlsx", template.fileName());
        assertEquals(HEADER, Xlsx.read(template.bytes()).getFirst());
    }
}
