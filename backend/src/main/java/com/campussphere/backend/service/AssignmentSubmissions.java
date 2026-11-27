package com.campussphere.backend.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.Assignments.State;
import com.campussphere.backend.service.StudyMaterials.FileDownload;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Student submissions and faculty marking. A student hands in work only for an assignment of their current class;
 * every attempt is kept (older attempts become SUPERSEDED, never overwritten). Faculty mark or return work only for
 * assignments of classes they currently teach. College Admins cannot open submissions or marks.
 */
@Service
@Transactional(readOnly = true)
public class AssignmentSubmissions {
    static final int PAGE_SIZE = 30;
    static final int MAX_ATTEMPTS = 50;
    public enum RosterFilter { ALL, PENDING, SUBMITTED, LATE, EVALUATED, RETURNED }

    public record Evaluation(BigDecimal marks, String feedback) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported evaluation field");
        }
    }
    public record ReturnRequest(String feedback) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported return field");
        }
    }
    public record Submission(long id, long assignmentId, String student, String studentEmail, int attempt, String status,
                             boolean late, OffsetDateTime submittedAt, String fileName, String format, long sizeBytes,
                             String comment, BigDecimal marks, String feedback, String evaluatedBy,
                             OffsetDateTime evaluatedAt) {}
    public record RosterRow(long studentId, String student, String email, Long submissionId, Integer attempt,
                            String status, boolean late, OffsetDateTime submittedAt, BigDecimal marks) {}
    public record Review(Submission submission, List<Submission> attempts, BigDecimal maxMarks) {}

    /** Whether a new attempt is accepted: refusal is null when it is. */
    record Decision(ApiError refusal, boolean late, boolean supersede) {
        static Decision refuse(ApiError error) { return new Decision(error, false, false); }
    }
    record Current(long id, String status, int attempt, String sha256, long student, BigDecimal marks,
                           String feedback) {}

    private final JdbcTemplate db;
    private final Assignments assignments;
    private final AssignmentNotices notices;
    private final MaterialFiles files;

    public AssignmentSubmissions(JdbcTemplate db, Assignments assignments, AssignmentNotices notices, MaterialFiles files) {
        this.db = db;
        this.assignments = assignments;
        this.notices = notices;
        this.files = files;
    }

    // ---- Rules ----

    /**
     * Decides whether a student may hand in work now. Work returned for correction may always be handed in again
     * while the assignment is published or closed; otherwise the deadline (or late window, if allowed) applies, and
     * a second attempt is accepted only when resubmission until the deadline is allowed.
     */
    static Decision decide(State a, String current, OffsetDateTime now) {
        boolean returned = "RETURNED".equals(current);
        boolean open = a.status().equals("PUBLISHED") || (returned && a.status().equals("CLOSED"));
        if (!open || now.isBefore(a.issueAt())) return Decision.refuse(ApiError.SUBMISSION_CLOSED);
        boolean late = now.isAfter(a.dueAt());
        if (!returned && late && !("ALLOWED".equals(a.latePolicy()) && a.lateUntil() != null && !now.isAfter(a.lateUntil()))) {
            return Decision.refuse(ApiError.SUBMISSION_CLOSED);
        }
        if (current == null) return new Decision(null, late, false);
        return switch (current) {
            case "RETURNED" -> new Decision(null, late, true);
            case "SUBMITTED" -> a.resubmission().equals("UNTIL_DEADLINE") && !late
                    ? new Decision(null, false, true) : Decision.refuse(ApiError.SUBMISSION_CONFLICT);
            default -> Decision.refuse(ApiError.SUBMISSION_CONFLICT);
        };
    }

    /** Marks between 0 and the maximum with at most two decimals; empty feedback is stored as none. */
    static Evaluation cleanEvaluation(Evaluation input, BigDecimal maxMarks) {
        if (input == null || input.marks() == null) throw ApiError.EVALUATION_INVALID.exception();
        BigDecimal marks = input.marks().stripTrailingZeros();
        if (marks.signum() < 0 || marks.compareTo(maxMarks) > 0 || marks.scale() > 2) {
            throw ApiError.EVALUATION_INVALID.exception();
        }
        String feedback = input.feedback() == null ? null : input.feedback().strip();
        if (feedback != null && feedback.length() > 4000) throw ApiError.EVALUATION_INVALID.exception();
        return new Evaluation(marks.setScale(2), feedback == null || feedback.isEmpty() ? null : feedback);
    }

    static String cleanReturn(ReturnRequest input) {
        String feedback = input == null || input.feedback() == null ? "" : input.feedback().strip();
        if (feedback.length() < 3 || feedback.length() > 4000) throw ApiError.EVALUATION_INVALID.exception();
        return feedback;
    }

    // ---- Students ----

    @Transactional(rollbackFor = IOException.class)
    public Submission submit(Account actor, long assignmentId, MultipartFile file, String comment) throws IOException {
        long college = actor.requireCollege(CollegeRole.STUDENT, null);
        String note = comment == null ? "" : comment.strip();
        if (note.length() > 1000) throw ApiError.INVALID_REQUEST.exception();
        // Only a student of the assignment's current class may hand in work, whatever ids the app sends.
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.assignments a
                    JOIN public.faculty_subject_assignments t ON t.college_id = a.college_id AND t.id = a.teaching_assignment_id
                    JOIN public.student_enrollments e ON e.college_id = t.college_id AND e.ended_at IS NULL
                        AND e.department_id = t.department_id AND e.program_id = t.program_id
                        AND e.academic_year_id = t.academic_year_id AND e.semester_id = t.semester_id
                        AND e.division_id = t.division_id AND e.student_id = ?
                    WHERE a.college_id = ? AND a.id = ? AND a.status <> 'DRAFT')
                """, Boolean.class, actor.id(), college, assignmentId))) throw ApiError.ASSIGNMENT_NOT_FOUND.exception();
        State a = assignments.lock(college, assignmentId, false);
        Current current = current(college, assignmentId, actor.id());
        Decision decision = decide(a, current == null ? null : current.status(), assignments.now());
        if (decision.refusal() != null && !(current != null && current.status().equals("SUBMITTED"))) {
            throw decision.refusal().exception();
        }
        MaterialFiles.Upload upload;
        try {
            var allowed = EnumSet.noneOf(MaterialFiles.Format.class);
            a.formats().forEach(f -> MaterialFiles.Format.of(f).ifPresent(allowed::add));
            upload = MaterialFiles.inspect(file, allowed, a.maxFileBytes());
        } catch (ApiError.Failure failure) {
            throw failure.error() == ApiError.MATERIAL_FILE_INVALID ? ApiError.SUBMISSION_FILE_INVALID.exception() : failure;
        }
        // Sending the same file again (for example a retry after a lost response) changes nothing.
        if (current != null && current.status().equals("SUBMITTED") && current.sha256().equals(upload.sha256())) {
            return byId(college, current.id());
        }
        if (decision.refusal() != null) throw decision.refusal().exception();
        Integer last = db.queryForObject("""
                SELECT coalesce(max(attempt), 0) FROM public.assignment_submissions
                WHERE college_id = ? AND assignment_id = ? AND student_id = ?
                """, Integer.class, college, assignmentId, actor.id());
        int attempt = (last == null ? 0 : last) + 1;
        if (attempt > MAX_ATTEMPTS) throw ApiError.SUBMISSION_CONFLICT.exception();
        if (decision.supersede()) {
            db.update("UPDATE public.assignment_submissions SET status = 'SUPERSEDED' WHERE college_id = ? AND id = ?",
                    college, current.id());
        }
        UUID key = UUID.randomUUID();
        long id;
        try {
            Long created = db.queryForObject("""
                    INSERT INTO public.assignment_submissions (college_id, assignment_id, student_id, attempt,
                        original_filename, storage_key, file_format, mime_type, byte_size, sha256, comment, submitted_at,
                        is_late)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, college, assignmentId, actor.id(), attempt, upload.fileName(), key,
                    upload.extension(), upload.mime(), upload.bytes().length, upload.sha256(), note,
                    assignments.now(), decision.late());
            id = created == null ? 0 : created;
        } catch (DuplicateKeyException duplicate) {
            throw ApiError.SUBMISSION_CONFLICT.exception();
        }
        assignments.event(college, assignmentId, id, actor.id(), "SUBMIT",
                "Attempt " + attempt + (decision.late() ? ", late" : ""));
        Assignments.afterCommit(committed -> { if (!committed) files.delete(key); });
        files.save(key, upload.bytes());
        return byId(college, id);
    }

    /** The student's own attempts at an assignment they can see, newest first. */
    public List<Submission> mine(Account actor, long assignmentId) {
        long college = actor.requireCollege(CollegeRole.STUDENT, null);
        assignments.detail(actor, CollegeRole.STUDENT, assignmentId);
        return attempts(college, assignmentId, actor.id());
    }

    // ---- Faculty ----

    public List<RosterRow> roster(Account actor, long assignmentId, RosterFilter filter, int page) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        if (page < 0 || page > 1000) throw ApiError.INVALID_REQUEST.exception();
        assignments.detail(actor, CollegeRole.FACULTY, assignmentId);
        String where = switch (filter == null ? RosterFilter.ALL : filter) {
            case ALL -> "";
            case PENDING -> " WHERE x.id IS NULL OR x.status = 'RETURNED'";
            case SUBMITTED -> " WHERE x.status = 'SUBMITTED'";
            case LATE -> " WHERE x.is_late";
            case EVALUATED -> " WHERE x.status = 'EVALUATED'";
            case RETURNED -> " WHERE x.status = 'RETURNED'";
        };
        return db.query("""
                WITH people AS (
                    SELECT e.student_id FROM public.assignments a
                    JOIN public.faculty_subject_assignments t ON t.college_id = a.college_id AND t.id = a.teaching_assignment_id
                    JOIN public.student_enrollments e ON e.college_id = t.college_id AND e.ended_at IS NULL
                        AND e.department_id = t.department_id AND e.program_id = t.program_id
                        AND e.academic_year_id = t.academic_year_id AND e.semester_id = t.semester_id
                        AND e.division_id = t.division_id
                    WHERE a.college_id = ? AND a.id = ?
                    UNION
                    SELECT student_id FROM public.assignment_submissions
                    WHERE college_id = ? AND assignment_id = ? AND status <> 'SUPERSEDED')
                SELECT p.student_id, u.full_name, u.email, x.id, x.attempt, x.status, x.is_late, x.submitted_at, x.marks
                FROM people p JOIN public.users u ON u.id = p.student_id
                LEFT JOIN public.assignment_submissions x ON x.college_id = ? AND x.assignment_id = ?
                    AND x.student_id = p.student_id AND x.status <> 'SUPERSEDED'
                """ + where + " ORDER BY u.full_name, p.student_id LIMIT ? OFFSET ?", (r, i) -> {
            long submission = r.getLong(4);
            boolean none = r.wasNull();
            return new RosterRow(r.getLong(1), r.getString(2), r.getString(3), none ? null : submission,
                    none ? null : r.getInt(5), none ? "NOT_SUBMITTED" : r.getString(6), !none && r.getBoolean(7),
                    r.getObject(8, OffsetDateTime.class), r.getBigDecimal(9));
        }, college, assignmentId, college, assignmentId, college, assignmentId, PAGE_SIZE, page * PAGE_SIZE);
    }

    /** One submission with all of that student's attempts, for the assignment's faculty. */
    public Review review(Account actor, long assignmentId, long submissionId) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        var assignment = assignments.detail(actor, CollegeRole.FACULTY, assignmentId);
        Submission submission = byId(college, submissionId);
        if (submission.assignmentId() != assignmentId) throw ApiError.SUBMISSION_NOT_FOUND.exception();
        Long student = db.queryForObject("SELECT student_id FROM public.assignment_submissions WHERE college_id = ? AND id = ?",
                Long.class, college, submissionId);
        return new Review(submission, attempts(college, assignmentId, student == null ? 0 : student), assignment.maxMarks());
    }

    @Transactional
    public Submission evaluate(Account actor, long assignmentId, long submissionId, Evaluation input) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        assignments.detail(actor, CollegeRole.FACULTY, assignmentId);
        // Locking the assignment keeps maximum marks fixed while marks are written.
        State a = assignments.lock(college, assignmentId, true);
        if (!a.status().equals("PUBLISHED") && !a.status().equals("CLOSED")) {
            throw ApiError.EVALUATION_STATE_CONFLICT.exception();
        }
        Evaluation clean = cleanEvaluation(input, a.maxMarks());
        Current current = locked(college, assignmentId, submissionId);
        if (!current.status().equals("SUBMITTED") && !current.status().equals("EVALUATED")) {
            throw ApiError.EVALUATION_STATE_CONFLICT.exception();
        }
        boolean again = current.status().equals("EVALUATED");
        if (again && current.marks().compareTo(clean.marks()) == 0 && Objects.equals(current.feedback(), clean.feedback())) {
            return byId(college, submissionId);
        }
        db.update("""
                UPDATE public.assignment_submissions SET status = 'EVALUATED', marks = ?, feedback = ?, evaluated_by = ?,
                    evaluated_at = GREATEST(statement_timestamp(), submitted_at)
                WHERE college_id = ? AND id = ?
                """, clean.marks(), clean.feedback(), actor.id(), college, submissionId);
        String note = again
                ? "Marks changed from " + current.marks().toPlainString() + " to " + clean.marks().toPlainString()
                : "Marks " + clean.marks().toPlainString() + " / " + a.maxMarks().toPlainString();
        long event = assignments.event(college, assignmentId, submissionId, actor.id(), again ? "REEVALUATE" : "EVALUATE", note);
        notices.evaluated(college, assignmentId, submissionId, current.student(), event);
        return byId(college, submissionId);
    }

    @Transactional
    public Submission returnForCorrection(Account actor, long assignmentId, long submissionId, ReturnRequest input) {
        long college = actor.requireCollege(CollegeRole.FACULTY, null);
        assignments.detail(actor, CollegeRole.FACULTY, assignmentId);
        State a = assignments.lock(college, assignmentId, true);
        if (!a.status().equals("PUBLISHED") && !a.status().equals("CLOSED")) {
            throw ApiError.EVALUATION_STATE_CONFLICT.exception();
        }
        String feedback = cleanReturn(input);
        Current current = locked(college, assignmentId, submissionId);
        if (!current.status().equals("SUBMITTED")) throw ApiError.EVALUATION_STATE_CONFLICT.exception();
        db.update("""
                UPDATE public.assignment_submissions SET status = 'RETURNED', feedback = ?, evaluated_by = ?,
                    evaluated_at = GREATEST(statement_timestamp(), submitted_at)
                WHERE college_id = ? AND id = ?
                """, feedback, actor.id(), college, submissionId);
        assignments.event(college, assignmentId, submissionId, actor.id(), "RETURN", null);
        notices.returned(college, assignmentId, submissionId, current.student());
        return byId(college, submissionId);
    }

    /** A submitted file: students get only their own; faculty only for assignments of classes they teach. */
    public FileDownload file(Account actor, CollegeRole role, long assignmentId, long submissionId) {
        if (role != CollegeRole.STUDENT && role != CollegeRole.FACULTY) throw ApiError.ACCESS_DENIED.exception();
        long college = actor.requireCollege(role, null);
        if (role == CollegeRole.FACULTY) assignments.detail(actor, CollegeRole.FACULTY, assignmentId);
        var row = db.query("""
                SELECT storage_key, original_filename, mime_type, byte_size FROM public.assignment_submissions
                WHERE college_id = ? AND assignment_id = ? AND id = ?""" + (role == CollegeRole.STUDENT ? " AND student_id = ?" : ""),
                (r, i) -> new Object[]{r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getLong(4)},
                role == CollegeRole.STUDENT ? new Object[]{college, assignmentId, submissionId, actor.id()}
                        : new Object[]{college, assignmentId, submissionId})
                .stream().findFirst().orElseThrow(ApiError.SUBMISSION_NOT_FOUND::exception);
        Path path = files.path((UUID) row[0]);
        if (!Files.isRegularFile(path)) throw ApiError.SUBMISSION_NOT_FOUND.exception();
        return new FileDownload(path, (String) row[1], (String) row[2], (Long) row[3]);
    }

    // ---- Helpers ----

    private static final String SUBMISSION_SELECT = """
            SELECT x.id, x.assignment_id, u.full_name student, u.email, x.attempt, x.status, x.is_late, x.submitted_at,
                   x.original_filename, x.file_format, x.byte_size, x.comment, x.marks, x.feedback, ev.full_name evaluator,
                   x.evaluated_at
            FROM public.assignment_submissions x
            JOIN public.users u ON u.id = x.student_id
            LEFT JOIN public.users ev ON ev.id = x.evaluated_by
            WHERE x.college_id = ?""";

    private Submission byId(long college, long id) {
        return db.query(SUBMISSION_SELECT + " AND x.id = ?", AssignmentSubmissions::map, college, id).stream().findFirst()
                .orElseThrow(ApiError.SUBMISSION_NOT_FOUND::exception);
    }

    private List<Submission> attempts(long college, long assignmentId, long student) {
        return db.query(SUBMISSION_SELECT + " AND x.assignment_id = ? AND x.student_id = ? ORDER BY x.attempt DESC",
                AssignmentSubmissions::map, college, assignmentId, student);
    }

    /** The student's current (not superseded) attempt, locked, or null when there is none. */
    private Current current(long college, long assignmentId, long student) {
        return db.query("""
                SELECT id, status, attempt, sha256, student_id, marks, feedback FROM public.assignment_submissions
                WHERE college_id = ? AND assignment_id = ? AND student_id = ? AND status <> 'SUPERSEDED' FOR UPDATE
                """, AssignmentSubmissions::current, college, assignmentId, student).stream().findFirst().orElse(null);
    }

    private Current locked(long college, long assignmentId, long submissionId) {
        return db.query("""
                SELECT id, status, attempt, sha256, student_id, marks, feedback FROM public.assignment_submissions
                WHERE college_id = ? AND assignment_id = ? AND id = ? FOR UPDATE
                """, AssignmentSubmissions::current, college, assignmentId, submissionId).stream().findFirst()
                .orElseThrow(ApiError.SUBMISSION_NOT_FOUND::exception);
    }

    private static Current current(ResultSet r, int row) throws SQLException {
        return new Current(r.getLong("id"), r.getString("status"), r.getInt("attempt"), r.getString("sha256"),
                r.getLong("student_id"), r.getBigDecimal("marks"), r.getString("feedback"));
    }

    private static Submission map(ResultSet r, int row) throws SQLException {
        return new Submission(r.getLong("id"), r.getLong("assignment_id"), r.getString("student"), r.getString("email"),
                r.getInt("attempt"), r.getString("status"), r.getBoolean("is_late"),
                r.getObject("submitted_at", OffsetDateTime.class), r.getString("original_filename"),
                r.getString("file_format"), r.getLong("byte_size"), r.getString("comment"), r.getBigDecimal("marks"),
                r.getString("feedback"), r.getString("evaluator"), r.getObject("evaluated_at", OffsetDateTime.class));
    }
}
