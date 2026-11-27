package com.campussphere.backend.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * College Admin account care for the college's own faculty and students: see everyone (including deactivated
 * people), correct a name or phone number, and deactivate or reactivate an account. Email stays fixed because it
 * is the sign-in identity. A deactivated account cannot sign in and its existing sessions stop working at once;
 * its history (attendance, materials, assignments, enrollments) is kept. Admin accounts are never changed here.
 */
@Service
@Transactional(readOnly = true)
public class CollegeAccounts {
    public enum Kind {
        FACULTY("FACULTY", ApiError.FACULTY_NOT_FOUND), STUDENT("STUDENT", ApiError.STUDENT_NOT_FOUND);

        final String role;
        final ApiError notFound;

        Kind(String role, ApiError notFound) {
            this.role = role;
            this.notFound = notFound;
        }

        public static Kind of(String path) {
            return switch (path) {
                case "faculty" -> FACULTY;
                case "students" -> STUDENT;
                default -> throw ApiError.INVALID_REQUEST.exception();
            };
        }
    }

    public record Person(long id, String name, String email, String phone, boolean active, boolean verified,
                         String department, LocalDateTime joinedAt) {}

    public record Details(String fullName, String phone) {
        public Details clean() {
            String name = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
            String digits = phone == null ? "" : phone.trim();
            if (name.isEmpty() || name.length() > 100 || (!digits.isEmpty() && !digits.matches("[0-9]{10}")))
                throw ApiError.INVALID_REQUEST.exception();
            return new Details(name, digits.isEmpty() ? null : digits);
        }
    }

    public record Status(Boolean active) {}

    /** Only people whose roles are exactly this kind (never an admin) and who belong to the admin's college. */
    static String people(Kind kind) {
        String profiles = kind == Kind.FACULTY ? "faculty_profiles p ON p.college_id = u.college_id AND p.faculty_id = u.id"
                : "student_profiles p ON p.college_id = u.college_id AND p.student_id = u.id";
        return """
                SELECT u.id, u.full_name, u.email, u.phone, (u.is_active IS TRUE) active, (u.is_verified IS TRUE) verified,
                       d.name department, u.created_at
                FROM public.users u
                LEFT JOIN public.%s
                LEFT JOIN public.departments d ON d.college_id = p.college_id AND d.id = p.department_id
                WHERE u.college_id = ?
                  AND EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                              WHERE ur.user_id = u.id AND r.name = '%s')
                  AND NOT EXISTS (SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                                  WHERE ur.user_id = u.id AND r.name <> '%s')
                """.formatted(profiles, kind.role, kind.role);
    }

    private static final RowMapper<Person> PERSON = (r, i) -> new Person(r.getLong("id"), r.getString("full_name"),
            r.getString("email"), r.getString("phone"), r.getBoolean("active"), r.getBoolean("verified"),
            r.getString("department"), r.getObject("created_at", LocalDateTime.class));

    private final JdbcTemplate jdbc;

    public CollegeAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Person> list(Account account, Kind kind, Boolean active, String query) {
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        var sql = new StringBuilder(people(kind));
        var args = new java.util.ArrayList<Object>(List.of(college));
        if (active != null) {
            sql.append(" AND (u.is_active IS TRUE) = ?");
            args.add(active);
        }
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.length() > 100) throw ApiError.INVALID_REQUEST.exception();
        if (!q.isEmpty()) {
            sql.append(" AND (lower(u.full_name) LIKE ? OR lower(u.email) LIKE ?)");
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY (u.is_active IS TRUE) DESC, u.full_name, u.id LIMIT 1000");
        return jdbc.query(sql.toString(), PERSON, args.toArray());
    }

    public Person get(Account account, Kind kind, long id) {
        return find(account.requireCollege(CollegeRole.COLLEGE_ADMIN, null), kind, id, false);
    }

    @Transactional
    public Person update(Account account, Kind kind, long id, Details raw) {
        var details = raw.clean();
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        find(college, kind, id, true);
        try {
            jdbc.update("UPDATE public.users SET full_name = ?, phone = ?, updated_at = now() WHERE college_id = ? AND id = ?",
                    details.fullName(), details.phone(), college, id);
        } catch (DuplicateKeyException e) {
            throw ApiError.ACCOUNT_ALREADY_EXISTS.exception();
        }
        return find(college, kind, id, false);
    }

    @Transactional
    public Person setActive(Account account, Kind kind, long id, Status status) {
        if (status == null || status.active() == null) throw ApiError.INVALID_REQUEST.exception();
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        if (id == account.id()) throw ApiError.ACCESS_DENIED.exception();
        find(college, kind, id, true);
        jdbc.update("UPDATE public.users SET is_active = ?, updated_at = now() WHERE college_id = ? AND id = ?",
                status.active(), college, id);
        return find(college, kind, id, false);
    }

    private Person find(long college, Kind kind, long id, boolean lock) {
        if (lock) jdbc.queryForList("SELECT id FROM public.users WHERE college_id = ? AND id = ? FOR UPDATE", Long.class, college, id);
        var rows = jdbc.query(people(kind) + " AND u.id = ?", PERSON, college, id);
        if (rows.isEmpty()) throw kind.notFound.exception();
        return rows.getFirst();
    }
}
