package com.campussphere.backend.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.dto.CollegeInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.ApiError;

@Service
public class PlatformCollegeService {
    private static final RowMapper<CollegeDetails> COLLEGE = (row, index) -> new CollegeDetails(
            row.getLong("id"), row.getString("code"), row.getString("name"),
            row.getBoolean("is_active"), row.getObject("created_at", OffsetDateTime.class),
            row.getObject("updated_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;
    private final PlatformAccess access;
    private final CollegeAdminInvitationService invitations;

    PlatformCollegeService(JdbcTemplate jdbc, PlatformAccess access, CollegeAdminInvitationService invitations) {
        this.jdbc = jdbc;
        this.access = access;
        this.invitations = invitations;
    }

    @Transactional(readOnly = true)
    public List<CollegeDetails> list(String search, Boolean active, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100 || (search != null && search.length() > 150)) {
            throw ApiError.INVALID_REQUEST.exception();
        }
        String query = search == null ? "" : search.strip();
        return jdbc.query("""
                SELECT * FROM public.colleges
                WHERE id > ? AND (CAST(? AS boolean) IS NULL OR is_active = ?)
                  AND (strpos(lower(name), lower(?)) > 0 OR strpos(lower(code), lower(?)) > 0)
                ORDER BY id LIMIT ?
                """, COLLEGE, after, active, active, query, query, limit);
    }

    @Transactional(readOnly = true)
    public CollegeDetails get(long id) {
        return find(id, false);
    }

    @Transactional
    public CollegeDetails create(Account account, CollegeInput input) {
        long actor = access.requireCurrentSuperAdmin(account);
        if (input.code() == null || input.name() == null) {
            throw ApiError.INVALID_REQUEST.exception();
        }
        var college = save("""
                INSERT INTO public.colleges (code, name, is_active)
                VALUES (?, ?, FALSE) RETURNING *
                """, code(input), name(input));
        access.audit(actor, "COLLEGE_CREATED", college.id());
        return college;
    }

    @Transactional
    public CollegeDetails update(Account account, long id, CollegeInput input) {
        long actor = access.requireCurrentSuperAdmin(account);
        if (input.code() == null && input.name() == null) {
            throw ApiError.INVALID_REQUEST.exception();
        }
        var college = save("""
                UPDATE public.colleges SET code = COALESCE(?, code), name = COALESCE(?, name),
                    updated_at = clock_timestamp()
                WHERE id = ? RETURNING *
                """, code(input), name(input), id);
        access.audit(actor, "COLLEGE_UPDATED", id);
        return college;
    }

    @Transactional
    public CollegeDetails status(Account account, long id, boolean active) {
        long actor = access.requireCurrentSuperAdmin(account);
        var current = find(id, true);
        if (current.active() == active) {
            return current;
        }
        var college = save("""
                UPDATE public.colleges SET is_active = ?, updated_at = clock_timestamp()
                WHERE id = ? RETURNING *
                """, active, id);
        if (!active) {
            invitations.revokeOutstanding(id, actor);
        }
        access.audit(actor, active ? "COLLEGE_ACTIVATED" : "COLLEGE_DEACTIVATED", id);
        return college;
    }

    private CollegeDetails find(long id, boolean lock) {
        String query = "SELECT * FROM public.colleges WHERE id = ?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(query, COLLEGE, id).stream().findFirst()
                .orElseThrow(ApiError.COLLEGE_NOT_FOUND::exception);
    }

    private CollegeDetails save(String sql, Object... parameters) {
        try {
            return jdbc.query(sql, COLLEGE, parameters).stream().findFirst()
                    .orElseThrow(ApiError.COLLEGE_NOT_FOUND::exception);
        } catch (DuplicateKeyException e) {
            throw ApiError.COLLEGE_CODE_CONFLICT.exception();
        }
    }

    private String code(CollegeInput input) {
        return input.code() == null ? null : input.code().toUpperCase(Locale.ROOT);
    }

    private String name(CollegeInput input) {
        if (input.name() == null) return null;
        if (input.name().isBlank()) throw ApiError.INVALID_REQUEST.exception();
        return input.name().strip();
    }

    public record CollegeDetails(long id, String code, String name, boolean active,
                                 OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
}
