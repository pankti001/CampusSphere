package com.campussphere.backend.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.security.ApiError;

@Service
public class FacultyAccessService {
    private final JdbcTemplate jdbc;

    public FacultyAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void openInitialRequest(Long applicantId) {
        openRequest(applicantId);
    }

    @Transactional
    public Request reapply(Long applicantId) {
        lockAccounts(applicantId, applicantId);
        if (!isActive(applicantId)) {
            throw ApiError.INVALID_AUTHENTICATION.exception();
        }
        if (!isEligibleApplicant(applicantId)) {
            throw ApiError.ACCESS_DENIED.exception();
        }
        if (hasRole(applicantId, "FACULTY")
                || latest(applicantId).filter(request -> request.status() == Status.REJECTED).isEmpty()) {
            throw ApiError.FACULTY_REQUEST_CONFLICT.exception();
        }
        return openRequest(applicantId);
    }

    @Transactional
    public Request decide(Long requestId, Long reviewerId, Status decision) {
        if (decision != Status.APPROVED && decision != Status.REJECTED) {
            throw ApiError.INVALID_REQUEST.exception();
        }
        Request request = findLegacy(requestId);
        Long applicantId = request.applicantId();
        lockAccounts(applicantId, reviewerId);
        if (!isActive(reviewerId)) {
            throw ApiError.INVALID_AUTHENTICATION.exception();
        }
        if (applicantId.equals(reviewerId) || !holdsAdminRole(reviewerId)) {
            throw ApiError.ACCESS_DENIED.exception();
        }
        if (!isActive(applicantId)) {
            throw ApiError.FACULTY_REQUEST_CONFLICT.exception();
        }
        // Recheck ownership after acquiring both account locks.
        findLegacy(requestId);
        int decided = jdbc.update("""
                UPDATE public.faculty_access_requests
                SET status = ?, reviewer_id = ?, decided_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PENDING' AND college_id IS NULL
                """, decision.name(), reviewerId, requestId);
        if (decided != 1) {
            throw ApiError.FACULTY_REQUEST_CONFLICT.exception();
        }
        if (decision == Status.APPROVED) {
            int granted = jdbc.update("""
                    INSERT INTO public.user_roles (user_id, role_id)
                    SELECT ?, id FROM public.roles WHERE name = 'FACULTY'
                    ON CONFLICT (user_id, role_id) DO NOTHING
                    """, applicantId);
            if (granted != 1 && !hasRole(applicantId, "FACULTY")) {
                throw new IllegalStateException("The FACULTY role is missing");
            }
            jdbc.update("""
                    UPDATE public.users SET is_verified = true, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                    """, applicantId);
        }
        return findLegacy(requestId);
    }

    public Optional<Request> latest(Long applicantId) {
        return query("""
                SELECT f.id, f.applicant_id, f.status, f.reviewer_id, f.created_at, f.decided_at
                FROM public.faculty_access_requests f
                JOIN public.users u ON u.id = f.applicant_id
                WHERE f.applicant_id = ? AND f.college_id IS NOT DISTINCT FROM u.college_id
                ORDER BY f.id DESC LIMIT 1
                """, applicantId).stream().findFirst();
    }

    private Request openRequest(Long applicantId) {
        return query("""
                INSERT INTO public.faculty_access_requests (applicant_id, college_id)
                SELECT id, college_id FROM public.users WHERE id = ?
                RETURNING id, applicant_id, status, reviewer_id, created_at, decided_at
                """, applicantId).getFirst();
    }

    private Request findLegacy(Long requestId) {
        return query("""
                SELECT f.id, f.applicant_id, f.status, f.reviewer_id, f.created_at, f.decided_at
                FROM public.faculty_access_requests f
                JOIN public.users u ON u.id = f.applicant_id
                WHERE f.id = ? AND f.college_id IS NULL AND u.college_id IS NULL
                """, requestId).stream().findFirst()
                .orElseThrow(() -> ApiError.FACULTY_REQUEST_NOT_FOUND.exception());
    }

    private List<Request> query(String sql, Long id) {
        return jdbc.query(sql, (row, index) -> new Request(row.getLong("id"), row.getLong("applicant_id"),
                Status.valueOf(row.getString("status")), row.getObject("reviewer_id", Long.class),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("decided_at", OffsetDateTime.class)), id);
    }

    private void lockAccounts(Long applicantId, Long reviewerId) {
        jdbc.queryForList("SELECT id FROM public.users WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                Long.class, applicantId, reviewerId);
    }

    private boolean isActive(Long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.users WHERE id = ? AND is_active IS TRUE)
                """, Boolean.class, userId));
    }

    private boolean isEligibleApplicant(Long applicantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.users u LEFT JOIN public.colleges c ON c.id = u.college_id
                    WHERE u.id = ? AND u.is_active IS TRUE
                      AND (u.college_id IS NULL OR c.is_active IS TRUE)
                )
                """, Boolean.class, applicantId));
    }

    private boolean holdsAdminRole(Long reviewerId) {
        return !jdbc.queryForList("""
                SELECT ur.user_id FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                JOIN public.users u ON u.id = ur.user_id
                WHERE ur.user_id = ? AND r.name = 'ADMIN' AND u.college_id IS NULL
                FOR SHARE OF ur, r
                """, Long.class, reviewerId).isEmpty();
    }

    private boolean hasRole(Long userId, String role) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM public.user_roles ur JOIN public.roles r ON r.id = ur.role_id
                    WHERE ur.user_id = ? AND r.name = ?
                )
                """, Boolean.class, userId, role));
    }

    public enum Status { PENDING, APPROVED, REJECTED }

    public record Request(Long id, Long applicantId, Status status, Long reviewerId,
                          OffsetDateTime createdAt, OffsetDateTime decidedAt) {}
}
