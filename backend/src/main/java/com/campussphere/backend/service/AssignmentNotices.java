package com.campussphere.backend.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app assignment notifications for students. Rows are written in the same transaction as the event (publish,
 * deadline change, marking, return), so a failed action leaves no notification. Each row has an event key that is
 * unique per student, so a retried event inserts nothing twice. Phone push (FCM) is not part of this module.
 * A student only sees a notification while they can see its assignment's published, issued state.
 */
@Service
@Transactional(readOnly = true)
public class AssignmentNotices {
    static final int PAGE_SIZE = 30;

    public record Notice(long id, String kind, String title, String body, long assignmentId,
                         OffsetDateTime dueAt, OffsetDateTime createdAt, OffsetDateTime readAt) {}

    private final JdbcTemplate db;

    public AssignmentNotices(JdbcTemplate db) {
        this.db = db;
    }

    /** Students currently enrolled in the assignment's class, joined from the assignment's teaching assignment. */
    private static final String CLASS_STUDENTS = """
            SELECT a.college_id, e.student_id, a.id, ?, ?, ?, left(a.title || ' · ' || s.name, 600)
            FROM public.assignments a
            JOIN public.faculty_subject_assignments t ON t.college_id = a.college_id AND t.id = a.teaching_assignment_id
            JOIN public.academic_subjects s ON s.college_id = a.college_id AND s.id = a.subject_id
            JOIN public.student_enrollments e ON e.college_id = t.college_id AND e.ended_at IS NULL
                AND e.department_id = t.department_id AND e.program_id = t.program_id
                AND e.academic_year_id = t.academic_year_id AND e.semester_id = t.semester_id
                AND e.division_id = t.division_id
            WHERE a.college_id = ? AND a.id = ?
            """;
    private static final String INSERT = """
            INSERT INTO public.assignment_notifications (college_id, student_id, assignment_id, event_key, kind, title, body)
            """;
    private static final String NO_DUPLICATES = " ON CONFLICT ON CONSTRAINT uq_assignment_notification_event DO NOTHING";

    static String publishedKey(long assignment) {
        return "published:" + assignment;
    }

    static String deadlineKey(long assignment, OffsetDateTime due) {
        return "deadline:" + assignment + ":" + due.toEpochSecond();
    }

    static String evaluatedKey(long submission, long event) {
        return "evaluated:" + submission + ":" + event;
    }

    static String returnedKey(long submission) {
        return "returned:" + submission;
    }

    int published(long college, long assignment) {
        return db.update(INSERT + CLASS_STUDENTS + NO_DUPLICATES, publishedKey(assignment), "PUBLISHED",
                "New assignment", college, assignment);
    }

    int deadlineChanged(long college, long assignment, OffsetDateTime due) {
        return db.update(INSERT + CLASS_STUDENTS + NO_DUPLICATES, deadlineKey(assignment, due), "DEADLINE_CHANGED",
                "Deadline changed", college, assignment);
    }

    int evaluated(long college, long assignment, long submission, long student, long event) {
        return personal(college, assignment, submission, student, evaluatedKey(submission, event), "EVALUATED",
                "Assignment marked");
    }

    int returned(long college, long assignment, long submission, long student) {
        return personal(college, assignment, submission, student, returnedKey(submission), "RETURNED",
                "Returned for correction");
    }

    private int personal(long college, long assignment, long submission, long student, String key, String kind,
                         String title) {
        return db.update("""
                INSERT INTO public.assignment_notifications (college_id, student_id, assignment_id, submission_id,
                    event_key, kind, title, body)
                SELECT a.college_id, ?, a.id, ?, ?, ?, ?, left(a.title || ' · ' || s.name, 600)
                FROM public.assignments a
                JOIN public.academic_subjects s ON s.college_id = a.college_id AND s.id = a.subject_id
                WHERE a.college_id = ? AND a.id = ?
                """ + NO_DUPLICATES, student, submission, key, kind, title, college, assignment);
    }

    private static final String VISIBLE = """
             FROM public.assignment_notifications n
            JOIN public.assignments a ON a.college_id = n.college_id AND a.id = n.assignment_id
            WHERE n.college_id = ? AND n.student_id = ? AND a.status <> 'DRAFT' AND a.issue_at <= statement_timestamp()
            """;

    public List<Notice> list(Account actor, boolean unreadOnly, int page) {
        long college = actor.requireCollege(CollegeRole.STUDENT, null);
        if (page < 0 || page > 1000) throw ApiError.INVALID_REQUEST.exception();
        return db.query("SELECT n.id, n.kind, n.title, n.body, n.assignment_id, a.due_at, n.created_at, n.read_at"
                        + VISIBLE + (unreadOnly ? " AND n.read_at IS NULL" : "") + " ORDER BY n.id DESC LIMIT ? OFFSET ?",
                (r, i) -> new Notice(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getLong(5),
                        r.getObject(6, OffsetDateTime.class), r.getObject(7, OffsetDateTime.class),
                        r.getObject(8, OffsetDateTime.class)), college, actor.id(), PAGE_SIZE, page * PAGE_SIZE);
    }

    public long unread(Account actor) {
        long college = actor.requireCollege(CollegeRole.STUDENT, null);
        Long count = db.queryForObject("SELECT count(*)" + VISIBLE + " AND n.read_at IS NULL", Long.class,
                college, actor.id());
        return count == null ? 0 : count;
    }

    /** Marks one of the student's own notifications as read. Marking a read notification again changes nothing. */
    @Transactional
    public void markRead(Account actor, long id) {
        long college = actor.requireCollege(CollegeRole.STUDENT, null);
        int changed = db.update("""
                UPDATE public.assignment_notifications SET read_at = statement_timestamp()
                WHERE college_id = ? AND student_id = ? AND id = ? AND read_at IS NULL
                """, college, actor.id(), id);
        if (changed == 0 && !Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.assignment_notifications WHERE college_id = ? AND student_id = ? AND id = ?)
                """, Boolean.class, college, actor.id(), id))) throw ApiError.NOTIFICATION_NOT_FOUND.exception();
    }
}
