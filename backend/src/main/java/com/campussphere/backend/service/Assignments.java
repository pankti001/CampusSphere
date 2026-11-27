package com.campussphere.backend.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.StudyMaterials.FileDownload;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Assignments (homework). Faculty manage assignments for classes they currently teach (an active
 * faculty_subject_assignments row for the same division and subject); students see published assignments of their
 * current class, or ones they have submitted to; College Admins see their own college's published work and statistics
 * only. College, faculty and student identity always come from the authenticated account, never from the request.
 */
@Service
@Transactional(readOnly = true)
public class Assignments {
    static final int PAGE_SIZE = 30;
    static final List<String> FORMATS = List.of("pdf", "docx", "pptx", "xlsx", "txt", "jpg", "png");
    static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "CLOSED", "ARCHIVED");
    static final int MIN_FILE_BYTES = 100 * 1024;
    static final int MAX_DAYS = 366;
    static final int MAX_LATE_DAYS = 60;

    // ---- Request and response shapes ----

    /** Create or edit request. classId is the faculty member's teaching assignment (class) id. */
    public record Input(Long classId, String title, String description, String instructions,
                        OffsetDateTime issueAt, OffsetDateTime dueAt, BigDecimal maxMarks, List<String> allowedFormats,
                        Integer maxFileBytes, String latePolicy, OffsetDateTime lateUntil, String resubmission) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported assignment field");
        }
    }
    public record Filter(Long subjectId, Long divisionId, String status, String q, int page) {
        public Filter {
            if (status != null && !STATUSES.contains(status)) throw ApiError.INVALID_REQUEST.exception();
            q = q == null || q.isBlank() ? null : q.strip();
            if ((q != null && q.length() > 100) || page < 0 || page > 1000) throw ApiError.INVALID_REQUEST.exception();
        }
    }
    public enum StudentTab { ALL, PENDING, SUBMITTED, LATE, EVALUATED }
    public record AdminFilter(Long programId, Long academicYearId, Long semesterId, Long divisionId, Long subjectId,
                              Long facultyId, LocalDate from, LocalDate to, String status, String q, int page) {
        public AdminFilter {
            if (status != null && (status.equals("DRAFT") || !STATUSES.contains(status))) throw ApiError.INVALID_REQUEST.exception();
            if (from != null && to != null && to.isBefore(from)) throw ApiError.INVALID_REQUEST.exception();
            q = q == null || q.isBlank() ? null : q.strip();
            if ((q != null && q.length() > 100) || page < 0 || page > 1000) throw ApiError.INVALID_REQUEST.exception();
        }
    }
    public record ClassOption(long classId, long subjectId, String subjectCode, String subject, long divisionId,
                              String division, long semesterId, String semester, long programId, String program,
                              long academicYearId, String academicYear) {}
    public record Counts(long roster, long submitted, long pending, long late, long toMark, long evaluated, long returned) {}
    public record Mine(long submissionId, int attempt, String status, boolean late, BigDecimal marks,
                       OffsetDateTime submittedAt) {}
    public record Assignment(long id, String title, String description, String instructions, OffsetDateTime issueAt,
                             OffsetDateTime dueAt, BigDecimal maxMarks, List<String> allowedFormats, int maxFileBytes,
                             String latePolicy, OffsetDateTime lateUntil, String resubmission, String status,
                             OffsetDateTime publishedAt, OffsetDateTime closedAt, long classId, long subjectId,
                             String subjectCode, String subject, long divisionId, String division, long semesterId,
                             String semester, long programId, String program, long academicYearId, String academicYear,
                             Long facultyId, String faculty, String attachmentName, String attachmentFormat,
                             Long attachmentSize, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                             boolean acceptingSubmissions, boolean overdue, boolean canEdit, Counts counts, Mine mine) {}
    public record FacultySummary(long published, long drafts, long closed, long toMark, long evaluated) {}
    public record StudentSummary(long pending, long dueThisWeek, long submitted, long evaluated, long unreadNotices,
                                 List<Assignment> upcoming) {}
    public record AdminStats(long published, long closed, long archived, long drafts, long submissions, long pending,
                             long toMark, long evaluated, long late) {}
    public record Option(long id, String label) {}
    public record Filters(List<Option> programs, List<Option> academicYears, List<Option> semesters,
                          List<Option> divisions, List<Option> subjects, List<Option> faculty) {}
    public record Event(String action, String actor, String note, OffsetDateTime time) {}

    /** The stored state of an assignment, used for business rules. */
    record State(long id, String status, OffsetDateTime issueAt, OffsetDateTime dueAt, BigDecimal maxMarks,
                 List<String> formats, int maxFileBytes, String latePolicy, OffsetDateTime lateUntil,
                 String resubmission, long classId, UUID attachmentKey, String title) {}

    private final JdbcTemplate db;
    private final MaterialFiles files;
    private final AssignmentNotices notices;
    private final Clock clock;
    private final ZoneId zone;

    @Autowired
    public Assignments(JdbcTemplate db, MaterialFiles files, AssignmentNotices notices,
                       @Value("${app.timezone:Asia/Kolkata}") String zone) {
        this(db, files, notices, Clock.systemUTC(), ZoneId.of(zone));
    }

    Assignments(JdbcTemplate db, MaterialFiles files, AssignmentNotices notices, Clock clock, ZoneId zone) {
        this.db = db;
        this.files = files;
        this.notices = notices;
        this.clock = clock;
        this.zone = zone;
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    // ---- SQL building blocks ----

    static final String COLUMNS = """
            SELECT a.id, a.title, a.description, a.instructions, a.issue_at, a.due_at, a.max_marks, a.allowed_formats,
                   a.max_file_bytes, a.late_policy, a.late_until, a.resubmission, a.status, a.published_at, a.closed_at,
                   a.teaching_assignment_id, a.subject_id, s.code subject_code, s.name subject, a.division_id,
                   d.name division, t.semester_id, sem.name semester, t.program_id, p.name program, t.academic_year_id,
                   y.name academic_year, a.faculty_id, u.full_name faculty, a.attachment_name, a.attachment_format,
                   a.attachment_size, a.created_at, a.updated_at""";
    static final String FROM = """

             FROM public.assignments a
            JOIN public.faculty_subject_assignments t ON t.college_id = a.college_id AND t.id = a.teaching_assignment_id
            JOIN public.academic_subjects s ON s.college_id = a.college_id AND s.id = a.subject_id
            JOIN public.academic_divisions d ON d.college_id = a.college_id AND d.id = a.division_id
            JOIN public.academic_semesters sem ON sem.college_id = t.college_id AND sem.id = t.semester_id
            JOIN public.academic_programs p ON p.college_id = t.college_id AND p.id = t.program_id
            JOIN public.academic_years y ON y.college_id = t.college_id AND y.id = t.academic_year_id
            JOIN public.users u ON u.id = a.faculty_id""";
    /** Students currently enrolled in the class of teaching assignment t. */
    static final String ROSTER = """
            SELECT e.student_id FROM public.student_enrollments e
            WHERE e.college_id = t.college_id AND e.ended_at IS NULL AND e.department_id = t.department_id
              AND e.program_id = t.program_id AND e.academic_year_id = t.academic_year_id
              AND e.semester_id = t.semester_id AND e.division_id = t.division_id""";
    static final String COUNT_COLUMNS = """
            , (SELECT count(*) FROM (""" + ROSTER + """
            ) r) roster,
              (SELECT count(*) FROM (""" + ROSTER + """
            ) r WHERE NOT EXISTS (SELECT 1 FROM public.assignment_submissions x WHERE x.college_id = a.college_id
                    AND x.assignment_id = a.id AND x.student_id = r.student_id AND x.status IN ('SUBMITTED', 'EVALUATED'))) pending,
              c.submitted, c.late, c.to_mark, c.evaluated, c.returned""";
    static final String COUNT_JOIN = """

            CROSS JOIN LATERAL (SELECT count(*) FILTER (WHERE x.status IN ('SUBMITTED', 'EVALUATED')) submitted,
                    count(*) FILTER (WHERE x.is_late) late, count(*) FILTER (WHERE x.status = 'SUBMITTED') to_mark,
                    count(*) FILTER (WHERE x.status = 'EVALUATED') evaluated,
                    count(*) FILTER (WHERE x.status = 'RETURNED') returned
                FROM public.assignment_submissions x
                WHERE x.college_id = a.college_id AND x.assignment_id = a.id AND x.status <> 'SUPERSEDED') c""";
    static final String MINE_COLUMNS = """
            , mine.id mine_id, mine.attempt mine_attempt, mine.status mine_status, mine.is_late mine_late,
              mine.marks mine_marks, mine.submitted_at mine_submitted_at""";
    static final String MINE_JOIN = """

            LEFT JOIN public.assignment_submissions mine ON mine.college_id = a.college_id AND mine.assignment_id = a.id
                AND mine.student_id = ? AND mine.status <> 'SUPERSEDED'""";
    static final String FACULTY_SCOPE = """

             AND EXISTS (SELECT 1 FROM public.faculty_subject_assignments m WHERE m.college_id = a.college_id
                AND m.faculty_id = ? AND m.division_id = a.division_id AND m.subject_id = a.subject_id AND m.ended_at IS NULL)""";
    static final String STUDENT_SCOPE = """

             AND a.status <> 'DRAFT' AND a.issue_at <= ? AND (mine.id IS NOT NULL OR EXISTS (""" + ROSTER
            + " AND e.student_id = ?))";

    /** A SQL statement under construction and its parameters, in order. */
    static final class Sql {
        final StringBuilder text = new StringBuilder();
        final List<Object> args = new ArrayList<>();
        Sql add(String sql, Object... values) {
            text.append(sql);
            args.addAll(Arrays.asList(values));
            return this;
        }
        Object[] params() { return args.toArray(); }
        @Override public String toString() { return text.toString(); }
    }

    /** SELECT ... WHERE limited to what the role may see in the actor's college. */
    Sql select(Account actor, CollegeRole role) {
        long college = actor.requireCollege(role, null);
        var sql = new Sql().add(COLUMNS);
        if (role == CollegeRole.STUDENT) sql.add(MINE_COLUMNS).add(FROM).add(MINE_JOIN, actor.id());
        else sql.add(COUNT_COLUMNS).add(FROM).add(COUNT_JOIN);
        sql.add("\n WHERE a.college_id = ?", college);
        switch (role) {
            case FACULTY -> sql.add(FACULTY_SCOPE, actor.id());
            case STUDENT -> sql.add(STUDENT_SCOPE, now(), actor.id());
            case COLLEGE_ADMIN -> sql.add(" AND a.status <> 'DRAFT'");
        }
        return sql;
    }

    // ---- Rules ----

    private static String text(String value, int max) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() > max) throw ApiError.ASSIGNMENT_INVALID.exception();
        return clean;
    }

    /** Validates and normalises a create/edit request. Missing issue date means now; missing options use defaults. */
    static Input clean(Input in, OffsetDateTime now) {
        if (in == null) throw ApiError.ASSIGNMENT_INVALID.exception();
        String title = text(in.title(), 160);
        if (title.isEmpty() || in.dueAt() == null || in.maxMarks() == null) throw ApiError.ASSIGNMENT_INVALID.exception();
        OffsetDateTime issue = in.issueAt() == null ? now : in.issueAt();
        if (!in.dueAt().isAfter(issue) || Duration.between(issue, in.dueAt()).toDays() > MAX_DAYS) {
            throw ApiError.ASSIGNMENT_DEADLINE_INVALID.exception();
        }
        BigDecimal marks = in.maxMarks().stripTrailingZeros();
        if (marks.signum() <= 0 || marks.compareTo(BigDecimal.valueOf(1000)) > 0 || marks.scale() > 2) {
            throw ApiError.ASSIGNMENT_INVALID.exception();
        }
        if (in.allowedFormats() == null || in.allowedFormats().isEmpty()) throw ApiError.ASSIGNMENT_INVALID.exception();
        var wanted = new HashSet<String>();
        for (String format : in.allowedFormats()) {
            String f = format == null ? "" : format.strip().toLowerCase(Locale.ROOT);
            if (f.equals("jpeg")) f = "jpg";
            if (!FORMATS.contains(f)) throw ApiError.ASSIGNMENT_INVALID.exception();
            wanted.add(f);
        }
        List<String> formats = FORMATS.stream().filter(wanted::contains).toList();
        int size = in.maxFileBytes() == null ? MaterialFiles.LIMIT : in.maxFileBytes();
        if (size < MIN_FILE_BYTES || size > MaterialFiles.LIMIT) throw ApiError.ASSIGNMENT_INVALID.exception();
        String late = in.latePolicy() == null ? "NONE" : in.latePolicy();
        if (late.equals("NONE")) {
            if (in.lateUntil() != null) throw ApiError.ASSIGNMENT_INVALID.exception();
        } else if (late.equals("ALLOWED")) {
            if (in.lateUntil() == null || !in.lateUntil().isAfter(in.dueAt())
                    || Duration.between(in.dueAt(), in.lateUntil()).toDays() > MAX_LATE_DAYS) {
                throw ApiError.ASSIGNMENT_DEADLINE_INVALID.exception();
            }
        } else throw ApiError.ASSIGNMENT_INVALID.exception();
        String resubmission = in.resubmission() == null ? "SINGLE" : in.resubmission();
        if (!resubmission.equals("SINGLE") && !resubmission.equals("UNTIL_DEADLINE")) throw ApiError.ASSIGNMENT_INVALID.exception();
        if (in.classId() != null && in.classId() <= 0) throw ApiError.ASSIGNMENT_INVALID.exception();
        return new Input(in.classId(), title, text(in.description(), 4000), text(in.instructions(), 4000), issue,
                in.dueAt(), marks.setScale(2, RoundingMode.UNNECESSARY), formats, size, late, in.lateUntil(), resubmission);
    }

    /** What an edit changes, after checking it is allowed in the assignment's current state. */
    record Change(boolean deadlineMoved, boolean marksChanged, boolean classChanged) {}

    static Change plan(State current, Input next, OffsetDateTime now, boolean marked) {
        boolean classChanged = next.classId() != null && next.classId() != current.classId();
        boolean deadlineMoved = !next.dueAt().isEqual(current.dueAt());
        boolean marksChanged = next.maxMarks().compareTo(current.maxMarks()) != 0;
        switch (current.status()) {
            case "DRAFT" -> { }
            case "PUBLISHED" -> {
                // The class and issue date are fixed once students can see the assignment.
                if (classChanged || !next.issueAt().isEqual(current.issueAt())) throw ApiError.ASSIGNMENT_STATE_CONFLICT.exception();
                // A published deadline may only move later, and never into the past.
                if (deadlineMoved && (next.dueAt().isBefore(current.dueAt()) || !next.dueAt().isAfter(now))) {
                    throw ApiError.ASSIGNMENT_DEADLINE_INVALID.exception();
                }
            }
            default -> throw ApiError.ASSIGNMENT_STATE_CONFLICT.exception();
        }
        if (marksChanged && marked) throw ApiError.ASSIGNMENT_MARKS_LOCKED.exception();
        return new Change(deadlineMoved, marksChanged, classChanged);
    }

    /** True while students can hand in work for the first time (deadline, or late window if allowed). */
    static boolean accepting(String status, OffsetDateTime issue, OffsetDateTime due, String latePolicy,
                             OffsetDateTime lateUntil, OffsetDateTime now) {
        OffsetDateTime end = "ALLOWED".equals(latePolicy) && lateUntil != null ? lateUntil : due;
        return "PUBLISHED".equals(status) && !now.isBefore(issue) && !now.isAfter(end);
    }

    // ---- Faculty ----

    public List<ClassOption> classes(Account actor) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        return db.query("""
                SELECT t.id, t.subject_id, s.code, s.name, t.division_id, d.name, t.semester_id, sem.name,
                       t.program_id, p.name, t.academic_year_id, y.name
                FROM public.faculty_subject_assignments t
                JOIN public.academic_subjects s ON s.college_id = t.college_id AND s.id = t.subject_id
                JOIN public.academic_divisions d ON d.college_id = t.college_id AND d.id = t.division_id
                JOIN public.academic_semesters sem ON sem.college_id = t.college_id AND sem.id = t.semester_id
                JOIN public.academic_programs p ON p.college_id = t.college_id AND p.id = t.program_id
                JOIN public.academic_years y ON y.college_id = t.college_id AND y.id = t.academic_year_id
                WHERE t.college_id = ? AND t.faculty_id = ? AND t.ended_at IS NULL
                ORDER BY s.name, d.name, t.id
                """, (r, i) -> new ClassOption(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getLong(5),
                r.getString(6), r.getLong(7), r.getString(8), r.getLong(9), r.getString(10), r.getLong(11),
                r.getString(12)), college, actor.id());
    }

    public List<Assignment> facultyList(Account actor, Filter filter) {
        var sql = select(actor, CollegeRole.FACULTY);
        if (filter.subjectId() != null) sql.add(" AND a.subject_id = ?", filter.subjectId());
        if (filter.divisionId() != null) sql.add(" AND a.division_id = ?", filter.divisionId());
        if (filter.status() != null) sql.add(" AND a.status = ?", filter.status());
        else sql.add(" AND a.status <> 'ARCHIVED'");
        if (filter.q() != null) sql.add(" AND strpos(lower(a.title), lower(?)) > 0", filter.q());
        sql.add("""

                 ORDER BY CASE a.status WHEN 'DRAFT' THEN 0 WHEN 'PUBLISHED' THEN 1 ELSE 2 END, a.due_at DESC, a.id DESC
                 LIMIT ? OFFSET ?""", PAGE_SIZE, filter.page() * PAGE_SIZE);
        return query(sql, CollegeRole.FACULTY);
    }

    public FacultySummary facultySummary(Account actor) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        return db.queryForObject("""
                SELECT count(*) FILTER (WHERE a.status = 'PUBLISHED'), count(*) FILTER (WHERE a.status = 'DRAFT'),
                       count(*) FILTER (WHERE a.status = 'CLOSED'), coalesce(sum(c.to_mark), 0), coalesce(sum(c.evaluated), 0)
                FROM public.assignments a""" + COUNT_JOIN + """

                WHERE a.college_id = ? AND a.status <> 'ARCHIVED'""" + FACULTY_SCOPE,
                (r, i) -> new FacultySummary(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5)),
                college, actor.id());
    }

    public Assignment detail(Account actor, CollegeRole role, long id) {
        var sql = select(actor, role).add(" AND a.id = ?", id);
        return query(sql, role).stream().findFirst().orElseThrow(ApiError.ASSIGNMENT_NOT_FOUND::exception);
    }

    @Transactional
    public Assignment create(Account actor, Input input) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        Input in = clean(input, now());
        if (in.classId() == null) throw ApiError.ASSIGNMENT_INVALID.exception();
        long[] scope = teachingClass(college, actor.id(), in.classId());
        Long id = db.queryForObject("""
                INSERT INTO public.assignments (college_id, teaching_assignment_id, faculty_id, division_id, subject_id,
                    title, description, instructions, issue_at, due_at, max_marks, allowed_formats, max_file_bytes,
                    late_policy, late_until, resubmission)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS varchar(4)[]), ?, ?, ?, ?) RETURNING id
                """, Long.class, college, in.classId(), actor.id(), scope[0], scope[1], in.title(), in.description(),
                in.instructions(), in.issueAt(), in.dueAt(), in.maxMarks(), array(in.allowedFormats()),
                in.maxFileBytes(), in.latePolicy(), in.lateUntil(), in.resubmission());
        event(college, id, null, actor.id(), "CREATE", null);
        return detail(actor, CollegeRole.FACULTY, id);
    }

    @Transactional
    public Assignment update(Account actor, long id, Input input) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        detail(actor, CollegeRole.FACULTY, id);
        State current = lock(college, id, true);
        OffsetDateTime now = now();
        Input raw = input == null ? null : new Input(input.classId(), input.title(), input.description(),
                input.instructions(), input.issueAt() == null ? current.issueAt() : input.issueAt(), input.dueAt(),
                input.maxMarks(), input.allowedFormats(), input.maxFileBytes(), input.latePolicy(), input.lateUntil(),
                input.resubmission());
        Input in = clean(raw, now);
        boolean marked = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.assignment_submissions
                               WHERE college_id = ? AND assignment_id = ? AND marks IS NOT NULL)
                """, Boolean.class, college, id));
        Change change = plan(current, in, now, marked);
        long classId = current.classId();
        long[] scope = null;
        if (change.classChanged()) {
            classId = in.classId();
            scope = teachingClass(college, actor.id(), classId);
        }
        db.update("""
                UPDATE public.assignments SET teaching_assignment_id = ?,
                    faculty_id = CASE WHEN ? THEN ? ELSE faculty_id END,
                    division_id = COALESCE(?, division_id), subject_id = COALESCE(?, subject_id),
                    title = ?, description = ?, instructions = ?, issue_at = ?, due_at = ?, max_marks = ?,
                    allowed_formats = CAST(? AS varchar(4)[]), max_file_bytes = ?, late_policy = ?, late_until = ?,
                    resubmission = ?, updated_at = statement_timestamp()
                WHERE college_id = ? AND id = ?
                """, classId, change.classChanged(), actor.id(), scope == null ? null : scope[0],
                scope == null ? null : scope[1], in.title(), in.description(), in.instructions(), in.issueAt(),
                in.dueAt(), in.maxMarks(), array(in.allowedFormats()), in.maxFileBytes(), in.latePolicy(),
                in.lateUntil(), in.resubmission(), college, id);
        String note = change.marksChanged()
                ? "Maximum marks changed from " + current.maxMarks().toPlainString() + " to " + in.maxMarks().toPlainString()
                : null;
        event(college, id, null, actor.id(), "EDIT", note);
        if (change.deadlineMoved() && current.status().equals("PUBLISHED")) {
            // Work handed in before the new deadline is no longer late.
            int onTime = db.update("""
                    UPDATE public.assignment_submissions SET is_late = false
                    WHERE college_id = ? AND assignment_id = ? AND is_late AND submitted_at <= ?
                    """, college, id, in.dueAt());
            event(college, id, null, actor.id(), "DEADLINE", "Deadline moved from " + current.dueAt() + " to "
                    + in.dueAt() + (onTime > 0 ? "; " + onTime + " submission(s) no longer late" : ""));
            notices.deadlineChanged(college, id, in.dueAt());
        }
        return detail(actor, CollegeRole.FACULTY, id);
    }

    @Transactional
    public Assignment publish(Account actor, long id) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        detail(actor, CollegeRole.FACULTY, id);
        State current = lock(college, id, true);
        if (!current.status().equals("DRAFT")) throw ApiError.ASSIGNMENT_STATE_CONFLICT.exception();
        if (!current.dueAt().isAfter(now())) throw ApiError.ASSIGNMENT_DEADLINE_INVALID.exception();
        db.update("""
                UPDATE public.assignments SET status = 'PUBLISHED', published_at = statement_timestamp(),
                    updated_at = statement_timestamp() WHERE college_id = ? AND id = ?
                """, college, id);
        event(college, id, null, actor.id(), "PUBLISH", null);
        notices.published(college, id);
        return detail(actor, CollegeRole.FACULTY, id);
    }

    @Transactional
    public Assignment close(Account actor, long id) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        detail(actor, CollegeRole.FACULTY, id);
        if (!lock(college, id, true).status().equals("PUBLISHED")) throw ApiError.ASSIGNMENT_STATE_CONFLICT.exception();
        db.update("""
                UPDATE public.assignments SET status = 'CLOSED', closed_at = statement_timestamp(),
                    updated_at = statement_timestamp() WHERE college_id = ? AND id = ?
                """, college, id);
        event(college, id, null, actor.id(), "CLOSE", null);
        return detail(actor, CollegeRole.FACULTY, id);
    }

    @Transactional
    public Assignment archive(Account actor, long id) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        detail(actor, CollegeRole.FACULTY, id);
        if (lock(college, id, true).status().equals("ARCHIVED")) throw ApiError.ASSIGNMENT_STATE_CONFLICT.exception();
        db.update("""
                UPDATE public.assignments SET status = 'ARCHIVED', archived_at = statement_timestamp(),
                    closed_at = CASE WHEN status = 'PUBLISHED' THEN statement_timestamp() ELSE closed_at END,
                    updated_at = statement_timestamp() WHERE college_id = ? AND id = ?
                """, college, id);
        event(college, id, null, actor.id(), "ARCHIVE", null);
        return detail(actor, CollegeRole.FACULTY, id);
    }

    @Transactional(rollbackFor = IOException.class)
    public Assignment attach(Account actor, long id, MultipartFile file) throws IOException {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        detail(actor, CollegeRole.FACULTY, id);
        State current = lock(college, id, true);
        if (!current.status().equals("DRAFT") && !current.status().equals("PUBLISHED")) {
            throw ApiError.ASSIGNMENT_STATE_CONFLICT.exception();
        }
        MaterialFiles.Upload upload;
        try {
            upload = MaterialFiles.inspect(file, EnumSet.allOf(MaterialFiles.Format.class), MaterialFiles.LIMIT);
        } catch (ApiError.Failure failure) {
            throw failure.error() == ApiError.MATERIAL_FILE_INVALID ? ApiError.ASSIGNMENT_FILE_INVALID.exception() : failure;
        }
        UUID key = UUID.randomUUID();
        db.update("""
                UPDATE public.assignments SET attachment_name = ?, attachment_key = ?, attachment_format = ?,
                    attachment_mime = ?, attachment_size = ?, attachment_sha256 = ?, updated_at = statement_timestamp()
                WHERE college_id = ? AND id = ?
                """, upload.fileName(), key, upload.extension(), upload.mime(), upload.bytes().length, upload.sha256(),
                college, id);
        event(college, id, null, actor.id(), "ATTACH", upload.fileName());
        UUID old = current.attachmentKey();
        afterCommit(committed -> {
            if (committed) { if (old != null) files.delete(old); } else files.delete(key);
        });
        files.save(key, upload.bytes());
        return detail(actor, CollegeRole.FACULTY, id);
    }

    @Transactional
    public Assignment detach(Account actor, long id) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        detail(actor, CollegeRole.FACULTY, id);
        State current = lock(college, id, true);
        if (current.attachmentKey() == null) return detail(actor, CollegeRole.FACULTY, id);
        if (!current.status().equals("DRAFT") && !current.status().equals("PUBLISHED")) {
            throw ApiError.ASSIGNMENT_STATE_CONFLICT.exception();
        }
        db.update("""
                UPDATE public.assignments SET attachment_name = NULL, attachment_key = NULL, attachment_format = NULL,
                    attachment_mime = NULL, attachment_size = NULL, attachment_sha256 = NULL,
                    updated_at = statement_timestamp()
                WHERE college_id = ? AND id = ?
                """, college, id);
        event(college, id, null, actor.id(), "DETACH", null);
        UUID old = current.attachmentKey();
        afterCommit(committed -> { if (committed) files.delete(old); });
        return detail(actor, CollegeRole.FACULTY, id);
    }

    /** The faculty attachment, for anyone who may see the assignment. */
    public FileDownload attachment(Account actor, CollegeRole role, long id) {
        Assignment assignment = detail(actor, role, id);
        if (assignment.attachmentName() == null) throw ApiError.ASSIGNMENT_NOT_FOUND.exception();
        var row = db.query("""
                SELECT attachment_key, attachment_mime FROM public.assignments
                WHERE college_id = ? AND id = ? AND attachment_key IS NOT NULL
                """, (r, i) -> new Object[]{r.getObject(1, UUID.class), r.getString(2)},
                actor.requireCollege(role, null), id).stream().findFirst()
                .orElseThrow(ApiError.ASSIGNMENT_NOT_FOUND::exception);
        Path path = files.path((UUID) row[0]);
        if (!Files.isRegularFile(path)) throw ApiError.ASSIGNMENT_NOT_FOUND.exception();
        return new FileDownload(path, assignment.attachmentName(), (String) row[1], assignment.attachmentSize());
    }

    /**
     * History of an assignment for its faculty or the College Admin. The College Admin sees only assignment-level
     * events: submissions, marks and returns (and who handed in late) stay with the class's faculty.
     */
    public List<Event> history(Account actor, CollegeRole role, long id) {
        if (role == CollegeRole.STUDENT) throw ApiError.ACCESS_DENIED.exception();
        detail(actor, role, id);
        return db.query("""
                SELECT e.action, u.full_name, e.note, e.occurred_at FROM public.assignment_events e
                JOIN public.users u ON u.id = e.actor_id
                WHERE e.college_id = ? AND e.assignment_id = ?""" + (role == CollegeRole.COLLEGE_ADMIN
                ? " AND e.submission_id IS NULL" : "") + " ORDER BY e.id",
                (r, i) -> new Event(r.getString(1), r.getString(2), r.getString(3),
                r.getObject(4, OffsetDateTime.class)), actor.requireCollege(role, null), id);
    }

    // ---- Students ----

    public List<Assignment> studentList(Account actor, StudentTab tab, int page) {
        if (page < 0 || page > 1000) throw ApiError.INVALID_REQUEST.exception();
        var sql = select(actor, CollegeRole.STUDENT);
        studentTab(sql, tab == null ? StudentTab.ALL : tab);
        sql.add(" LIMIT ? OFFSET ?", PAGE_SIZE, page * PAGE_SIZE);
        return query(sql, CollegeRole.STUDENT);
    }

    private static void studentTab(Sql sql, StudentTab tab) {
        switch (tab) {
            case PENDING -> sql.add(" AND a.status = 'PUBLISHED' AND (mine.id IS NULL OR mine.status = 'RETURNED') ORDER BY a.due_at, a.id");
            case SUBMITTED -> sql.add(" AND mine.status = 'SUBMITTED' ORDER BY mine.submitted_at DESC, a.id DESC");
            case LATE -> sql.add(" AND mine.is_late ORDER BY mine.submitted_at DESC, a.id DESC");
            case EVALUATED -> sql.add(" AND mine.status = 'EVALUATED' ORDER BY a.due_at DESC, a.id DESC");
            case ALL -> sql.add(" ORDER BY a.due_at DESC, a.id DESC");
        }
    }

    public StudentSummary studentSummary(Account actor) {
        OffsetDateTime now = now();
        var sql = new Sql().add("""
                SELECT count(*) FILTER (WHERE a.status = 'PUBLISHED' AND (mine.id IS NULL OR mine.status = 'RETURNED')),
                       count(*) FILTER (WHERE a.status = 'PUBLISHED' AND (mine.id IS NULL OR mine.status = 'RETURNED')
                                        AND a.due_at BETWEEN ? AND ?),
                       count(*) FILTER (WHERE mine.status = 'SUBMITTED'), count(*) FILTER (WHERE mine.status = 'EVALUATED')
                """, now, now.plusDays(7));
        sql.add(FROM).add(MINE_JOIN, actor.id())
                .add("\n WHERE a.college_id = ?", actor.requireCollege(CollegeRole.STUDENT, null))
                .add(STUDENT_SCOPE, now, actor.id());
        long[] counts = db.queryForObject(sql.toString(), (r, i) -> new long[]{r.getLong(1), r.getLong(2), r.getLong(3),
                r.getLong(4)}, sql.params());
        var upcoming = select(actor, CollegeRole.STUDENT)
                .add(" AND a.status = 'PUBLISHED' AND (mine.id IS NULL OR mine.status = 'RETURNED') AND a.due_at >= ?", now)
                .add(" ORDER BY a.due_at, a.id LIMIT 3");
        return new StudentSummary(counts[0], counts[1], counts[2], counts[3], notices.unread(actor),
                query(upcoming, CollegeRole.STUDENT));
    }

    // ---- College Admin monitoring (read-only) ----

    private Sql adminFilter(Sql sql, AdminFilter f) {
        if (f.programId() != null) sql.add(" AND t.program_id = ?", f.programId());
        if (f.academicYearId() != null) sql.add(" AND t.academic_year_id = ?", f.academicYearId());
        if (f.semesterId() != null) sql.add(" AND t.semester_id = ?", f.semesterId());
        if (f.divisionId() != null) sql.add(" AND a.division_id = ?", f.divisionId());
        if (f.subjectId() != null) sql.add(" AND a.subject_id = ?", f.subjectId());
        if (f.facultyId() != null) sql.add(" AND a.faculty_id = ?", f.facultyId());
        if (f.from() != null) sql.add(" AND a.due_at >= ?", f.from().atStartOfDay(zone).toOffsetDateTime());
        if (f.to() != null) sql.add(" AND a.due_at < ?", f.to().plusDays(1).atStartOfDay(zone).toOffsetDateTime());
        if (f.status() != null) sql.add(" AND a.status = ?", f.status());
        if (f.q() != null) sql.add(" AND strpos(lower(a.title), lower(?)) > 0", f.q());
        return sql;
    }

    public List<Assignment> adminList(Account actor, AdminFilter filter) {
        var sql = adminFilter(select(actor, CollegeRole.COLLEGE_ADMIN), filter)
                .add(" ORDER BY a.due_at DESC, a.id DESC LIMIT ? OFFSET ?", PAGE_SIZE, filter.page() * PAGE_SIZE);
        return query(sql, CollegeRole.COLLEGE_ADMIN);
    }

    /** Statistics include the number of drafts, but never their content. */
    public AdminStats adminStats(Account actor, AdminFilter filter) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var inner = new Sql().add("SELECT a.status").add(COUNT_COLUMNS).add(FROM).add(COUNT_JOIN)
                .add("\n WHERE a.college_id = ?", college);
        adminFilter(inner, filter);
        String sql = """
                SELECT count(*) FILTER (WHERE z.status = 'PUBLISHED'), count(*) FILTER (WHERE z.status = 'CLOSED'),
                       count(*) FILTER (WHERE z.status = 'ARCHIVED'), count(*) FILTER (WHERE z.status = 'DRAFT'),
                       coalesce(sum(z.submitted), 0),
                       coalesce(sum(z.pending) FILTER (WHERE z.status IN ('PUBLISHED', 'CLOSED')), 0),
                       coalesce(sum(z.to_mark), 0), coalesce(sum(z.evaluated), 0), coalesce(sum(z.late), 0)
                FROM (""" + inner + ") z";
        return db.queryForObject(sql, (r, i) -> new AdminStats(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4),
                r.getLong(5), r.getLong(6), r.getLong(7), r.getLong(8), r.getLong(9)), inner.params());
    }

    /** Filter choices taken from the college's published assignments. */
    public Filters adminFilters(Account actor) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var programs = new TreeMap<Long, String>();
        var years = new TreeMap<Long, String>();
        var semesters = new TreeMap<Long, String>();
        var divisions = new TreeMap<Long, String>();
        var subjects = new TreeMap<Long, String>();
        var faculty = new TreeMap<Long, String>();
        db.query("""
                SELECT DISTINCT t.program_id, p.name, t.academic_year_id, y.name, t.semester_id, sem.name,
                       a.division_id, d.name, a.subject_id, s.code, s.name, a.faculty_id, u.full_name""" + FROM + """

                WHERE a.college_id = ? AND a.status <> 'DRAFT'
                """, (org.springframework.jdbc.core.RowCallbackHandler) r -> {
            programs.put(r.getLong(1), r.getString(2));
            years.put(r.getLong(3), r.getString(4));
            semesters.put(r.getLong(5), r.getString(6) + " (" + r.getString(2) + ")");
            divisions.put(r.getLong(7), r.getString(8) + " (" + r.getString(2) + ", " + r.getString(4) + ")");
            subjects.put(r.getLong(9), r.getString(10) + " · " + r.getString(11));
            faculty.put(r.getLong(12), r.getString(13));
        }, college);
        return new Filters(options(programs), options(years), options(semesters), options(divisions),
                options(subjects), options(faculty));
    }

    private static List<Option> options(Map<Long, String> values) {
        return values.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(Option::label, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    // ---- Shared helpers ----

    private List<Assignment> query(Sql sql, CollegeRole role) {
        OffsetDateTime now = now();
        return db.query(sql.toString(), (r, i) -> map(r, role, now), sql.params());
    }

    /** The faculty member's own active class (teaching assignment): {division id, subject id}. */
    private long[] teachingClass(long college, long faculty, long classId) {
        return db.query("""
                SELECT division_id, subject_id FROM public.faculty_subject_assignments
                WHERE college_id = ? AND id = ? AND faculty_id = ? AND ended_at IS NULL FOR SHARE
                """, (r, i) -> new long[]{r.getLong(1), r.getLong(2)}, college, classId, faculty).stream().findFirst()
                .orElseThrow(ApiError.ASSIGNMENT_INVALID::exception);
    }

    /** Locks the assignment row (FOR UPDATE, or FOR SHARE for readers that must not race a state change). */
    State lock(long college, long id, boolean exclusive) {
        return db.query("""
                SELECT id, status, issue_at, due_at, max_marks, allowed_formats, max_file_bytes, late_policy, late_until,
                       resubmission, teaching_assignment_id, attachment_key, title
                FROM public.assignments WHERE college_id = ? AND id = ?
                """ + (exclusive ? " FOR UPDATE" : " FOR SHARE"), (r, i) -> new State(r.getLong("id"),
                r.getString("status"), r.getObject("issue_at", OffsetDateTime.class),
                r.getObject("due_at", OffsetDateTime.class), r.getBigDecimal("max_marks"), formats(r, "allowed_formats"),
                r.getInt("max_file_bytes"), r.getString("late_policy"), r.getObject("late_until", OffsetDateTime.class),
                r.getString("resubmission"), r.getLong("teaching_assignment_id"), r.getObject("attachment_key", UUID.class),
                r.getString("title")), college, id).stream().findFirst()
                .orElseThrow(ApiError.ASSIGNMENT_NOT_FOUND::exception);
    }

    /** Appends to the assignment's history and returns the event id. */
    long event(long college, long assignment, Long submission, long actor, String action, String note) {
        Long id = db.queryForObject("""
                INSERT INTO public.assignment_events (college_id, assignment_id, submission_id, actor_id, action, note)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, college, assignment, submission, actor, action,
                note == null ? null : note.length() > 500 ? note.substring(0, 500) : note);
        return id == null ? 0 : id;
    }

    interface FileAction { void run(boolean committed) throws IOException; }

    /** Runs file clean-up once the transaction ends, so files and rows never disagree. */
    static void afterCommit(FileAction action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                try { action.run(status == STATUS_COMMITTED); } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        });
    }

    static String array(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    static List<String> formats(ResultSet r, String column) throws SQLException {
        java.sql.Array array = r.getArray(column);
        if (array == null) return List.of();
        Object values = array.getArray();
        return values instanceof String[] strings ? List.of(strings) : List.of();
    }

    private static Assignment map(ResultSet r, CollegeRole role, OffsetDateTime now) throws SQLException {
        String status = r.getString("status");
        OffsetDateTime issue = r.getObject("issue_at", OffsetDateTime.class);
        OffsetDateTime due = r.getObject("due_at", OffsetDateTime.class);
        String latePolicy = r.getString("late_policy");
        OffsetDateTime lateUntil = r.getObject("late_until", OffsetDateTime.class);
        Number size = (Number) r.getObject("attachment_size");
        Counts counts = null;
        Mine mine = null;
        if (role == CollegeRole.STUDENT) {
            long mineId = r.getLong("mine_id");
            if (!r.wasNull()) mine = new Mine(mineId, r.getInt("mine_attempt"), r.getString("mine_status"),
                    r.getBoolean("mine_late"), r.getBigDecimal("mine_marks"),
                    r.getObject("mine_submitted_at", OffsetDateTime.class));
        } else {
            counts = new Counts(r.getLong("roster"), r.getLong("submitted"),
                    "DRAFT".equals(status) ? 0 : r.getLong("pending"), r.getLong("late"), r.getLong("to_mark"),
                    r.getLong("evaluated"), r.getLong("returned"));
        }
        return new Assignment(r.getLong("id"), r.getString("title"), r.getString("description"),
                r.getString("instructions"), issue, due, r.getBigDecimal("max_marks"), formats(r, "allowed_formats"),
                r.getInt("max_file_bytes"), latePolicy, lateUntil, r.getString("resubmission"), status,
                r.getObject("published_at", OffsetDateTime.class), r.getObject("closed_at", OffsetDateTime.class),
                r.getLong("teaching_assignment_id"), r.getLong("subject_id"), r.getString("subject_code"),
                r.getString("subject"), r.getLong("division_id"), r.getString("division"), r.getLong("semester_id"),
                r.getString("semester"), r.getLong("program_id"), r.getString("program"), r.getLong("academic_year_id"),
                r.getString("academic_year"), role == CollegeRole.COLLEGE_ADMIN ? r.getLong("faculty_id") : null,
                r.getString("faculty"), r.getString("attachment_name"), r.getString("attachment_format"),
                size == null ? null : size.longValue(), r.getObject("created_at", OffsetDateTime.class),
                r.getObject("updated_at", OffsetDateTime.class), accepting(status, issue, due, latePolicy, lateUntil, now),
                now.isAfter(due), role == CollegeRole.FACULTY && (status.equals("DRAFT") || status.equals("PUBLISHED")),
                counts, mine);
    }
}
