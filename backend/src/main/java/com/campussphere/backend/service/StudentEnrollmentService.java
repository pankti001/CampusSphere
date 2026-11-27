package com.campussphere.backend.service;

import java.util.List;
import java.util.Objects;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Positive;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StudentEnrollmentService {
    // Admission verification is mandatory for managed student data, even when legacy greeting access is configurable.
    static final String STUDENT_ONLY = """
              AND EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                          WHERE ur.user_id = u.id AND r.name = 'STUDENT')
              AND NOT EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                              WHERE ur.user_id = u.id AND r.name <> 'STUDENT')
            """;
    private static final String MEMBERS = """
            SELECT u.id, u.full_name, u.email, p.department_id, d.name AS department
            FROM public.users u
            LEFT JOIN public.student_profiles p ON p.college_id = u.college_id AND p.student_id = u.id
            LEFT JOIN public.departments d ON d.college_id = p.college_id AND d.id = p.department_id
            WHERE u.college_id = ? AND u.is_active IS TRUE AND u.is_verified IS TRUE
            """ + STUDENT_ONLY;
    private static final RowMapper<Member> MEMBER = (r, n) -> new Member(r.getLong("id"),
            r.getString("full_name"), r.getString("email"), r.getObject("department_id", Long.class),
            r.getString("department"));
    private static final String ENROLLMENTS = """
            SELECT e.id, c.name AS college, d.name AS department, p.name AS program, y.name AS academic_year,
                   s.name AS semester, v.name AS division, e.ended_at IS NULL AS current
            FROM public.student_enrollments e
            JOIN public.colleges c ON c.id = e.college_id
            JOIN public.departments d ON d.college_id = e.college_id AND d.id = e.department_id
            JOIN public.academic_programs p ON p.college_id = e.college_id AND p.id = e.program_id
            JOIN public.academic_years y ON y.college_id = e.college_id AND y.id = e.academic_year_id
            JOIN public.academic_semesters s ON s.college_id = e.college_id AND s.id = e.semester_id
            JOIN public.academic_divisions v ON v.college_id = e.college_id AND v.id = e.division_id
            WHERE e.college_id = ? AND e.student_id = ?
            """;
    private static final RowMapper<Enrollment> ENROLLMENT = (r, n) -> new Enrollment(r.getLong("id"),
            r.getString("college"), r.getString("department"), r.getString("program"), r.getString("academic_year"),
            r.getString("semester"), r.getString("division"), r.getBoolean("current"));
    private final JdbcTemplate jdbc;

    public StudentEnrollmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Member> list(Account account) {
        return jdbc.query(MEMBERS + " ORDER BY u.full_name, u.id", MEMBER, college(account, CollegeRole.COLLEGE_ADMIN));
    }

    public Member profile(Account account, long studentId) {
        return member(college(account, CollegeRole.COLLEGE_ADMIN), studentId, false);
    }

    @Transactional
    public Member changeDepartment(Account account, long studentId, long departmentId) {
        long college = college(account, CollegeRole.COLLEGE_ADMIN);
        Member student = member(college, studentId, true);
        if (Objects.equals(student.departmentId(), departmentId)) return student;
        if (!exists("SELECT EXISTS (SELECT 1 FROM public.departments WHERE college_id = ? AND id = ?)",
                college, departmentId)) throw ApiError.DEPARTMENT_NOT_FOUND.exception();
        if (!exists("SELECT EXISTS (SELECT 1 FROM public.departments WHERE college_id = ? AND id = ? AND is_active)",
                college, departmentId)) throw ApiError.DEPARTMENT_INACTIVE.exception();
        if (exists("""
                SELECT EXISTS (SELECT 1 FROM public.student_enrollments
                               WHERE college_id = ? AND student_id = ? AND ended_at IS NULL)
                """, college, studentId)) throw ApiError.STUDENT_DEPARTMENT_CONFLICT.exception();
        jdbc.update("""
                INSERT INTO public.student_profiles (college_id, student_id, department_id) VALUES (?, ?, ?)
                ON CONFLICT (college_id, student_id) DO UPDATE SET department_id = EXCLUDED.department_id
                """, college, studentId, departmentId);
        return member(college, studentId, false);
    }

    public List<Enrollment> history(Account account, long studentId) {
        long college = college(account, CollegeRole.COLLEGE_ADMIN);
        member(college, studentId, false);
        return jdbc.query(ENROLLMENTS + " ORDER BY e.id DESC", ENROLLMENT, college, studentId);
    }

    public List<Enrollment> current(Account account) {
        long college = college(account, CollegeRole.STUDENT);
        if (!account.verified()) throw ApiError.VERIFICATION_REQUIRED.exception();
        member(college, account.id(), false);
        return jdbc.query(ENROLLMENTS + " AND e.ended_at IS NULL", ENROLLMENT, college, account.id());
    }

    @Transactional
    public Enrollment enroll(Account account, long studentId, EnrollmentInput input) {
        long college = college(account, CollegeRole.COLLEGE_ADMIN);
        Member student = member(college, studentId, true);
        if (Objects.equals(student.departmentId(), input.departmentId()) && !exists(
                "SELECT EXISTS (SELECT 1 FROM public.departments WHERE college_id = ? AND id = ? AND is_active)",
                college, input.departmentId())) throw ApiError.DEPARTMENT_INACTIVE.exception();
        boolean valid = Objects.equals(student.departmentId(), input.departmentId()) && exists("""
                SELECT EXISTS (
                    SELECT 1 FROM public.academic_programs p
                    JOIN public.academic_semesters s ON s.college_id = p.college_id AND s.program_id = p.id
                    JOIN public.academic_divisions v ON v.college_id = p.college_id AND v.program_id = p.id
                         AND v.academic_year_id = s.academic_year_id
                    WHERE p.college_id = ? AND p.department_id = ? AND p.id = ? AND s.academic_year_id = ?
                         AND s.id = ? AND v.id = ?
                )
                """, college, input.departmentId(), input.programId(), input.academicYearId(),
                input.semesterId(), input.divisionId());
        if (!valid) throw ApiError.STUDENT_ENROLLMENT_INVALID.exception();
        long id = jdbc.query("""
                INSERT INTO public.student_enrollments
                    (college_id, student_id, department_id, program_id, academic_year_id, semester_id, division_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (student_id) WHERE ended_at IS NULL DO NOTHING RETURNING id
                """, (r, n) -> r.getLong("id"), college, studentId, input.departmentId(), input.programId(),
                input.academicYearId(), input.semesterId(), input.divisionId())
                .stream().findFirst().orElseThrow(ApiError.STUDENT_ENROLLMENT_CONFLICT::exception);
        return jdbc.query(ENROLLMENTS + " AND e.id = ?", ENROLLMENT, college, studentId, id).getFirst();
    }

    @Transactional
    public void end(Account account, long studentId, long enrollmentId) {
        long college = college(account, CollegeRole.COLLEGE_ADMIN);
        member(college, studentId, true);
        if (jdbc.update("""
                UPDATE public.student_enrollments SET ended_at = GREATEST(statement_timestamp(), created_at)
                WHERE college_id = ? AND student_id = ? AND id = ? AND ended_at IS NULL
                """, college, studentId, enrollmentId) != 1) {
            throw ApiError.STUDENT_ENROLLMENT_NOT_FOUND.exception();
        }
    }

    private Member member(long college, long studentId, boolean lock) {
        if (lock && jdbc.queryForList("""
                SELECT id FROM public.users WHERE college_id = ? AND id = ? FOR UPDATE
                """, Long.class, college, studentId).isEmpty()) throw ApiError.STUDENT_NOT_FOUND.exception();
        return jdbc.query(MEMBERS + " AND u.id = ?", MEMBER, college, studentId)
                .stream().findFirst().orElseThrow(ApiError.STUDENT_NOT_FOUND::exception);
    }

    private boolean exists(String sql, Object... arguments) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, arguments));
    }

    static long college(Account account, CollegeRole role) {
        if (account == null) throw ApiError.ACCESS_DENIED.exception();
        return account.requireCollege(role, null);
    }

    public record Member(long id, String name, String email, Long departmentId, String department) {}
    public record Enrollment(long id, String college, String department, String program, String academicYear,
                             String semester, String division, boolean current) {}
    public record EnrollmentInput(@Positive long departmentId, @Positive long programId,
                                  @Positive long academicYearId, @Positive long semesterId,
                                  @Positive long divisionId) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("Unsupported enrollment field");
        }
    }
}
