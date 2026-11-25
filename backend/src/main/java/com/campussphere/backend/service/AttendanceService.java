package com.campussphere.backend.service;

import static com.campussphere.backend.dto.AttendanceModels.*;

import java.time.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AttendanceService {
    private static final ZoneId COLLEGE_ZONE = ZoneId.of("Asia/Kolkata");
    static final int PAGE_SIZE = 50;
    static final String SESSIONS = """
            SELECT s.*, sub.name subject, v.name division, u.full_name faculty,
                   d.name department, p.name program, sem.name semester
            FROM public.attendance_sessions s
            JOIN public.academic_subjects sub ON sub.college_id=s.college_id AND sub.id=s.subject_id
            JOIN public.academic_divisions v ON v.college_id=s.college_id AND v.id=s.division_id
            JOIN public.users u ON u.college_id=s.college_id AND u.id=s.faculty_id
            JOIN public.departments d ON d.college_id=s.college_id AND d.id=s.department_id
            JOIN public.academic_programs p ON p.college_id=s.college_id AND p.id=s.program_id
            JOIN public.academic_semesters sem ON sem.college_id=s.college_id AND sem.id=s.semester_id
            """;
    static final RowMapper<Session> SESSION = (r, i) -> new Session(r.getLong("id"), r.getLong("assignment_id"),
            State.valueOf(r.getString("status")), r.getLong("revision"), r.getObject("session_date", LocalDate.class),
            r.getInt("lecture_number"), r.getObject("start_time", LocalTime.class), r.getObject("end_time", LocalTime.class),
            r.getObject("submitted_at", OffsetDateTime.class), r.getString("subject"), r.getString("division"),
            r.getString("faculty"), r.getString("department"), r.getString("program"), r.getString("semester"));
    private static final String ROSTER = """
            SELECT r.student_id, u.full_name, r.status
            FROM public.attendance_records r
            JOIN public.users u ON u.college_id=r.college_id AND u.id=r.student_id
            WHERE r.college_id=? AND r.session_id=? ORDER BY u.full_name, r.student_id
            """;
    private final JdbcTemplate jdbc;
    private final AttendanceReports reports;

    public AttendanceService(JdbcTemplate jdbc, AttendanceReports reports) {
        this.jdbc = jdbc;
        this.reports = reports;
    }

    public List<Assignment> assignments(Account account) {
        return jdbc.query("""
                SELECT a.id, sub.name subject, v.name division, sem.name semester, p.name program
                FROM public.faculty_subject_assignments a
                JOIN public.academic_subjects sub ON sub.college_id=a.college_id AND sub.id=a.subject_id
                JOIN public.academic_divisions v ON v.college_id=a.college_id AND v.id=a.division_id
                JOIN public.academic_semesters sem ON sem.college_id=a.college_id AND sem.id=a.semester_id
                JOIN public.academic_programs p ON p.college_id=a.college_id AND p.id=a.program_id
                WHERE a.college_id=? AND a.faculty_id=? AND a.ended_at IS NULL
                ORDER BY sub.name, v.name, a.id
                """, (r, i) -> new Assignment(r.getLong("id"), r.getString("subject"), r.getString("division"),
                r.getString("semester"), r.getString("program")),
                account.requireCollege(CollegeRole.FACULTY, null), account.id());
    }

    public List<Session> history(Account account, int page) {
        return jdbc.query(SESSIONS + """
                WHERE s.college_id=? AND s.faculty_id=?
                ORDER BY s.session_date DESC, s.id DESC LIMIT ? OFFSET ?
                """, SESSION, account.requireCollege(CollegeRole.FACULTY, null), account.id(), PAGE_SIZE, offset(page));
    }

    public SessionDetail detail(Account account, CollegeRole role, long id) {
        long college = account.requireCollege(role, null);
        var session = session(college, owner(account, role), id, false);
        return new SessionDetail(session, roster(college, id));
    }

    @Transactional
    public SessionDetail create(Account account, Create input) {
        long college = writableCollege(account, CollegeRole.FACULTY);
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        OffsetDateTime cutoff = rosterAt(input, now);
        var authorized = jdbc.queryForList("""
                SELECT id FROM public.faculty_subject_assignments
                WHERE college_id=? AND faculty_id=? AND id=? AND ended_at IS NULL AND created_at<=?
                FOR SHARE
                """, Long.class, college, account.id(), input.assignmentId(), cutoff);
        if (authorized.isEmpty()) throw ApiError.ATTENDANCE_NOT_FOUND.exception();
        long id = jdbc.query("""
                INSERT INTO public.attendance_sessions (college_id,assignment_id,faculty_id,department_id,
                    program_id,academic_year_id,semester_id,division_id,subject_id,session_date,lecture_number,
                    start_time,end_time,roster_at)
                SELECT college_id,id,faculty_id,department_id,program_id,academic_year_id,semester_id,
                    division_id,subject_id,?,?,?,?,?
                FROM public.faculty_subject_assignments WHERE college_id=? AND faculty_id=? AND id=?
                ON CONFLICT ON CONSTRAINT uq_attendance_lecture DO NOTHING RETURNING id
                """, (r, i) -> r.getLong(1), input.date(), input.lectureNumber(), input.startTime(), input.endTime(),
                cutoff, college, account.id(), input.assignmentId()).stream().findFirst()
                .orElseThrow(ApiError.ATTENDANCE_SESSION_CONFLICT::exception);
        int size = jdbc.update("""
                INSERT INTO public.attendance_records (college_id,session_id,student_id,enrollment_id,department_id,
                    program_id,academic_year_id,semester_id,division_id)
                SELECT s.college_id,s.id,e.student_id,e.id,e.department_id,e.program_id,e.academic_year_id,
                    e.semester_id,e.division_id
                FROM public.attendance_sessions s
                JOIN public.student_enrollments e ON (e.college_id,e.department_id,e.program_id,e.academic_year_id,
                    e.semester_id,e.division_id)=(s.college_id,s.department_id,s.program_id,s.academic_year_id,
                    s.semester_id,s.division_id)
                JOIN public.users u ON u.college_id=e.college_id AND u.id=e.student_id
                WHERE s.college_id=? AND s.id=? AND e.created_at<=s.roster_at
                  AND (e.ended_at IS NULL OR e.ended_at>s.roster_at) AND u.is_active AND u.is_verified
                  AND EXISTS (SELECT FROM public.user_roles ur JOIN public.roles role ON role.id=ur.role_id
                              WHERE ur.user_id=u.id AND role.name='STUDENT')
                """, college, id);
        if (size == 0 || size > 2000) throw ApiError.ATTENDANCE_ROSTER_INVALID.exception();
        return detail(account, CollegeRole.FACULTY, id);
    }

    @Transactional
    public SessionDetail save(Account account, long id, Marks input, boolean submit) {
        long college = writableCollege(account, CollegeRole.FACULTY);
        var session = session(college, account.id(), id, true);
        if (session.status() != State.DRAFT || session.revision() != input.revision()) conflict();
        requireActiveAssignment(college, account.id(), session.assignmentId());
        var marks = checkedMarks(roster(college, id), input.records(), submit);
        for (var mark : marks.entrySet()) {
            jdbc.update("""
                    UPDATE public.attendance_records SET status=? WHERE college_id=? AND session_id=? AND student_id=?
                    """, mark.getValue().name(), college, id, mark.getKey());
        }
        jdbc.update("""
                UPDATE public.attendance_sessions SET revision=revision+1, status=?,
                    submitted_at=CASE WHEN ? THEN GREATEST(statement_timestamp(),created_at) END
                WHERE college_id=? AND id=?
                """, submit ? "SUBMITTED" : "DRAFT", submit, college, id);
        if (submit) reports.reconcileAlerts(college, id, reports.policy(college));
        return detail(account, CollegeRole.FACULTY, id);
    }

    @Transactional
    public SessionDetail correct(Account account, CollegeRole role, long id, long studentId, CorrectionInput input) {
        long college = writableCollege(account, role);
        var session = session(college, owner(account, role), id, true);
        Policy policy = reports.policy(college);
        if (session.revision() != input.revision() || session.status() == State.DRAFT) conflict();
        if (role == CollegeRole.FACULTY) {
            requireActiveAssignment(college, account.id(), session.assignmentId());
            if (session.status() != State.SUBMITTED || policy.correctionHours() == 0
                    || !Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT statement_timestamp() <= submitted_at + (? * interval '1 hour')
                        FROM public.attendance_sessions WHERE college_id=? AND id=?
                        """, Boolean.class, policy.correctionHours(), college, id))) conflict();
        }
        var row = roster(college, id).stream().filter(r -> r.studentId() == studentId).findFirst()
                .orElseThrow(ApiError.ATTENDANCE_NOT_FOUND::exception);
        if (row.status() == null) conflict();
        if (row.status() == input.status()) throw ApiError.INVALID_REQUEST.exception();
        jdbc.update("""
                INSERT INTO public.attendance_corrections (college_id,session_id,student_id,actor_id,
                    previous_status,new_status,reason) VALUES (?,?,?,?,?,?,?)
                """, college, id, studentId, account.id(), row.status().name(), input.status().name(), input.reason().strip());
        jdbc.update("UPDATE public.attendance_records SET status=? WHERE college_id=? AND session_id=? AND student_id=?",
                input.status().name(), college, id, studentId);
        jdbc.update("UPDATE public.attendance_sessions SET revision=revision+1 WHERE college_id=? AND id=?", college, id);
        reports.reconcileAlerts(college, id, policy);
        return detail(account, role, id);
    }

    @Transactional
    public SessionDetail lock(Account account, long id, Revision input) {
        long college = writableCollege(account, CollegeRole.COLLEGE_ADMIN);
        var session = session(college, null, id, true);
        if (session.status() != State.SUBMITTED || session.revision() != input.revision()) conflict();
        jdbc.update("""
                UPDATE public.attendance_sessions SET status='LOCKED', revision=revision+1,
                    locked_at=GREATEST(statement_timestamp(),submitted_at) WHERE college_id=? AND id=?
                """, college, id);
        return detail(account, CollegeRole.COLLEGE_ADMIN, id);
    }

    public List<Correction> audit(Account account, long id, int page) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        session(college, null, id, false);
        return jdbc.query("""
                SELECT a.*, u.full_name AS actor FROM public.attendance_corrections a
                JOIN public.users u ON u.college_id=a.college_id AND u.id=a.actor_id
                WHERE a.college_id=? AND a.session_id=? ORDER BY a.id DESC LIMIT ? OFFSET ?
                """, (r, i) -> new Correction(r.getLong("id"), r.getLong("student_id"), r.getString("actor"),
                Mark.valueOf(r.getString("previous_status")), Mark.valueOf(r.getString("new_status")),
                r.getString("reason"), r.getObject("corrected_at", OffsetDateTime.class)), college, id, PAGE_SIZE, offset(page));
    }

    @Transactional
    public Policy updatePolicy(Account account, Policy input) {
        long college = writableCollege(account, CollegeRole.COLLEGE_ADMIN);
        if (input.criticalThreshold().compareTo(input.warningThreshold()) >= 0)
            throw ApiError.ATTENDANCE_POLICY_INVALID.exception();
        jdbc.update("""
                INSERT INTO public.attendance_policies (college_id,warning_threshold,critical_threshold,count_late,
                    exclude_excused,correction_hours)
                VALUES (?,?,?,?,?,?) ON CONFLICT (college_id) DO UPDATE SET
                    warning_threshold=EXCLUDED.warning_threshold, critical_threshold=EXCLUDED.critical_threshold,
                    count_late=EXCLUDED.count_late, exclude_excused=EXCLUDED.exclude_excused,
                    correction_hours=EXCLUDED.correction_hours
                """, college, input.warningThreshold(), input.criticalThreshold(), input.countLate(),
                input.excludeExcused(), input.correctionHours());
        reports.reconcileAlerts(college, null, input);
        return input;
    }

    private long writableCollege(Account account, CollegeRole role) {
        long college = account.requireCollege(role, null);
        // Serialize attendance publications, corrections and policy changes within the same college.
        jdbc.query("SELECT pg_advisory_xact_lock(671530,pg_catalog.hashint8(?))", rs -> null, college);
        if (jdbc.queryForList("SELECT id FROM public.colleges WHERE id=? AND is_active FOR SHARE", Long.class, college)
                .isEmpty()) throw ApiError.ACCESS_DENIED.exception();
        return college;
    }

    private void requireActiveAssignment(long college, long faculty, long assignment) {
        if (jdbc.queryForList("""
                SELECT id FROM public.faculty_subject_assignments
                WHERE college_id=? AND faculty_id=? AND id=? AND ended_at IS NULL FOR SHARE
                """, Long.class, college, faculty, assignment).isEmpty()) throw ApiError.ATTENDANCE_NOT_FOUND.exception();
    }

    private static Long owner(Account account, CollegeRole role) {
        return role == CollegeRole.FACULTY ? account.id() : null;
    }

    private Session session(long college, Long faculty, long id, boolean lock) {
        return jdbc.query(SESSIONS + " WHERE s.college_id=? AND s.id=? AND (?::bigint IS NULL OR s.faculty_id=?)"
                        + (lock ? " FOR UPDATE OF s" : ""), SESSION, college, id, faculty, faculty)
                .stream().findFirst().orElseThrow(ApiError.ATTENDANCE_NOT_FOUND::exception);
    }

    private List<RosterRow> roster(long college, long id) {
        return jdbc.query(ROSTER, (r, i) -> {
            String mark = r.getString("status");
            return new RosterRow(r.getLong("student_id"), r.getString("full_name"), mark == null ? null : Mark.valueOf(mark));
        }, college, id);
    }

    static int offset(int page) {
        if (page < 0 || page > 1000) throw ApiError.INVALID_REQUEST.exception();
        return page * PAGE_SIZE;
    }

    private static void conflict() { throw ApiError.ATTENDANCE_STATE_CONFLICT.exception(); }

    static OffsetDateTime rosterAt(Create input, OffsetDateTime now) {
        LocalDate today = now.atZoneSameInstant(COLLEGE_ZONE).toLocalDate();
        if (input.date().isAfter(today) || (input.endTime() != null
                && (input.startTime() == null || !input.endTime().isAfter(input.startTime())))) {
            throw ApiError.ATTENDANCE_SESSION_INVALID.exception();
        }
        if (input.startTime() == null && input.date().equals(today)) return now;
        LocalTime time = input.startTime() != null ? input.startTime() : LocalTime.of(23, 59, 59, 999_999_000);
        var at = input.date().atTime(time).atZone(COLLEGE_ZONE).toOffsetDateTime();
        if (at.isAfter(now)) throw ApiError.ATTENDANCE_SESSION_INVALID.exception();
        return at;
    }

    static Map<Long, Mark> checkedMarks(List<RosterRow> roster, List<MarkInput> input, boolean submitted) {
        var allowed = new HashSet<Long>();
        roster.forEach(r -> allowed.add(r.studentId()));
        var result = new HashMap<Long, Mark>();
        for (var mark : input) {
            if (!allowed.contains(mark.studentId()) || result.put(mark.studentId(), mark.status()) != null)
                throw ApiError.ATTENDANCE_ROSTER_INVALID.exception();
        }
        if (submitted && (allowed.isEmpty() || result.size() != allowed.size()))
            throw ApiError.ATTENDANCE_ROSTER_INVALID.exception();
        return result;
    }
}
