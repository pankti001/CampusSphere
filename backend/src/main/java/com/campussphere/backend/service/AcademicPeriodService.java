package com.campussphere.backend.service;

import java.util.List;
import java.util.regex.Pattern;

import com.campussphere.backend.dto.AcademicPeriodInput;
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
public class AcademicPeriodService {
    private static final Pattern YEAR = Pattern.compile("[1-9][0-9]{3}-[1-9][0-9]{3}");
    private static final Pattern SEMESTER = Pattern.compile("[1-9][0-9]?");
    private static final RowMapper<Period> PERIOD =
            (row, index) -> new Period(row.getLong("id"), row.getString("code"), row.getString("name"));
    private final JdbcTemplate jdbc;

    public AcademicPeriodService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Period> years(Account account) {
        return jdbc.query("""
                SELECT id, code, name FROM public.academic_years WHERE college_id = ? ORDER BY code
                """, PERIOD, college(account));
    }

    @Transactional
    public Period createYear(Account account, AcademicPeriodInput input) {
        long college = college(account);
        requireCreateFields(input);
        return jdbc.query("""
                INSERT INTO public.academic_years (college_id, code, name) VALUES (?, ?, ?)
                ON CONFLICT (college_id, code) DO NOTHING RETURNING id, code, name
                """, PERIOD, college, yearCode(input.code()), name(input.name())).stream().findFirst()
                .orElseThrow(ApiError.ACADEMIC_YEAR_CONFLICT::exception);
    }

    @Transactional
    public Period updateYear(Account account, long id, AcademicPeriodInput input) {
        long college = college(account);
        requireUpdateFields(input);
        try {
            return jdbc.query("""
                    UPDATE public.academic_years SET code = COALESCE(?, code), name = COALESCE(?, name)
                    WHERE college_id = ? AND id = ? RETURNING id, code, name
                    """, PERIOD, yearCode(input.code()), name(input.name()), college, id).stream().findFirst()
                    .orElseThrow(ApiError.ACADEMIC_YEAR_NOT_FOUND::exception);
        } catch (DuplicateKeyException failure) {
            throw ApiError.ACADEMIC_YEAR_CONFLICT.exception();
        }
    }

    public List<Period> semesters(Account account, long program, long year) {
        long college = semesterScope(account, program, year);
        return jdbc.query("""
                SELECT id, semester_number::text AS code, name FROM public.academic_semesters
                WHERE college_id = ? AND program_id = ? AND academic_year_id = ? ORDER BY semester_number
                """, PERIOD, college, program, year);
    }

    @Transactional
    public Period createSemester(Account account, long program, long year, AcademicPeriodInput input) {
        long college = semesterScope(account, program, year);
        requireCreateFields(input);
        return jdbc.query("""
                INSERT INTO public.academic_semesters (college_id, program_id, academic_year_id, semester_number, name)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (college_id, program_id, academic_year_id, semester_number) DO NOTHING
                RETURNING id, semester_number::text AS code, name
                """, PERIOD, college, program, year, semesterNumber(input.code()), name(input.name())).stream().findFirst()
                .orElseThrow(ApiError.SEMESTER_CONFLICT::exception);
    }

    @Transactional
    public Period updateSemester(Account account, long program, long year, long id, AcademicPeriodInput input) {
        long college = semesterScope(account, program, year);
        requireUpdateFields(input);
        try {
            return jdbc.query("""
                    UPDATE public.academic_semesters
                    SET semester_number = COALESCE(?, semester_number), name = COALESCE(?, name)
                    WHERE college_id = ? AND program_id = ? AND academic_year_id = ? AND id = ?
                    RETURNING id, semester_number::text AS code, name
                    """, PERIOD, semesterNumber(input.code()), name(input.name()), college, program, year, id)
                    .stream().findFirst().orElseThrow(ApiError.SEMESTER_NOT_FOUND::exception);
        } catch (DuplicateKeyException failure) {
            throw ApiError.SEMESTER_CONFLICT.exception();
        }
    }

    private long semesterScope(Account account, long program, long year) {
        long college = college(account);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.academic_programs WHERE college_id = ? AND id = ?)
                """, Boolean.class, college, program))) throw ApiError.PROGRAM_NOT_FOUND.exception();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.academic_years WHERE college_id = ? AND id = ?)
                """, Boolean.class, college, year))) throw ApiError.ACADEMIC_YEAR_NOT_FOUND.exception();
        return college;
    }

    private long college(Account account) {
        if (account == null) throw ApiError.ACCESS_DENIED.exception();
        return account.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
    }

    private static void requireCreateFields(AcademicPeriodInput input) {
        if (input.code() == null || input.name() == null) throw ApiError.INVALID_REQUEST.exception();
    }

    private static void requireUpdateFields(AcademicPeriodInput input) {
        if (input.code() == null && input.name() == null) throw ApiError.INVALID_REQUEST.exception();
    }

    private static String yearCode(String code) {
        if (code == null) return null;
        if (!YEAR.matcher(code).matches() || Integer.parseInt(code.substring(5)) != Integer.parseInt(code.substring(0, 4)) + 1)
            throw ApiError.INVALID_REQUEST.exception();
        return code;
    }

    private static Short semesterNumber(String code) {
        if (code == null) return null;
        if (!SEMESTER.matcher(code).matches()) throw ApiError.INVALID_REQUEST.exception();
        return Short.valueOf(code);
    }

    private static String name(String name) {
        if (name == null) return null;
        if (name.isBlank() || name.length() > 150) throw ApiError.INVALID_REQUEST.exception();
        return name.strip();
    }

    public record Period(long id, String code, String name) {}
}
