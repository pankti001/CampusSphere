package com.campussphere.backend.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.Timetables.Detail;
import com.campussphere.backend.service.Timetables.Summary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two more ways for a College Admin to create timetables, next to manual entry: an Excel (.xlsx) upload for one
 * semester (one or more divisions) and a copy of an existing timetable. Both always create DRAFT versions and never
 * publish. An upload is first previewed (every row checked against the college's real divisions, subjects, faculty
 * assignments and classrooms, plus faculty, division and classroom clashes); importing repeats every check under the
 * college lock and saves all drafts in one transaction, so an invalid file saves nothing.
 */
@Service
@Transactional(readOnly = true)
public class TimetableImports {
    static final List<String> HEADERS = List.of("Division", "Day", "Start", "End", "Type", "Subject code",
            "Faculty email", "Classroom code", "Label");
    static final int MAX_ROWS = 1000;
    private static final String[] DAYS = {"", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    public record ImportRow(int row, String division, String day, String start, String end, String type,
                            String subjectCode, String facultyEmail, String classroomCode, String label,
                            List<String> errors) {}

    /** A draft the import would create. publishedVersion is the version it would later replace, if any. */
    public record ImportDivision(long divisionId, String division, int periods, int lessons, int newVersion,
                                 Integer publishedVersion) {}

    public record Preview(String fileName, String sha256, int rows, int validRows, int errorRows,
                          List<String> fileErrors, List<ImportDivision> divisions, List<ImportRow> details,
                          boolean canImport) {}

    public record Imported(List<Summary> drafts, int periods) {}

    public record CopyRequest(Long sourceTimetableId, Long semesterId, Long divisionId) {
        public CopyRequest {
            if (sourceTimetableId == null || sourceTimetableId <= 0 || semesterId == null || semesterId <= 0
                    || divisionId == null || divisionId <= 0) throw ApiError.INVALID_REQUEST.exception();
        }
    }

    /** The new draft, how many periods were copied, and why the others were left out. */
    public record CopyResult(Detail timetable, int copied, List<String> skipped) {}

    public record Template(String fileName, byte[] bytes) {}

    private final JdbcTemplate jdbc;
    private final Timetables timetables;

    public TimetableImports(JdbcTemplate jdbc, Timetables timetables) {
        this.jdbc = jdbc;
        this.timetables = timetables;
    }

    // ---- Parsing helpers (pure, unit-tested) ----

    /** Monday … Sunday, Mon … Sun or 1 … 7. */
    static Integer day(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (t.matches("[1-7]")) return Integer.parseInt(t);
        for (int d = 1; d <= 7; d++) {
            String name = DAYS[d].toLowerCase(Locale.ROOT);
            if (t.equals(name) || t.equals(name.substring(0, 3))) return d;
        }
        return null;
    }

    /** "09:00", "9:00", "9:00 AM", "13:30:00" or an Excel time value such as 0.375; null when not a whole minute time. */
    static LocalTime time(String text) {
        String t = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        var clock = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*(AM|PM)?").matcher(t);
        if (clock.matches()) {
            int h = Integer.parseInt(clock.group(1));
            int m = Integer.parseInt(clock.group(2));
            if (clock.group(3) != null && Integer.parseInt(clock.group(3)) != 0) return null;
            if (clock.group(4) != null) {
                if (h < 1 || h > 12) return null;
                h = h % 12 + (clock.group(4).equals("PM") ? 12 : 0);
            }
            return h > 23 || m > 59 ? null : LocalTime.of(h, m);
        }
        if (t.matches("0?\\.\\d+|0")) {
            long minutes = Math.round(Double.parseDouble(t) * 24 * 60);
            return minutes < 0 || minutes >= 24 * 60 ? null : LocalTime.of((int) (minutes / 60), (int) (minutes % 60));
        }
        return null;
    }

    static String kind(String text) {
        String t = text == null ? "" : text.trim().toUpperCase(Locale.ROOT);
        return switch (t) {
            case "", "LESSON", "LECTURE", "CLASS" -> "LESSON";
            case "BREAK" -> "BREAK";
            case "LUNCH", "LUNCH BREAK" -> "LUNCH";
            default -> null;
        };
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String cell(List<String> row, int i) {
        return i < row.size() ? row.get(i).trim() : "";
    }

    static String safeName(String name) {
        String n = name == null ? "timetable.xlsx" : name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return n.isEmpty() ? "timetable.xlsx" : n.length() > 80 ? n.substring(0, 80) : n;
    }

    // ---- Reference data of one semester in one college ----

    record Division(long id, String code, String name) {}
    record Faculty(long id, String name, boolean active) {}
    record Room(long id, String code, boolean active) {}
    record Assignment(long id, long facultyId, long subjectId) {}
    record Versions(int next, Integer draft, Integer published) {}
    record Lesson(long divisionId, int day, LocalTime start, LocalTime end, long facultyId, long classroomId, String with) {}

    record Reference(String label, Map<String, Division> divisions, Set<String> subjects, Map<String, Faculty> faculty,
                     Map<String, Room> rooms, Map<String, Assignment> assignments, Map<Long, Versions> versions,
                     List<Lesson> otherLessons) {}

    private Reference reference(long college, long semesterId) {
        var scope = jdbc.query("""
                SELECT sem.program_id, sem.academic_year_id, p.name || ' · ' || sem.name label
                FROM public.academic_semesters sem
                JOIN public.academic_programs p ON p.college_id=sem.college_id AND p.id=sem.program_id
                WHERE sem.college_id=? AND sem.id=?
                """, (r, i) -> new Object[]{r.getLong(1), r.getLong(2), r.getString(3)}, college, semesterId);
        if (scope.isEmpty()) throw ApiError.TIMETABLE_SCOPE_INVALID.exception();
        var s = scope.getFirst();
        var divisions = new LinkedHashMap<String, Division>();
        jdbc.query("""
                SELECT id, code, name FROM public.academic_divisions
                WHERE college_id=? AND program_id=? AND academic_year_id=? ORDER BY code
                """, rs -> { divisions.put(rs.getString("code").toUpperCase(Locale.ROOT),
                new Division(rs.getLong("id"), rs.getString("code"), rs.getString("name"))); }, college, s[0], s[1]);
        var subjects = new HashSet<String>();
        jdbc.query("SELECT code FROM public.academic_subjects WHERE college_id=? AND semester_id=?",
                rs -> { subjects.add(rs.getString(1).toUpperCase(Locale.ROOT)); }, college, semesterId);
        var faculty = new HashMap<String, Faculty>();
        jdbc.query("""
                SELECT lower(u.email) email, u.id, u.full_name, (u.is_active IS TRUE) active
                FROM public.faculty_profiles f JOIN public.users u ON u.college_id=f.college_id AND u.id=f.faculty_id
                WHERE f.college_id=?
                """, rs -> { faculty.put(rs.getString("email"), new Faculty(rs.getLong("id"), rs.getString("full_name"),
                rs.getBoolean("active"))); }, college);
        var rooms = new HashMap<String, Room>();
        jdbc.query("SELECT id, code, is_active FROM public.classrooms WHERE college_id=?",
                rs -> { rooms.put(rs.getString("code").toUpperCase(Locale.ROOT), new Room(rs.getLong("id"),
                        rs.getString("code"), rs.getBoolean("is_active"))); }, college);
        var assignments = new HashMap<String, Assignment>();
        jdbc.query("""
                SELECT a.id, a.faculty_id, a.subject_id, a.division_id, upper(sub.code) code, lower(u.email) email
                FROM public.faculty_subject_assignments a
                JOIN public.academic_subjects sub ON sub.college_id=a.college_id AND sub.id=a.subject_id
                JOIN public.users u ON u.college_id=a.college_id AND u.id=a.faculty_id
                WHERE a.college_id=? AND a.semester_id=? AND a.ended_at IS NULL
                """, rs -> { assignments.put(rs.getLong("division_id") + "|" + rs.getString("code") + "|" + rs.getString("email"),
                new Assignment(rs.getLong("id"), rs.getLong("faculty_id"), rs.getLong("subject_id"))); }, college, semesterId);
        var versions = new HashMap<Long, Versions>();
        jdbc.query("""
                SELECT division_id, max(version) latest, max(version) FILTER (WHERE status='DRAFT') draft,
                       max(version) FILTER (WHERE status='PUBLISHED') published
                FROM public.timetables WHERE college_id=? AND semester_id=? GROUP BY division_id
                """, rs -> { versions.put(rs.getLong("division_id"), new Versions(rs.getInt("latest") + 1,
                rs.getObject("draft", Integer.class), rs.getObject("published", Integer.class))); }, college, semesterId);
        var lessons = jdbc.query("""
                SELECT e.division_id, e.day_of_week, e.start_time, e.end_time, e.faculty_id, e.classroom_id,
                       p.name || ' · ' || v.name || ' · ' || sub.code with_label
                FROM public.timetable_entries e
                JOIN public.timetables t ON t.college_id=e.college_id AND t.id=e.timetable_id
                     AND t.status IN ('DRAFT','PUBLISHED')
                JOIN public.faculty_subject_assignments a ON a.college_id=e.college_id AND a.id=e.assignment_id
                     AND a.ended_at IS NULL
                JOIN public.academic_divisions v ON v.college_id=e.college_id AND v.id=e.division_id
                JOIN public.academic_programs p ON p.college_id=e.college_id AND p.id=e.program_id
                JOIN public.academic_subjects sub ON sub.college_id=e.college_id AND sub.id=e.subject_id
                WHERE e.college_id=? AND e.kind='LESSON'
                LIMIT 20000
                """, (r, i) -> new Lesson(r.getLong("division_id"), r.getInt("day_of_week"),
                r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class),
                r.getLong("faculty_id"), r.getLong("classroom_id"), r.getString("with_label")), college);
        return new Reference((String) s[2], divisions, subjects, faculty, rooms, assignments, versions, lessons);
    }

    // ---- Checking a file ----

    /** A checked row ready to save. assignment and room are null for breaks. */
    record Checked(int row, Division division, String kind, int day, LocalTime start, LocalTime end,
                   Assignment assignment, Room room, String label) {}

    record Analysis(Preview preview, List<Checked> rows) {}

    /** Checks every row; never writes. Shared by preview and import so both apply exactly the same rules. */
    static Analysis analyze(Reference ref, String fileName, byte[] bytes) {
        String name = safeName(fileName);
        String hash = bytes == null ? "" : sha256(bytes);
        var fileErrors = new ArrayList<String>();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) fileErrors.add("Upload an Excel workbook ending in .xlsx.");
        List<List<String>> sheet;
        try {
            sheet = Xlsx.read(bytes);
        } catch (Xlsx.Invalid e) {
            fileErrors.add(e.getMessage());
            return new Analysis(new Preview(name, hash, 0, 0, 0, fileErrors, List.of(), List.of(), false), List.of());
        }
        if (sheet.isEmpty()) fileErrors.add("The first worksheet is empty. Use the downloaded template.");
        else {
            var header = sheet.getFirst();
            for (int i = 0; i < HEADERS.size(); i++) {
                if (!cell(header, i).equalsIgnoreCase(HEADERS.get(i))) {
                    fileErrors.add("The first row must contain the template headers: " + String.join(", ", HEADERS) + ".");
                    break;
                }
            }
        }
        var data = new ArrayList<Map.Entry<Integer, List<String>>>();
        for (int i = 1; i < sheet.size(); i++) {
            var row = sheet.get(i);
            if (row.stream().anyMatch(c -> !c.isBlank())) data.add(Map.entry(i + 1, row));
        }
        if (fileErrors.isEmpty() && data.isEmpty()) fileErrors.add("The file has no timetable rows below the headers.");
        if (data.size() > MAX_ROWS) fileErrors.add("The file has " + data.size() + " rows; the limit is " + MAX_ROWS + ".");
        if (!fileErrors.isEmpty())
            return new Analysis(new Preview(name, hash, data.size(), 0, data.size(), fileErrors, List.of(), List.of(), false), List.of());

        var details = new ArrayList<ImportRow>();
        var errors = new ArrayList<List<String>>();
        var checked = new ArrayList<Checked>();
        for (var entry : data) {
            var row = entry.getValue();
            var problems = new ArrayList<String>();
            String divisionText = cell(row, 0), dayText = cell(row, 1), startText = cell(row, 2), endText = cell(row, 3);
            String typeText = cell(row, 4), subjectText = cell(row, 5), emailText = cell(row, 6), roomText = cell(row, 7);
            String label = cell(row, 8);
            Division division = ref.divisions().get(divisionText.toUpperCase(Locale.ROOT));
            if (divisionText.isEmpty()) problems.add("Division is required.");
            else if (division == null) problems.add("Division '" + divisionText + "' is not a division of " + ref.label() + ".");
            Integer day = day(dayText);
            if (day == null) problems.add("Day must be Monday to Sunday.");
            LocalTime start = time(startText), end = time(endText);
            if (start == null) problems.add("Start must be a time such as 09:00.");
            if (end == null) problems.add("End must be a time such as 10:00.");
            String kind = kind(typeText);
            if (kind == null) problems.add("Type must be Lesson, Break or Lunch.");
            if (start != null && end != null && kind != null) {
                int minutes = end.toSecondOfDay() / 60 - start.toSecondOfDay() / 60;
                if (minutes <= 0) problems.add("End must be after start.");
                else if (start.getMinute() % 5 != 0 || end.getMinute() % 5 != 0) problems.add("Use 5-minute steps, for example 09:00 or 09:05.");
                else if (kind.equals("LESSON") && minutes > 240) problems.add("A lesson can be at most 4 hours.");
                else if (!kind.equals("LESSON") && minutes > 120) problems.add("A break can be at most 2 hours.");
            }
            if (label.length() > 60) problems.add("Label can be at most 60 characters.");
            Assignment assignment = null;
            Room room = null;
            if ("LESSON".equals(kind)) {
                String code = subjectText.toUpperCase(Locale.ROOT);
                String email = emailText.toLowerCase(Locale.ROOT);
                Faculty faculty = ref.faculty().get(email);
                room = ref.rooms().get(roomText.toUpperCase(Locale.ROOT));
                if (code.isEmpty()) problems.add("Subject code is required for a lesson.");
                else if (!ref.subjects().contains(code)) problems.add("Subject '" + subjectText + "' is not part of " + ref.label() + ".");
                if (email.isEmpty()) problems.add("Faculty email is required for a lesson.");
                else if (faculty == null) problems.add("No faculty member with email " + emailText + " in your college.");
                else if (!faculty.active()) problems.add(faculty.name() + "'s account is inactive.");
                if (roomText.isEmpty()) problems.add("Classroom code is required for a lesson.");
                else if (room == null) problems.add("Classroom '" + roomText + "' does not exist.");
                else if (!room.active()) problems.add("Classroom '" + roomText + "' is inactive.");
                if (division != null && faculty != null && faculty.active() && ref.subjects().contains(code)) {
                    assignment = ref.assignments().get(division.id() + "|" + code + "|" + email);
                    if (assignment == null) problems.add(faculty.name() + " is not assigned to teach " + subjectText
                            + " to division " + division.code() + ".");
                }
            } else if (kind != null && (!subjectText.isEmpty() || !emailText.isEmpty() || !roomText.isEmpty())) {
                problems.add("Leave subject, faculty and classroom empty for a break or lunch.");
            }
            details.add(new ImportRow(entry.getKey(), divisionText, dayText, startText, endText, typeText, subjectText,
                    emailText, roomText, label, problems));
            errors.add(problems);
            if (problems.isEmpty()) checked.add(new Checked(entry.getKey(), division, kind, day, start, end, assignment,
                    room, label.isEmpty() ? null : label));
        }

        // Clashes inside the file.
        var indexOfRow = new HashMap<Integer, Integer>();
        for (int i = 0; i < details.size(); i++) indexOfRow.put(details.get(i).row(), i);
        for (int i = 0; i < checked.size(); i++) {
            for (int j = i + 1; j < checked.size(); j++) {
                var a = checked.get(i);
                var b = checked.get(j);
                if (a.day() != b.day() || !Timetables.overlaps(a.start(), a.end(), b.start(), b.end())) continue;
                var ea = errors.get(indexOfRow.get(a.row()));
                var eb = errors.get(indexOfRow.get(b.row()));
                if (a.division().id() == b.division().id()) {
                    ea.add("Overlaps row " + b.row() + " of the same division.");
                    eb.add("Overlaps row " + a.row() + " of the same division.");
                    continue;
                }
                if (a.assignment() == null || b.assignment() == null) continue;
                if (a.assignment().facultyId() == b.assignment().facultyId()) {
                    ea.add("The same faculty member also teaches row " + b.row() + " at this time.");
                    eb.add("The same faculty member also teaches row " + a.row() + " at this time.");
                }
                if (a.room().id() == b.room().id()) {
                    ea.add("Classroom also used in row " + b.row() + " at this time.");
                    eb.add("Classroom also used in row " + a.row() + " at this time.");
                }
            }
        }
        // Clashes with other divisions' current timetables (the divisions in this file are being replaced).
        var fileDivisions = new HashSet<Long>();
        checked.forEach(c -> fileDivisions.add(c.division().id()));
        for (var c : checked) {
            if (c.assignment() == null) continue;
            for (var other : ref.otherLessons()) {
                if (fileDivisions.contains(other.divisionId()) || other.day() != c.day()
                        || !Timetables.overlaps(c.start(), c.end(), other.start(), other.end())) continue;
                var e = errors.get(indexOfRow.get(c.row()));
                String when = DAYS[other.day()].substring(0, 3) + " " + other.start() + "–" + other.end();
                if (other.facultyId() == c.assignment().facultyId())
                    e.add("The faculty member already teaches " + other.with() + " (" + when + ").");
                if (other.classroomId() == c.room().id())
                    e.add("The classroom is already used by " + other.with() + " (" + when + ").");
            }
        }

        // Divisions that would get a new draft.
        var byDivision = new LinkedHashMap<Long, List<Checked>>();
        checked.forEach(c -> byDivision.computeIfAbsent(c.division().id(), k -> new ArrayList<>()).add(c));
        var divisions = new ArrayList<ImportDivision>();
        for (var group : byDivision.values()) {
            var d = group.getFirst().division();
            var v = ref.versions().getOrDefault(d.id(), new Versions(1, null, null));
            if (v.draft() != null) fileErrors.add("Division " + d.code() + " already has a draft (version " + v.draft()
                    + "). Publish or discard that draft before importing.");
            divisions.add(new ImportDivision(d.id(), d.name(), group.size(),
                    (int) group.stream().filter(c -> c.kind().equals("LESSON")).count(), v.next(), v.published()));
        }
        int errorRows = (int) errors.stream().filter(e -> !e.isEmpty()).count();
        var finalDetails = new ArrayList<ImportRow>();
        for (int i = 0; i < details.size(); i++) {
            var d = details.get(i);
            finalDetails.add(new ImportRow(d.row(), d.division(), d.day(), d.start(), d.end(), d.type(), d.subjectCode(),
                    d.facultyEmail(), d.classroomCode(), d.label(), List.copyOf(errors.get(i))));
        }
        boolean lessons = checked.stream().anyMatch(c -> c.kind().equals("LESSON"));
        if (errorRows == 0 && !lessons) fileErrors.add("The file has no lessons.");
        boolean ok = fileErrors.isEmpty() && errorRows == 0;
        return new Analysis(new Preview(name, hash, details.size(), details.size() - errorRows, errorRows, fileErrors,
                divisions, finalDetails, ok), ok ? checked : List.of());
    }

    // ---- Endpoints ----

    public Preview preview(Account account, long semesterId, String fileName, byte[] bytes) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        return analyze(reference(college, semesterId), fileName, bytes).preview();
    }

    /** Saves the previewed file as drafts, or nothing if anything is wrong. */
    @Transactional
    public Imported importFile(Account account, long semesterId, String fileName, byte[] bytes, String previewedSha256) {
        long college = timetables.writableCollege(account);
        var analysis = analyze(reference(college, semesterId), fileName, bytes);
        if (previewedSha256 == null || !analysis.preview().sha256().equalsIgnoreCase(previewedSha256.trim()))
            throw ApiError.TIMETABLE_IMPORT_CHANGED.exception();
        if (!analysis.preview().canImport()) throw ApiError.TIMETABLE_IMPORT_INVALID.exception();
        var byDivision = new LinkedHashMap<Long, List<Checked>>();
        analysis.rows().forEach(c -> byDivision.computeIfAbsent(c.division().id(), k -> new ArrayList<>()).add(c));
        var drafts = new ArrayList<Summary>();
        for (var group : byDivision.entrySet()) {
            if (timetables.hasDraft(college, semesterId, group.getKey())) throw ApiError.TIMETABLE_DRAFT_EXISTS.exception();
            long id = timetables.insertDraft(college, semesterId, group.getKey(), account.id());
            for (var c : group.getValue()) {
                timetables.insertEntry(college, id, c.kind(), c.day(), c.start(), c.end(),
                        c.assignment() == null ? null : c.assignment().id(),
                        c.assignment() == null ? null : c.assignment().facultyId(),
                        c.assignment() == null ? null : c.assignment().subjectId(),
                        c.room() == null ? null : c.room().id(), c.label(), account.id());
            }
            var summary = timetables.summary(college, id);
            timetables.event(college, id, account.id(), "CREATED", null, "Version " + summary.version()
                    + " imported from " + analysis.preview().fileName() + " (" + group.getValue().size()
                    + " periods). Not published.");
            drafts.add(summary);
        }
        return new Imported(drafts, analysis.rows().size());
    }

    /** The template workbook; with a semester it also lists that semester's real divisions, assignments and rooms. */
    public Template template(Account account, Long semesterId) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var sheets = new ArrayList<Xlsx.Sheet>();
        sheets.add(new Xlsx.Sheet("Timetable", List.of(HEADERS)));
        sheets.add(new Xlsx.Sheet("Instructions", List.of(
                List.of("How to fill in the Timetable sheet"),
                List.of("One row per period. Keep the first row (the headers) exactly as it is."),
                List.of("Division: the division code, for example A. See the Reference sheet."),
                List.of("Day: Monday to Sunday (Mon to Sun also works)."),
                List.of("Start and End: 24-hour times such as 09:00 and 10:00, in 5-minute steps."),
                List.of("Type: Lesson, Break or Lunch. Leave it empty for a lesson."),
                List.of("A lesson needs Subject code, Faculty email and Classroom code. The faculty member must be assigned to that subject and division."),
                List.of("A break or lunch leaves Subject code, Faculty email and Classroom code empty."),
                List.of("Label is optional, for example Lab or Short break."),
                List.of("Uploading creates DRAFT timetables only. Nothing is published until you publish each draft."),
                List.of("A division that already has a draft cannot be imported. Publish or discard that draft first."),
                List.of("Limits: .xlsx only, up to 2 MB and 1000 rows. Workbooks with macros are refused."))));
        String fileName = "campussphere-timetable-template.xlsx";
        if (semesterId != null) {
            var ref = reference(college, semesterId);
            var rows = new ArrayList<List<String>>();
            rows.add(List.of("Reference for " + ref.label()));
            rows.add(List.of());
            rows.add(List.of("Division code", "Division name"));
            ref.divisions().values().forEach(d -> rows.add(List.of(d.code(), d.name())));
            rows.add(List.of());
            rows.add(List.of("Division code", "Subject code", "Subject", "Faculty email", "Faculty name"));
            rows.addAll(jdbc.query("""
                    SELECT v.code division, sub.code subject_code, sub.name subject, u.email, u.full_name
                    FROM public.faculty_subject_assignments a
                    JOIN public.academic_divisions v ON v.college_id=a.college_id AND v.id=a.division_id
                    JOIN public.academic_subjects sub ON sub.college_id=a.college_id AND sub.id=a.subject_id
                    JOIN public.users u ON u.college_id=a.college_id AND u.id=a.faculty_id AND u.is_active IS TRUE
                    WHERE a.college_id=? AND a.semester_id=? AND a.ended_at IS NULL
                    ORDER BY v.code, sub.code, u.full_name LIMIT 2000
                    """, (r, i) -> List.of(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5)),
                    college, semesterId));
            rows.add(List.of());
            rows.add(List.of("Classroom code", "Classroom name", "Seats"));
            rows.addAll(jdbc.query("""
                    SELECT code, name, COALESCE(capacity::text, '') FROM public.classrooms
                    WHERE college_id=? AND is_active ORDER BY code LIMIT 500
                    """, (r, i) -> List.of(r.getString(1), r.getString(2), r.getString(3)), college));
            sheets.add(new Xlsx.Sheet("Reference", rows));
            fileName = "campussphere-timetable-" + ref.label().replaceAll("[^A-Za-z0-9]+", "-").toLowerCase(Locale.ROOT)
                    .replaceAll("^-|-$", "") + ".xlsx";
        }
        return new Template(fileName, Xlsx.write(sheets));
    }

    /**
     * Copies another timetable of this college (for example last semester's) into a new draft for a division and
     * semester. Each lesson is matched to a current assignment of the target class with the same subject code —
     * preferably the same faculty member. Lessons without a matching assignment, with an inactive classroom or that
     * would clash are left out and listed, so the admin can add them by hand.
     */
    @Transactional
    public CopyResult copy(Account account, CopyRequest request) {
        long college = timetables.writableCollege(account);
        var source = jdbc.query("""
                SELECT t.version, p.name || ' · ' || sem.name || ' · ' || v.name label FROM public.timetables t
                JOIN public.academic_programs p ON p.college_id=t.college_id AND p.id=t.program_id
                JOIN public.academic_semesters sem ON sem.college_id=t.college_id AND sem.id=t.semester_id
                JOIN public.academic_divisions v ON v.college_id=t.college_id AND v.id=t.division_id
                WHERE t.college_id=? AND t.id=?
                """, (r, i) -> new Object[]{r.getInt(1), r.getString(2)}, college, request.sourceTimetableId());
        if (source.isEmpty()) throw ApiError.TIMETABLE_NOT_FOUND.exception();
        if (jdbc.queryForList("""
                SELECT sem.id FROM public.academic_semesters sem
                JOIN public.academic_divisions v ON v.college_id=sem.college_id AND v.program_id=sem.program_id
                     AND v.academic_year_id=sem.academic_year_id
                WHERE sem.college_id=? AND sem.id=? AND v.id=?
                """, Long.class, college, request.semesterId(), request.divisionId()).isEmpty())
            throw ApiError.TIMETABLE_SCOPE_INVALID.exception();
        if (timetables.hasDraft(college, request.semesterId(), request.divisionId()))
            throw ApiError.TIMETABLE_DRAFT_EXISTS.exception();

        record SourceEntry(String kind, int day, LocalTime start, LocalTime end, Long facultyId, String subjectCode,
                           Long classroomId, String classroomCode, boolean roomActive, String label) {}
        var entries = jdbc.query("""
                SELECT e.kind, e.day_of_week, e.start_time, e.end_time, e.faculty_id, sub.code subject_code,
                       e.classroom_id, c.code classroom_code, (c.is_active IS TRUE) room_active, e.label
                FROM public.timetable_entries e
                LEFT JOIN public.academic_subjects sub ON sub.college_id=e.college_id AND sub.id=e.subject_id
                LEFT JOIN public.classrooms c ON c.college_id=e.college_id AND c.id=e.classroom_id
                WHERE e.college_id=? AND e.timetable_id=?
                ORDER BY e.day_of_week, e.start_time, e.id
                """, (r, i) -> new SourceEntry(r.getString("kind"), r.getInt("day_of_week"),
                r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class),
                r.getObject("faculty_id", Long.class), r.getString("subject_code"), r.getObject("classroom_id", Long.class),
                r.getString("classroom_code"), r.getBoolean("room_active"), r.getString("label")),
                college, request.sourceTimetableId());
        record Target(long id, long facultyId, long subjectId, String code) {}
        var targets = jdbc.query("""
                SELECT a.id, a.faculty_id, a.subject_id, upper(sub.code) code FROM public.faculty_subject_assignments a
                JOIN public.academic_subjects sub ON sub.college_id=a.college_id AND sub.id=a.subject_id
                JOIN public.users u ON u.college_id=a.college_id AND u.id=a.faculty_id AND u.is_active IS TRUE
                WHERE a.college_id=? AND a.semester_id=? AND a.division_id=? AND a.ended_at IS NULL
                """, (r, i) -> new Target(r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4)),
                college, request.semesterId(), request.divisionId());

        long id = timetables.insertDraft(college, request.semesterId(), request.divisionId(), account.id());
        int copied = 0;
        var skipped = new ArrayList<String>();
        for (var e : entries) {
            String when = DAYS[e.day()].substring(0, 3) + " " + e.start() + "–" + e.end();
            Target target = null;
            if (e.kind().equals("LESSON")) {
                String code = e.subjectCode() == null ? "" : e.subjectCode().toUpperCase(Locale.ROOT);
                var matches = targets.stream().filter(t -> t.code().equals(code)).toList();
                target = matches.stream().filter(t -> Objects.equals(t.facultyId(), e.facultyId())).findFirst()
                        .orElse(matches.size() == 1 ? matches.getFirst() : null);
                if (target == null) {
                    skipped.add(when + " " + e.subjectCode() + ": " + (matches.isEmpty()
                            ? "no faculty member teaches this subject to the new class."
                            : "several faculty members teach it; add it by hand."));
                    continue;
                }
                if (!e.roomActive()) {
                    skipped.add(when + " " + e.subjectCode() + ": classroom " + e.classroomCode() + " is inactive.");
                    continue;
                }
            }
            var clashes = timetables.findClashes(college, id, request.divisionId(), e.day(), e.start(), e.end(),
                    target == null ? null : target.facultyId(), target == null ? null : e.classroomId(), -1);
            if (!clashes.isEmpty()) {
                var c = clashes.getFirst();
                skipped.add(when + " " + (e.subjectCode() == null ? e.kind().toLowerCase(Locale.ROOT) : e.subjectCode())
                        + ": clashes with " + c.with() + " (" + c.type().toLowerCase(Locale.ROOT) + ").");
                continue;
            }
            timetables.insertEntry(college, id, e.kind(), e.day(), e.start(), e.end(),
                    target == null ? null : target.id(), target == null ? null : target.facultyId(),
                    target == null ? null : target.subjectId(), target == null ? null : e.classroomId(), e.label(),
                    account.id());
            copied++;
        }
        timetables.touchDraft(college, id);
        var summary = timetables.summary(college, id);
        timetables.event(college, id, account.id(), "CREATED", null, "Version " + summary.version() + " copied from "
                + source.getFirst()[1] + " version " + source.getFirst()[0] + ": " + copied + " periods copied, "
                + skipped.size() + " left out. Not published.");
        return new CopyResult(timetables.detail(college, id), copied, skipped);
    }
}
