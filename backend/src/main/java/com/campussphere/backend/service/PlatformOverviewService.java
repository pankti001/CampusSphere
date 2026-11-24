package com.campussphere.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformOverviewService {
    private final JdbcTemplate jdbc;

    public PlatformOverviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Overview load() {
        return jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE is_active) AS active,
                       count(*) FILTER (WHERE NOT is_active) AS inactive,
                       (SELECT count(*) FROM public.college_admin_invitations
                        WHERE accepted_at IS NULL AND revoked_at IS NULL AND expired_at IS NULL
                          AND expires_at > statement_timestamp()) AS pending
                FROM public.colleges
                """, (row, index) -> new Overview(row.getLong("total"), row.getLong("active"),
                row.getLong("inactive"), row.getLong("pending")));
    }

    public record Overview(long totalColleges, long activeColleges, long inactiveColleges,
                           long pendingInvitations) {}
}
