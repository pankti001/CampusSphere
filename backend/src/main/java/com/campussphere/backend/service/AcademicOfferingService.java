package com.campussphere.backend.service;

import java.util.List;
import java.util.Locale;

import com.campussphere.backend.dto.AcademicOfferingInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AcademicOfferingService {
    public enum Type {
        DIVISION("academic_divisions", "academic_year_id", ApiError.DIVISION_NOT_FOUND, ApiError.DIVISION_CONFLICT),
        SUBJECT("academic_subjects", "semester_id", ApiError.SUBJECT_NOT_FOUND, ApiError.SUBJECT_CONFLICT);

        private final String table;
        private final String parentKey;
        private final ApiError missing;
        private final ApiError duplicate;

        Type(String table, String parentKey, ApiError missing, ApiError duplicate) {
            this.table = table;
            this.parentKey = parentKey;
            this.missing = missing;
            this.duplicate = duplicate;
        }
    }

    private static final RowMapper<Offering> OFFERING =
            (row, index) -> new Offering(row.getLong("id"), row.getString("code"), row.getString("name"));
    private final JdbcTemplate jdbc;

    public AcademicOfferingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Offering> list(Account account, Type type, long program, long parent) {
        long college = requireScope(account, type, program, parent);
        return jdbc.query("""
                SELECT id, code, name FROM public.%s
                WHERE college_id = ? AND program_id = ? AND %s = ? ORDER BY code
                """.formatted(type.table, type.parentKey), OFFERING, college, program, parent);
    }

    @Transactional
    public Offering create(Account account, Type type, long program, long parent, AcademicOfferingInput input) {
        long college = requireScope(account, type, program, parent);
        String name = name(input.name());
        if (input.code() == null || name == null) throw ApiError.INVALID_REQUEST.exception();
        return jdbc.query("""
                INSERT INTO public.%s (college_id, program_id, %s, code, name) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (college_id, program_id, %s, code) DO NOTHING RETURNING id, code, name
                """.formatted(type.table, type.parentKey, type.parentKey),
                OFFERING, college, program, parent, code(input.code()), name)
                .stream().findFirst().orElseThrow(type.duplicate::exception);
    }

    @Transactional
    public Offering update(Account account, Type type, long program, long parent, long id, AcademicOfferingInput input) {
        long college = requireScope(account, type, program, parent);
        String name = name(input.name());
        if (input.code() == null && name == null) throw ApiError.INVALID_REQUEST.exception();
        try {
            return jdbc.query("""
                    UPDATE public.%s SET code = COALESCE(?, code), name = COALESCE(?, name)
                    WHERE college_id = ? AND program_id = ? AND %s = ? AND id = ?
                    RETURNING id, code, name
                    """.formatted(type.table, type.parentKey), OFFERING, code(input.code()), name,
                    college, program, parent, id).stream().findFirst().orElseThrow(type.missing::exception);
        } catch (DuplicateKeyException failure) {
            throw type.duplicate.exception();
        }
    }

    private long requireScope(Account account, Type type, long program, long parent) {
        if (account == null) throw ApiError.ACCESS_DENIED.exception();
        long college = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        if (type == Type.DIVISION) {
            if (!exists("SELECT EXISTS (SELECT 1 FROM public.academic_programs WHERE college_id = ? AND id = ?)",
                    college, program)) throw ApiError.PROGRAM_NOT_FOUND.exception();
            if (!exists("SELECT EXISTS (SELECT 1 FROM public.academic_years WHERE college_id = ? AND id = ?)",
                    college, parent)) throw ApiError.ACADEMIC_YEAR_NOT_FOUND.exception();
        } else if (!exists("""
                SELECT EXISTS (SELECT 1 FROM public.academic_semesters
                    WHERE college_id = ? AND program_id = ? AND id = ?)
                """, college, program, parent)) {
            throw ApiError.SEMESTER_NOT_FOUND.exception();
        }
        return college;
    }

    private boolean exists(String sql, Object... parameters) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, parameters));
    }

    private static String name(String value) {
        if (value == null) return null;
        if (value.isBlank()) throw ApiError.INVALID_REQUEST.exception();
        return value.strip();
    }

    private static String code(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    public record Offering(long id, String code, String name) {}
}
