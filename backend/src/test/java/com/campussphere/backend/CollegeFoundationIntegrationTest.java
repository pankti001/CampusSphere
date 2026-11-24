package com.campussphere.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Set;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.entity.College;
import com.campussphere.backend.entity.Role;
import com.campussphere.backend.entity.User;
import com.campussphere.backend.repository.RoleRepository;
import com.campussphere.backend.repository.UserRepository;
import com.campussphere.backend.security.AccountAccessPolicy;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.security.JwtService;
import com.campussphere.backend.service.FacultyAccessService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CollegeFoundationIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private AccountAccessPolicy policy;
    @Autowired private JwtService jwt;
    @Autowired private FacultyAccessService faculty;

    private College alpha;
    private College beta;

    @BeforeEach
    void createSyntheticColleges() {
        assertEquals("campus_sphere_test", jdbc.queryForObject("SELECT current_database()", String.class));
        alpha = college("PHASE51_ALPHA");
        beta = college("PHASE51_BETA");
    }

    @Test
    void jwtLoadsCurrentCollegeAndDeactivationRemovesStudentAccess() throws Exception {
        var user = new User();
        user.setFullName("Synthetic Student");
        user.setEmail("phase51-student@example.invalid");
        user.setPassword("unused-test-fixture");
        user.setIsVerified(true);
        user.setCollege(alpha);
        user.setRoles(Set.of(roles.findByName("STUDENT").orElseThrow()));
        users.saveAndFlush(user);
        String bearer = "Bearer " + jwt.generateToken(user.getEmail(), user.getId());

        mvc.perform(get("/api/auth/status").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.college.id").value(alpha.getId()))
                .andExpect(jsonPath("$.college.active").value(true))
                .andExpect(jsonPath("$.roles[0]").value("STUDENT"));
        mvc.perform(get("/api/student/dashboard").header("Authorization", bearer))
                .andExpect(status().isOk());

        jdbc.update("UPDATE public.colleges SET is_active = false WHERE id = ?", alpha.getId());
        entities.clear();
        mvc.perform(get("/api/auth/status").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.college.active").value(false))
                .andExpect(jsonPath("$.roles").isEmpty());
        mvc.perform(get("/api/student/dashboard").header("Authorization", bearer))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void scopedPolicyRejectsCrossCollegeUnassignedAndPlatformAccess() {
        Account admin = syntheticPrincipal("COLLEGE_ADMIN", alpha);
        assertEquals(alpha.getId().longValue(), admin.requireCollege(CollegeRole.COLLEGE_ADMIN, null));
        assertEquals(alpha.getId().longValue(), admin.requireCollege(CollegeRole.COLLEGE_ADMIN, alpha.getId()));
        assertDenied(admin, CollegeRole.COLLEGE_ADMIN, beta.getId());
        assertDenied(syntheticPrincipal("COLLEGE_ADMIN", null), CollegeRole.COLLEGE_ADMIN, alpha.getId());
        assertDenied(syntheticPrincipal("ADMIN", null), CollegeRole.COLLEGE_ADMIN, alpha.getId());
        assertDenied(syntheticPrincipal("SUPER_ADMIN", null), CollegeRole.COLLEGE_ADMIN, alpha.getId());
        assertDenied(syntheticPrincipal("STUDENT", null), CollegeRole.STUDENT, alpha.getId());
        assertFalse(syntheticPrincipal("SUPER_ADMIN", alpha).roles().contains("SUPER_ADMIN"));
        assertFalse(syntheticPrincipal("ADMIN", alpha).roles().contains("ADMIN"));
    }

    @Test
    void legacyAdminCannotDecideCollegeOwnedFacultyRequest() throws Exception {
        long applicant = applicant(alpha.getId());
        faculty.openInitialRequest(applicant);
        var request = faculty.latest(applicant).orElseThrow();
        assertEquals(alpha.getId(), jdbc.queryForObject(
                "SELECT college_id FROM public.faculty_access_requests WHERE id = ?", Long.class, request.id()));
        var legacy = new Account(-1L, true, false, Set.of("ADMIN"), null);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(legacy, null, legacy.authorities());

        mvc.perform(post("/api/admin/faculty-requests/{id}/decision", request.id())
                .with(authentication(authentication)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FACULTY_REQUEST_NOT_FOUND"));
        assertEquals(FacultyAccessService.Status.PENDING, faculty.latest(applicant).orElseThrow().status());
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM public.user_roles WHERE user_id = ?",
                Long.class, applicant));
    }

    @Test
    void databaseRejectsFacultyRequestWithWrongApplicantCollege() {
        long applicant = applicant(alpha.getId());
        var failure = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO public.faculty_access_requests (applicant_id, college_id)
                VALUES (?, ?)
                """, applicant, beta.getId()));
        assertTrue(failure.getMostSpecificCause().getMessage().contains("fk_faculty_request_college_applicant"));
    }

    @Test
    void publicRegistrationCannotGrantNewAdminRoles() throws Exception {
        for (String role : Set.of("SUPER_ADMIN", "COLLEGE_ADMIN")) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                    {"fullName":"Synthetic Applicant","email":"phase51-admin@example.invalid",
                     "password":"test-only-password","role":"%s"}
                    """.formatted(role)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_ALLOWED"));
        }
        assertFalse(users.existsByEmail("phase51-admin@example.invalid"));
    }

    private void assertDenied(Account account, CollegeRole role, Long targetCollege) {
        var failure = assertThrows(ApiError.Failure.class, () -> account.requireCollege(role, targetCollege));
        assertEquals(ApiError.ACCESS_DENIED, failure.error());
    }

    private Account syntheticPrincipal(String role, College college) {
        var user = new User();
        user.setCollege(college);
        user.setIsVerified(true);
        user.setRoles(Set.of(new Role(role, "In-memory authorization fixture")));
        return policy.account(user);
    }

    private College college(String code) {
        Long id = jdbc.queryForObject("INSERT INTO public.colleges (code, name, is_active) VALUES (?, ?, TRUE) RETURNING id",
                Long.class, code, code);
        return entities.find(College.class, id);
    }

    private long applicant(Long collegeId) {
        return jdbc.queryForObject("""
                INSERT INTO public.users (full_name, email, password, college_id)
                VALUES ('Synthetic Applicant', 'phase51-faculty@example.invalid', 'unused-test-fixture', ?)
                RETURNING id
                """, Long.class, collegeId);
    }
}
