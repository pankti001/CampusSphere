package com.campussphere.backend.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    private static final RowMapper<Period> PERIOD = (row, index) -> new Period(row.getLong("id"), row.getString("code"),
            row.getString("name"), row.getObject("start_date", LocalDate.class), row.getObject("end_date", LocalDate.class));
    private static final String YEAR_COLUMNS = "id, code, name, start_date, end_date";
    private static final String SEMESTER_COLUMNS = "id, semester_number::text AS code, name, start_date, end_date";
    private final JdbcTemplate jdbc;

    public AcademicPeriodService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Dates of a period: both present or both absent. */
    record Dates(LocalDate start, LocalDate end) {
        static final Dates NONE = new Dates(null, null);
    }

    public List<Period> years(Account account) {
        return jdbc.query("SELECT " + YEAR_COLUMNS + " FROM public.academic_years WHERE college_id = ? ORDER BY code",
                PERIOD, college(account));
    }

    @Transactional
    public Period createYear(Account account, AcademicPeriodInput input) {
        long college = college(account);
        requireCreateFields(input);
        String code = yearCode(input.code());
        Dates dates = yearDates(code, requested(input, Dates.NONE));
        return jdbc.query("""
                INSERT INTO public.academic_years (college_id, code, name, start_date, end_date) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (college_id, code) DO NOTHING RETURNING\s""" + YEAR_COLUMNS,
                PERIOD, college, code, name(input.name()), dates.start(), dates.end()).stream().findFirst()
                .orElseThrow(ApiError.ACADEMIC_YEAR_CONFLICT::exception);
    }

    @Transactional
    public Period updateYear(Account account, long id, AcademicPeriodInput input) {
        long college = college(account);
        requireUpdateFields(input);
        Period current = jdbc.query("SELECT " + YEAR_COLUMNS + " FROM public.academic_years WHERE college_id = ? AND id = ? FOR UPDATE",
                PERIOD, college, id).stream().findFirst().orElseThrow(ApiError.ACADEMIC_YEAR_NOT_FOUND::exception);
        String code = input.code() == null ? current.code() : yearCode(input.code());
        Dates dates = yearDates(code, requested(input, new Dates(current.startDate(), current.endDate())));
        // Semesters of this year must still fit inside its (new) dates.
        if (dates.start() != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.academic_semesters WHERE college_id = ? AND academic_year_id = ?
                               AND start_date IS NOT NULL AND (start_date < ? OR end_date > ?))
                """, Boolean.class, college, id, dates.start(), dates.end()))) throw ApiError.ACADEMIC_DATES_INVALID.exception();
        try {
            return jdbc.query("""
                    UPDATE public.academic_years SET code = ?, name = COALESCE(?, name), start_date = ?, end_date = ?
                    WHERE college_id = ? AND id = ? RETURNING\s""" + YEAR_COLUMNS,
                    PERIOD, code, name(input.name()), dates.start(), dates.end(), college, id).stream().findFirst()
                    .orElseThrow(ApiError.ACADEMIC_YEAR_NOT_FOUND::exception);
        } catch (DuplicateKeyException failure) {
            throw ApiError.ACADEMIC_YEAR_CONFLICT.exception();
        }
    }

    public List<Period> semesters(Account account, long program, long year) {
        long college = semesterScope(account, program, year);
        return jdbc.query("SELECT " + SEMESTER_COLUMNS + """
                 FROM public.academic_semesters
                WHERE college_id = ? AND program_id = ? AND academic_year_id = ? ORDER BY semester_number
                """, PERIOD, college, program, year);
    }

    @Transactional
    public Period createSemester(Account account, long program, long year, AcademicPeriodInput input) {
        long college = semesterScope(account, program, year);
        requireCreateFields(input);
        Dates dates = semesterDates(college, year, requested(input, Dates.NONE));
        return jdbc.query("""
                INSERT INTO public.academic_semesters (college_id, program_id, academic_year_id, semester_number, name,
                    start_date, end_date)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (college_id, program_id, academic_year_id, semester_number) DO NOTHING
                RETURNING\s""" + SEMESTER_COLUMNS, PERIOD, college, program, year, semesterNumber(input.code()),
                name(input.name()), dates.start(), dates.end()).stream().findFirst()
                .orElseThrow(ApiError.SEMESTER_CONFLICT::exception);
    }

    @Transactional
    public Period updateSemester(Account account, long program, long year, long id, AcademicPeriodInput input) {
        long college = semesterScope(account, program, year);
        requireUpdateFields(input);
        Period current = jdbc.query("SELECT " + SEMESTER_COLUMNS + """
                 FROM public.academic_semesters
                WHERE college_id = ? AND program_id = ? AND academic_year_id = ? AND id = ? FOR UPDATE
                """, PERIOD, college, program, year, id).stream().findFirst()
                .orElseThrow(ApiError.SEMESTER_NOT_FOUND::exception);
        Dates dates = semesterDates(college, year, requested(input, new Dates(current.startDate(), current.endDate())));
        try {
            return jdbc.query("""
                    UPDATE public.academic_semesters
                    SET semester_number = COALESCE(?, semester_number), name = COALESCE(?, name), start_date = ?, end_date = ?
                    WHERE college_id = ? AND program_id = ? AND academic_year_id = ? AND id = ?
                    RETURNING\s""" + SEMESTER_COLUMNS, PERIOD, semesterNumber(input.code()), name(input.name()),
                    dates.start(), dates.end(), college, program, year, id)
                    .stream().findFirst().orElseThrow(ApiError.SEMESTER_NOT_FOUND::exception);
        } catch (DuplicateKeyException failure) {
            throw ApiError.SEMESTER_CONFLICT.exception();
        }
    }

    /** The dates the request asks for: the given ones, none when clearing, otherwise the current ones. */
    static Dates requested(AcademicPeriodInput input, Dates current) {
        if (Boolean.TRUE.equals(input.clearDates())) {
            if (input.startDate() != null || input.endDate() != null) throw ApiError.ACADEMIC_DATES_INVALID.exception();
            return Dates.NONE;
        }
        if (input.startDate() == null && input.endDate() == null) return current;
        if (input.startDate() == null || input.endDate() == null) throw ApiError.ACADEMIC_DATES_INVALID.exception();
        if (!input.endDate().isAfter(input.startDate())
                || ChronoUnit.DAYS.between(input.startDate(), input.endDate()) > 400) throw ApiError.ACADEMIC_DATES_INVALID.exception();
        return new Dates(input.startDate(), input.endDate());
    }

    /** A year's dates sit in its own years: 2026-2027 starts in 2026 and ends in 2026 or 2027. */
    static Dates yearDates(String code, Dates dates) {
        if (dates.start() == null) return dates;
        int first = Integer.parseInt(code.substring(0, 4));
        if (dates.start().getYear() != first || dates.end().getYear() > first + 1) throw ApiError.ACADEMIC_DATES_INVALID.exception();
        return dates;
    }

    /** A semester's dates lie inside its academic year's dates (when the year has dates). */
    private Dates semesterDates(long college, long year, Dates dates) {
        if (dates.start() == null) return dates;
        var yearDates = jdbc.query("SELECT start_date, end_date FROM public.academic_years WHERE college_id = ? AND id = ?",
                (r, i) -> new Dates(r.getObject("start_date", LocalDate.class), r.getObject("end_date", LocalDate.class)),
                college, year).stream().findFirst().orElse(Dates.NONE);
        if (yearDates.start() != null && (dates.start().isBefore(yearDates.start()) || dates.end().isAfter(yearDates.end())))
            throw ApiError.ACADEMIC_DATES_INVALID.exception();
        return dates;
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
        if (input.code() == null && input.name() == null && input.startDate() == null && input.endDate() == null
                && !Boolean.TRUE.equals(input.clearDates())) throw ApiError.INVALID_REQUEST.exception();
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

    public record Period(long id, String code, String name, LocalDate startDate, LocalDate endDate) {
        public Period(long id, String code, String name) {
            this(id, code, name, null, null);
        }
    }
}
