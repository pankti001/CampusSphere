package com.campussphere.backend.service;

import java.util.List;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.dto.DepartmentInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;

@Service
@Transactional(readOnly = true)
public class DepartmentService {
    private static final RowMapper<Department> DEPARTMENT = (row, index) ->
            new Department(row.getLong("id"), row.getString("code"), row.getString("name"));

    private final JdbcTemplate jdbc;

    public DepartmentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Department> list(Account account) {
        long collegeId = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        return jdbc.query("""
                SELECT id, code, name FROM public.departments
                WHERE college_id = ? ORDER BY code
                """, DEPARTMENT, collegeId);
    }

    public Department get(Account account, long id) {
        long collegeId = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        return jdbc.query("""
                SELECT id, code, name FROM public.departments
                WHERE college_id = ? AND id = ?
                """, DEPARTMENT, collegeId, id).stream().findFirst()
                .orElseThrow(ApiError.DEPARTMENT_NOT_FOUND::exception);
    }

    @Transactional
    public Department create(Account account, DepartmentInput input) {
        long collegeId = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        String name = validatedName(input.name());
        if (input.code() == null || name == null) {
            throw ApiError.INVALID_REQUEST.exception();
        }
        return jdbc.query("""
                INSERT INTO public.departments (college_id, code, name)
                VALUES (?, ?, ?)
                ON CONFLICT (college_id, code) DO NOTHING
                RETURNING id, code, name
                """, DEPARTMENT, collegeId, input.code().toUpperCase(Locale.ROOT), name)
                .stream().findFirst().orElseThrow(ApiError.DEPARTMENT_CODE_CONFLICT::exception);
    }

    @Transactional
    public Department update(Account account, long id, DepartmentInput input) {
        long collegeId = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        String name = validatedName(input.name());
        String code = input.code() == null ? null : input.code().toUpperCase(Locale.ROOT);
        if (code == null && name == null) {
            throw ApiError.INVALID_REQUEST.exception();
        }
        try {
            return jdbc.query("""
                    UPDATE public.departments
                    SET code = COALESCE(?, code), name = COALESCE(?, name)
                    WHERE college_id = ? AND id = ?
                    RETURNING id, code, name
                    """, DEPARTMENT, code, name, collegeId, id).stream().findFirst()
                    .orElseThrow(ApiError.DEPARTMENT_NOT_FOUND::exception);
        } catch (DuplicateKeyException e) {
            throw ApiError.DEPARTMENT_CODE_CONFLICT.exception();
        }
    }

    private String validatedName(String name) {
        if (name == null) return null;
        if (name.isBlank()) throw ApiError.INVALID_REQUEST.exception();
        return name.strip();
    }

    public record Department(long id, String code, String name) {}
}
