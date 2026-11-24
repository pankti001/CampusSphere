package com.campussphere.backend.service;

import java.util.List;
import java.util.Locale;

import com.campussphere.backend.dto.ProgramInput;
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
public class ProgramService {
    private static final RowMapper<Program> PROGRAM = (row, index) ->
            new Program(row.getLong("id"), row.getString("code"), row.getString("name"));

    private final JdbcTemplate jdbc;

    public ProgramService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Program> list(Account account, long departmentId) {
        long collegeId = requireDepartment(account, departmentId);
        return jdbc.query("""
                SELECT id, code, name FROM public.academic_programs
                WHERE college_id = ? AND department_id = ? ORDER BY code
                """, PROGRAM, collegeId, departmentId);
    }

    @Transactional
    public Program create(Account account, long departmentId, ProgramInput input) {
        long collegeId = requireDepartment(account, departmentId);
        String name = name(input);
        if (input.code() == null || name == null) throw ApiError.INVALID_REQUEST.exception();
        return jdbc.query("""
                INSERT INTO public.academic_programs (college_id, department_id, code, name)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (college_id, code) DO NOTHING
                RETURNING id, code, name
                """, PROGRAM, collegeId, departmentId, code(input), name)
                .stream().findFirst().orElseThrow(ApiError.PROGRAM_CODE_CONFLICT::exception);
    }

    @Transactional
    public Program update(Account account, long departmentId, long programId, ProgramInput input) {
        long collegeId = requireDepartment(account, departmentId);
        String name = name(input);
        if (input.code() == null && name == null) throw ApiError.INVALID_REQUEST.exception();
        try {
            return jdbc.query("""
                    UPDATE public.academic_programs
                    SET code = COALESCE(?, code), name = COALESCE(?, name)
                    WHERE college_id = ? AND department_id = ? AND id = ?
                    RETURNING id, code, name
                    """, PROGRAM, code(input), name, collegeId, departmentId, programId)
                    .stream().findFirst().orElseThrow(ApiError.PROGRAM_NOT_FOUND::exception);
        } catch (DuplicateKeyException failure) {
            throw ApiError.PROGRAM_CODE_CONFLICT.exception();
        }
    }

    private long requireDepartment(Account account, long departmentId) {
        if (account == null) throw ApiError.ACCESS_DENIED.exception();
        long collegeId = account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        boolean own = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.departments WHERE college_id = ? AND id = ?)
                """, Boolean.class, collegeId, departmentId));
        if (!own) throw ApiError.DEPARTMENT_NOT_FOUND.exception();
        return collegeId;
    }

    private String code(ProgramInput input) {
        return input.code() == null ? null : input.code().toUpperCase(Locale.ROOT);
    }

    private String name(ProgramInput input) {
        if (input.name() == null) return null;
        if (input.name().isBlank()) throw ApiError.INVALID_REQUEST.exception();
        return input.name().strip();
    }

    public record Program(long id, String code, String name) {}
}
