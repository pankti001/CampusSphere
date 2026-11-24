package com.campussphere.backend.service;

import java.util.List;
import java.util.Locale;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.campussphere.backend.service.StudentEnrollmentService.STUDENT_ONLY;
import static com.campussphere.backend.service.StudentEnrollmentService.college;

@Service
@Transactional(readOnly = true)
public class StudentAffiliationService {
    private static final String REQUESTS = """
            SELECT r.id, r.status, c.name AS college, c.code AS college_code, u.full_name, u.email
            FROM public.student_college_requests r
            JOIN public.colleges c ON c.id = r.college_id
            JOIN public.users u ON u.id = r.student_id
            """;
    private static final RowMapper<Affiliation> AFFILIATION = (r, n) -> new Affiliation(r.getLong("id"),
            r.getString("status"), r.getString("college"), r.getString("college_code"),
            r.getString("full_name"), r.getString("email"));
    private static final String ELIGIBLE = """
            SELECT u.id FROM public.users u
            WHERE u.id = ? AND u.college_id IS NULL AND u.is_active IS TRUE
            """ + STUDENT_ONLY;
    private final JdbcTemplate jdbc;

    public StudentAffiliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Affiliation> mine(Account account) {
        long student = applicant(account);
        if (jdbc.queryForList(ELIGIBLE, Long.class, student).isEmpty()) throw ApiError.ACCESS_DENIED.exception();
        return jdbc.query(REQUESTS + " WHERE r.student_id = ? ORDER BY r.id DESC LIMIT 1", AFFILIATION, student);
    }

    @Transactional
    public Affiliation submit(Account account, String collegeCode) {
        long student = applicant(account);
        if (jdbc.queryForList(ELIGIBLE + " FOR UPDATE OF u", Long.class, student).isEmpty()) {
            throw ApiError.ACCESS_DENIED.exception();
        }
        long college = jdbc.queryForList("""
                SELECT id FROM public.colleges WHERE code = ? AND is_active IS TRUE FOR SHARE
                """, Long.class, collegeCode.toUpperCase(Locale.ROOT))
                .stream().findFirst().orElseThrow(ApiError.STUDENT_COLLEGE_UNAVAILABLE::exception);
        long id = jdbc.query("""
                INSERT INTO public.student_college_requests (student_id, college_id) VALUES (?, ?)
                ON CONFLICT (student_id) WHERE status = 'PENDING' DO NOTHING RETURNING id
                """, (r, n) -> r.getLong("id"), student, college)
                .stream().findFirst().orElseThrow(ApiError.STUDENT_AFFILIATION_CONFLICT::exception);
        return jdbc.query(REQUESTS + " WHERE r.college_id = ? AND r.id = ?", AFFILIATION, college, id).getFirst();
    }

    public List<Affiliation> pending(Account account) {
        return jdbc.query(REQUESTS + " WHERE r.college_id = ? AND r.status = 'PENDING' ORDER BY r.id",
                AFFILIATION, college(account, CollegeRole.COLLEGE_ADMIN));
    }

    @Transactional
    public Affiliation decide(Account account, long id, boolean approved, boolean admissionConfirmed) {
        long college = college(account, CollegeRole.COLLEGE_ADMIN);
        if (approved && !admissionConfirmed) throw ApiError.STUDENT_ADMISSION_UNCONFIRMED.exception();
        long student = jdbc.queryForList("""
                SELECT student_id FROM public.student_college_requests WHERE college_id = ? AND id = ?
                """, Long.class, college, id)
                .stream().findFirst().orElseThrow(ApiError.STUDENT_AFFILIATION_NOT_FOUND::exception);
        // Serialize approval and submission on the applicant's identity before touching the request.
        if (jdbc.queryForList("""
                SELECT id FROM public.users WHERE id = ? FOR UPDATE
                """, Long.class, student).isEmpty()) throw ApiError.STUDENT_AFFILIATION_CONFLICT.exception();
        if (jdbc.queryForList("""
                SELECT id FROM public.colleges WHERE id = ? AND is_active IS TRUE FOR SHARE
                """, Long.class, college).isEmpty()) throw ApiError.ACCESS_DENIED.exception();
        if (jdbc.update("""
                UPDATE public.student_college_requests
                SET status = ?, reviewed_by = ?, reviewed_at = GREATEST(statement_timestamp(), created_at)
                WHERE college_id = ? AND id = ? AND status = 'PENDING'
                """, approved ? "APPROVED" : "REJECTED", account.id(), college, id) != 1) {
            throw ApiError.STUDENT_AFFILIATION_CONFLICT.exception();
        }
        if (approved && jdbc.update("""
                UPDATE public.users u SET college_id = ?, is_verified = TRUE, updated_at = CURRENT_TIMESTAMP
                WHERE u.id = ? AND u.college_id IS NULL AND u.is_active IS TRUE
                """ + STUDENT_ONLY, college, student) != 1) {
            throw ApiError.STUDENT_AFFILIATION_CONFLICT.exception();
        }
        return jdbc.query(REQUESTS + " WHERE r.college_id = ? AND r.id = ?", AFFILIATION, college, id).getFirst();
    }

    // Account status uses the same effective-role gate to decide whether to offer the request form.
    public static boolean eligible(Account account) {
        return account != null && account.college() == null
                && (account.roles().equals(java.util.Set.of("STUDENT"))
                    || (account.studentRestricted() && account.roles().isEmpty()));
    }

    private static long applicant(Account account) {
        if (!eligible(account)) throw ApiError.ACCESS_DENIED.exception();
        return account.id();
    }

    public record Affiliation(long id, String status, String college, String collegeCode, String name, String email) {}
}
