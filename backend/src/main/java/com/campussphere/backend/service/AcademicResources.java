package com.campussphere.backend.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.StudyMaterials.FileDownload;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Academic Resources: syllabus versions (with optional units) and previous-year question papers.
 *
 * <ul>
 *   <li>Faculty upload only for subjects they currently teach (an active teaching assignment), and manage only their
 *       own uploads.</li>
 *   <li>Students see published resources only for subjects of semesters they are or were enrolled in (their own
 *       program's current and earlier semesters; never future ones).</li>
 *   <li>College Admins see and moderate everything of their own college.</li>
 * </ul>
 * The college and person always come from the signed-in account; every query is limited to that college, so another
 * college's resources are simply "not found". Files are PDFs in the existing private storage, served only through
 * these checks.
 */
@Service
@Transactional
public class AcademicResources {
    static final Set<String> EXAM_TYPES = Set.of("MID1", "MID2", "FINAL", "OTHER");
    static final int PAGE = 50;

    public enum Kind {
        SYLLABUS("syllabus_versions", "syllabus_version_id"), PAPER("question_papers", "question_paper_id");
        final String table, eventColumn;
        Kind(String table, String eventColumn) { this.table = table; this.eventColumn = eventColumn; }
    }

    // ---- Shapes ----

    public record SubjectChoice(long subjectId, String code, String name, String department, String program,
                                String semester, String academicYear) {}
    public record SemesterChoice(long semesterId, String semester, String academicYear, String program, boolean current) {}
    public record StudentSubject(long subjectId, String code, String name, long semesterId, String semester,
                                 String academicYear, long syllabusVersions, long papers) {}
    public record SyllabusFields(String title, String description, String versionLabel, LocalDate effectiveFrom) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record PaperFields(String examType, String examTypeLabel, Integer examYear, String examSession, String title,
                              String description) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record Unit(Integer number, String title, String description, String topics, String objectives, String references) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    public record Moderation(String action, String note) {
        @JsonAnySetter public void rejectUnknownField(String f, Object v) { throw new IllegalArgumentException("Unsupported field"); }
    }
    /** One syllabus version or question paper, as the viewer may see it. */
    public record Entry(long id, String kind, long subjectId, String subjectCode, String subject, String program,
                           String semester, String academicYear, String faculty, String title, String description,
                           String versionLabel, LocalDate effectiveFrom, boolean current,
                           String examType, String examTypeLabel, Integer examYear, String examSession,
                           String fileName, long sizeBytes, String status, String moderationNote,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt, boolean canEdit, boolean canModerate,
                           List<Unit> units) {}
    public record Filter(Long subjectId, Long semesterId, Integer examYear, String examType, String status, Long facultyId,
                         String q, int page) {
        public Filter {
            q = q == null || q.isBlank() ? null : q.strip();
            if ((q != null && q.length() > 80) || (examType != null && !EXAM_TYPES.contains(examType))
                    || (examYear != null && (examYear < 1990 || examYear > 2100))
                    || (status != null && !Set.of("PUBLISHED", "ARCHIVED", "HIDDEN", "REMOVED").contains(status))
                    || page < 0 || page > 1000) throw ApiError.RESOURCE_INVALID.exception();
        }
        public static Filter none() { return new Filter(null, null, null, null, null, null, null, 0); }
    }
    public record Coverage(long subjectId, String code, String subject, String program, String semester,
                           String academicYear, long syllabusVersions, boolean hasCurrentSyllabus, long papers) {}
    public record Count(String label, long count) {}
    public record Stats(List<Count> syllabusByStatus, List<Count> papersByStatus, List<Count> uploadsByMonth,
                        List<Count> uploadsByFaculty) {}
    public record Event(String action, String actor, String note, OffsetDateTime time) {}

    private final JdbcTemplate db;
    private final MaterialFiles files;

    public AcademicResources(JdbcTemplate db, MaterialFiles files) {
        this.db = db;
        this.files = files;
    }

    // ---- Rules (no database) ----

    private static String text(String value, int max, boolean required) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() > max || (required && clean.isEmpty())) throw ApiError.RESOURCE_INVALID.exception();
        return clean;
    }

    static SyllabusFields clean(SyllabusFields in) {
        if (in == null) throw ApiError.RESOURCE_INVALID.exception();
        LocalDate from = in.effectiveFrom();
        if (from != null && (from.getYear() < 1990 || from.getYear() > 2100)) throw ApiError.RESOURCE_INVALID.exception();
        return new SyllabusFields(text(in.title(), 160, true), text(in.description(), 2000, false),
                text(in.versionLabel(), 40, true), from);
    }

    static PaperFields clean(PaperFields in) {
        if (in == null || in.examType() == null || !EXAM_TYPES.contains(in.examType()) || in.examYear() == null
                || in.examYear() < 1990 || in.examYear() > 2100) throw ApiError.RESOURCE_INVALID.exception();
        String label = in.examType().equals("OTHER") ? text(in.examTypeLabel(), 60, true) : null;
        if (!in.examType().equals("OTHER") && in.examTypeLabel() != null && !in.examTypeLabel().isBlank())
            throw ApiError.RESOURCE_INVALID.exception();
        return new PaperFields(in.examType(), label, in.examYear(), text(in.examSession(), 60, false),
                text(in.title(), 160, true), text(in.description(), 2000, false));
    }

    static List<Unit> clean(List<Unit> units) {
        if (units == null || units.size() > 30) throw ApiError.RESOURCE_INVALID.exception();
        var seen = new HashSet<Integer>();
        var clean = new ArrayList<Unit>();
        for (Unit u : units) {
            if (u == null || u.number() == null || u.number() < 1 || u.number() > 30 || !seen.add(u.number()))
                throw ApiError.RESOURCE_INVALID.exception();
            clean.add(new Unit(u.number(), text(u.title(), 160, true), text(u.description(), 2000, false),
                    text(u.topics(), 4000, false), text(u.objectives(), 2000, false), text(u.references(), 2000, false)));
        }
        clean.sort(Comparator.comparingInt(Unit::number));
        return List.copyOf(clean);
    }

    /** The status a moderation or archive action leads to, or null when it is not allowed from [from]. */
    static String next(String action, String from) {
        return switch (action) {
            case "ARCHIVE" -> from.equals("PUBLISHED") ? "ARCHIVED" : null;
            case "UNARCHIVE" -> from.equals("ARCHIVED") ? "PUBLISHED" : null;
            case "HIDE" -> from.equals("PUBLISHED") || from.equals("ARCHIVED") ? "HIDDEN" : null;
            case "RESTORE" -> from.equals("HIDDEN") ? "PUBLISHED" : null;
            case "REMOVE" -> from.equals("REMOVED") ? null : "REMOVED";
            default -> throw ApiError.RESOURCE_INVALID.exception();
        };
    }

    private static MaterialFiles.Upload pdf(MultipartFile file) throws IOException {
        try {
            return MaterialFiles.inspect(file, EnumSet.of(MaterialFiles.Format.PDF), MaterialFiles.LIMIT);
        } catch (ApiError.Failure failure) {
            throw failure.error() == ApiError.MATERIAL_FILE_INVALID ? ApiError.RESOURCE_FILE_INVALID.exception() : failure;
        }
    }

    // ---- Reading ----

    private static final String SUBJECT_JOINS = """
            JOIN public.academic_subjects s ON s.college_id = r.college_id AND s.id = r.subject_id
            JOIN public.academic_semesters sem ON sem.college_id = s.college_id AND sem.id = s.semester_id
            JOIN public.academic_programs p ON p.college_id = s.college_id AND p.id = s.program_id
            JOIN public.academic_years y ON y.college_id = sem.college_id AND y.id = sem.academic_year_id
            JOIN public.users fu ON fu.id = r.faculty_id
            """;

    private static String select(Kind kind) {
        String specific = kind == Kind.SYLLABUS
                ? """
                  r.version_label, r.effective_from,
                  r.id = (SELECT max(c.id) FROM public.syllabus_versions c WHERE c.college_id = r.college_id
                          AND c.subject_id = r.subject_id AND c.status = 'PUBLISHED') is_current,
                  NULL exam_type, NULL exam_type_label, NULL exam_year, NULL exam_session,"""
                : """
                  NULL version_label, NULL effective_from, false is_current,
                  r.exam_type, r.exam_type_label, r.exam_year, r.exam_session,""";
        return "SELECT r.id, r.subject_id, s.code subject_code, s.name subject, p.name program, sem.name semester, "
                + "y.name academic_year, fu.full_name faculty, r.title, r.description, " + specific
                + " r.original_filename, r.byte_size, r.status, r.moderation_note, r.created_at, r.updated_at, r.faculty_id, "
                + "EXISTS (SELECT 1 FROM public.faculty_subject_assignments t WHERE t.college_id = r.college_id "
                + "AND t.faculty_id = r.faculty_id AND t.subject_id = r.subject_id AND t.ended_at IS NULL) still_teaching "
                + "FROM public." + kind.table + " r\n" + SUBJECT_JOINS + "WHERE r.college_id = ?";
    }

    /** What each role may see, with its arguments. */
    private static void scope(CollegeRole role, long me, StringBuilder sql, List<Object> args) {
        switch (role) {
            case FACULTY -> { sql.append(" AND r.faculty_id = ?"); args.add(me); }
            case STUDENT -> {
                sql.append(" AND r.status = 'PUBLISHED' AND EXISTS (SELECT 1 FROM public.student_enrollments e")
                   .append(" WHERE e.college_id = s.college_id AND e.student_id = ? AND e.program_id = s.program_id")
                   .append(" AND e.semester_id = s.semester_id)");
                args.add(me);
            }
            default -> { }
        }
    }

    private static Entry map(ResultSet r, Kind kind, CollegeRole role, long me) throws SQLException {
        String status = r.getString("status");
        boolean mine = r.getLong("faculty_id") == me && role == CollegeRole.FACULTY;
        Number year = (Number) r.getObject("exam_year");
        return new Entry(r.getLong("id"), kind.name(), r.getLong("subject_id"), r.getString("subject_code"),
                r.getString("subject"), r.getString("program"), r.getString("semester"), r.getString("academic_year"),
                r.getString("faculty"), r.getString("title"), r.getString("description"), r.getString("version_label"),
                r.getObject("effective_from", LocalDate.class), r.getBoolean("is_current"), r.getString("exam_type"),
                r.getString("exam_type_label"), year == null ? null : year.intValue(), r.getString("exam_session"),
                r.getString("original_filename"), r.getLong("byte_size"), status,
                role == CollegeRole.STUDENT ? null : r.getString("moderation_note"),
                r.getObject("created_at", OffsetDateTime.class), r.getObject("updated_at", OffsetDateTime.class),
                mine && r.getBoolean("still_teaching") && (status.equals("PUBLISHED") || status.equals("ARCHIVED")),
                role == CollegeRole.COLLEGE_ADMIN && !status.equals("REMOVED"), List.of());
    }

    /** The signed-in account's own college for that role; students are further limited by their enrollments. */
    static long college(Account actor, CollegeRole role) {
        return actor.requireCollege(role, null);
    }

    public List<Entry> list(Account actor, CollegeRole role, Kind kind, Filter f) {
        long college = college(actor, role);
        var sql = new StringBuilder(select(kind));
        var args = new ArrayList<Object>(List.of(college));
        scope(role, actor.id(), sql, args);
        if (f.subjectId() != null) { sql.append(" AND r.subject_id = ?"); args.add(f.subjectId()); }
        if (f.semesterId() != null) { sql.append(" AND s.semester_id = ?"); args.add(f.semesterId()); }
        if (kind == Kind.PAPER && f.examYear() != null) { sql.append(" AND r.exam_year = ?"); args.add(f.examYear()); }
        if (kind == Kind.PAPER && f.examType() != null) { sql.append(" AND r.exam_type = ?"); args.add(f.examType()); }
        if (role != CollegeRole.STUDENT && f.status() != null) { sql.append(" AND r.status = ?"); args.add(f.status()); }
        if (role == CollegeRole.COLLEGE_ADMIN && f.facultyId() != null) { sql.append(" AND r.faculty_id = ?"); args.add(f.facultyId()); }
        if (f.q() != null) {
            sql.append(" AND (strpos(lower(r.title), lower(?)) > 0 OR strpos(lower(s.name), lower(?)) > 0 OR strpos(lower(s.code), lower(?)) > 0)");
            args.add(f.q()); args.add(f.q()); args.add(f.q());
        }
        sql.append(kind == Kind.SYLLABUS ? " ORDER BY s.code, r.id DESC" : " ORDER BY r.exam_year DESC, s.code, r.id DESC");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(PAGE); args.add(f.page() * PAGE);
        return db.query(sql.toString(), (r, i) -> map(r, kind, role, actor.id()), args.toArray());
    }

    private Entry find(Account actor, CollegeRole role, Kind kind, long id, boolean lock) {
        long college = college(actor, role);
        var sql = new StringBuilder(select(kind));
        var args = new ArrayList<Object>(List.of(college));
        scope(role, actor.id(), sql, args);
        sql.append(" AND r.id = ?");
        args.add(id);
        if (lock) sql.append(" FOR UPDATE OF r");
        return db.query(sql.toString(), (r, i) -> map(r, kind, role, actor.id()), args.toArray()).stream().findFirst()
                .orElseThrow(ApiError.RESOURCE_NOT_FOUND::exception);
    }

    /** One resource; a syllabus comes with its units. */
    public Entry detail(Account actor, CollegeRole role, Kind kind, long id) {
        Entry found = find(actor, role, kind, id, false);
        if (kind == Kind.PAPER) return found;
        List<Unit> units = db.query("""
                SELECT unit_number, title, description, topics, objectives, references_text FROM public.syllabus_units
                WHERE college_id = ? AND version_id = ? ORDER BY unit_number
                """, (r, i) -> new Unit(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6)), college(actor, role), id);
        return withUnits(found, units);
    }

    private static Entry withUnits(Entry r, List<Unit> units) {
        return new Entry(r.id(), r.kind(), r.subjectId(), r.subjectCode(), r.subject(), r.program(), r.semester(),
                r.academicYear(), r.faculty(), r.title(), r.description(), r.versionLabel(), r.effectiveFrom(), r.current(),
                r.examType(), r.examTypeLabel(), r.examYear(), r.examSession(), r.fileName(), r.sizeBytes(), r.status(),
                r.moderationNote(), r.createdAt(), r.updatedAt(), r.canEdit(), r.canModerate(), units);
    }

    /** The PDF, after the same checks as reading the resource itself. */
    public FileDownload download(Account actor, CollegeRole role, Kind kind, long id) {
        Entry found = find(actor, role, kind, id, false);
        UUID key = db.queryForObject("SELECT storage_key FROM public." + kind.table + " WHERE college_id = ? AND id = ?",
                UUID.class, college(actor, role), id);
        Path path = files.path(key);
        if (!Files.isRegularFile(path)) throw ApiError.RESOURCE_NOT_FOUND.exception();
        return new FileDownload(path, found.fileName(), "application/pdf", found.sizeBytes());
    }

    // ---- Faculty ----

    /** Subjects the faculty member currently teaches (in any class); the only subjects they can upload for. */
    public List<SubjectChoice> teachableSubjects(Account actor) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        return db.query("""
                SELECT DISTINCT s.id, s.code, s.name, d.name, p.name, sem.name, y.name
                FROM public.faculty_subject_assignments a
                JOIN public.academic_subjects s ON s.college_id = a.college_id AND s.id = a.subject_id
                JOIN public.academic_semesters sem ON sem.college_id = s.college_id AND sem.id = s.semester_id
                JOIN public.academic_programs p ON p.college_id = s.college_id AND p.id = s.program_id
                JOIN public.departments d ON d.college_id = a.college_id AND d.id = a.department_id
                JOIN public.academic_years y ON y.college_id = sem.college_id AND y.id = sem.academic_year_id
                WHERE a.college_id = ? AND a.faculty_id = ? AND a.ended_at IS NULL
                ORDER BY s.code, s.id
                """, (r, i) -> new SubjectChoice(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
                r.getString(5), r.getString(6), r.getString(7)), college, actor.id());
    }

    /** The faculty member's active teaching assignment for the subject (any class); never trusts the app's subject. */
    private long[] teaching(long college, long faculty, Long subjectId) {
        if (subjectId == null) throw ApiError.RESOURCE_INVALID.exception();
        return db.query("""
                SELECT id, division_id FROM public.faculty_subject_assignments
                WHERE college_id = ? AND faculty_id = ? AND subject_id = ? AND ended_at IS NULL ORDER BY id LIMIT 1 FOR SHARE
                """, (r, i) -> new long[]{r.getLong(1), r.getLong(2)}, college, faculty, subjectId).stream().findFirst()
                .orElseThrow(ApiError.RESOURCE_NOT_FOUND::exception);
    }

    /** Saves the file once the database work commits; a failed transaction leaves no stray file. */
    private void store(UUID key, byte[] bytes) throws IOException {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { files.delete(key); } catch (IOException e) { throw new UncheckedIOException(e); }
                }
            }
        });
        files.save(key, bytes);
    }

    @Transactional(rollbackFor = IOException.class)
    public Entry uploadSyllabus(Account actor, Long subjectId, SyllabusFields input, MultipartFile file) throws IOException {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        SyllabusFields in = clean(input);
        long[] t = teaching(college, actor.id(), subjectId);
        var upload = pdf(file);
        UUID key = UUID.randomUUID();
        long id;
        try {
            id = db.queryForObject("""
                    INSERT INTO public.syllabus_versions (college_id, assignment_id, faculty_id, division_id, subject_id,
                        title, description, version_label, effective_from, original_filename, storage_key, byte_size, sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, college, t[0], actor.id(), t[1], subjectId, in.title(), in.description(),
                    in.versionLabel(), in.effectiveFrom(), upload.fileName(), key, upload.bytes().length, upload.sha256());
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.RESOURCE_DUPLICATE.exception();
        }
        event(college, Kind.SYLLABUS, id, actor.id(), "UPLOAD", null);
        store(key, upload.bytes());
        return find(actor, CollegeRole.FACULTY, Kind.SYLLABUS, id, false);
    }

    @Transactional(rollbackFor = IOException.class)
    public Entry uploadPaper(Account actor, Long subjectId, PaperFields input, MultipartFile file) throws IOException {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        PaperFields in = clean(input);
        long[] t = teaching(college, actor.id(), subjectId);
        var upload = pdf(file);
        UUID key = UUID.randomUUID();
        long id;
        try {
            id = db.queryForObject("""
                    INSERT INTO public.question_papers (college_id, assignment_id, faculty_id, division_id, subject_id,
                        exam_type, exam_type_label, exam_year, exam_session, title, description, original_filename,
                        storage_key, byte_size, sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, college, t[0], actor.id(), t[1], subjectId, in.examType(), in.examTypeLabel(),
                    in.examYear(), in.examSession(), in.title(), in.description(), upload.fileName(), key,
                    upload.bytes().length, upload.sha256());
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.RESOURCE_DUPLICATE.exception();
        }
        event(college, Kind.PAPER, id, actor.id(), "UPLOAD", null);
        store(key, upload.bytes());
        return find(actor, CollegeRole.FACULTY, Kind.PAPER, id, false);
    }

    private Entry ownEditable(Account actor, Kind kind, long id) {
        Entry current = find(actor, CollegeRole.FACULTY, kind, id, true);
        if (!current.canEdit()) throw ApiError.RESOURCE_STATE_CONFLICT.exception();
        return current;
    }

    public Entry editSyllabus(Account actor, long id, SyllabusFields input) {
        SyllabusFields in = clean(input);
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        ownEditable(actor, Kind.SYLLABUS, id);
        try {
            db.update("""
                    UPDATE public.syllabus_versions SET title = ?, description = ?, version_label = ?, effective_from = ?,
                        updated_at = statement_timestamp() WHERE college_id = ? AND id = ?
                    """, in.title(), in.description(), in.versionLabel(), in.effectiveFrom(), college, id);
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.RESOURCE_DUPLICATE.exception();
        }
        event(college, Kind.SYLLABUS, id, actor.id(), "EDIT", null);
        return detail(actor, CollegeRole.FACULTY, Kind.SYLLABUS, id);
    }

    public Entry editPaper(Account actor, long id, PaperFields input) {
        PaperFields in = clean(input);
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        ownEditable(actor, Kind.PAPER, id);
        db.update("""
                UPDATE public.question_papers SET exam_type = ?, exam_type_label = ?, exam_year = ?, exam_session = ?,
                    title = ?, description = ?, updated_at = statement_timestamp() WHERE college_id = ? AND id = ?
                """, in.examType(), in.examTypeLabel(), in.examYear(), in.examSession(), in.title(), in.description(),
                college, id);
        event(college, Kind.PAPER, id, actor.id(), "EDIT", null);
        return find(actor, CollegeRole.FACULTY, Kind.PAPER, id, false);
    }

    /** A corrected PDF for the same syllabus version or paper; the old file is deleted once this commits. */
    @Transactional(rollbackFor = IOException.class)
    public Entry replaceFile(Account actor, Kind kind, long id, MultipartFile file) throws IOException {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        ownEditable(actor, kind, id);
        var upload = pdf(file);
        UUID old = db.queryForObject("SELECT storage_key FROM public." + kind.table + " WHERE college_id = ? AND id = ?",
                UUID.class, college, id);
        UUID key = UUID.randomUUID();
        try {
            db.update("UPDATE public." + kind.table + " SET original_filename = ?, storage_key = ?, byte_size = ?, sha256 = ?, "
                    + "updated_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                    upload.fileName(), key, upload.bytes().length, upload.sha256(), college, id);
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.RESOURCE_DUPLICATE.exception();
        }
        event(college, kind, id, actor.id(), "REPLACE", null);
        store(key, upload.bytes());
        Assignments.afterCommit(committed -> { if (committed) files.delete(old); });
        return detail(actor, CollegeRole.FACULTY, kind, id);
    }

    /** The faculty member archives (or brings back) their own upload; archived resources are not shown to students. */
    public Entry archive(Account actor, Kind kind, long id, boolean archive) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        Entry current = ownEditable(actor, kind, id);
        String action = archive ? "ARCHIVE" : "UNARCHIVE";
        String to = next(action, current.status());
        if (to == null) throw ApiError.RESOURCE_STATE_CONFLICT.exception();
        db.update("UPDATE public." + kind.table + " SET status = ?, updated_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                to, college, id);
        event(college, kind, id, actor.id(), action, null);
        return detail(actor, CollegeRole.FACULTY, kind, id);
    }

    /** Replaces the unit-wise content of the faculty member's own syllabus version (an empty list removes it). */
    public Entry saveUnits(Account actor, long id, List<Unit> input) {
        List<Unit> units = clean(input);
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        ownEditable(actor, Kind.SYLLABUS, id);
        db.update("DELETE FROM public.syllabus_units WHERE college_id = ? AND version_id = ?", college, id);
        for (Unit u : units) {
            db.update("""
                    INSERT INTO public.syllabus_units (college_id, version_id, unit_number, title, description, topics,
                        objectives, references_text) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, college, id, u.number(), u.title(), u.description(), u.topics(), u.objectives(), u.references());
        }
        event(college, Kind.SYLLABUS, id, actor.id(), "UNITS", units.size() + " unit(s)");
        return detail(actor, CollegeRole.FACULTY, Kind.SYLLABUS, id);
    }

    // ---- Students ----

    /** Semesters the student is or was enrolled in, current first. */
    public List<SemesterChoice> semesters(Account actor) {
        long college = college(actor, CollegeRole.STUDENT);
        return db.query("""
                SELECT sem.id, sem.name, y.name, p.name, bool_or(e.ended_at IS NULL) current
                FROM public.student_enrollments e
                JOIN public.academic_semesters sem ON sem.college_id = e.college_id AND sem.id = e.semester_id
                JOIN public.academic_years y ON y.college_id = sem.college_id AND y.id = sem.academic_year_id
                JOIN public.academic_programs p ON p.college_id = e.college_id AND p.id = e.program_id
                WHERE e.college_id = ? AND e.student_id = ?
                GROUP BY sem.id, sem.name, y.name, p.name ORDER BY bool_or(e.ended_at IS NULL) DESC, sem.id DESC
                """, (r, i) -> new SemesterChoice(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
                r.getBoolean(5)), college, actor.id());
    }

    /** Subjects of those semesters, with how many published syllabus versions and papers each has. */
    public List<StudentSubject> subjects(Account actor, Long semesterId) {
        long college = college(actor, CollegeRole.STUDENT);
        var sql = new StringBuilder("""
                SELECT s.id, s.code, s.name, sem.id, sem.name, y.name,
                       (SELECT count(*) FROM public.syllabus_versions v WHERE v.college_id = s.college_id
                          AND v.subject_id = s.id AND v.status = 'PUBLISHED'),
                       (SELECT count(*) FROM public.question_papers q WHERE q.college_id = s.college_id
                          AND q.subject_id = s.id AND q.status = 'PUBLISHED')
                FROM public.academic_subjects s
                JOIN public.academic_semesters sem ON sem.college_id = s.college_id AND sem.id = s.semester_id
                JOIN public.academic_years y ON y.college_id = sem.college_id AND y.id = sem.academic_year_id
                WHERE s.college_id = ? AND EXISTS (SELECT 1 FROM public.student_enrollments e WHERE e.college_id = s.college_id
                  AND e.student_id = ? AND e.program_id = s.program_id AND e.semester_id = s.semester_id)""");
        var args = new ArrayList<Object>(List.of(college, actor.id()));
        if (semesterId != null) { sql.append(" AND s.semester_id = ?"); args.add(semesterId); }
        sql.append(" ORDER BY sem.id DESC, s.code");
        return db.query(sql.toString(), (r, i) -> new StudentSubject(r.getLong(1), r.getString(2), r.getString(3),
                r.getLong(4), r.getString(5), r.getString(6), r.getLong(7), r.getLong(8)), args.toArray());
    }

    // ---- College Admin ----

    public Entry moderate(Account actor, Kind kind, long id, Moderation request) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        String action = request == null ? null : request.action();
        if (action == null || !Set.of("HIDE", "RESTORE", "REMOVE", "ARCHIVE").contains(action)) throw ApiError.RESOURCE_INVALID.exception();
        String note = request.note() == null ? "" : request.note().strip();
        if ((action.equals("HIDE") || action.equals("REMOVE")) && (note.length() < 3 || note.length() > 500))
            throw ApiError.RESOURCE_INVALID.exception();
        if (note.length() > 500) throw ApiError.RESOURCE_INVALID.exception();
        Entry current = find(actor, CollegeRole.COLLEGE_ADMIN, kind, id, true);
        String to = next(action, current.status());
        if (to == null) throw ApiError.RESOURCE_STATE_CONFLICT.exception();
        boolean noted = to.equals("HIDDEN") || to.equals("REMOVED");
        db.update("UPDATE public." + kind.table + " SET status = ?, moderation_note = ?, updated_at = statement_timestamp() "
                + "WHERE college_id = ? AND id = ?", to, noted ? note : null, college, id);
        event(college, kind, id, actor.id(), action, note.isEmpty() ? null : note);
        return detail(actor, CollegeRole.COLLEGE_ADMIN, kind, id);
    }

    public List<Event> events(Account actor, Kind kind, long id) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        find(actor, CollegeRole.COLLEGE_ADMIN, kind, id, false);
        return db.query("SELECT e.action, u.full_name, e.note, e.occurred_at FROM public.academic_resource_events e "
                + "JOIN public.users u ON u.id = e.actor_id WHERE e.college_id = ? AND e." + kind.eventColumn + " = ? ORDER BY e.id",
                (r, i) -> new Event(r.getString(1), r.getString(2), r.getString(3), r.getObject(4, OffsetDateTime.class)),
                college, id);
    }

    /** Every subject of the college with its syllabus and question paper availability; optional semester filter. */
    public List<Coverage> coverage(Account actor, Long semesterId) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var sql = new StringBuilder("""
                SELECT s.id, s.code, s.name, p.name, sem.name, y.name,
                       (SELECT count(*) FROM public.syllabus_versions v WHERE v.college_id = s.college_id AND v.subject_id = s.id
                          AND v.status <> 'REMOVED'),
                       EXISTS (SELECT 1 FROM public.syllabus_versions v WHERE v.college_id = s.college_id AND v.subject_id = s.id
                          AND v.status = 'PUBLISHED'),
                       (SELECT count(*) FROM public.question_papers q WHERE q.college_id = s.college_id AND q.subject_id = s.id
                          AND q.status = 'PUBLISHED')
                FROM public.academic_subjects s
                JOIN public.academic_semesters sem ON sem.college_id = s.college_id AND sem.id = s.semester_id
                JOIN public.academic_programs p ON p.college_id = s.college_id AND p.id = s.program_id
                JOIN public.academic_years y ON y.college_id = sem.college_id AND y.id = sem.academic_year_id
                WHERE s.college_id = ?""");
        var args = new ArrayList<Object>(List.of(college));
        if (semesterId != null) { sql.append(" AND s.semester_id = ?"); args.add(semesterId); }
        sql.append(" ORDER BY p.name, sem.id, s.code LIMIT 500");
        return db.query(sql.toString(), (r, i) -> new Coverage(r.getLong(1), r.getString(2), r.getString(3), r.getString(4),
                r.getString(5), r.getString(6), r.getLong(7), r.getBoolean(8), r.getLong(9)), args.toArray());
    }

    public Stats stats(Account actor) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var byStatus = new Object() {
            List<Count> of(String table) {
                return db.query("SELECT status, count(*) FROM public." + table + " WHERE college_id = ? GROUP BY status ORDER BY status",
                        (r, i) -> new Count(r.getString(1), r.getLong(2)), college);
            }
        };
        List<Count> months = db.query("""
                SELECT to_char(date_trunc('month', occurred_at), 'YYYY-MM'), count(*) FROM public.academic_resource_events
                WHERE college_id = ? AND action = 'UPLOAD' AND occurred_at >= statement_timestamp() - interval '12 months'
                GROUP BY 1 ORDER BY 1
                """, (r, i) -> new Count(r.getString(1), r.getLong(2)), college);
        List<Count> faculty = db.query("""
                SELECT u.full_name, count(*) FROM public.academic_resource_events e JOIN public.users u ON u.id = e.actor_id
                WHERE e.college_id = ? AND e.action = 'UPLOAD' GROUP BY u.id, u.full_name ORDER BY count(*) DESC, u.full_name LIMIT 10
                """, (r, i) -> new Count(r.getString(1), r.getLong(2)), college);
        return new Stats(byStatus.of(Kind.SYLLABUS.table), byStatus.of(Kind.PAPER.table), months, faculty);
    }

    private void event(long college, Kind kind, long id, long actor, String action, String note) {
        db.update("INSERT INTO public.academic_resource_events (college_id, " + kind.eventColumn + ", actor_id, action, note) "
                + "VALUES (?, ?, ?, ?, ?)", college, id, actor, action, note);
    }
}
