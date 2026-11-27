package com.campussphere.backend.service;

import java.time.OffsetDateTime;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The College Admin home: the admin's own college and real counts of what it holds. Every number is a count over
 * the signed-in admin's college only; the rules match the lists the admin opens from the home screen.
 */
@Service
@Transactional(readOnly = true)
public class CollegeOverview {
    public record Profile(long id, String code, String name, boolean active, OffsetDateTime createdAt,
                          OffsetDateTime updatedAt) {}

    /**
     * faculty and students: active, verified accounts (the same people the Faculty and Students lists show).
     * currentEnrollments / activeAssignments: not ended. pending*: requests waiting for this college.
     * attendanceSessions: submitted or locked. materials: published. liveAnnouncements: published, started and not
     * expired. divisionsWithoutTimetable: divisions with no published timetable in any semester.
     */
    public record Counts(long departments, long programs, long academicYears, long semesters, long divisions,
                         long subjects, long classrooms, long faculty, long students, long currentEnrollments,
                         long activeAssignments, long pendingFacultyRequests, long pendingStudentRequests,
                         long attendanceSessions, long materials, long liveAnnouncements, long publishedTimetables,
                         long draftTimetables, long divisionsWithoutTimetable) {}

    static final String ROLE = """
            EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                    WHERE ur.user_id = u.id AND r.name = '%s')""";

    static final String COUNTS = """
            SELECT
              (SELECT count(*) FROM public.departments WHERE college_id = :c) departments,
              (SELECT count(*) FROM public.academic_programs WHERE college_id = :c) programs,
              (SELECT count(*) FROM public.academic_years WHERE college_id = :c) academic_years,
              (SELECT count(*) FROM public.academic_semesters WHERE college_id = :c) semesters,
              (SELECT count(*) FROM public.academic_divisions WHERE college_id = :c) divisions,
              (SELECT count(*) FROM public.academic_subjects WHERE college_id = :c) subjects,
              (SELECT count(*) FROM public.classrooms WHERE college_id = :c AND is_active) classrooms,
              (SELECT count(*) FROM public.users u WHERE u.college_id = :c AND u.is_active IS TRUE
                 AND u.is_verified IS TRUE AND %s
                 AND NOT EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                                 WHERE ur.user_id = u.id AND r.name = 'SUPER_ADMIN')) faculty,
              (SELECT count(*) FROM public.users u WHERE u.college_id = :c AND u.is_active IS TRUE
                 AND u.is_verified IS TRUE AND %s
                 AND NOT EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                                 WHERE ur.user_id = u.id AND r.name <> 'STUDENT')) students,
              (SELECT count(*) FROM public.student_enrollments WHERE college_id = :c AND ended_at IS NULL) current_enrollments,
              (SELECT count(*) FROM public.faculty_subject_assignments WHERE college_id = :c AND ended_at IS NULL) active_assignments,
              (SELECT count(*) FROM public.faculty_access_requests
                 WHERE requested_college_id = :c AND status = 'PENDING') pending_faculty_requests,
              (SELECT count(*) FROM public.student_college_requests
                 WHERE college_id = :c AND status = 'PENDING') pending_student_requests,
              (SELECT count(*) FROM public.attendance_sessions
                 WHERE college_id = :c AND status IN ('SUBMITTED', 'LOCKED')) attendance_sessions,
              (SELECT count(*) FROM public.study_materials WHERE college_id = :c AND status = 'PUBLISHED') materials,
              (SELECT count(*) FROM public.announcements WHERE college_id = :c AND status = 'PUBLISHED'
                 AND publish_at <= statement_timestamp()
                 AND (expires_at IS NULL OR expires_at > statement_timestamp())) live_announcements,
              (SELECT count(*) FROM public.timetables WHERE college_id = :c AND status = 'PUBLISHED') published_timetables,
              (SELECT count(*) FROM public.timetables WHERE college_id = :c AND status = 'DRAFT') draft_timetables,
              (SELECT count(*) FROM public.academic_divisions v WHERE v.college_id = :c
                 AND NOT EXISTS (SELECT 1 FROM public.timetables t WHERE t.college_id = v.college_id
                                 AND t.division_id = v.id AND t.status = 'PUBLISHED')) divisions_without_timetable
            """.formatted(ROLE.formatted("FACULTY"), ROLE.formatted("STUDENT"));

    private final JdbcTemplate jdbc;

    public CollegeOverview(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Profile profile(Account account) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var rows = jdbc.query("""
                SELECT id, code, name, is_active, created_at, updated_at FROM public.colleges WHERE id = ?
                """, (r, i) -> new Profile(r.getLong("id"), r.getString("code"), r.getString("name"),
                r.getBoolean("is_active"), r.getObject("created_at", OffsetDateTime.class),
                r.getObject("updated_at", OffsetDateTime.class)), college);
        if (rows.isEmpty()) throw ApiError.ACCESS_DENIED.exception();
        return rows.getFirst();
    }

    public Counts counts(Account account) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        int uses = COUNTS.split(":c", -1).length - 1;
        Object[] args = new Object[uses];
        java.util.Arrays.fill(args, college);
        return jdbc.queryForObject(COUNTS.replace(":c", "?"), (r, i) -> new Counts(r.getLong("departments"),
                r.getLong("programs"), r.getLong("academic_years"), r.getLong("semesters"), r.getLong("divisions"),
                r.getLong("subjects"), r.getLong("classrooms"), r.getLong("faculty"), r.getLong("students"),
                r.getLong("current_enrollments"), r.getLong("active_assignments"),
                r.getLong("pending_faculty_requests"), r.getLong("pending_student_requests"),
                r.getLong("attendance_sessions"), r.getLong("materials"), r.getLong("live_announcements"),
                r.getLong("published_timetables"), r.getLong("draft_timetables"),
                r.getLong("divisions_without_timetable")), args);
    }
}
