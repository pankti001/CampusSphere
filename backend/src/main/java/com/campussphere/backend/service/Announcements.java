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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * College announcements. College, author and reader identity always come from the authenticated account.
 * College Admins manage every announcement of their college; Faculty manage only their own, and only for
 * divisions they actively teach; Students and Faculty read what is published for their audience.
 * Time policy: all instants are timestamptz and compared with the database clock, so SCHEDULED and EXPIRED
 * are derived, never stored, and cannot drift.
 */
@Service
@Transactional(readOnly = true)
public class Announcements {
    public static final Set<String> CATEGORIES = Set.of("GENERAL", "ACADEMIC", "EXAMINATION", "EVENTS", "HOLIDAYS",
            "FEES", "IMPORTANT_NOTICE", "EMERGENCY");
    public static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    public static final Set<String> AUDIENCES = Set.of("EVERYONE", "STUDENTS", "FACULTY");
    public static final Set<String> SCOPES = Set.of("COLLEGE", "DEPARTMENT", "PROGRAM", "ACADEMIC_YEAR", "SEMESTER", "DIVISION");
    public static final Set<String> STATUSES = Set.of("DRAFT", "SCHEDULED", "PUBLISHED", "EXPIRED", "ARCHIVED");
    static final int PAGE_SIZE = 30;

    public record Input(String title, String body, String category, String priority, String audience, String scope,
                        Long targetId, OffsetDateTime expiresAt) {
        /** Trims and validates the text fields and enumerations. Throws INVALID_REQUEST. */
        Input clean() {
            String t = title == null ? "" : title.strip();
            String b = body == null ? "" : body.strip();
            if (t.isEmpty() || t.length() > 200 || b.isEmpty() || b.length() > 10_000
                    || category == null || !CATEGORIES.contains(category) || priority == null || !PRIORITIES.contains(priority)
                    || audience == null || !AUDIENCES.contains(audience) || scope == null || !SCOPES.contains(scope)
                    || (scope.equals("COLLEGE") ? targetId != null : targetId == null || targetId <= 0)) {
                throw ApiError.INVALID_REQUEST.exception();
            }
            return new Input(t, b, category, priority, audience, scope, targetId, expiresAt);
        }
    }
    public record Publish(OffsetDateTime publishAt) {}
    public record Filter(String category, String priority, String status, String q, Boolean unread, int page) {
        public Filter {
            if (category != null && !CATEGORIES.contains(category)) throw ApiError.INVALID_REQUEST.exception();
            if (priority != null && !PRIORITIES.contains(priority)) throw ApiError.INVALID_REQUEST.exception();
            if (status != null && !STATUSES.contains(status)) throw ApiError.INVALID_REQUEST.exception();
            q = q == null || q.isBlank() ? null : q.strip();
            if ((q != null && q.length() > 100) || page < 0 || page > 10_000) throw ApiError.INVALID_REQUEST.exception();
        }
    }
    /** Server-resolved audience: the ids are derived from the database, never taken from the client. */
    record Target(String scope, Long departmentId, Long programId, Long yearId, Long semesterId, Long divisionId) {}
    public record TargetOption(String scope, long id, String label) {}
    public record Announcement(long id, String title, String body, String category, String priority, String audience,
                               String scope, Long targetId, String target, String status, OffsetDateTime publishAt,
                               OffsetDateTime expiresAt, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                               String author, String authorRole, String attachmentName, String attachmentFormat,
                               Long attachmentSize, Boolean read, boolean canEdit, boolean canPublish,
                               boolean canArchive, boolean canDelete) {}
    public record Stats(long audienceSize, long readCount) {}
    public record Overview(long drafts, long scheduled, long published, long expired, long archived) {}
    public record FileDownload(Path path, String fileName, String mimeType, long sizeBytes) {}

    private final JdbcTemplate db;
    private final MaterialFiles files;

    public Announcements(JdbcTemplate db, MaterialFiles files) {
        this.db = db;
        this.files = files;
    }

    // ---------- SQL building blocks ----------

    /** Effective status. SCHEDULED/EXPIRED are derived from PUBLISHED and the two instants. */
    static final String EFFECTIVE = """
            CASE WHEN a.status = 'PUBLISHED' AND a.publish_at > statement_timestamp() THEN 'SCHEDULED'
                 WHEN a.status = 'PUBLISHED' AND a.expires_at IS NOT NULL AND a.expires_at <= statement_timestamp() THEN 'EXPIRED'
                 ELSE a.status END""";
    /** Live = published, already visible and not yet expired. */
    static final String LIVE = """
             a.status = 'PUBLISHED' AND a.publish_at <= statement_timestamp()
             AND (a.expires_at IS NULL OR a.expires_at > statement_timestamp()) """;

    /** Matches a student (column or '?' placeholder used twice) to an announcement audience. */
    static String studentMatch(String user) {
        return """
                 a.audience IN ('EVERYONE', 'STUDENTS') AND (a.scope = 'COLLEGE'
                   OR (a.scope = 'DEPARTMENT' AND EXISTS (SELECT 1 FROM public.student_profiles p
                       WHERE p.college_id = a.college_id AND p.student_id = %1$s AND p.department_id = a.department_id))
                   OR EXISTS (SELECT 1 FROM public.student_enrollments e
                       WHERE e.college_id = a.college_id AND e.student_id = %1$s AND e.ended_at IS NULL AND (
                            (a.scope = 'DEPARTMENT' AND e.department_id = a.department_id)
                         OR (a.scope = 'PROGRAM' AND e.program_id = a.program_id)
                         OR (a.scope = 'ACADEMIC_YEAR' AND e.academic_year_id = a.academic_year_id)
                         OR (a.scope = 'SEMESTER' AND e.semester_id = a.semester_id)
                         OR (a.scope = 'DIVISION' AND e.division_id = a.division_id)))) """.formatted(user);
    }

    /** Matches a faculty member (column or '?' placeholder used twice) to an announcement audience. */
    static String facultyMatch(String user) {
        return """
                 a.audience IN ('EVERYONE', 'FACULTY') AND (a.scope = 'COLLEGE'
                   OR (a.scope = 'DEPARTMENT' AND EXISTS (SELECT 1 FROM public.faculty_profiles p
                       WHERE p.college_id = a.college_id AND p.faculty_id = %1$s AND p.department_id = a.department_id))
                   OR EXISTS (SELECT 1 FROM public.faculty_subject_assignments f
                       WHERE f.college_id = a.college_id AND f.faculty_id = %1$s AND f.ended_at IS NULL AND (
                            (a.scope = 'DEPARTMENT' AND f.department_id = a.department_id)
                         OR (a.scope = 'PROGRAM' AND f.program_id = a.program_id)
                         OR (a.scope = 'ACADEMIC_YEAR' AND f.academic_year_id = a.academic_year_id)
                         OR (a.scope = 'SEMESTER' AND f.semester_id = a.semester_id)
                         OR (a.scope = 'DIVISION' AND f.division_id = a.division_id)))) """.formatted(user);
    }

    private static final String SELECT = """
            SELECT a.*, u.full_name author, (%s) effective,
                   COALESCE(dv.name, sem.name, yr.name, pr.name, dp.name, 'Entire college') target_label,
                   EXISTS (SELECT 1 FROM public.announcement_reads r WHERE r.announcement_id = a.id AND r.user_id = ?) is_read
            FROM public.announcements a
            JOIN public.users u ON u.id = a.author_id
            LEFT JOIN public.departments dp ON dp.college_id = a.college_id AND dp.id = a.department_id
            LEFT JOIN public.academic_programs pr ON pr.college_id = a.college_id AND pr.id = a.program_id
            LEFT JOIN public.academic_years yr ON yr.college_id = a.college_id AND yr.id = a.academic_year_id
            LEFT JOIN public.academic_semesters sem ON sem.college_id = a.college_id AND sem.id = a.semester_id
            LEFT JOIN public.academic_divisions dv ON dv.college_id = a.college_id AND dv.id = a.division_id
            WHERE a.college_id = ?
            """.formatted(EFFECTIVE);

    /** Role scope for listing. Each clause carries its own parameters. */
    private record Scope(String sql, List<Object> args) {}

    private static Scope readerScope(CollegeRole role, long user) {
        return switch (role) {
            case COLLEGE_ADMIN -> new Scope("", List.of());
            case STUDENT -> new Scope(" AND " + LIVE + " AND " + studentMatch("?"), List.of(user, user));
            case FACULTY -> new Scope(" AND " + LIVE + " AND " + facultyMatch("?"), List.of(user, user));
        };
    }

    // ---------- reading ----------

    /** Feed for students and faculty (received announcements) or the full college list for admins. */
    public List<Announcement> list(Account actor, CollegeRole role, Filter filter) {
        long college = actor.requireCollege(role, null);
        return query(actor, role, readerScope(role, actor.id()), filter, college);
    }

    /** Announcements written by the faculty member, in every status. */
    public List<Announcement> mine(Account actor, Filter filter) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        return query(actor, CollegeRole.FACULTY, new Scope(" AND a.author_id = ?", List.of(actor.id())), filter, college);
    }

    private List<Announcement> query(Account actor, CollegeRole role, Scope scope, Filter filter, long college) {
        var sql = new StringBuilder(SELECT).append(scope.sql());
        var args = new ArrayList<Object>(List.of(actor.id(), college));
        args.addAll(scope.args());
        if (filter.category() != null) { sql.append(" AND a.category = ?"); args.add(filter.category()); }
        if (filter.priority() != null) { sql.append(" AND a.priority = ?"); args.add(filter.priority()); }
        if (filter.status() != null && role == CollegeRole.COLLEGE_ADMIN || filter.status() != null && scope.sql().contains("author_id")) {
            sql.append(" AND (").append(EFFECTIVE).append(") = ?");
            args.add(filter.status());
        }
        if (filter.q() != null) {
            sql.append(" AND (strpos(lower(a.title), lower(?)) > 0 OR strpos(lower(a.body), lower(?)) > 0)");
            args.add(filter.q()); args.add(filter.q());
        }
        if (Boolean.TRUE.equals(filter.unread()) && role != CollegeRole.COLLEGE_ADMIN) {
            sql.append(" AND NOT EXISTS (SELECT 1 FROM public.announcement_reads r WHERE r.announcement_id = a.id AND r.user_id = ?)");
            args.add(actor.id());
        }
        sql.append(" ORDER BY CASE a.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END,")
           .append(" COALESCE(a.publish_at, a.updated_at) DESC, a.id DESC LIMIT ? OFFSET ?");
        args.add(PAGE_SIZE);
        args.add(filter.page() * PAGE_SIZE);
        return db.query(sql.toString(), (r, i) -> map(r, actor, role), args.toArray());
    }

    /** One announcement the caller may see: admins any in their college, readers only live ones for them,
     *  faculty also their own in any status. */
    public Announcement detail(Account actor, CollegeRole role, long id) {
        long college = actor.requireCollege(role, null);
        String where = switch (role) {
            case COLLEGE_ADMIN -> "";
            case STUDENT -> " AND " + LIVE + " AND " + studentMatch("?");
            case FACULTY -> " AND (a.author_id = ? OR (" + LIVE + " AND " + facultyMatch("?") + "))";
        };
        var args = new ArrayList<Object>(List.of(actor.id(), college));
        if (role == CollegeRole.STUDENT) { args.add(actor.id()); args.add(actor.id()); }
        if (role == CollegeRole.FACULTY) { args.add(actor.id()); args.add(actor.id()); args.add(actor.id()); }
        args.add(id);
        return db.query(SELECT + where + " AND a.id = ?", (r, i) -> map(r, actor, role), args.toArray()).stream()
                .findFirst().orElseThrow(ApiError.ANNOUNCEMENT_NOT_FOUND::exception);
    }

    /** Marks a live, visible announcement as read. Idempotent. */
    @Transactional
    public void markRead(Account actor, CollegeRole role, long id) {
        if (role == CollegeRole.COLLEGE_ADMIN) throw ApiError.ACCESS_DENIED.exception();
        Announcement visible = detail(actor, role, id);
        if (!"PUBLISHED".equals(visible.status())) throw ApiError.ANNOUNCEMENT_NOT_FOUND.exception();
        db.update("""
                INSERT INTO public.announcement_reads (college_id, announcement_id, user_id) VALUES (?, ?, ?)
                ON CONFLICT (announcement_id, user_id) DO NOTHING
                """, actor.requireCollege(role, null), id, actor.id());
    }

    /** Audience options: admins can pick anything in their college; faculty only divisions they teach. */
    public List<TargetOption> targets(Account actor, CollegeRole role) {
        long college = actor.requireCollege(role, null);
        if (role == CollegeRole.FACULTY) {
            return db.query("""
                    SELECT DISTINCT d.id, d.name || ' · ' || p.name || ' · ' || y.name label
                    FROM public.faculty_subject_assignments f
                    JOIN public.academic_divisions d ON d.college_id = f.college_id AND d.id = f.division_id
                    JOIN public.academic_programs p ON p.college_id = f.college_id AND p.id = f.program_id
                    JOIN public.academic_years y ON y.college_id = f.college_id AND y.id = f.academic_year_id
                    WHERE f.college_id = ? AND f.faculty_id = ? AND f.ended_at IS NULL ORDER BY label
                    """, (r, i) -> new TargetOption("DIVISION", r.getLong(1), r.getString(2)), college, actor.id());
        }
        if (role != CollegeRole.COLLEGE_ADMIN) throw ApiError.ACCESS_DENIED.exception();
        return db.query("""
                SELECT 'DEPARTMENT', id, name FROM public.departments WHERE college_id = ?
                UNION ALL SELECT 'PROGRAM', p.id, p.name || ' (' || d.name || ')' FROM public.academic_programs p
                    JOIN public.departments d ON d.college_id = p.college_id AND d.id = p.department_id WHERE p.college_id = ?
                UNION ALL SELECT 'ACADEMIC_YEAR', id, name FROM public.academic_years WHERE college_id = ?
                UNION ALL SELECT 'SEMESTER', s.id, s.name || ' · ' || p.name || ' · ' || y.name FROM public.academic_semesters s
                    JOIN public.academic_programs p ON p.college_id = s.college_id AND p.id = s.program_id
                    JOIN public.academic_years y ON y.college_id = s.college_id AND y.id = s.academic_year_id WHERE s.college_id = ?
                UNION ALL SELECT 'DIVISION', v.id, v.name || ' · ' || p.name || ' · ' || y.name FROM public.academic_divisions v
                    JOIN public.academic_programs p ON p.college_id = v.college_id AND p.id = v.program_id
                    JOIN public.academic_years y ON y.college_id = v.college_id AND y.id = v.academic_year_id WHERE v.college_id = ?
                ORDER BY 1, 3
                """, (r, i) -> new TargetOption(r.getString(1), r.getLong(2), r.getString(3)),
                college, college, college, college, college);
    }

    // ---------- writing ----------

    /** Saves a new announcement as a DRAFT. Publish or schedule it with {@link #publish}. */
    @Transactional
    public Announcement create(Account actor, CollegeRole role, Input raw) {
        long college = authorCollege(actor, role);
        Input input = raw.clean();
        requireFutureExpiry(input.expiresAt());
        Target target = resolve(actor, role, college, input);
        long id = db.queryForObject("""
                INSERT INTO public.announcements (college_id, author_id, author_role, title, body, category, priority,
                    audience, scope, department_id, program_id, academic_year_id, semester_id, division_id, expires_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, college, actor.id(), role.name(), input.title(), input.body(), input.category(),
                input.priority(), input.audience(), target.scope(), target.departmentId(), target.programId(),
                target.yearId(), target.semesterId(), target.divisionId(), input.expiresAt(), actor.id());
        return detail(actor, role, id);
    }

    @Transactional
    public Announcement update(Account actor, CollegeRole role, long id, Input raw) {
        long college = authorCollege(actor, role);
        Input input = raw.clean();
        Managed current = managed(actor, role, college, id);
        if (current.status().equals("ARCHIVED")) throw ApiError.ANNOUNCEMENT_STATE_CONFLICT.exception();
        requireFutureExpiry(input.expiresAt());
        if (input.expiresAt() != null && current.publishAt() != null && !input.expiresAt().isAfter(current.publishAt())) {
            throw ApiError.ANNOUNCEMENT_SCHEDULE_INVALID.exception();
        }
        Target target = resolve(actor, role, college, input);
        db.update("""
                UPDATE public.announcements SET title = ?, body = ?, category = ?, priority = ?, audience = ?, scope = ?,
                    department_id = ?, program_id = ?, academic_year_id = ?, semester_id = ?, division_id = ?, expires_at = ?,
                    updated_at = statement_timestamp(), updated_by = ?
                WHERE college_id = ? AND id = ?
                """, input.title(), input.body(), input.category(), input.priority(), input.audience(), target.scope(),
                target.departmentId(), target.programId(), target.yearId(), target.semesterId(), target.divisionId(),
                input.expiresAt(), actor.id(), college, id);
        return detail(actor, role, id);
    }

    /** Publishes now (publishAt null or in the past) or schedules for a future instant. Works on drafts and
     *  on published announcements (to reschedule). */
    @Transactional
    public Announcement publish(Account actor, CollegeRole role, long id, Publish request) {
        long college = authorCollege(actor, role);
        Managed current = managed(actor, role, college, id);
        if (current.status().equals("ARCHIVED")) throw ApiError.ANNOUNCEMENT_STATE_CONFLICT.exception();
        OffsetDateTime requested = request == null ? null : request.publishAt();
        OffsetDateTime when = db.queryForObject(
                "SELECT GREATEST(statement_timestamp(), COALESCE(CAST(? AS timestamptz), statement_timestamp()))",
                OffsetDateTime.class, requested);
        if (current.expiresAt() != null && !current.expiresAt().isAfter(when)) {
            throw ApiError.ANNOUNCEMENT_SCHEDULE_INVALID.exception();
        }
        db.update("""
                UPDATE public.announcements SET status = 'PUBLISHED', publish_at = ?, updated_at = statement_timestamp(),
                    updated_by = ? WHERE college_id = ? AND id = ?
                """, when, actor.id(), college, id);
        return detail(actor, role, id);
    }

    @Transactional
    public Announcement archive(Account actor, CollegeRole role, long id) {
        long college = authorCollege(actor, role);
        Managed current = managed(actor, role, college, id);
        if (current.status().equals("ARCHIVED")) throw ApiError.ANNOUNCEMENT_STATE_CONFLICT.exception();
        db.update("""
                UPDATE public.announcements SET status = 'ARCHIVED', updated_at = statement_timestamp(), updated_by = ?
                WHERE college_id = ? AND id = ?
                """, actor.id(), college, id);
        return detail(actor, role, id);
    }

    /** Only drafts can be deleted; anything that was published is archived instead, so its history stays. */
    @Transactional
    public void delete(Account actor, CollegeRole role, long id) {
        long college = authorCollege(actor, role);
        Managed current = managed(actor, role, college, id);
        if (!current.status().equals("DRAFT")) throw ApiError.ANNOUNCEMENT_STATE_CONFLICT.exception();
        db.update("DELETE FROM public.announcements WHERE college_id = ? AND id = ?", college, id);
        if (current.attachmentKey() != null) deleteFileAfterCommit(current.attachmentKey());
    }

    /** Adds or replaces the single attachment. The file is checked like a study material. */
    @Transactional(rollbackFor = IOException.class)
    public Announcement attach(Account actor, CollegeRole role, long id, MultipartFile file) throws IOException {
        long college = authorCollege(actor, role);
        Managed current = managed(actor, role, college, id);
        if (current.status().equals("ARCHIVED")) throw ApiError.ANNOUNCEMENT_STATE_CONFLICT.exception();
        var upload = MaterialFiles.inspect(file);
        UUID key = UUID.randomUUID();
        db.update("""
                UPDATE public.announcements SET attachment_name = ?, attachment_key = ?, attachment_format = ?,
                    attachment_mime = ?, attachment_size = ?, attachment_sha256 = ?, updated_at = statement_timestamp(),
                    updated_by = ? WHERE college_id = ? AND id = ?
                """, upload.fileName(), key, upload.extension(), upload.mime(), upload.bytes().length, upload.sha256(),
                actor.id(), college, id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                try {
                    if (status == STATUS_COMMITTED) { if (current.attachmentKey() != null) files.delete(current.attachmentKey()); }
                    else files.delete(key);
                } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        });
        files.save(key, upload.bytes());
        return detail(actor, role, id);
    }

    @Transactional
    public Announcement detach(Account actor, CollegeRole role, long id) {
        long college = authorCollege(actor, role);
        Managed current = managed(actor, role, college, id);
        if (current.status().equals("ARCHIVED") || current.attachmentKey() == null) {
            throw ApiError.ANNOUNCEMENT_STATE_CONFLICT.exception();
        }
        db.update("""
                UPDATE public.announcements SET attachment_name = NULL, attachment_key = NULL, attachment_format = NULL,
                    attachment_mime = NULL, attachment_size = NULL, attachment_sha256 = NULL,
                    updated_at = statement_timestamp(), updated_by = ? WHERE college_id = ? AND id = ?
                """, actor.id(), college, id);
        deleteFileAfterCommit(current.attachmentKey());
        return detail(actor, role, id);
    }

    /** Download only after the same visibility check as {@link #detail}. */
    public FileDownload attachment(Account actor, CollegeRole role, long id) {
        Announcement visible = detail(actor, role, id);
        if (visible.attachmentName() == null) throw ApiError.ANNOUNCEMENT_NOT_FOUND.exception();
        var row = db.queryForMap("SELECT attachment_key, attachment_mime FROM public.announcements WHERE college_id = ? AND id = ?",
                actor.requireCollege(role, null), id);
        Path path = files.path((UUID) row.get("attachment_key"));
        if (!Files.isRegularFile(path)) throw ApiError.ANNOUNCEMENT_NOT_FOUND.exception();
        return new FileDownload(path, visible.attachmentName(), (String) row.get("attachment_mime"), visible.attachmentSize());
    }

    // ---------- statistics (College Admin) ----------

    public Stats stats(Account actor, long id) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        detail(actor, CollegeRole.COLLEGE_ADMIN, id);
        long audience = db.queryForObject("""
                SELECT count(DISTINCT u.id) FROM public.announcements a
                JOIN public.users u ON u.college_id = a.college_id AND u.is_active AND u.is_verified
                JOIN public.user_roles ur ON ur.user_id = u.id JOIN public.roles ro ON ro.id = ur.role_id
                WHERE a.college_id = ? AND a.id = ? AND (
                      (ro.name = 'STUDENT' AND %s) OR (ro.name = 'FACULTY' AND %s))
                """.formatted(studentMatch("u.id"), facultyMatch("u.id")), Long.class, college, id);
        long reads = db.queryForObject("SELECT count(*) FROM public.announcement_reads WHERE college_id = ? AND announcement_id = ?",
                Long.class, college, id);
        return new Stats(audience, reads);
    }

    public Overview overview(Account actor) {
        long college = actor.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        return db.queryForObject("""
                SELECT count(*) FILTER (WHERE e = 'DRAFT'), count(*) FILTER (WHERE e = 'SCHEDULED'),
                       count(*) FILTER (WHERE e = 'PUBLISHED'), count(*) FILTER (WHERE e = 'EXPIRED'),
                       count(*) FILTER (WHERE e = 'ARCHIVED')
                FROM (SELECT %s e FROM public.announcements a WHERE a.college_id = ?) s
                """.formatted(EFFECTIVE), (r, i) -> new Overview(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5)),
                college);
    }

    // ---------- helpers ----------

    private record Managed(String status, OffsetDateTime publishAt, OffsetDateTime expiresAt, UUID attachmentKey) {}

    /** Only College Admins and Faculty write announcements. */
    private static long authorCollege(Account actor, CollegeRole role) {
        if (role == CollegeRole.STUDENT) throw ApiError.ACCESS_DENIED.exception();
        return actor.requireCollege(role, null);
    }

    /** Locks a row the caller may manage: admins any in the college, faculty only their own. */
    private Managed managed(Account actor, CollegeRole role, long college, long id) {
        String own = role == CollegeRole.FACULTY ? " AND author_id = ?" : "";
        var args = new ArrayList<Object>(List.of(college, id));
        if (role == CollegeRole.FACULTY) args.add(actor.id());
        return db.query("SELECT status, publish_at, expires_at, attachment_key FROM public.announcements"
                        + " WHERE college_id = ? AND id = ?" + own + " FOR UPDATE",
                (r, i) -> new Managed(r.getString(1), r.getObject(2, OffsetDateTime.class),
                        r.getObject(3, OffsetDateTime.class), r.getObject(4, UUID.class)), args.toArray())
                .stream().findFirst().orElseThrow(ApiError.ANNOUNCEMENT_NOT_FOUND::exception);
    }

    /** Derives the full audience from the single target id, inside the caller's college. Faculty may only
     *  address the students of divisions they actively teach. */
    Target resolve(Account actor, CollegeRole role, long college, Input input) {
        if (role == CollegeRole.FACULTY) {
            if (!input.scope().equals("DIVISION") || !input.audience().equals("STUDENTS")) {
                throw ApiError.ANNOUNCEMENT_TARGET_INVALID.exception();
            }
            return one("""
                    SELECT 'DIVISION', department_id, program_id, academic_year_id, NULL::bigint, division_id
                    FROM public.faculty_subject_assignments
                    WHERE college_id = ? AND faculty_id = ? AND division_id = ? AND ended_at IS NULL LIMIT 1
                    """, college, actor.id(), input.targetId());
        }
        return switch (input.scope()) {
            case "COLLEGE" -> new Target("COLLEGE", null, null, null, null, null);
            case "DEPARTMENT" -> one("""
                    SELECT 'DEPARTMENT', id, NULL::bigint, NULL::bigint, NULL::bigint, NULL::bigint
                    FROM public.departments WHERE college_id = ? AND id = ?""", college, input.targetId());
            case "PROGRAM" -> one("""
                    SELECT 'PROGRAM', department_id, id, NULL::bigint, NULL::bigint, NULL::bigint
                    FROM public.academic_programs WHERE college_id = ? AND id = ?""", college, input.targetId());
            case "ACADEMIC_YEAR" -> one("""
                    SELECT 'ACADEMIC_YEAR', NULL::bigint, NULL::bigint, id, NULL::bigint, NULL::bigint
                    FROM public.academic_years WHERE college_id = ? AND id = ?""", college, input.targetId());
            case "SEMESTER" -> one("""
                    SELECT 'SEMESTER', p.department_id, s.program_id, s.academic_year_id, s.id, NULL::bigint
                    FROM public.academic_semesters s JOIN public.academic_programs p ON p.college_id = s.college_id AND p.id = s.program_id
                    WHERE s.college_id = ? AND s.id = ?""", college, input.targetId());
            case "DIVISION" -> one("""
                    SELECT 'DIVISION', p.department_id, v.program_id, v.academic_year_id, NULL::bigint, v.id
                    FROM public.academic_divisions v JOIN public.academic_programs p ON p.college_id = v.college_id AND p.id = v.program_id
                    WHERE v.college_id = ? AND v.id = ?""", college, input.targetId());
            default -> throw ApiError.ANNOUNCEMENT_TARGET_INVALID.exception();
        };
    }

    private Target one(String sql, Object... args) {
        return db.query(sql, (r, i) -> new Target(r.getString(1), (Long) r.getObject(2), (Long) r.getObject(3),
                (Long) r.getObject(4), (Long) r.getObject(5), (Long) r.getObject(6)), args)
                .stream().findFirst().orElseThrow(ApiError.ANNOUNCEMENT_TARGET_INVALID::exception);
    }

    /** An expiry, when given, must still be in the future. */
    static void requireFutureExpiry(OffsetDateTime expiresAt) {
        if (expiresAt != null && !expiresAt.isAfter(OffsetDateTime.now())) {
            throw ApiError.ANNOUNCEMENT_SCHEDULE_INVALID.exception();
        }
    }

    private void deleteFileAfterCommit(UUID key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { files.delete(key); } catch (IOException e) { throw new UncheckedIOException(e); }
            }
        });
    }

    /** Which management actions the caller may take, given the effective status. */
    static boolean[] permissions(CollegeRole role, boolean own, String effective) {
        boolean manager = role == CollegeRole.COLLEGE_ADMIN || (role == CollegeRole.FACULTY && own);
        boolean open = !effective.equals("ARCHIVED");
        return new boolean[] {
                manager && open,                                              // edit
                manager && (effective.equals("DRAFT") || effective.equals("SCHEDULED")), // publish / reschedule
                manager && open,                                              // archive
                manager && effective.equals("DRAFT")                          // delete
        };
    }

    private static Long targetId(ResultSet r, String scope) throws SQLException {
        String column = switch (scope) {
            case "DEPARTMENT" -> "department_id";
            case "PROGRAM" -> "program_id";
            case "ACADEMIC_YEAR" -> "academic_year_id";
            case "SEMESTER" -> "semester_id";
            case "DIVISION" -> "division_id";
            default -> null;
        };
        return column == null ? null : (Long) r.getObject(column);
    }

    private static Announcement map(ResultSet r, Account actor, CollegeRole role) throws SQLException {
        String effective = r.getString("effective");
        boolean own = r.getLong("author_id") == actor.id();
        boolean[] can = permissions(role, own, effective);
        String scope = r.getString("scope");
        Object size = r.getObject("attachment_size");
        return new Announcement(r.getLong("id"), r.getString("title"), r.getString("body"), r.getString("category"),
                r.getString("priority"), r.getString("audience"), scope, targetId(r, scope), r.getString("target_label"),
                effective, r.getObject("publish_at", OffsetDateTime.class), r.getObject("expires_at", OffsetDateTime.class),
                r.getObject("created_at", OffsetDateTime.class), r.getObject("updated_at", OffsetDateTime.class),
                r.getString("author"), r.getString("author_role"), r.getString("attachment_name"),
                r.getString("attachment_format"), size == null ? null : ((Number) size).longValue(),
                role == CollegeRole.COLLEGE_ADMIN ? null : r.getBoolean("is_read"), can[0], can[1], can[2], can[3]);
    }
}
