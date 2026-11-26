package com.campussphere.backend.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Study materials for Faculty (own active assignments), Students (current enrollment) and College Admins
 * (own college). College, faculty and student identity always come from the authenticated account.
 */
@Service
@Transactional(readOnly = true)
public class StudyMaterials {
    public static final Set<String> TYPES =
            Set.of("NOTES", "SLIDES", "ASSIGNMENT", "QUESTION_BANK", "LAB_MANUAL", "REFERENCE", "OTHER");
    public static final Set<String> STATUSES = Set.of("PUBLISHED", "HIDDEN", "REMOVED", "DELETED");

    public record Fields(String title, String description, String topic, String type) {
        Fields clean() {
            String t = title == null ? "" : title.strip();
            String d = description == null ? "" : description.strip();
            String p = topic == null || topic.isBlank() ? null : topic.strip();
            if (t.isEmpty() || t.length() > 160 || d.length() > 2000 || (p != null && p.length() > 120)
                    || type == null || !TYPES.contains(type)) throw ApiError.INVALID_REQUEST.exception();
            return new Fields(t, d, p, type);
        }
    }
    public record Filter(Long subjectId, Long divisionId, Long facultyId, String type, String status, String q) {
        public Filter {
            if (type != null && (type.isBlank() || !TYPES.contains(type))) throw ApiError.INVALID_REQUEST.exception();
            if (status != null && (status.isBlank() || !STATUSES.contains(status))) throw ApiError.INVALID_REQUEST.exception();
            q = q == null || q.isBlank() ? null : q.strip();
            if (q != null && q.length() > 100) throw ApiError.INVALID_REQUEST.exception();
        }
        public static Filter none() { return new Filter(null, null, null, null, null, null); }
    }
    public record Moderation(String action, String note) {}
    public record Assignment(long id, long subjectId, String subject, long divisionId, String division,
                             String semester, String program, String academicYear) {}
    public record Material(long id, String title, String description, String topic, String type,
                           long subjectId, String subject, long divisionId, String division, String semester,
                           String program, String academicYear, Long facultyId, String faculty, long assignmentId,
                           String fileName, String format, String mimeType, long sizeBytes, String status,
                           String moderationNote, OffsetDateTime uploadedAt, OffsetDateTime updatedAt,
                           boolean canEdit, boolean canModerate) {}
    public record Event(String action, String actor, String note, OffsetDateTime time) {}
    public record FileDownload(Path path, String fileName, String mimeType, long sizeBytes) {}

    private final JdbcTemplate db;
    private final MaterialFiles files;

    public StudyMaterials(JdbcTemplate db, MaterialFiles files) {
        this.db = db;
        this.files = files;
    }

    private static final String SELECT = """
            SELECT m.id, m.title, m.description, m.topic, m.material_type, m.subject_id, s.name subject,
                   m.division_id, d.name division, sem.name semester, p.name program, y.name academic_year,
                   m.faculty_id, u.full_name faculty, m.assignment_id, m.original_filename, m.file_format,
                   m.mime_type, m.byte_size, m.status, m.moderation_note, m.created_at, m.updated_at
            FROM public.study_materials m
            JOIN public.faculty_subject_assignments a ON a.college_id = m.college_id AND a.id = m.assignment_id
            JOIN public.academic_subjects s ON s.college_id = m.college_id AND s.id = m.subject_id
            JOIN public.academic_divisions d ON d.college_id = m.college_id AND d.id = m.division_id
            JOIN public.academic_semesters sem ON sem.college_id = a.college_id AND sem.id = a.semester_id
            JOIN public.academic_programs p ON p.college_id = a.college_id AND p.id = a.program_id
            JOIN public.academic_years y ON y.college_id = a.college_id AND y.id = a.academic_year_id
            JOIN public.users u ON u.id = m.faculty_id
            WHERE m.college_id = ?
            """;

    /** Extra WHERE clause that limits rows to what the role may see. Every clause takes exactly one parameter. */
    private static String scope(CollegeRole role) {
        return switch (role) {
            case COLLEGE_ADMIN -> " AND ? > 0 ";
            case FACULTY -> " AND m.faculty_id = ? AND a.ended_at IS NULL AND m.status <> 'DELETED' ";
            case STUDENT -> """
                     AND m.status = 'PUBLISHED' AND EXISTS (SELECT 1 FROM public.student_enrollments e
                         WHERE e.college_id = a.college_id AND e.student_id = ? AND e.ended_at IS NULL
                           AND e.department_id = a.department_id AND e.program_id = a.program_id
                           AND e.academic_year_id = a.academic_year_id AND e.semester_id = a.semester_id
                           AND e.division_id = a.division_id)
                    """;
        };
    }

    public List<Assignment> assignments(Account actor) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        return db.query("""
                SELECT a.id, a.subject_id, s.name subject, a.division_id, d.name division, sem.name semester,
                       p.name program, y.name academic_year
                FROM public.faculty_subject_assignments a
                JOIN public.academic_subjects s ON s.college_id = a.college_id AND s.id = a.subject_id
                JOIN public.academic_divisions d ON d.college_id = a.college_id AND d.id = a.division_id
                JOIN public.academic_semesters sem ON sem.college_id = a.college_id AND sem.id = a.semester_id
                JOIN public.academic_programs p ON p.college_id = a.college_id AND p.id = a.program_id
                JOIN public.academic_years y ON y.college_id = a.college_id AND y.id = a.academic_year_id
                WHERE a.college_id = ? AND a.faculty_id = ? AND a.ended_at IS NULL
                ORDER BY s.name, d.name, a.id
                """, (r, i) -> new Assignment(r.getLong(1), r.getLong(2), r.getString(3), r.getLong(4),
                r.getString(5), r.getString(6), r.getString(7), r.getString(8)), college, actor.id());
    }

    public List<Material> list(Account actor, CollegeRole role, Filter filter) {
        long college = actor.requireCollege(role, null);
        var sql = new StringBuilder(SELECT).append(scope(role));
        var args = new ArrayList<Object>(List.of(college, actor.id()));
        if (filter.subjectId() != null) { sql.append(" AND m.subject_id = ?"); args.add(filter.subjectId()); }
        if (filter.divisionId() != null) { sql.append(" AND m.division_id = ?"); args.add(filter.divisionId()); }
        if (filter.facultyId() != null && role == CollegeRole.COLLEGE_ADMIN) {
            sql.append(" AND m.faculty_id = ?"); args.add(filter.facultyId());
        }
        if (filter.type() != null) { sql.append(" AND m.material_type = ?"); args.add(filter.type()); }
        if (filter.status() != null && role != CollegeRole.STUDENT) { sql.append(" AND m.status = ?"); args.add(filter.status()); }
        if (filter.q() != null) {
            sql.append(" AND strpos(lower(m.title), lower(?)) > 0");
            args.add(filter.q());
        }
        sql.append(" ORDER BY m.created_at DESC, m.id DESC LIMIT 200");
        return db.query(sql.toString(), (r, i) -> map(r, role), args.toArray());
    }

    public Material detail(Account actor, CollegeRole role, long id) {
        return find(actor, role, id, false);
    }

    private Material find(Account actor, CollegeRole role, long id, boolean lock) {
        long college = actor.requireCollege(role, null);
        String sql = SELECT + scope(role) + " AND m.id = ?" + (lock ? " FOR UPDATE OF m" : "");
        return db.query(sql, (r, i) -> map(r, role), college, actor.id(), id).stream().findFirst()
                .orElseThrow(ApiError.MATERIAL_NOT_FOUND::exception);
    }

    public FileDownload download(Account actor, CollegeRole role, long id) {
        Material material = find(actor, role, id, false);
        if (material.status().equals("DELETED")) throw ApiError.MATERIAL_NOT_FOUND.exception();
        UUID key = db.queryForObject("SELECT storage_key FROM public.study_materials WHERE college_id = ? AND id = ?",
                UUID.class, actor.requireCollege(role, null), id);
        Path path = files.path(key);
        if (!Files.isRegularFile(path)) throw ApiError.MATERIAL_NOT_FOUND.exception();
        return new FileDownload(path, material.fileName(), material.mimeType(), material.sizeBytes());
    }

    @Transactional(rollbackFor = IOException.class)
    public Material upload(Account actor, long assignmentId, Fields input, MultipartFile file) throws IOException {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        Fields fields = input.clean();
        var scope = db.query("""
                SELECT division_id, subject_id FROM public.faculty_subject_assignments
                WHERE college_id = ? AND id = ? AND faculty_id = ? AND ended_at IS NULL FOR SHARE
                """, (r, i) -> new long[]{r.getLong(1), r.getLong(2)}, college, assignmentId, actor.id());
        if (scope.isEmpty()) throw ApiError.MATERIAL_NOT_FOUND.exception();
        var upload = MaterialFiles.inspect(file);
        UUID key = UUID.randomUUID();
        long id;
        try {
            id = db.queryForObject("""
                    INSERT INTO public.study_materials (college_id, assignment_id, faculty_id, division_id, subject_id,
                        title, description, topic, material_type, original_filename, storage_key, file_format,
                        mime_type, byte_size, sha256)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, college, assignmentId, actor.id(), scope.get(0)[0], scope.get(0)[1],
                    fields.title(), fields.description(), fields.topic(), fields.type(), upload.fileName(), key,
                    upload.extension(), upload.mime(), upload.bytes().length, upload.sha256());
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.MATERIAL_DUPLICATE.exception();
        }
        event(college, id, actor.id(), "UPLOAD", null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { files.delete(key); } catch (IOException e) { throw new UncheckedIOException(e); }
                }
            }
        });
        files.save(key, upload.bytes());
        return find(actor, CollegeRole.FACULTY, id, false);
    }

    @Transactional
    public Material update(Account actor, long id, Fields input) {
        Fields fields = input.clean();
        Material current = find(actor, CollegeRole.FACULTY, id, true);
        if (!current.canEdit()) throw ApiError.MATERIAL_STATE_CONFLICT.exception();
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        db.update("""
                UPDATE public.study_materials SET title = ?, description = ?, topic = ?, material_type = ?,
                    updated_at = statement_timestamp() WHERE college_id = ? AND id = ?
                """, fields.title(), fields.description(), fields.topic(), fields.type(), college, id);
        event(college, id, actor.id(), "EDIT", null);
        return find(actor, CollegeRole.FACULTY, id, false);
    }

    /** Faculty delete is a soft delete: the record, file and history are kept for the college's audit. */
    @Transactional
    public void delete(Account actor, long id) {
        Material current = find(actor, CollegeRole.FACULTY, id, true);
        if (!current.canEdit()) throw ApiError.MATERIAL_STATE_CONFLICT.exception();
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        db.update("UPDATE public.study_materials SET status = 'DELETED', updated_at = statement_timestamp() WHERE college_id = ? AND id = ?",
                college, id);
        event(college, id, actor.id(), "DELETE", null);
    }

    /** HIDE (reversible) and REMOVE (final) need a reason; RESTORE returns a hidden material to students. */
    @Transactional
    public Material moderate(Account actor, long id, Moderation request) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        String action = request == null ? null : request.action();
        String note = request == null || request.note() == null ? null : request.note().strip();
        if (action == null || !Set.of("HIDE", "RESTORE", "REMOVE").contains(action)) throw ApiError.INVALID_REQUEST.exception();
        if (!action.equals("RESTORE") && (note == null || note.length() < 3 || note.length() > 500)) {
            throw ApiError.INVALID_REQUEST.exception();
        }
        Material current = find(actor, CollegeRole.COLLEGE_ADMIN, id, true);
        String from = current.status();
        String to = switch (action) {
            case "HIDE" -> from.equals("PUBLISHED") ? "HIDDEN" : null;
            case "RESTORE" -> from.equals("HIDDEN") ? "PUBLISHED" : null;
            default -> from.equals("PUBLISHED") || from.equals("HIDDEN") ? "REMOVED" : null;
        };
        if (to == null) throw ApiError.MATERIAL_STATE_CONFLICT.exception();
        db.update("""
                UPDATE public.study_materials SET status = ?, moderation_note = ?, updated_at = statement_timestamp()
                WHERE college_id = ? AND id = ?
                """, to, to.equals("PUBLISHED") ? null : note, college, id);
        event(college, id, actor.id(), action, note);
        return find(actor, CollegeRole.COLLEGE_ADMIN, id, false);
    }

    public List<Event> events(Account actor, long id) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        find(actor, CollegeRole.COLLEGE_ADMIN, id, false);
        return db.query("""
                SELECT e.action, u.full_name, e.note, e.occurred_at FROM public.study_material_events e
                JOIN public.users u ON u.id = e.actor_id
                WHERE e.college_id = ? AND e.material_id = ? ORDER BY e.id
                """, (r, i) -> new Event(r.getString(1), r.getString(2), r.getString(3),
                r.getObject(4, OffsetDateTime.class)), college, id);
    }

    private void event(long college, long material, long actor, String action, String note) {
        db.update("INSERT INTO public.study_material_events (college_id, material_id, actor_id, action, note) VALUES (?, ?, ?, ?, ?)",
                college, material, actor, action, note);
    }

    private static Material map(ResultSet r, CollegeRole role) throws SQLException {
        String status = r.getString("status");
        boolean manageable = status.equals("PUBLISHED") || status.equals("HIDDEN");
        boolean admin = role == CollegeRole.COLLEGE_ADMIN;
        return new Material(r.getLong("id"), r.getString("title"), r.getString("description"), r.getString("topic"),
                r.getString("material_type"), r.getLong("subject_id"), r.getString("subject"),
                r.getLong("division_id"), r.getString("division"), r.getString("semester"), r.getString("program"),
                r.getString("academic_year"), admin ? r.getLong("faculty_id") : null, r.getString("faculty"),
                r.getLong("assignment_id"), r.getString("original_filename"), r.getString("file_format"),
                r.getString("mime_type"), r.getLong("byte_size"), status,
                role == CollegeRole.STUDENT ? null : r.getString("moderation_note"),
                r.getObject("created_at", OffsetDateTime.class), r.getObject("updated_at", OffsetDateTime.class),
                role == CollegeRole.FACULTY && manageable, admin && !status.equals("DELETED") && !status.equals("REMOVED"));
    }
}
