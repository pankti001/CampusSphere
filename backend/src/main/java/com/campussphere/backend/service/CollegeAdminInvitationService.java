package com.campussphere.backend.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.dto.CollegeInvitationInput.Accept;
import com.campussphere.backend.entity.User;
import com.campussphere.backend.repository.RoleRepository;
import com.campussphere.backend.repository.UserRepository;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.ApiError;

import jakarta.persistence.EntityManager;

@Service
public class CollegeAdminInvitationService {
    private static final RowMapper<Invitation> INVITATION = (row, index) -> {
        var expires = row.getObject("expires_at", OffsetDateTime.class);
        var accepted = row.getObject("accepted_at", OffsetDateTime.class);
        var revoked = row.getObject("revoked_at", OffsetDateTime.class);
        var expired = row.getObject("expired_at", OffsetDateTime.class);
        if (accepted == null && revoked == null && expired == null
                && !row.getObject("observed_at", OffsetDateTime.class).isBefore(expires)) {
            expired = expires;
        }
        return new Invitation(row.getLong("id"), row.getLong("college_id"), row.getString("email"),
                row.getObject("created_at", OffsetDateTime.class), expires, accepted, revoked, expired);
    };

    private final JdbcTemplate jdbc;
    private final PlatformAccess access;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwords;
    private final EntityManager entities;

    CollegeAdminInvitationService(JdbcTemplate jdbc, PlatformAccess access, UserRepository users,
            RoleRepository roles, PasswordEncoder passwords, EntityManager entities) {
        this.jdbc = jdbc;
        this.access = access;
        this.users = users;
        this.roles = roles;
        this.passwords = passwords;
        this.entities = entities;
    }

    @Transactional(readOnly = true)
    public List<Invitation> list(long collegeId, long before, int limit) {
        if (before < 1 || limit < 1 || limit > 100) throw ApiError.INVALID_REQUEST.exception();
        if (!collegeExists(collegeId)) throw ApiError.COLLEGE_NOT_FOUND.exception();
        return jdbc.query("""
                SELECT *, statement_timestamp() AS observed_at FROM public.college_admin_invitations
                WHERE college_id = ? AND id < ? ORDER BY id DESC LIMIT ?
                """, INVITATION, collegeId, before, limit);
    }

    @Transactional
    public Issued issue(Account account, long collegeId, String email) {
        long actor = access.requireCurrentSuperAdmin(account);
        if (!lockCollege(collegeId).orElseThrow(ApiError.COLLEGE_NOT_FOUND::exception)) {
            throw ApiError.COLLEGE_INACTIVE.exception();
        }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 150) throw ApiError.INVALID_REQUEST.exception();
        if (existingEmail(normalized)) {
            throw ApiError.ACCOUNT_ALREADY_EXISTS.exception();
        }
        var now = databaseTime();
        closeExpired(collegeId, now);
        String token = InvitationToken.generate();
        var invitation = jdbc.query("""
                INSERT INTO public.college_admin_invitations
                    (college_id, email, token_hash, issued_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (college_id, email)
                  WHERE accepted_at IS NULL AND revoked_at IS NULL AND expired_at IS NULL
                DO NOTHING RETURNING *, statement_timestamp() AS observed_at
                """, INVITATION, collegeId, normalized, InvitationToken.hash(token), actor, now, now.plusHours(24))
                .stream().findFirst().orElseThrow(ApiError.INVITATION_CONFLICT::exception);
        access.audit(actor, "INVITATION_ISSUED", invitation.id());
        return new Issued(invitation, token);
    }

    @Transactional
    public void revoke(Account account, long collegeId, long invitationId) {
        long actor = access.requireCurrentSuperAdmin(account);
        lockCollege(collegeId).orElseThrow(ApiError.COLLEGE_NOT_FOUND::exception);
        var invitation = jdbc.query("""
                SELECT *, statement_timestamp() AS observed_at FROM public.college_admin_invitations
                WHERE college_id = ? AND id = ? FOR UPDATE
                """, INVITATION, collegeId, invitationId).stream().findFirst()
                .orElseThrow(ApiError.INVITATION_NOT_FOUND::exception);
        if (invitation.revokedAt() != null) return;
        var now = databaseTime();
        if (invitation.acceptedAt() != null || !now.isBefore(invitation.expiresAt())) {
            throw ApiError.INVITATION_CONFLICT.exception();
        }
        jdbc.update("""
                UPDATE public.college_admin_invitations SET revoked_at = ?, revoked_by = ? WHERE id = ?
                """, now, actor, invitationId);
        access.audit(actor, "INVITATION_REVOKED", invitationId);
    }

    void revokeOutstanding(long collegeId, long actor) {
        var now = databaseTime();
        closeExpired(collegeId, now);
        var revoked = jdbc.queryForList("""
                UPDATE public.college_admin_invitations SET revoked_at = ?, revoked_by = ?
                WHERE college_id = ? AND accepted_at IS NULL AND revoked_at IS NULL AND expired_at IS NULL
                RETURNING id
                """, Long.class, now, actor, collegeId);
        revoked.forEach(id -> access.audit(actor, "INVITATION_REVOKED", id));
    }

    @Transactional
    public void accept(Accept input) {
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72 || input.password().isBlank()) {
            throw ApiError.INVALID_REQUEST.exception();
        }
        byte[] hash = InvitationToken.hash(input.token());
        long collegeId = jdbc.queryForList("""
                SELECT college_id FROM public.college_admin_invitations WHERE token_hash = ?
                """, Long.class, hash).stream().findFirst().orElseThrow(ApiError.INVITATION_INVALID::exception);

        if (!lockCollege(collegeId).orElse(false)) throw ApiError.INVITATION_INVALID.exception();
        var invitation = jdbc.query("""
                SELECT *, statement_timestamp() AS observed_at FROM public.college_admin_invitations
                WHERE college_id = ? AND token_hash = ? FOR UPDATE
                """, INVITATION, collegeId, hash).stream().findFirst()
                .orElseThrow(ApiError.INVITATION_INVALID::exception);
        if (!invitation.status().equals("PENDING") || existingEmail(invitation.email())) {
            throw ApiError.INVITATION_INVALID.exception();
        }

        var user = new User();
        user.setFullName(input.fullName().strip());
        user.setEmail(invitation.email());
        user.setCollege(entities.getReference(com.campussphere.backend.entity.College.class, collegeId));
        user.setIsVerified(true);
        user.setPassword(passwords.encode(input.password()));
        user.setRoles(Set.of(roles.findByName("COLLEGE_ADMIN").orElseThrow()));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (e.getMostSpecificCause() instanceof java.sql.SQLException sql
                    && "23505".equals(sql.getSQLState())) {
                throw ApiError.INVITATION_INVALID.exception();
            }
            throw e;
        }

        var now = databaseTime();
        if (!now.isBefore(invitation.expiresAt())) throw ApiError.INVITATION_INVALID.exception();
        jdbc.update("""
                UPDATE public.college_admin_invitations SET accepted_at = ?, accepted_by = ? WHERE id = ?
                """, now, user.getId(), invitation.id());
        access.audit(user.getId(), "INVITATION_ACCEPTED", invitation.id());
    }

    private boolean existingEmail(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.users WHERE lower(email) = ?)
                """, Boolean.class, email));
    }

    private boolean collegeExists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.colleges WHERE id = ?)
                """, Boolean.class, id));
    }

    private java.util.Optional<Boolean> lockCollege(long id) {
        return jdbc.query("SELECT is_active FROM public.colleges WHERE id = ? FOR UPDATE",
                (row, index) -> row.getBoolean(1), id).stream().findFirst();
    }

    private OffsetDateTime databaseTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (row, index) ->
                row.getObject(1, OffsetDateTime.class));
    }

    private void closeExpired(long collegeId, OffsetDateTime now) {
        jdbc.update("""
                UPDATE public.college_admin_invitations SET expired_at = expires_at
                WHERE college_id = ? AND expires_at <= ?
                  AND accepted_at IS NULL AND revoked_at IS NULL AND expired_at IS NULL
                """, collegeId, now);
    }

    public record Invitation(long id, long collegeId, String email, OffsetDateTime createdAt,
            OffsetDateTime expiresAt, OffsetDateTime acceptedAt, OffsetDateTime revokedAt,
            OffsetDateTime expiredAt) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public String status() {
            if (acceptedAt != null) return "ACCEPTED";
            if (revokedAt != null) return "REVOKED";
            if (expiredAt != null) return "EXPIRED";
            return "PENDING";
        }
    }

    public record Issued(Invitation invitation, String token) {
        @Override
        public String toString() {
            return "IssuedInvitation[redacted]";
        }
    }
}
