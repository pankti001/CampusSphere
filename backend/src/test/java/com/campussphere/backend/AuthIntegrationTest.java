package com.campussphere.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.entity.User;
import com.campussphere.backend.repository.RoleRepository;
import com.campussphere.backend.repository.UserRepository;
import com.campussphere.backend.security.CustomUserDetailsService;
import com.campussphere.backend.security.JwtService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwords;
    @Autowired private Environment environment;
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private CustomUserDetailsService accountLookup;

    @Test
    void studentRegistrationPreservesContractAndRestrictsUnverifiedAccess() throws Exception {
        String email = "pending-student@example.invalid";
        register(email, "STUDENT").andExpect(status().isCreated())
                .andExpect(content().string("Registration successful"));
        var login = login(email).andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.userId").isNumber())
                .andExpect(jsonPath("$.fullName").value("Integration Test"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles").isEmpty());
        String token = responseToken(login);
        mvc.perform(get("/api/auth/status").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(false));
        dashboard("student", token).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_REQUIRED"));
        var student = users.findByEmail(email).orElseThrow();
        student.setIsVerified(true);
        users.flush();
        dashboard("student", token).andExpect(status().isOk());
        dashboard("faculty", token).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        register("public-admin@example.invalid", "ADMIN").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REGISTRATION_NOT_ALLOWED"));
        register(email, "STUDENT").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void tokenStopsAuthenticatingWhenAccountIsDisabledOrDeleted() throws Exception {
        var user = fixture("student", "STUDENT");
        String token = jwt.generateToken(user.getEmail(), user.getId());
        dashboard("student", token).andExpect(status().isOk());
        user.setIsActive(false);
        users.flush();
        assertUnauthorized(token);
        users.delete(user);
        users.flush();
        entities.clear();
        assertUnauthorized(token);
        assertUnauthorized("not-a-token");
        var expired = new JwtService(environment.getRequiredProperty("app.jwt.secret"), -30_000);
        assertUnauthorized(expired.generateToken(user.getEmail(), user.getId()));
        mvc.perform(get("/api/student/dashboard")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("INVALID_AUTHENTICATION"));
    }

    @Test
    void accountLookupOutageReturns503() throws Exception {
        var student = fixture("outage", "STUDENT");
        String token = jwt.generateToken(student.getEmail(), student.getId());
        doThrow(new DataAccessResourceFailureException("simulated outage")).when(accountLookup)
                .loadAccount(student.getId());
        dashboard("student", token).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }

    @Test
    void facultyReapplicationPreservesHistoryAndRequiresCurrentAdminApproval() throws Exception {
        String email = "faculty-applicant@example.invalid";
        register(email, "FACULTY").andExpect(status().isCreated())
                .andExpect(content().string("Registration successful"));
        String applicantToken = responseToken(login(email).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isEmpty()));
        var applicant = users.findByEmail(email).orElseThrow();
        var pending = mvc.perform(get("/api/auth/status").header("Authorization", bearer(applicantToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.facultyRequest.status").value("PENDING"));
        long first = mapper.readTree(pending.andReturn().getResponse().getContentAsString())
                .path("facultyRequest").path("id").asLong();
        dashboard("faculty", applicantToken).andExpect(status().isForbidden());
        dashboard("student", applicantToken).andExpect(status().isForbidden());
        decide(first, "APPROVED", applicantToken).andExpect(status().isForbidden());

        var admin = fixture("admin", "ADMIN");
        String adminToken = jwt.generateToken(admin.getEmail(), admin.getId());
        admin.setIsActive(false);
        users.flush();
        decide(first, "APPROVED", adminToken).andExpect(status().isUnauthorized());
        admin.setIsActive(true);
        users.flush();

        decide(first, "REJECTED", adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewerId").value(admin.getId()))
                .andExpect(jsonPath("$.createdAt").exists()).andExpect(jsonPath("$.decidedAt").exists());
        decide(first, "APPROVED", adminToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FACULTY_REQUEST_CONFLICT"));

        var reapplied = reapply(applicantToken).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
        long second = mapper.readTree(reapplied.andReturn().getResponse().getContentAsString())
                .path("id").asLong();
        reapply(applicantToken).andExpect(status().isConflict());
        decide(second, "APPROVED", adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        decide(second, "REJECTED", adminToken).andExpect(status().isConflict());
        entities.clear();
        dashboard("faculty", applicantToken).andExpect(status().isOk());
        dashboard("student", applicantToken).andExpect(status().isForbidden());
        reapply(applicantToken).andExpect(status().isConflict());
        assertEquals(List.of("REJECTED", "APPROVED"), jdbc.queryForList(
                "SELECT status FROM public.faculty_access_requests WHERE applicant_id = ? ORDER BY id",
                String.class, applicant.getId()));
    }

    private User fixture(String name, String role) {
        var user = new User();
        user.setFullName("Integration Test");
        user.setEmail(name + "@example.invalid");
        user.setPassword(passwords.encode("test-only-password"));
        user.setIsVerified(true);
        user.setRoles(Set.of(roles.findByName(role).orElseThrow()));
        return users.saveAndFlush(user);
    }

    private ResultActions register(String email, String role) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Integration Test","email":"%s","password":"test-only-password","role":"%s"}
                """.formatted(email, role)));
    }

    private ResultActions login(String email) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"test-only-password"}
                """.formatted(email)));
    }

    private String responseToken(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("token").asText();
    }

    private ResultActions dashboard(String role, String token) throws Exception {
        return mvc.perform(get("/api/" + role + "/dashboard").header("Authorization", bearer(token)));
    }

    private void assertUnauthorized(String token) throws Exception {
        dashboard("student", token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_AUTHENTICATION"));
    }

    private ResultActions decide(long requestId, String decision, String token) throws Exception {
        return mvc.perform(post("/api/admin/faculty-requests/{requestId}/decision", requestId)
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + decision + "\"}"));
    }

    private ResultActions reapply(String token) throws Exception {
        return mvc.perform(post("/api/auth/faculty/reapply").header("Authorization", bearer(token)));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
