package com.campussphere.backend.service;

import java.time.*;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * College timetables, versioned per division and semester.
 *
 * <p>Rules: the college always comes from the signed-in account. Only a College Admin writes. Entries change only
 * while their timetable is a DRAFT; a published timetable is changed by starting a new draft version (a copy of
 * the published one) and publishing it, which archives the previous version. Faculty see their own lessons in
 * published timetables; students see the published timetable of their current enrollment.
 *
 * <p>Concurrency: every write takes a per-college transaction lock first, so two admins cannot save overlapping
 * faculty or classroom bookings at the same moment. Conflict checks run after the lock, on committed data.
 */
@Service
@Transactional(readOnly = true)
public class Timetables {
    static final int PAGE_SIZE = 50;
    static final long LOCK_NAMESPACE = 671540;
    static final ZoneId COLLEGE_ZONE = ZoneId.of("Asia/Kolkata");
    static final Set<String> KINDS = Set.of("LESSON", "BREAK", "LUNCH");
    static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
    private static final String[] DAYS = {"", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    // ---- Requests ----

    /** One period. Lessons need an assignment (subject + faculty of this division) and a classroom; breaks need neither. */
    public record EntryInput(String kind, Integer day, LocalTime start, LocalTime end, Long assignmentId,
                             Long classroomId, String label) {
        public EntryInput clean() {
            String k = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
            if (!KINDS.contains(k) || day == null || day < 1 || day > 7 || start == null || end == null) throw invalid();
            if (start.getSecond() != 0 || start.getNano() != 0 || end.getSecond() != 0 || end.getNano() != 0
                    || start.getMinute() % 5 != 0 || end.getMinute() % 5 != 0 || !end.isAfter(start)) throw invalid();
            long minutes = Duration.between(start, end).toMinutes();
            boolean lesson = k.equals("LESSON");
            if (minutes < 5 || minutes > (lesson ? 240 : 120)) throw invalid();
            String text = label == null || label.isBlank() ? null : label.trim();
            if (text != null && text.length() > 60) throw invalid();
            if (lesson ? assignmentId == null || assignmentId <= 0 || classroomId == null || classroomId <= 0
                       : assignmentId != null || classroomId != null) throw invalid();
            return new EntryInput(k, day, start, end, assignmentId, classroomId, text);
        }

        private static RuntimeException invalid() {
            return ApiError.TIMETABLE_ENTRY_INVALID.exception();
        }
    }

    public record Create(Long semesterId, Long divisionId) {
        public Create {
            if (semesterId == null || semesterId <= 0 || divisionId == null || divisionId <= 0)
                throw ApiError.TIMETABLE_SCOPE_INVALID.exception();
        }
    }

    /** The revision the admin previewed; publishing fails if the draft changed since. */
    public record Publish(Long revision) {
        public Publish {
            if (revision == null || revision < 0) throw ApiError.INVALID_REQUEST.exception();
        }
    }

    public record Filter(Long academicYearId, Long departmentId, Long programId, Long semesterId, Long divisionId,
                         String status, int page) {
        public Filter {
            for (Long id : new Long[]{academicYearId, departmentId, programId, semesterId, divisionId})
                if (id != null && id <= 0) throw ApiError.INVALID_REQUEST.exception();
            status = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
            if (status != null && !STATUSES.contains(status)) throw ApiError.INVALID_REQUEST.exception();
            if (page < 0 || page > 10_000) throw ApiError.INVALID_REQUEST.exception();
        }
    }

    public record ClassroomInput(String code, String name, Integer capacity, Boolean active) {
        public ClassroomInput clean() {
            String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
            String n = name == null ? "" : name.trim();
            if (!c.matches("[A-Z0-9][A-Z0-9_-]{0,29}") || n.isEmpty() || n.length() > 150
                    || (capacity != null && (capacity < 1 || capacity > 2000)))
                throw ApiError.CLASSROOM_INVALID.exception();
            return new ClassroomInput(c, n, capacity, active == null || active);
        }
    }

    // ---- Responses ----

    public record ScopeOption(long semesterId, long divisionId, long academicYearId, String academicYear,
                              long departmentId, String department, long programId, String program,
                              int semesterNumber, String semester, String division) {}

    public record Summary(long id, int version, String status, long revision, long semesterId, long divisionId,
                          String academicYear, String department, String program, String semester, String division,
                          int lessons, int breaks, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                          OffsetDateTime publishedAt, OffsetDateTime archivedAt) {}

    public record Entry(long id, String kind, int day, LocalTime start, LocalTime end, Long assignmentId,
                        String subjectCode, String subject, Long facultyId, String faculty, Long classroomId,
                        String classroomCode, String classroom, String label) {}

    /** Something that blocks publication. type: EMPTY, ASSIGNMENT_ENDED, CLASSROOM_INACTIVE, FACULTY_CONFLICT, CLASSROOM_CONFLICT. */
    public record Problem(String type, Long entryId, String detail) {}

    /** notified is the number of faculty notifications a publish or unpublish just created (otherwise 0). */
    public record Detail(Summary timetable, List<Entry> entries, List<Problem> problems, boolean ready, int notified) {
        public Detail(Summary timetable, List<Entry> entries, List<Problem> problems, boolean ready) {
            this(timetable, entries, problems, ready, 0);
        }

        Detail withNotified(int count) {
            return new Detail(timetable, entries, problems, ready, count);
        }
    }

    public record AssignmentOption(long id, String subjectCode, String subject, long facultyId, String faculty) {}

    public record Classroom(long id, String code, String name, Integer capacity, boolean active) {}

    public record Options(List<AssignmentOption> assignments, List<Classroom> classrooms) {}

    /** A rejected save: type DIVISION, FACULTY or CLASSROOM; the times and name of what it overlaps. */
    public record Clash(String type, int day, LocalTime start, LocalTime end, String with) {}

    public record Event(long id, long timetableId, int version, String action, String detail, String actor,
                        OffsetDateTime at) {}

    public record Slot(long id, String kind, int day, LocalTime start, LocalTime end, String subjectCode,
                       String subject, String faculty, String classroomCode, String classroom, String label,
                       String division, String program, String semester, int version, OffsetDateTime publishedAt) {}

    /**
     * A personal timetable. For students, enrolled is false when they have no current enrollment; slots are
     * empty when their division has no published timetable yet. For faculty, enrolled is always true.
     */
    public record Schedule(boolean enrolled, String title, Integer version, OffsetDateTime publishedAt, List<Slot> slots) {}

    public record Day(LocalDate date, int day, Schedule schedule) {}

    /** A rejection that carries details for the app: overlapping bookings or problems blocking publication. */
    public static final class Rejected extends RuntimeException {
        private final ApiError error;
        private final List<Clash> clashes;
        private final List<Problem> problems;

        public Rejected(ApiError error, List<Clash> clashes, List<Problem> problems) {
            super(error.body().message());
            this.error = error;
            this.clashes = List.copyOf(clashes);
            this.problems = List.copyOf(problems);
        }

        public ApiError error() { return error; }
        public List<Clash> clashes() { return clashes; }
        public List<Problem> problems() { return problems; }
    }

    // ---- Conflict detection (pure, unit-tested) ----

    /** An existing period near the candidate. sameTimetable means it belongs to the timetable being edited. */
    record Booked(long entryId, boolean sameTimetable, String kind, int day, LocalTime start, LocalTime end,
                  Long facultyId, Long classroomId, String with) {}

    record Candidate(int day, LocalTime start, LocalTime end, Long facultyId, Long classroomId) {}

    static boolean overlaps(LocalTime aStart, LocalTime aEnd, LocalTime bStart, LocalTime bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /**
     * Any period of the same timetable that overlaps is a division clash (two subjects, or a lesson in a break).
     * A lesson of another division clashes when it uses the same faculty member or the same classroom.
     */
    static List<Clash> clashes(Candidate c, List<Booked> booked) {
        var found = new ArrayList<Clash>();
        for (var b : booked) {
            if (b.day() != c.day() || !overlaps(c.start(), c.end(), b.start(), b.end())) continue;
            if (b.sameTimetable()) {
                found.add(new Clash("DIVISION", b.day(), b.start(), b.end(), b.with()));
                continue;
            }
            if (!"LESSON".equals(b.kind())) continue;
            if (c.facultyId() != null && c.facultyId().equals(b.facultyId()))
                found.add(new Clash("FACULTY", b.day(), b.start(), b.end(), b.with()));
            if (c.classroomId() != null && c.classroomId().equals(b.classroomId()))
                found.add(new Clash("CLASSROOM", b.day(), b.start(), b.end(), b.with()));
        }
        return found;
    }

    static String when(int day, LocalTime start, LocalTime end) {
        return DAYS[day] + " " + start + "–" + end;
    }

    // ---- SQL ----

    private static final String SUMMARY = """
            SELECT t.id, t.version, t.status, t.revision, t.semester_id, t.division_id, y.code academic_year,
                   d.name department, p.name program, sem.name semester, v.name division,
                   (SELECT count(*) FROM public.timetable_entries e
                    WHERE e.college_id=t.college_id AND e.timetable_id=t.id AND e.kind='LESSON') lessons,
                   (SELECT count(*) FROM public.timetable_entries e
                    WHERE e.college_id=t.college_id AND e.timetable_id=t.id AND e.kind<>'LESSON') breaks,
                   t.created_at, t.updated_at, t.published_at, t.archived_at
            FROM public.timetables t
            JOIN public.academic_years y ON y.college_id=t.college_id AND y.id=t.academic_year_id
            JOIN public.departments d ON d.college_id=t.college_id AND d.id=t.department_id
            JOIN public.academic_programs p ON p.college_id=t.college_id AND p.id=t.program_id
            JOIN public.academic_semesters sem ON sem.college_id=t.college_id AND sem.id=t.semester_id
            JOIN public.academic_divisions v ON v.college_id=t.college_id AND v.id=t.division_id
            """;
    static final RowMapper<Summary> SUMMARY_ROW = (r, i) -> new Summary(r.getLong("id"), r.getInt("version"),
            r.getString("status"), r.getLong("revision"), r.getLong("semester_id"), r.getLong("division_id"),
            r.getString("academic_year"), r.getString("department"), r.getString("program"), r.getString("semester"),
            r.getString("division"), r.getInt("lessons"), r.getInt("breaks"),
            r.getObject("created_at", OffsetDateTime.class), r.getObject("updated_at", OffsetDateTime.class),
            r.getObject("published_at", OffsetDateTime.class), r.getObject("archived_at", OffsetDateTime.class));

    private static final String ENTRIES = """
            SELECT e.id, e.kind, e.day_of_week, e.start_time, e.end_time, e.assignment_id, sub.code subject_code,
                   sub.name subject, e.faculty_id, u.full_name faculty, e.classroom_id, c.code classroom_code,
                   c.name classroom, e.label
            FROM public.timetable_entries e
            LEFT JOIN public.academic_subjects sub ON sub.college_id=e.college_id AND sub.id=e.subject_id
            LEFT JOIN public.users u ON u.college_id=e.college_id AND u.id=e.faculty_id
            LEFT JOIN public.classrooms c ON c.college_id=e.college_id AND c.id=e.classroom_id
            WHERE e.college_id=? AND e.timetable_id=?
            ORDER BY e.day_of_week, e.start_time, e.id
            """;
    static final RowMapper<Entry> ENTRY_ROW = (r, i) -> new Entry(r.getLong("id"), r.getString("kind"),
            r.getInt("day_of_week"), r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class),
            r.getObject("assignment_id", Long.class), r.getString("subject_code"), r.getString("subject"),
            r.getObject("faculty_id", Long.class), r.getString("faculty"), r.getObject("classroom_id", Long.class),
            r.getString("classroom_code"), r.getString("classroom"), r.getString("label"));

    /**
     * Existing periods that could clash with a candidate: everything in the edited timetable, plus lessons in other
     * divisions' current draft or published timetables that use the same faculty member or classroom and whose
     * assignment is still active. Archived versions never count. Other semesters of the same division are not
     * compared because a division follows one semester at a time.
     */
    static final String BOOKED = """
            SELECT e.id, (e.timetable_id=?) same_timetable, e.kind, e.day_of_week, e.start_time, e.end_time,
                   e.faculty_id, e.classroom_id,
                   CASE WHEN e.kind='LESSON' THEN p.name || ' · ' || v.name || ' · ' || sub.code
                        ELSE COALESCE(e.label, initcap(e.kind)) END with_label
            FROM public.timetable_entries e
            JOIN public.timetables t ON t.college_id=e.college_id AND t.id=e.timetable_id
            JOIN public.academic_divisions v ON v.college_id=e.college_id AND v.id=e.division_id
            JOIN public.academic_programs p ON p.college_id=e.college_id AND p.id=e.program_id
            LEFT JOIN public.academic_subjects sub ON sub.college_id=e.college_id AND sub.id=e.subject_id
            LEFT JOIN public.faculty_subject_assignments a ON a.college_id=e.college_id AND a.id=e.assignment_id
            WHERE e.college_id=? AND e.day_of_week=? AND e.start_time<? AND e.end_time>? AND e.id<>?
              AND (e.timetable_id=?
                   OR (t.status IN ('DRAFT','PUBLISHED') AND t.division_id<>? AND e.kind='LESSON'
                       AND a.ended_at IS NULL AND (e.faculty_id=? OR e.classroom_id=?)))
            ORDER BY e.start_time, e.id LIMIT 20
            """;
    private static final RowMapper<Booked> BOOKED_ROW = (r, i) -> new Booked(r.getLong("id"),
            r.getBoolean("same_timetable"), r.getString("kind"), r.getInt("day_of_week"),
            r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class),
            r.getObject("faculty_id", Long.class), r.getObject("classroom_id", Long.class), r.getString("with_label"));

    private static final String SLOTS = """
            SELECT e.id, e.kind, e.day_of_week, e.start_time, e.end_time, sub.code subject_code, sub.name subject,
                   u.full_name faculty, c.code classroom_code, c.name classroom, e.label, v.name division,
                   p.name program, sem.name semester, t.version, t.published_at
            FROM public.timetable_entries e
            JOIN public.timetables t ON t.college_id=e.college_id AND t.id=e.timetable_id AND t.status='PUBLISHED'
            JOIN public.academic_divisions v ON v.college_id=e.college_id AND v.id=e.division_id
            JOIN public.academic_programs p ON p.college_id=e.college_id AND p.id=e.program_id
            JOIN public.academic_semesters sem ON sem.college_id=e.college_id AND sem.id=e.semester_id
            LEFT JOIN public.academic_subjects sub ON sub.college_id=e.college_id AND sub.id=e.subject_id
            LEFT JOIN public.users u ON u.college_id=e.college_id AND u.id=e.faculty_id
            LEFT JOIN public.classrooms c ON c.college_id=e.college_id AND c.id=e.classroom_id
            """;
    private static final RowMapper<Slot> SLOT_ROW = (r, i) -> new Slot(r.getLong("id"), r.getString("kind"),
            r.getInt("day_of_week"), r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class),
            r.getString("subject_code"), r.getString("subject"), r.getString("faculty"), r.getString("classroom_code"),
            r.getString("classroom"), r.getString("label"), r.getString("division"), r.getString("program"),
            r.getString("semester"), r.getInt("version"), r.getObject("published_at", OffsetDateTime.class));

    private static final RowMapper<Classroom> CLASSROOM_ROW = (r, i) -> new Classroom(r.getLong("id"),
            r.getString("code"), r.getString("name"), r.getObject("capacity", Integer.class), r.getBoolean("is_active"));

    private final JdbcTemplate jdbc;
    private final FacultyNotifications notifications;

    public Timetables(JdbcTemplate jdbc, FacultyNotifications notifications) {
        this.notifications = notifications;
        this.jdbc = jdbc;
    }

    // ---- College Admin: reading ----

    public List<ScopeOption> scopes(Account account) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        return jdbc.query("""
                SELECT sem.id semester_id, v.id division_id, y.id year_id, y.code academic_year, d.id department_id,
                       d.name department, p.id program_id, p.name program, sem.semester_number, sem.name semester,
                       v.name division
                FROM public.academic_semesters sem
                JOIN public.academic_divisions v ON v.college_id=sem.college_id AND v.program_id=sem.program_id
                     AND v.academic_year_id=sem.academic_year_id
                JOIN public.academic_programs p ON p.college_id=sem.college_id AND p.id=sem.program_id
                JOIN public.departments d ON d.college_id=p.college_id AND d.id=p.department_id
                JOIN public.academic_years y ON y.college_id=sem.college_id AND y.id=sem.academic_year_id
                WHERE sem.college_id=?
                ORDER BY y.code DESC, d.name, p.name, sem.semester_number, v.code, v.id
                LIMIT 2000
                """, (r, i) -> new ScopeOption(r.getLong("semester_id"), r.getLong("division_id"), r.getLong("year_id"),
                r.getString("academic_year"), r.getLong("department_id"), r.getString("department"), r.getLong("program_id"),
                r.getString("program"), r.getInt("semester_number"), r.getString("semester"), r.getString("division")),
                college);
    }

    public List<Summary> list(Account account, Filter filter) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var sql = new StringBuilder(SUMMARY).append(" WHERE t.college_id=?");
        var args = new ArrayList<Object>(List.of(college));
        addFilter(sql, args, "t.academic_year_id", filter.academicYearId());
        addFilter(sql, args, "t.department_id", filter.departmentId());
        addFilter(sql, args, "t.program_id", filter.programId());
        addFilter(sql, args, "t.semester_id", filter.semesterId());
        addFilter(sql, args, "t.division_id", filter.divisionId());
        addFilter(sql, args, "t.status", filter.status());
        sql.append(" ORDER BY y.code DESC, d.name, p.name, sem.semester_number, v.code, t.version DESC LIMIT ? OFFSET ?");
        args.add(PAGE_SIZE);
        args.add(filter.page() * PAGE_SIZE);
        return jdbc.query(sql.toString(), SUMMARY_ROW, args.toArray());
    }

    private static void addFilter(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append("=?");
        args.add(value);
    }

    public Detail detail(Account account, long id) {
        return detail(account.requireCollege(CollegeRole.COLLEGE_ADMIN, null), id);
    }

    /** The published timetable of one division and semester. */
    public Detail published(Account account, long semesterId, long divisionId) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var id = jdbc.queryForList("""
                SELECT id FROM public.timetables
                WHERE college_id=? AND semester_id=? AND division_id=? AND status='PUBLISHED'
                """, Long.class, college, semesterId, divisionId);
        if (id.isEmpty()) throw ApiError.TIMETABLE_NOT_FOUND.exception();
        return detail(college, id.getFirst());
    }

    /** Lessons and classrooms the admin may choose from for this timetable's division and semester. */
    public Options options(Account account, long id) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var t = summary(college, id);
        var assignments = jdbc.query("""
                SELECT a.id, sub.code subject_code, sub.name subject, a.faculty_id, u.full_name faculty
                FROM public.faculty_subject_assignments a
                JOIN public.academic_subjects sub ON sub.college_id=a.college_id AND sub.id=a.subject_id
                JOIN public.users u ON u.college_id=a.college_id AND u.id=a.faculty_id AND u.is_active IS TRUE
                WHERE a.college_id=? AND a.semester_id=? AND a.division_id=? AND a.ended_at IS NULL
                ORDER BY sub.code, u.full_name, a.id
                """, (r, i) -> new AssignmentOption(r.getLong("id"), r.getString("subject_code"), r.getString("subject"),
                r.getLong("faculty_id"), r.getString("faculty")), college, t.semesterId(), t.divisionId());
        var rooms = jdbc.query("""
                SELECT id, code, name, capacity, is_active FROM public.classrooms
                WHERE college_id=? AND is_active ORDER BY code LIMIT 500
                """, CLASSROOM_ROW, college);
        return new Options(assignments, rooms);
    }

    /** Change history of every version of this timetable's division and semester, newest first. */
    public List<Event> history(Account account, long id, int page) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        if (page < 0 || page > 10_000) throw ApiError.INVALID_REQUEST.exception();
        var t = summary(college, id);
        return jdbc.query("""
                SELECT ev.id, ev.timetable_id, t.version, ev.action, ev.detail, u.full_name actor, ev.created_at
                FROM public.timetable_events ev
                JOIN public.timetables t ON t.college_id=ev.college_id AND t.id=ev.timetable_id
                JOIN public.users u ON u.college_id=ev.college_id AND u.id=ev.actor_id
                WHERE ev.college_id=? AND t.semester_id=? AND t.division_id=?
                ORDER BY ev.id DESC LIMIT ? OFFSET ?
                """, (r, i) -> new Event(r.getLong("id"), r.getLong("timetable_id"), r.getInt("version"),
                r.getString("action"), r.getString("detail"), r.getString("actor"),
                r.getObject("created_at", OffsetDateTime.class)),
                college, t.semesterId(), t.divisionId(), PAGE_SIZE, page * PAGE_SIZE);
    }

    /** A faculty member's published weekly timetable, as the admin sees it. */
    public Schedule facultySchedule(Account account, long facultyId) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var name = jdbc.queryForList("""
                SELECT u.full_name FROM public.faculty_profiles f
                JOIN public.users u ON u.college_id=f.college_id AND u.id=f.faculty_id
                WHERE f.college_id=? AND f.faculty_id=?
                """, String.class, college, facultyId);
        if (name.isEmpty()) throw ApiError.TIMETABLE_NOT_FOUND.exception();
        return teaching(college, facultyId, null, name.getFirst());
    }

    public List<Classroom> classrooms(Account account) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        return jdbc.query("""
                SELECT id, code, name, capacity, is_active FROM public.classrooms
                WHERE college_id=? ORDER BY is_active DESC, code LIMIT 500
                """, CLASSROOM_ROW, college);
    }

    // ---- College Admin: writing ----

    @Transactional
    public Classroom createClassroom(Account account, ClassroomInput raw) {
        var in = raw.clean();
        long college = writableCollege(account);
        var id = jdbc.queryForList("""
                INSERT INTO public.classrooms (college_id, code, name, capacity, is_active) VALUES (?,?,?,?,?)
                ON CONFLICT ON CONSTRAINT uq_classroom_college_code DO NOTHING RETURNING id
                """, Long.class, college, in.code(), in.name(), in.capacity(), in.active());
        if (id.isEmpty()) throw ApiError.CLASSROOM_CODE_TAKEN.exception();
        return new Classroom(id.getFirst(), in.code(), in.name(), in.capacity(), in.active());
    }

    @Transactional
    public Classroom updateClassroom(Account account, long id, ClassroomInput raw) {
        var in = raw.clean();
        long college = writableCollege(account);
        int changed;
        try {
            changed = jdbc.update("""
                    UPDATE public.classrooms SET code=?, name=?, capacity=?, is_active=? WHERE college_id=? AND id=?
                    """, in.code(), in.name(), in.capacity(), in.active(), college, id);
        } catch (DuplicateKeyException e) {
            throw ApiError.CLASSROOM_CODE_TAKEN.exception();
        }
        if (changed == 0) throw ApiError.CLASSROOM_NOT_FOUND.exception();
        return new Classroom(id, in.code(), in.name(), in.capacity(), in.active());
    }

    /** Starts a new draft version; when a published version exists, the draft starts as its copy. */
    @Transactional
    public Detail create(Account account, Create input) {
        long college = writableCollege(account);
        var scope = jdbc.query("""
                SELECT p.department_id, sem.program_id, sem.academic_year_id
                FROM public.academic_semesters sem
                JOIN public.academic_divisions v ON v.college_id=sem.college_id AND v.program_id=sem.program_id
                     AND v.academic_year_id=sem.academic_year_id
                JOIN public.academic_programs p ON p.college_id=sem.college_id AND p.id=sem.program_id
                WHERE sem.college_id=? AND sem.id=? AND v.id=?
                """, (r, i) -> new long[]{r.getLong(1), r.getLong(2), r.getLong(3)},
                college, input.semesterId(), input.divisionId());
        if (scope.isEmpty()) throw ApiError.TIMETABLE_SCOPE_INVALID.exception();
        var current = jdbc.query("""
                SELECT id, version, status FROM public.timetables
                WHERE college_id=? AND semester_id=? AND division_id=? AND status<>'ARCHIVED' FOR UPDATE
                """, (r, i) -> new Object[]{r.getLong("id"), r.getInt("version"), r.getString("status")},
                college, input.semesterId(), input.divisionId());
        if (current.stream().anyMatch(row -> "DRAFT".equals(row[2]))) throw ApiError.TIMETABLE_DRAFT_EXISTS.exception();
        int version = jdbc.queryForObject("""
                SELECT COALESCE(max(version), 0) + 1 FROM public.timetables
                WHERE college_id=? AND semester_id=? AND division_id=?
                """, Integer.class, college, input.semesterId(), input.divisionId());
        long[] s = scope.getFirst();
        long id = jdbc.queryForObject("""
                INSERT INTO public.timetables (college_id, department_id, program_id, academic_year_id, semester_id,
                    division_id, version, created_by)
                VALUES (?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, college, s[0], s[1], s[2], input.semesterId(), input.divisionId(), version, account.id());
        var published = current.stream().filter(row -> "PUBLISHED".equals(row[2])).findFirst();
        String detail = "Version " + version + " started as an empty draft.";
        if (published.isPresent()) {
            int copied = jdbc.update("""
                    INSERT INTO public.timetable_entries (college_id, timetable_id, department_id, program_id,
                        academic_year_id, semester_id, division_id, kind, day_of_week, start_time, end_time,
                        assignment_id, faculty_id, subject_id, classroom_id, label, updated_by)
                    SELECT college_id, CAST(? AS bigint), department_id, program_id, academic_year_id, semester_id,
                        division_id, kind, day_of_week, start_time, end_time, assignment_id, faculty_id, subject_id,
                        classroom_id, label, CAST(? AS bigint)
                    FROM public.timetable_entries WHERE college_id=? AND timetable_id=?
                    """, id, account.id(), college, published.get()[0]);
            detail = "Version " + version + " started from published version " + published.get()[1]
                    + " (" + copied + " periods copied).";
        }
        event(college, id, account.id(), "CREATED", null, detail);
        return detail(college, id);
    }

    @Transactional
    public Detail addEntry(Account account, long timetableId, EntryInput raw) {
        var in = raw.clean();
        long college = writableCollege(account);
        var t = lockDraft(college, timetableId);
        var lesson = lessonParts(college, t, in);
        requireNoClash(college, t, in, lesson, -1);
        long entryId = jdbc.queryForObject("""
                INSERT INTO public.timetable_entries (college_id, timetable_id, department_id, program_id,
                    academic_year_id, semester_id, division_id, kind, day_of_week, start_time, end_time,
                    assignment_id, faculty_id, subject_id, classroom_id, label, updated_by)
                SELECT t.college_id, t.id, t.department_id, t.program_id, t.academic_year_id, t.semester_id,
                    t.division_id, CAST(? AS varchar), CAST(? AS smallint), CAST(? AS time), CAST(? AS time),
                    CAST(? AS bigint), CAST(? AS bigint), CAST(? AS bigint), CAST(? AS bigint), CAST(? AS varchar),
                    CAST(? AS bigint)
                FROM public.timetables t WHERE t.college_id=? AND t.id=?
                RETURNING id
                """, Long.class, in.kind(), in.day(), in.start(), in.end(), in.assignmentId(),
                lesson == null ? null : lesson.facultyId(), lesson == null ? null : lesson.subjectId(),
                in.classroomId(), in.label(), account.id(), college, t.id());
        touch(college, t.id());
        event(college, t.id(), account.id(), "ENTRY_ADDED", entryId, "Added " + describe(in, lesson));
        return detail(college, t.id());
    }

    @Transactional
    public Detail updateEntry(Account account, long timetableId, long entryId, EntryInput raw) {
        var in = raw.clean();
        long college = writableCollege(account);
        var t = lockDraft(college, timetableId);
        String before = describeExisting(college, t.id(), entryId);
        var lesson = lessonParts(college, t, in);
        requireNoClash(college, t, in, lesson, entryId);
        jdbc.update("""
                UPDATE public.timetable_entries SET kind=?, day_of_week=?, start_time=?, end_time=?, assignment_id=?,
                    faculty_id=?, subject_id=?, classroom_id=?, label=?, updated_by=?,
                    updated_at=GREATEST(statement_timestamp(), created_at)
                WHERE college_id=? AND timetable_id=? AND id=?
                """, in.kind(), in.day(), in.start(), in.end(), in.assignmentId(),
                lesson == null ? null : lesson.facultyId(), lesson == null ? null : lesson.subjectId(),
                in.classroomId(), in.label(), account.id(), college, t.id(), entryId);
        touch(college, t.id());
        event(college, t.id(), account.id(), "ENTRY_CHANGED", entryId, "Changed " + before + " → " + describe(in, lesson));
        return detail(college, t.id());
    }

    @Transactional
    public Detail deleteEntry(Account account, long timetableId, long entryId) {
        long college = writableCollege(account);
        var t = lockDraft(college, timetableId);
        String before = describeExisting(college, t.id(), entryId);
        jdbc.update("DELETE FROM public.timetable_entries WHERE college_id=? AND timetable_id=? AND id=?",
                college, t.id(), entryId);
        touch(college, t.id());
        event(college, t.id(), account.id(), "ENTRY_REMOVED", entryId, "Removed " + before);
        return detail(college, t.id());
    }

    /** Publishes a ready draft; the previously published version of the same division is archived first. */
    @Transactional
    public Detail publish(Account account, long id, Publish input) {
        long college = writableCollege(account);
        var t = lockDraft(college, id);
        if (t.revision() != input.revision()) throw ApiError.TIMETABLE_STATE_CONFLICT.exception();
        var problems = problems(college, summary(college, id));
        if (!problems.isEmpty()) throw new Rejected(ApiError.TIMETABLE_NOT_READY, List.of(), problems);
        var previous = jdbc.query("""
                SELECT id, version FROM public.timetables
                WHERE college_id=? AND semester_id=? AND division_id=? AND status='PUBLISHED' FOR UPDATE
                """, (r, i) -> new long[]{r.getLong("id"), r.getInt("version")}, college, t.semesterId(), t.divisionId());
        for (long[] old : previous) {
            jdbc.update("""
                    UPDATE public.timetables SET status='ARCHIVED', archived_by=?, replaced_by=?,
                        archived_at=GREATEST(statement_timestamp(), created_at),
                        updated_at=GREATEST(statement_timestamp(), updated_at)
                    WHERE college_id=? AND id=?
                    """, account.id(), t.id(), college, old[0]);
            event(college, old[0], account.id(), "REPLACED", null,
                    "Version " + old[1] + " was replaced by version " + t.version() + ".");
        }
        jdbc.update("""
                UPDATE public.timetables SET status='PUBLISHED', published_by=?,
                    published_at=GREATEST(statement_timestamp(), created_at),
                    updated_at=GREATEST(statement_timestamp(), updated_at)
                WHERE college_id=? AND id=? AND status='DRAFT'
                """, account.id(), college, t.id());
        // Faculty are told about their own new, changed, reassigned or removed lessons, in this same transaction.
        int notified = notifications.onPublish(college, t.id(), previous.isEmpty() ? null : previous.getFirst()[0]);
        event(college, t.id(), account.id(), "PUBLISHED", null, "Version " + t.version() + " published"
                + (previous.isEmpty() ? "" : ", replacing version " + previous.getFirst()[1])
                + ". " + notified + " faculty notification" + (notified == 1 ? "" : "s") + " sent.");
        return detail(college, t.id()).withNotified(notified);
    }

    /** Discards a draft, or unpublishes a published version. Archived versions stay in the history. */
    @Transactional
    public Detail archive(Account account, long id) {
        long college = writableCollege(account);
        var row = jdbc.query("""
                SELECT status, version FROM public.timetables WHERE college_id=? AND id=? FOR UPDATE
                """, (r, i) -> new Object[]{r.getString("status"), r.getInt("version")}, college, id);
        if (row.isEmpty()) throw ApiError.TIMETABLE_NOT_FOUND.exception();
        String status = (String) row.getFirst()[0];
        if ("ARCHIVED".equals(status)) throw ApiError.TIMETABLE_STATE_CONFLICT.exception();
        jdbc.update("""
                UPDATE public.timetables SET status='ARCHIVED', archived_by=?,
                    archived_at=GREATEST(statement_timestamp(), created_at),
                    updated_at=GREATEST(statement_timestamp(), updated_at)
                WHERE college_id=? AND id=?
                """, account.id(), college, id);
        int notified = "PUBLISHED".equals(status) ? notifications.onUnpublish(college, id) : 0;
        event(college, id, account.id(), "ARCHIVED", null, "DRAFT".equals(status)
                ? "Draft version " + row.getFirst()[1] + " discarded."
                : "Version " + row.getFirst()[1] + " unpublished. Students and faculty no longer see it. "
                  + notified + " faculty notification" + (notified == 1 ? "" : "s") + " sent.");
        return detail(college, id).withNotified(notified);
    }

    // ---- Faculty and students ----

    public Schedule facultyWeek(Account account) {
        long college = account.requireCollege(CollegeRole.FACULTY, null);
        return teaching(college, account.id(), null, null);
    }

    public Day facultyDay(Account account, LocalDate date) {
        long college = account.requireCollege(CollegeRole.FACULTY, null);
        LocalDate day = date == null ? LocalDate.now(COLLEGE_ZONE) : date;
        return new Day(day, day.getDayOfWeek().getValue(), teaching(college, account.id(), day.getDayOfWeek().getValue(), null));
    }

    public Schedule studentWeek(Account account) {
        long college = account.requireCollege(CollegeRole.STUDENT, null);
        return studentSchedule(college, account.id(), null);
    }

    public Day studentDay(Account account, LocalDate date) {
        long college = account.requireCollege(CollegeRole.STUDENT, null);
        LocalDate day = date == null ? LocalDate.now(COLLEGE_ZONE) : date;
        return new Day(day, day.getDayOfWeek().getValue(), studentSchedule(college, account.id(), day.getDayOfWeek().getValue()));
    }

    /** Lessons of active assignments in published timetables only; drafts and archived versions are never shown. */
    private Schedule teaching(long college, long facultyId, Integer day, String title) {
        var args = new ArrayList<Object>(List.of(college, facultyId));
        if (day != null) args.add(day);
        String sql = SLOTS
                + " JOIN public.faculty_subject_assignments a ON a.college_id=e.college_id AND a.id=e.assignment_id"
                + " AND a.ended_at IS NULL WHERE e.college_id=? AND e.faculty_id=?"
                + (day == null ? "" : " AND e.day_of_week=?")
                + " ORDER BY e.day_of_week, e.start_time, e.id LIMIT 500";
        var slots = jdbc.query(sql, SLOT_ROW, args.toArray());
        var latest = slots.stream().map(Slot::publishedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new Schedule(true, title, null, latest, slots);
    }

    /** The division comes from the student's current enrollment, never from the request. */
    private Schedule studentSchedule(long college, long studentId, Integer day) {
        var enrollment = jdbc.query("""
                SELECT p.name || ' · ' || sem.name || ' · ' || v.name title, t.id timetable_id, t.version, t.published_at
                FROM public.student_enrollments en
                JOIN public.academic_programs p ON p.college_id=en.college_id AND p.id=en.program_id
                JOIN public.academic_semesters sem ON sem.college_id=en.college_id AND sem.id=en.semester_id
                JOIN public.academic_divisions v ON v.college_id=en.college_id AND v.id=en.division_id
                LEFT JOIN public.timetables t ON t.college_id=en.college_id AND t.semester_id=en.semester_id
                     AND t.division_id=en.division_id AND t.status='PUBLISHED'
                WHERE en.college_id=? AND en.student_id=? AND en.ended_at IS NULL
                """, (r, i) -> new Object[]{r.getString("title"), r.getObject("timetable_id", Long.class),
                r.getObject("version", Integer.class), r.getObject("published_at", OffsetDateTime.class)},
                college, studentId);
        if (enrollment.isEmpty()) return new Schedule(false, null, null, null, List.of());
        var row = enrollment.getFirst();
        if (row[1] == null) return new Schedule(true, (String) row[0], null, null, List.of());
        var args = new ArrayList<Object>(List.of(college, row[1]));
        if (day != null) args.add(day);
        var slots = jdbc.query(SLOTS + " WHERE e.college_id=? AND e.timetable_id=?"
                + (day == null ? "" : " AND e.day_of_week=?") + " ORDER BY e.day_of_week, e.start_time, e.id",
                SLOT_ROW, args.toArray());
        return new Schedule(true, (String) row[0], (Integer) row[2], (OffsetDateTime) row[3], slots);
    }

    // ---- Helpers ----

    record Locked(long id, int version, long revision, long semesterId, long divisionId) {}

    record Lesson(long facultyId, long subjectId, String subjectCode, String subject, String faculty, String classroom) {}

    /** Serializes timetable writes per college and refuses writes for inactive colleges. */
    long writableCollege(Account account) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        jdbc.query("SELECT pg_advisory_xact_lock(" + LOCK_NAMESPACE + ", pg_catalog.hashint8(?))", rs -> null, college);
        if (jdbc.queryForList("SELECT id FROM public.colleges WHERE id=? AND is_active FOR SHARE", Long.class, college)
                .isEmpty()) throw ApiError.ACCESS_DENIED.exception();
        return college;
    }

    private Locked lockDraft(long college, long id) {
        var rows = jdbc.query("""
                SELECT id, version, revision, semester_id, division_id, status FROM public.timetables
                WHERE college_id=? AND id=? FOR UPDATE
                """, (r, i) -> new Object[]{new Locked(r.getLong("id"), r.getInt("version"), r.getLong("revision"),
                r.getLong("semester_id"), r.getLong("division_id")), r.getString("status")}, college, id);
        if (rows.isEmpty()) throw ApiError.TIMETABLE_NOT_FOUND.exception();
        if (!"DRAFT".equals(rows.getFirst()[1])) throw ApiError.TIMETABLE_STATE_CONFLICT.exception();
        return (Locked) rows.getFirst()[0];
    }

    /** For a lesson: the assignment must be active, of this division and semester, with an active faculty member. */
    private Lesson lessonParts(long college, Locked t, EntryInput in) {
        if (!"LESSON".equals(in.kind())) return null;
        var lesson = jdbc.query("""
                SELECT a.faculty_id, a.subject_id, sub.code, sub.name, u.full_name
                FROM public.faculty_subject_assignments a
                JOIN public.users u ON u.college_id=a.college_id AND u.id=a.faculty_id AND u.is_active IS TRUE
                JOIN public.academic_subjects sub ON sub.college_id=a.college_id AND sub.id=a.subject_id
                WHERE a.college_id=? AND a.id=? AND a.semester_id=? AND a.division_id=? AND a.ended_at IS NULL
                FOR SHARE OF a
                """, (r, i) -> new Object[]{r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5)},
                college, in.assignmentId(), t.semesterId(), t.divisionId());
        if (lesson.isEmpty()) throw ApiError.TIMETABLE_ASSIGNMENT_INVALID.exception();
        var room = jdbc.queryForList("""
                SELECT code FROM public.classrooms WHERE college_id=? AND id=? AND is_active FOR SHARE
                """, String.class, college, in.classroomId());
        if (room.isEmpty()) throw ApiError.TIMETABLE_CLASSROOM_INVALID.exception();
        var l = lesson.getFirst();
        return new Lesson((Long) l[0], (Long) l[1], (String) l[2], (String) l[3], (String) l[4], room.getFirst());
    }

    private void requireNoClash(long college, Locked t, EntryInput in, Lesson lesson, long excludeEntry) {
        var found = findClashes(college, t.id(), t.divisionId(), in.day(), in.start(), in.end(),
                lesson == null ? null : lesson.facultyId(), in.classroomId(), excludeEntry);
        if (!found.isEmpty()) throw new Rejected(ApiError.TIMETABLE_CONFLICT, found, List.of());
    }

    /** Clashes a period would have in this timetable (any overlap) or with other divisions' faculty or classroom use. */
    List<Clash> findClashes(long college, long timetableId, long divisionId, int day, LocalTime start, LocalTime end,
                            Long faculty, Long room, long excludeEntry) {
        var booked = jdbc.query(BOOKED, BOOKED_ROW, timetableId, college, day, end, start, excludeEntry,
                timetableId, divisionId, faculty == null ? -1L : faculty, room == null ? -1L : room);
        return clashes(new Candidate(day, start, end, faculty, room), booked);
    }

    /** Adds a period to a draft; the timetable supplies the college and academic scope. */
    long insertEntry(long college, long timetableId, String kind, int day, LocalTime start, LocalTime end,
                     Long assignmentId, Long facultyId, Long subjectId, Long classroomId, String label, long actor) {
        return jdbc.queryForObject("""
                INSERT INTO public.timetable_entries (college_id, timetable_id, department_id, program_id,
                    academic_year_id, semester_id, division_id, kind, day_of_week, start_time, end_time,
                    assignment_id, faculty_id, subject_id, classroom_id, label, updated_by)
                SELECT t.college_id, t.id, t.department_id, t.program_id, t.academic_year_id, t.semester_id,
                    t.division_id, CAST(? AS varchar), CAST(? AS smallint), CAST(? AS time), CAST(? AS time),
                    CAST(? AS bigint), CAST(? AS bigint), CAST(? AS bigint), CAST(? AS bigint), CAST(? AS varchar),
                    CAST(? AS bigint)
                FROM public.timetables t WHERE t.college_id=? AND t.id=?
                RETURNING id
                """, Long.class, kind, day, start, end, assignmentId, facultyId, subjectId, classroomId, label, actor,
                college, timetableId);
    }

    /** Creates an empty draft version for a division and semester; the caller has checked no draft exists. */
    long insertDraft(long college, long semesterId, long divisionId, long actor) {
        int version = jdbc.queryForObject("""
                SELECT COALESCE(max(version), 0) + 1 FROM public.timetables
                WHERE college_id=? AND semester_id=? AND division_id=?
                """, Integer.class, college, semesterId, divisionId);
        return jdbc.queryForObject("""
                INSERT INTO public.timetables (college_id, department_id, program_id, academic_year_id, semester_id,
                    division_id, version, created_by)
                SELECT sem.college_id, p.department_id, sem.program_id, sem.academic_year_id, sem.id, v.id,
                    CAST(? AS integer), CAST(? AS bigint)
                FROM public.academic_semesters sem
                JOIN public.academic_divisions v ON v.college_id=sem.college_id AND v.program_id=sem.program_id
                     AND v.academic_year_id=sem.academic_year_id
                JOIN public.academic_programs p ON p.college_id=sem.college_id AND p.id=sem.program_id
                WHERE sem.college_id=? AND sem.id=? AND v.id=?
                RETURNING id
                """, Long.class, version, actor, college, semesterId, divisionId);
    }

    boolean hasDraft(long college, long semesterId, long divisionId) {
        return !jdbc.queryForList("""
                SELECT id FROM public.timetables WHERE college_id=? AND semester_id=? AND division_id=? AND status='DRAFT'
                FOR UPDATE
                """, Long.class, college, semesterId, divisionId).isEmpty();
    }

    void touchDraft(long college, long id) {
        touch(college, id);
    }

    private void touch(long college, long id) {
        jdbc.update("""
                UPDATE public.timetables SET revision=revision+1, updated_at=GREATEST(statement_timestamp(), updated_at)
                WHERE college_id=? AND id=?
                """, college, id);
    }

    void event(long college, long timetable, long actor, String action, Long entry, String detail) {
        String text = detail.length() > 300 ? detail.substring(0, 299) + "…" : detail;
        jdbc.update("""
                INSERT INTO public.timetable_events (college_id, timetable_id, actor_id, action, entry_id, detail)
                VALUES (?,?,?,?,?,?)
                """, college, timetable, actor, action, entry, text);
    }

    static String describe(EntryInput in, Lesson lesson) {
        String time = when(in.day(), in.start(), in.end());
        if (lesson == null) return time + " · " + (in.label() == null ? (in.kind().equals("LUNCH") ? "Lunch" : "Break") : in.label());
        return time + " · " + lesson.subjectCode() + " " + lesson.subject() + " · " + lesson.faculty() + " · " + lesson.classroom();
    }

    private String describeExisting(long college, long timetable, long entryId) {
        var entry = jdbc.query(ENTRIES.replace("ORDER BY", "AND e.id=? ORDER BY"), ENTRY_ROW, college, timetable, entryId);
        if (entry.isEmpty()) throw ApiError.TIMETABLE_NOT_FOUND.exception();
        var e = entry.getFirst();
        String time = when(e.day(), e.start(), e.end());
        return "LESSON".equals(e.kind())
                ? time + " · " + e.subjectCode() + " " + e.subject() + " · " + e.faculty() + " · " + e.classroomCode()
                : time + " · " + (e.label() == null ? ("LUNCH".equals(e.kind()) ? "Lunch" : "Break") : e.label());
    }

    Summary summary(long college, long id) {
        var rows = jdbc.query(SUMMARY + " WHERE t.college_id=? AND t.id=?", SUMMARY_ROW, college, id);
        if (rows.isEmpty()) throw ApiError.TIMETABLE_NOT_FOUND.exception();
        return rows.getFirst();
    }

    Detail detail(long college, long id) {
        var t = summary(college, id);
        var entries = jdbc.query(ENTRIES, ENTRY_ROW, college, id);
        var problems = "ARCHIVED".equals(t.status()) ? List.<Problem>of() : problems(college, t);
        return new Detail(t, entries, problems, "DRAFT".equals(t.status()) && problems.isEmpty());
    }

    /**
     * What blocks publication: no lessons, ended assignments or inactive faculty, inactive classrooms, and faculty or
     * classroom double bookings against other divisions' published timetables.
     */
    List<Problem> problems(long college, Summary t) {
        var problems = new ArrayList<Problem>();
        if (t.lessons() == 0) problems.add(new Problem("EMPTY", null, "Add at least one lesson."));
        jdbc.query("""
                SELECT e.id, e.day_of_week, e.start_time, e.end_time, sub.code subject_code, u.full_name faculty,
                       c.code classroom_code, (a.ended_at IS NOT NULL OR u.is_active IS NOT TRUE) ended,
                       (c.is_active IS NOT TRUE) room_inactive
                FROM public.timetable_entries e
                JOIN public.faculty_subject_assignments a ON a.college_id=e.college_id AND a.id=e.assignment_id
                JOIN public.users u ON u.college_id=e.college_id AND u.id=e.faculty_id
                JOIN public.classrooms c ON c.college_id=e.college_id AND c.id=e.classroom_id
                JOIN public.academic_subjects sub ON sub.college_id=e.college_id AND sub.id=e.subject_id
                WHERE e.college_id=? AND e.timetable_id=? AND e.kind='LESSON'
                  AND (a.ended_at IS NOT NULL OR u.is_active IS NOT TRUE OR c.is_active IS NOT TRUE)
                ORDER BY e.day_of_week, e.start_time LIMIT 100
                """, rs -> {
            String time = when(rs.getInt("day_of_week"), rs.getObject("start_time", LocalTime.class),
                    rs.getObject("end_time", LocalTime.class));
            if (rs.getBoolean("ended")) problems.add(new Problem("ASSIGNMENT_ENDED", rs.getLong("id"),
                    time + ": " + rs.getString("faculty") + " no longer teaches " + rs.getString("subject_code") + " here."));
            if (rs.getBoolean("room_inactive")) problems.add(new Problem("CLASSROOM_INACTIVE", rs.getLong("id"),
                    time + ": classroom " + rs.getString("classroom_code") + " is inactive."));
        }, college, t.id());
        jdbc.query("""
                SELECT e.id, e.day_of_week, e.start_time, e.end_time, (o.faculty_id=e.faculty_id) same_faculty,
                       (o.classroom_id=e.classroom_id) same_room, u.full_name faculty, c.code classroom_code,
                       op.name || ' · ' || ov.name || ' · ' || osub.code other
                FROM public.timetable_entries e
                JOIN public.timetable_entries o ON o.college_id=e.college_id AND o.day_of_week=e.day_of_week
                     AND o.start_time<e.end_time AND o.end_time>e.start_time AND o.kind='LESSON'
                     AND (o.faculty_id=e.faculty_id OR o.classroom_id=e.classroom_id)
                JOIN public.timetables ot ON ot.college_id=o.college_id AND ot.id=o.timetable_id
                     AND ot.status='PUBLISHED' AND ot.division_id<>?
                JOIN public.faculty_subject_assignments oa ON oa.college_id=o.college_id AND oa.id=o.assignment_id
                     AND oa.ended_at IS NULL
                JOIN public.academic_divisions ov ON ov.college_id=o.college_id AND ov.id=o.division_id
                JOIN public.academic_programs op ON op.college_id=o.college_id AND op.id=o.program_id
                JOIN public.academic_subjects osub ON osub.college_id=o.college_id AND osub.id=o.subject_id
                JOIN public.users u ON u.college_id=e.college_id AND u.id=e.faculty_id
                JOIN public.classrooms c ON c.college_id=e.college_id AND c.id=e.classroom_id
                WHERE e.college_id=? AND e.timetable_id=? AND e.kind='LESSON'
                ORDER BY e.day_of_week, e.start_time LIMIT 100
                """, rs -> {
            String time = when(rs.getInt("day_of_week"), rs.getObject("start_time", LocalTime.class),
                    rs.getObject("end_time", LocalTime.class));
            if (rs.getBoolean("same_faculty")) problems.add(new Problem("FACULTY_CONFLICT", rs.getLong("id"),
                    time + ": " + rs.getString("faculty") + " also teaches " + rs.getString("other") + "."));
            if (rs.getBoolean("same_room")) problems.add(new Problem("CLASSROOM_CONFLICT", rs.getLong("id"),
                    time + ": classroom " + rs.getString("classroom_code") + " is also used by " + rs.getString("other") + "."));
        }, t.divisionId(), college, t.id());
        return problems;
    }
}
