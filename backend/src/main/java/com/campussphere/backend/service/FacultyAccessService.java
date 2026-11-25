package com.campussphere.backend.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;

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

    @Transactional(propagation = Propagation.MANDATORY)
    public void openInitialRequest(Long applicantId, String collegeCode) {
        if (collegeCode == null) {
            openRequest(applicantId);
            return;
        }
        Long target = jdbc.queryForList("""
                SELECT id FROM public.colleges WHERE code = ? AND is_active IS TRUE FOR SHARE
                """, Long.class, collegeCode.toUpperCase(Locale.ROOT)).stream().findFirst()
                .orElseThrow(ApiError.FACULTY_COLLEGE_UNAVAILABLE::exception);
        openCollegeRequest(applicantId, target);
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
        var rejected = latest(applicantId).filter(request -> request.status() == Status.REJECTED);
        if (hasRole(applicantId, "FACULTY") || rejected.isEmpty()) {
            throw ApiError.FACULTY_REQUEST_CONFLICT.exception();
        }
        Long target = jdbc.queryForObject("""
                SELECT requested_college_id FROM public.faculty_access_requests WHERE id = ?
                """, Long.class, rejected.get().id());
        if (target != null) {
            requireActiveCollege(target, ApiError.FACULTY_COLLEGE_UNAVAILABLE);
            return openCollegeRequest(applicantId, target);
        }
        return openRequest(applicantId);
    }

    @Transactional(readOnly = true)
    public List<CollegeRequest> pending(Account reviewer) {
        return collegeRequests("""
                WHERE f.requested_college_id = ? AND f.status = 'PENDING'
                  AND u.college_id IS NULL AND u.is_active IS TRUE
                ORDER BY f.id
                """, reviewer.requireCollege(CollegeRole.COLLEGE_ADMIN, null));
    }

    @Transactional
    public CollegeRequest decideForCollege(Account reviewer, Long requestId, Status decision,
                                           boolean employmentConfirmed) {
        long college = reviewer.requireCollege(CollegeRole.COLLEGE_ADMIN, null);
        if (decision != Status.APPROVED && decision != Status.REJECTED) {
            throw ApiError.INVALID_REQUEST.exception();
        }
        var request = findCollegeRequest(college, requestId);
        if (decision == Status.APPROVED && !employmentConfirmed) {
            throw ApiError.FACULTY_EMPLOYMENT_UNCONFIRMED.exception();
        }
        long applicant = request.applicantId();
        lockAccounts(applicant, reviewer.id());
        requireActiveCollege(college, ApiError.ACCESS_DENIED);
        if (applicant == reviewer.id() || jdbc.queryForList("""
                SELECT u.id FROM public.users u
                JOIN public.user_roles ur ON ur.user_id = u.id
                JOIN public.roles r ON r.id = ur.role_id
                WHERE u.id = ? AND u.college_id = ? AND u.is_active IS TRUE AND r.name = 'COLLEGE_ADMIN'
                FOR SHARE OF ur, r
                """, Long.class, reviewer.id(), college).isEmpty()) {
            throw ApiError.ACCESS_DENIED.exception();
        }
        if (request.status() != Status.PENDING || jdbc.queryForList("""
                SELECT u.id FROM public.users u
                WHERE u.id = ? AND u.college_id IS NULL AND u.is_active IS TRUE
                  AND NOT EXISTS (SELECT 1 FROM public.user_roles ur WHERE ur.user_id = u.id)
                """, Long.class, applicant).isEmpty()) {
            throw ApiError.FACULTY_REQUEST_CONFLICT.exception();
        }

        if (decision == Status.APPROVED) {
            jdbc.update("""
                    UPDATE public.users SET college_id = ?, is_verified = TRUE, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, college, applicant);
            if (jdbc.update("""
                    INSERT INTO public.user_roles (user_id, role_id)
                    SELECT ?, id FROM public.roles WHERE name = 'FACULTY'
                    """, applicant) != 1) {
                throw new IllegalStateException("The FACULTY role is missing");
            }
        }
        if (jdbc.update("""
                UPDATE public.faculty_access_requests
                SET status = ?, reviewer_id = ?, decided_at = CURRENT_TIMESTAMP, college_id = ?
                WHERE id = ? AND requested_college_id = ? AND status = 'PENDING'
                """, decision.name(), reviewer.id(), decision == Status.APPROVED ? college : null,
                requestId, college) != 1) {
            throw ApiError.FACULTY_REQUEST_CONFLICT.exception();
        }
        return findCollegeRequest(college, requestId);
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
                WHERE id = ? AND status = 'PENDING' AND college_id IS NULL AND requested_college_id IS NULL
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

    private Request openCollegeRequest(Long applicantId, long target) {
        return jdbc.query("""
                INSERT INTO public.faculty_access_requests (applicant_id, requested_college_id)
                VALUES (?, ?) RETURNING id, applicant_id, status, reviewer_id, created_at, decided_at
                """, FacultyAccessService::readRequest, applicantId, target).getFirst();
    }

    private void requireActiveCollege(long college, ApiError error) {
        if (jdbc.queryForList("""
                SELECT id FROM public.colleges WHERE id = ? AND is_active IS TRUE FOR SHARE
                """, Long.class, college).isEmpty()) {
            throw error.exception();
        }
    }

    private CollegeRequest findCollegeRequest(long college, long requestId) {
        return collegeRequests("WHERE f.requested_college_id = ? AND f.id = ?", college, requestId)
                .stream().findFirst().orElseThrow(ApiError.FACULTY_REQUEST_NOT_FOUND::exception);
    }

    private List<CollegeRequest> collegeRequests(String predicate, Object... arguments) {
        return jdbc.query("""
                SELECT f.id, f.applicant_id, f.status, u.full_name, u.email
                FROM public.faculty_access_requests f
                JOIN public.users u ON u.id = f.applicant_id
                """ + predicate, (row, index) -> new CollegeRequest(row.getLong("id"),
                row.getLong("applicant_id"), Status.valueOf(row.getString("status")),
                row.getString("full_name"), row.getString("email")), arguments);
    }

    private Request findLegacy(Long requestId) {
        return query("""
                SELECT f.id, f.applicant_id, f.status, f.reviewer_id, f.created_at, f.decided_at
                FROM public.faculty_access_requests f
                JOIN public.users u ON u.id = f.applicant_id
                WHERE f.id = ? AND f.requested_college_id IS NULL AND f.college_id IS NULL AND u.college_id IS NULL
                """, requestId).stream().findFirst()
                .orElseThrow(() -> ApiError.FACULTY_REQUEST_NOT_FOUND.exception());
    }

    private List<Request> query(String sql, Long id) {
        return jdbc.query(sql, FacultyAccessService::readRequest, id);
    }

    private static Request readRequest(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Request(row.getLong("id"), row.getLong("applicant_id"),
                Status.valueOf(row.getString("status")), row.getObject("reviewer_id", Long.class),
                row.getObject("created_at", OffsetDateTime.class),
                row.getObject("decided_at", OffsetDateTime.class));
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

    public record CollegeRequest(long id, long applicantId, Status status, String name, String email) {}
}
