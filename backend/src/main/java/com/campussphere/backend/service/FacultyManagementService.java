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
public class FacultyManagementService {
    private static final String MEMBERS = """
            SELECT u.id, u.full_name, u.email, p.department_id, d.name AS department
            FROM public.users u
            LEFT JOIN public.faculty_profiles p ON p.college_id = u.college_id AND p.faculty_id = u.id
            LEFT JOIN public.departments d ON d.college_id = p.college_id AND d.id = p.department_id
            WHERE u.college_id = ? AND u.is_active IS TRUE AND u.is_verified IS TRUE
              AND EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                          WHERE ur.user_id = u.id AND r.name = 'FACULTY')
              AND NOT EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                              WHERE ur.user_id = u.id AND r.name = 'SUPER_ADMIN')
            """;
    private static final RowMapper<Member> MEMBER = (r, n) -> new Member(r.getLong("id"),
            r.getString("full_name"), r.getString("email"), r.getObject("department_id", Long.class),
            r.getString("department"));
    private static final String ASSIGNMENTS = """
            SELECT a.id, d.name AS department, p.name AS program, y.name AS academic_year,
                   s.name AS semester, v.name AS division, b.name AS subject, b.code AS subject_code
            FROM public.faculty_subject_assignments a
            JOIN public.departments d ON d.college_id = a.college_id AND d.id = a.department_id
            JOIN public.academic_programs p ON p.college_id = a.college_id AND p.id = a.program_id
            JOIN public.academic_years y ON y.college_id = a.college_id AND y.id = a.academic_year_id
            JOIN public.academic_semesters s ON s.college_id = a.college_id AND s.id = a.semester_id
            JOIN public.academic_divisions v ON v.college_id = a.college_id AND v.id = a.division_id
            JOIN public.academic_subjects b ON b.college_id = a.college_id AND b.id = a.subject_id
            WHERE a.college_id = ? AND a.faculty_id = ? AND a.ended_at IS NULL
            """;
    private static final RowMapper<Assignment> ASSIGNMENT = (r, n) -> new Assignment(r.getLong("id"),
            r.getString("department"), r.getString("program"), r.getString("academic_year"), r.getString("semester"),
            r.getString("division"), r.getString("subject"), r.getString("subject_code"));
    private final JdbcTemplate jdbc;

    public FacultyManagementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Member> list(Account account) {
        return jdbc.query(MEMBERS + " ORDER BY u.full_name, u.id", MEMBER, college(account, CollegeRole.COLLEGE_ADMIN));
    }

    public Member profile(Account account, long facultyId) {
        return member(college(account, CollegeRole.COLLEGE_ADMIN), facultyId, false);
    }

    @Transactional
    public Member changeDepartment(Account account, long facultyId, long departmentId) {
        long college = college(account, CollegeRole.COLLEGE_ADMIN);
        Member member = member(college, facultyId, true);
        if (Objects.equals(member.departmentId(), departmentId)) return member;
        if (!exists("SELECT EXISTS (SELECT 1 FROM public.departments WHERE college_id = ? AND id = ?)",
                college, departmentId)) throw ApiError.DEPARTMENT_NOT_FOUND.exception();
        if (!exists("SELECT EXISTS (SELECT 1 FROM public.departments WHERE college_id = ? AND id = ? AND is_active)",
                college, departmentId)) throw ApiError.DEPARTMENT_INACTIVE.exception();
        if (exists("""
                SELECT EXISTS (SELECT 1 FROM public.faculty_subject_assignments
                               WHERE college_id = ? AND faculty_id = ? AND ended_at IS NULL)
                """, college, facultyId)) throw ApiError.FACULTY_DEPARTMENT_CONFLICT.exception();

        jdbc.update("""
                INSERT INTO public.faculty_profiles (college_id, faculty_id, department_id)
                VALUES (?, ?, ?) ON CONFLICT (college_id, faculty_id)
                DO UPDATE SET department_id = EXCLUDED.department_id
                """, college, facultyId, departmentId);
        return member(college, facultyId, false);
    }

    public List<Assignment> assignments(Account account, long facultyId) {
        long college = college(account, CollegeRole.COLLEGE_ADMIN);
        member(college, facultyId, false);
        return active(college, facultyId);
    }

    public List<Assignment> dashboard(Account account) {
        return active(college(account, CollegeRole.FACULTY), account.id());
    }

    @Transactional
    public Assignment assign(Account account, long facultyId, AssignmentInput input) {
        long college = college(account, CollegeRole.COLLEGE_ADMIN);
        Member member = member(college, facultyId, true);
        if (!Objects.equals(member.departmentId(), input.departmentId())) {
            throw ApiError.FACULTY_ASSIGNMENT_INVALID.exception();
        }
        if (!exists("SELECT EXISTS (SELECT 1 FROM public.departments WHERE college_id = ? AND id = ? AND is_active)",
                college, input.departmentId())) throw ApiError.DEPARTMENT_INACTIVE.exception();
        boolean matchingHierarchy = exists("""
                SELECT EXISTS (
                    SELECT 1 FROM public.academic_programs p
                    JOIN public.academic_semesters s ON s.college_id = p.college_id AND s.program_id = p.id
                    JOIN public.academic_divisions v ON v.college_id = p.college_id AND v.program_id = p.id
                         AND v.academic_year_id = s.academic_year_id
                    JOIN public.academic_subjects b ON b.college_id = p.college_id AND b.program_id = p.id
                         AND b.semester_id = s.id
                    WHERE p.college_id = ? AND p.department_id = ? AND p.id = ? AND s.academic_year_id = ?
                         AND s.id = ? AND v.id = ? AND b.id = ?
                )
                """, college, input.departmentId(), input.programId(), input.academicYearId(),
                input.semesterId(), input.divisionId(), input.subjectId());
        if (!matchingHierarchy) throw ApiError.FACULTY_ASSIGNMENT_INVALID.exception();

        long id = jdbc.query("""
                INSERT INTO public.faculty_subject_assignments
                    (college_id, faculty_id, department_id, program_id, academic_year_id, semester_id, division_id, subject_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (college_id, faculty_id, division_id, subject_id) WHERE ended_at IS NULL
                DO NOTHING RETURNING id
                """, (row, index) -> row.getLong("id"), college, facultyId, input.departmentId(), input.programId(),
                input.academicYearId(), input.semesterId(), input.divisionId(), input.subjectId())
                .stream().findFirst().orElseThrow(ApiError.FACULTY_ASSIGNMENT_CONFLICT::exception);
        return jdbc.query(ASSIGNMENTS + " AND a.id = ?", ASSIGNMENT, college, facultyId, id).getFirst();
    }

    @Transactional
    public void end(Account account, long facultyId, long assignmentId) {
        long college = college(account, CollegeRole.COLLEGE_ADMIN);
        member(college, facultyId, true);
        if (jdbc.update("""
                UPDATE public.faculty_subject_assignments SET ended_at = GREATEST(statement_timestamp(), created_at)
                WHERE college_id = ? AND faculty_id = ? AND id = ? AND ended_at IS NULL
                """, college, facultyId, assignmentId) == 0) {
            throw ApiError.FACULTY_ASSIGNMENT_NOT_FOUND.exception();
        }
    }

    private List<Assignment> active(long college, long facultyId) {
        return jdbc.query(ASSIGNMENTS + " ORDER BY y.code, s.semester_number, v.code, b.code",
                ASSIGNMENT, college, facultyId);
    }

    private Member member(long college, long facultyId, boolean lock) {
        if (lock) {
            // Serialize all mutations for this faculty member. Read the profile in a fresh statement after waiting.
            if (jdbc.queryForList("""
                    SELECT id FROM public.users WHERE college_id = ? AND id = ? FOR UPDATE
                    """, Long.class, college, facultyId).isEmpty()) throw ApiError.FACULTY_NOT_FOUND.exception();
        }
        return jdbc.query(MEMBERS + " AND u.id = ?", MEMBER, college, facultyId)
                .stream().findFirst().orElseThrow(ApiError.FACULTY_NOT_FOUND::exception);
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private static long college(Account account, CollegeRole role) {
        if (account == null) throw ApiError.ACCESS_DENIED.exception();
        return account.requireCollege(role, null);
    }

    public record Member(long id, String name, String email, Long departmentId, String department) {}
    public record Assignment(long id, String department, String program, String academicYear, String semester,
                             String division, String subject, String subjectCode) {}
    public record AssignmentInput(@Positive long departmentId, @Positive long programId,
                                  @Positive long academicYearId, @Positive long semesterId,
                                  @Positive long divisionId, @Positive long subjectId) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported assignment field");
        }
    }
}
