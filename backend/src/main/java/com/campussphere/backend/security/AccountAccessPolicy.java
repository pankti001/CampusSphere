package com.campussphere.backend.security;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import com.campussphere.backend.entity.College;
import com.campussphere.backend.entity.Role;
import com.campussphere.backend.entity.User;

@Component
public class AccountAccessPolicy {
    private final boolean studentVerificationRequired;

    public AccountAccessPolicy(
            @Value("${app.auth.student-verification-required:true}") boolean studentVerificationRequired) {
        this.studentVerificationRequired = studentVerificationRequired;
    }

    public Account account(User user) {
        Set<String> roles = user.getRoles().stream().map(Role::getName)
                .collect(Collectors.toUnmodifiableSet());
        College college = user.getCollege();
        var scope = college == null ? null : new CollegeScope(college.getId(), college.isActive());
        // Platform identity is exclusive even if a conflicting grant is added outside onboarding.
        if (roles.contains("SUPER_ADMIN")) {
            roles = Set.of("SUPER_ADMIN");
        }
        boolean verified = Boolean.TRUE.equals(user.getIsVerified());
        boolean studentRestricted = studentVerificationRequired && !verified && roles.contains("STUDENT")
                && (scope == null || scope.active());
        Set<String> assignedRoles = roles;
        Set<String> effectiveRoles = assignedRoles.stream()
                .filter(role -> permits(role, scope, verified, studentRestricted))
                .filter(role -> !"COLLEGE_ADMIN".equals(role)
                        || (!assignedRoles.contains("ADMIN") && !assignedRoles.contains("SUPER_ADMIN")))
                .collect(Collectors.toUnmodifiableSet());
        return new Account(user.getId(), verified, studentRestricted, effectiveRoles, scope);
    }

    private boolean permits(String role, CollegeScope scope, boolean verified, boolean studentRestricted) {
        return switch (role) {
            case "ADMIN", "SUPER_ADMIN" -> scope == null;
            case "COLLEGE_ADMIN" -> scope != null && scope.active();
            // Unassigned users retain access to the legacy greeting routes only.
            case "STUDENT" -> !studentRestricted && (scope == null || scope.active());
            case "FACULTY" -> verified && (scope == null || scope.active());
            default -> false;
        };
    }

    public enum CollegeRole { COLLEGE_ADMIN, FACULTY, STUDENT }

    public record CollegeScope(Long id, boolean active) {}

    public record Account(Long id, boolean verified, boolean studentRestricted, Set<String> roles,
                          CollegeScope college) {
        public Set<SimpleGrantedAuthority> authorities() {
            return roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                    .collect(Collectors.toUnmodifiableSet());
        }

        public long requireCollege(CollegeRole role, Long requestedCollegeId) {
            if (college == null || !college.active() || !roles.contains(role.name())) {
                throw (role == CollegeRole.STUDENT && studentRestricted
                        ? ApiError.VERIFICATION_REQUIRED : ApiError.ACCESS_DENIED).exception();
            }
            if (requestedCollegeId != null && !Objects.equals(requestedCollegeId, college.id())) {
                throw ApiError.ACCESS_DENIED.exception();
            }
            return college.id();
        }
    }
}
