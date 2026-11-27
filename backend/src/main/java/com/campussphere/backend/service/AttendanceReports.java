package com.campussphere.backend.service;

import static com.campussphere.backend.dto.AttendanceModels.*;
import static com.campussphere.backend.service.AttendanceService.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AttendanceReports {
    private static final String COUNTS = """
            count(*) FILTER (WHERE r.status='PRESENT') AS present,
            count(*) FILTER (WHERE r.status='ABSENT') AS absent,
            count(*) FILTER (WHERE r.status='LATE') AS late,
            count(*) FILTER (WHERE r.status='EXCUSED') AS excused
            """;
    private static final String RECORDS = """
            FROM public.attendance_records r
            JOIN public.attendance_sessions s ON s.college_id=r.college_id AND s.id=r.session_id
            """;
    private static final String PUBLISHED = " WHERE s.college_id=? AND s.status IN ('SUBMITTED','LOCKED')";
    private static final String SUBJECT = """
            JOIN public.academic_subjects sub ON sub.college_id=s.college_id AND sub.id=s.subject_id
            """;
    private static final String STUDENT = """
            JOIN public.users student ON student.college_id=r.college_id AND student.id=r.student_id
            """;
    private static final String ALERTS = """
            SELECT a.*, u.full_name AS student FROM public.attendance_alerts a
            JOIN public.users u ON u.college_id=a.college_id AND u.id=a.student_id
            WHERE a.college_id=?
            """;
    private static final RowMapper<Alert> ALERT = (r, i) -> new Alert(r.getLong("id"), r.getLong("student_id"),
            r.getString("student"), Severity.valueOf(r.getString("severity")), r.getBigDecimal("percentage"),
            r.getObject("created_at", OffsetDateTime.class), r.getObject("resolved_at", OffsetDateTime.class));
    private final JdbcTemplate jdbc;

    public AttendanceReports(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Policy policy(Account account) { return policy(account.requireCollege(CollegeRole.COLLEGE_ADMIN, null)); }

    Policy policy(long college) {
        return jdbc.query("""
                SELECT warning_threshold,critical_threshold,count_late,exclude_excused,correction_hours
                FROM public.attendance_policies WHERE college_id=?
                """, (r, i) -> new Policy(r.getBigDecimal(1), r.getBigDecimal(2), r.getBoolean(3),
                r.getBoolean(4), r.getInt(5)), college).stream().findFirst().orElse(Policy.DEFAULT);
    }

    public StudentOverview student(Account account, Filter filter) {
        long college = account.requireCollege(CollegeRole.STUDENT, null);
        if (!account.verified()) throw ApiError.VERIFICATION_REQUIRED.exception();
        var query = filtered(college, new Filter(filter.from(), filter.to(), null, null, null, null,
                filter.subjectId(), account.id(), null, filter.page()));
        Policy policy = policy(college);
        var history = jdbc.query("""
                SELECT s.id,s.session_date,s.lecture_number,sub.name AS subject,v.name AS division,r.status
                """ + RECORDS + SUBJECT + """
                JOIN public.academic_divisions v ON v.college_id=s.college_id AND v.id=s.division_id
                """ + query.where() + " ORDER BY s.session_date DESC,s.id DESC LIMIT ? OFFSET ?", (r, i) ->
                new HistoryRow(r.getLong("id"), r.getObject("session_date", LocalDate.class), r.getInt("lecture_number"),
                        r.getString("subject"), r.getString("division"), Mark.valueOf(r.getString("status"))),
                query.paged());
        return new StudentOverview(policy, overall(query, policy), subjects(query, policy), history,
                jdbc.query(ALERTS + " AND a.student_id=? ORDER BY a.id DESC LIMIT ?", ALERT, college, account.id(),
                        PAGE_SIZE));
    }

    public List<Option> studentSubjects(Account account) {
        long college = account.requireCollege(CollegeRole.STUDENT, null);
        if (!account.verified()) throw ApiError.VERIFICATION_REQUIRED.exception();
        return jdbc.query("SELECT DISTINCT sub.id,sub.name AS label " + RECORDS + SUBJECT + PUBLISHED
                        + " AND r.student_id=? ORDER BY label,sub.id", AttendanceReports::option, college, account.id());
    }

    public Report report(Account account, Filter filter) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        Query query = filtered(college, filter);
        Policy policy = policy(college);
        var students = jdbc.query("SELECT student.id,student.full_name," + COUNTS + RECORDS + STUDENT + query.where()
                        + " GROUP BY student.id,student.full_name ORDER BY student.full_name,student.id LIMIT ? OFFSET ?",
                (r, i) -> new StudentSummary(r.getLong("id"), r.getString("full_name"), summary(r, policy)), query.paged());
        // Use the same filters while returning each session once, including when filtering by Student.
        var sessions = jdbc.query(SESSIONS + """
                WHERE (s.college_id,s.id) IN (
                    SELECT s.college_id,s.id
                """ + RECORDS + query.where() + """
                ) ORDER BY s.session_date DESC,s.id DESC LIMIT ? OFFSET ?
                """, SESSION, query.paged());
        return new Report(policy, overall(query, policy), subjects(query, policy), students, sessions);
    }

    public List<Alert> lowAttendance(Account account, int page) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        return jdbc.query(ALERTS + " AND a.resolved_at IS NULL ORDER BY a.percentage,a.id LIMIT ? OFFSET ?",
                ALERT, college, PAGE_SIZE, offset(page));
    }

    public ReportOptions options(Account account) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var result = new LinkedHashMap<String, List<Option>>();
        // Labels carry their context ("BSc · Semester 3", "BSc · 2026-2027 · A") so equal names can be told apart.
        addOptions(result, "department", "departments", "department_id", college, "t.name");
        addOptions(result, "program", "academic_programs", "program_id", college, "t.name");
        addOptions(result, "semester", "academic_semesters", "semester_id", college, PROGRAM_OF_T + " || ' · ' || t.name");
        addOptions(result, "division", "academic_divisions", "division_id", college, PROGRAM_OF_T + " || ' · ' || "
                + "(SELECT y.code FROM public.academic_years y WHERE y.college_id=t.college_id AND y.id=t.academic_year_id)"
                + " || ' · ' || t.name");
        addOptions(result, "subject", "academic_subjects", "subject_id", college, "t.code || ' · ' || t.name");
        result.put("faculty", jdbc.query("""
                SELECT DISTINCT u.id,u.full_name AS label FROM public.attendance_sessions s
                JOIN public.users u ON u.college_id=s.college_id AND u.id=s.faculty_id
                """ + PUBLISHED + " ORDER BY label,u.id", AttendanceReports::option, college));
        result.put("student", jdbc.query("SELECT DISTINCT student.id,student.full_name AS label " + RECORDS + STUDENT
                + PUBLISHED + " ORDER BY label,student.id", AttendanceReports::option, college));
        return new ReportOptions(result);
    }

    private static final String PROGRAM_OF_T =
            "(SELECT p.name FROM public.academic_programs p WHERE p.college_id=t.college_id AND p.id=t.program_id)";

    private void addOptions(Map<String, List<Option>> options, String key, String table, String column, long college,
                            String label) {
        // Table, column and label expressions come only from the fixed mappings above.
        options.put(key, jdbc.query("SELECT DISTINCT t.id," + label + " AS label FROM public.attendance_sessions s"
                + " JOIN public." + table + " t ON t.college_id=s.college_id AND t.id=s." + column + PUBLISHED
                + " ORDER BY label,t.id", AttendanceReports::option, college));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcileAlerts(long college, Long session, Policy policy) {
        var totals = jdbc.query("SELECT r.student_id," + COUNTS + RECORDS + PUBLISHED + """
                 AND (?::bigint IS NULL OR EXISTS (SELECT FROM public.attendance_records affected
                   WHERE affected.college_id=r.college_id AND affected.student_id=r.student_id AND affected.session_id=?))
                GROUP BY r.student_id
                """, (r, i) -> new StudentTotal(r.getLong("student_id"), summary(r, policy)), college, session, session);
        for (var item : totals) {
            var total = item.summary();
            jdbc.update("""
                    UPDATE public.attendance_alerts SET resolved_at=GREATEST(statement_timestamp(),created_at)
                    WHERE college_id=? AND student_id=? AND resolved_at IS NULL AND severity<>?
                    """, college, item.studentId(), total.severity().name());
            if (total.severity() != Severity.NORMAL) {
                jdbc.update("""
                        INSERT INTO public.attendance_alerts (college_id,student_id,severity,percentage)
                        VALUES (?,?,?,?) ON CONFLICT (college_id,student_id) WHERE resolved_at IS NULL
                        DO UPDATE SET percentage=EXCLUDED.percentage
                        """, college, item.studentId(), total.severity().name(), total.percentage());
            }
        }
    }

    private Summary overall(Query query, Policy policy) {
        return jdbc.queryForObject("SELECT " + COUNTS + RECORDS + query.where(), (r, i) -> summary(r, policy),
                query.args());
    }

    private List<SubjectSummary> subjects(Query query, Policy policy) {
        return jdbc.query("SELECT sub.id,sub.name," + COUNTS + RECORDS + SUBJECT + query.where()
                        + " GROUP BY sub.id,sub.name ORDER BY sub.name,sub.id",
                (r, i) -> new SubjectSummary(r.getLong("id"), r.getString("name"), summary(r, policy)), query.args());
    }

    private static Query filtered(long college, Filter filter) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to()))
            throw ApiError.INVALID_REQUEST.exception();
        var where = new StringBuilder(PUBLISHED);
        var args = new ArrayList<Object>();
        args.add(college);
        append(where, args, "s.session_date>=?", filter.from());
        append(where, args, "s.session_date<=?", filter.to());
        append(where, args, "s.department_id=?", filter.departmentId());
        append(where, args, "s.program_id=?", filter.programId());
        append(where, args, "s.semester_id=?", filter.semesterId());
        append(where, args, "s.division_id=?", filter.divisionId());
        append(where, args, "s.subject_id=?", filter.subjectId());
        append(where, args, "r.student_id=?", filter.studentId());
        append(where, args, "s.faculty_id=?", filter.facultyId());
        return new Query(where.toString(), args, offset(filter.page()));
    }

    private static void append(StringBuilder where, List<Object> args, String sql, Object value) {
        if (value == null) return;
        where.append(" AND ").append(sql);
        args.add(value);
    }

    private static Option option(ResultSet row, int index) throws SQLException {
        return new Option(row.getLong("id"), row.getString("label"));
    }

    private static Summary summary(ResultSet row, Policy policy) throws SQLException {
        return summarize(row.getLong("present"), row.getLong("absent"), row.getLong("late"), row.getLong("excused"),
                policy);
    }

    static Summary summarize(long present, long absent, long late, long excused, Policy policy) {
        long attended = present + (policy.countLate() ? late : 0);
        long eligible = present + absent + late + (policy.excludeExcused() ? 0 : excused);
        if (eligible == 0) return new Summary(present, absent, late, excused, attended, eligible, null, Severity.NORMAL);
        BigDecimal numerator = BigDecimal.valueOf(attended).movePointRight(2);
        BigDecimal denominator = BigDecimal.valueOf(eligible);
        Severity severity = numerator.compareTo(policy.criticalThreshold().multiply(denominator)) < 0
                ? Severity.CRITICAL : numerator.compareTo(policy.warningThreshold().multiply(denominator)) < 0
                ? Severity.WARNING : Severity.NORMAL;
        return new Summary(present, absent, late, excused, attended, eligible,
                numerator.divide(denominator, 2, RoundingMode.HALF_UP), severity);
    }

    private record StudentTotal(long studentId, Summary summary) {}
    private record Query(String where, List<Object> values, int offset) {
        Object[] args() { return values.toArray(); }
        Object[] paged() {
            var page = new ArrayList<>(values);
            page.add(PAGE_SIZE);
            page.add(offset);
            return page.toArray();
        }
    }
}
