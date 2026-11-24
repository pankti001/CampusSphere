package com.campussphere.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.ApiError;

@Component
class PlatformAccess {
    private final JdbcTemplate jdbc;

    PlatformAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long requireCurrentSuperAdmin(Account account) {
        return requireCurrentSuperAdmin(account, true);
    }

    void verifySuperAdminForRead(Account account) {
        requireCurrentSuperAdmin(account, false);
    }

    private long requireCurrentSuperAdmin(Account account, boolean lock) {
        if (account == null || account.college() != null || !account.roles().contains("SUPER_ADMIN")) {
            throw ApiError.ACCESS_DENIED.exception();
        }
        var active = jdbc.query("""
                SELECT is_active FROM public.users WHERE id = ? AND college_id IS NULL
                """ + (lock ? " FOR SHARE" : ""), (row, index) -> Boolean.TRUE.equals(row.getObject(1)), account.id());
        if (!active.contains(true) || jdbc.queryForList("""
                SELECT ur.role_id FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.name = 'SUPER_ADMIN'
                """ + (lock ? " FOR SHARE OF ur, r" : ""), Long.class, account.id()).isEmpty()) {
            throw ApiError.ACCESS_DENIED.exception();
        }
        return account.id();
    }

    void audit(long actor, String action, long target) {
        jdbc.update("""
                INSERT INTO public.privileged_audit_events (actor_id, action, target_id)
                VALUES (?, ?, ?)
                """, actor, action, target);
    }
}
