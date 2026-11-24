package com.campussphere.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.security.JwtService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/campus_sphere_test"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformOnboardingIntegrationTest {
    private static final String COLLEGES = "/api/platform/colleges";
    private static final String ACCEPT = "/api/auth/college-admin-invitations/accept";

    @Autowired private MockMvc mvc;
    @Autowired private jakarta.persistence.EntityManager entities;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private ObjectMapper json;

    private String platform;

    @BeforeEach
    void createSyntheticPlatformActor() {
        assertEquals("campus_sphere_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(7, jdbc.queryForObject("""
                SELECT count(*) FROM public.flyway_schema_history
                WHERE success AND version IN ('1', '2', '3', '4', '5', '6', '7')
                """, Integer.class));
        platform = account("platform", null, "SUPER_ADMIN", true);
    }

    @Test
    void rejectsDuplicateCanonicalCollegeCode() throws Exception {
        long college = college("PHASE53_ALPHA");
        create("phase53_alpha").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLEGE_CODE_CONFLICT"));
        // A uniqueness violation aborts the PostgreSQL transaction. Check it last.
        assertTrue(college > 0);
    }

    @Test
    void platformCanListUpdateAndChangeStatusButHasNoTenantPrivileges() throws Exception {
        long id = college("PHASE53_EDIT");
        mvc.perform(get(COLLEGES).param("search", "PHASE53_EDIT").header("Authorization", platform))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].active").value(false));
        mvc.perform(patch(COLLEGES + "/" + id).header("Authorization", platform)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Synthetic College Updated"}
                        """)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Synthetic College Updated"));
        changeStatus(id, true);
        String tenant = account("tenant", id, "COLLEGE_ADMIN", true);
        mvc.perform(get("/api/college/departments").header("Authorization", tenant)).andExpect(status().isOk());
        for (String path : new String[] {"/api/college/departments", "/api/student/dashboard",
                "/api/faculty/dashboard", "/api/admin/dashboard"}) {
            mvc.perform(get(path).header("Authorization", platform)).andExpect(status().isForbidden());
        }
        for (String role : new String[] {"ADMIN", "STUDENT", "FACULTY"}) {
            mvc.perform(get(COLLEGES).header("Authorization", account(role, null, role, true)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get(COLLEGES).header("Authorization", tenant)).andExpect(status().isForbidden());
        mvc.perform(post(invites(id)).header("Authorization", tenant).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"denied@example.invalid\"}")).andExpect(status().isForbidden());
        mvc.perform(get(COLLEGES).header("Authorization", account("disabled", null, "SUPER_ADMIN", false)))
                .andExpect(status().isUnauthorized());
        changeStatus(id, false);
        mvc.perform(get("/api/college/departments").header("Authorization", tenant))
                .andExpect(status().isForbidden());
        changeStatus(id, true);
        mvc.perform(get("/api/college/departments").header("Authorization", tenant)).andExpect(status().isOk());
    }

    @Test
    void invitationsAreBoundToCollegeAndEmailAndConsumedOnce() throws Exception {
        long alpha = college("PHASE53_INVITE");
        long beta = college("PHASE53_BETA");
        issue(alpha, "invitee@example.invalid").andExpect(status().isConflict());
        changeStatus(alpha, true);
        var receipt = issue(alpha, "Invitee@Example.Invalid").andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var body = json.readTree(receipt.getResponse().getContentAsString());
        String token = body.path("token").asText();
        long invitationId = body.path("invitation").path("id").asLong();
        assertEquals(43, token.length());
        assertEquals(32, jdbc.queryForObject("""
                SELECT octet_length(token_hash) FROM public.college_admin_invitations WHERE id = ?
                """, Integer.class, invitationId));
        var expectedDigest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(java.security.MessageDigest.isEqual(expectedDigest, jdbc.queryForObject("""
                SELECT token_hash FROM public.college_admin_invitations WHERE id = ?
                """, byte[].class, invitationId)), "Stored hash must match the SHA-256 digest");
        assertEquals(86400L, jdbc.queryForObject("""
                SELECT extract(epoch FROM expires_at - created_at)::bigint
                FROM public.college_admin_invitations WHERE id = ?
                """, Long.class, invitationId));
        mvc.perform(get(invites(alpha)).header("Authorization", platform)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].token").doesNotExist());
        mvc.perform(post(invites(beta) + "/" + invitationId + "/revoke").header("Authorization", platform))
                .andExpect(status().isNotFound());
        accept("A".repeat(43)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        mvc.perform(post(ACCEPT).contentType(MediaType.APPLICATION_JSON).content("""
                {"token":"%s","fullName":"Synthetic Recipient","password":"synthetic-only-password",
                 "collegeId":%d,"email":"forged@example.invalid"}
                """.formatted(token, beta))).andExpect(status().isNoContent());
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM public.privileged_audit_events a
                JOIN public.college_admin_invitations i ON i.id = a.target_id
                WHERE a.action = 'INVITATION_ACCEPTED' AND i.id = ?
                  AND a.actor_id = i.accepted_by AND a.occurred_at IS NOT NULL
                """, Integer.class, invitationId));
        accept(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        assertEquals(alpha, jdbc.queryForObject("""
                SELECT college_id FROM public.users WHERE email = 'invitee@example.invalid'
                """, Long.class));
        assertEquals("COLLEGE_ADMIN", jdbc.queryForObject("""
                SELECT r.name FROM public.roles r JOIN public.user_roles ur ON r.id = ur.role_id
                JOIN public.users u ON u.id = ur.user_id WHERE u.email = 'invitee@example.invalid'
                """, String.class));
        mvc.perform(get(invites(alpha)).header("Authorization", platform)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));
    }

    @Test
    void deactivationRevokesPendingInvitationsAndExpiryRemainsVisible() throws Exception {
        long college = college("PHASE53_REVOKE");
        changeStatus(college, true);
        String token = token(college, "pending@example.invalid");
        String expired = token(college, "expired@example.invalid");
        jdbc.update("""
                UPDATE public.college_admin_invitations
                SET created_at = created_at - interval '25 hours',
                    expires_at = expires_at - interval '25 hours'
                WHERE college_id = ? AND email = 'expired@example.invalid'
                """, college);
        accept(expired).andExpect(status().isBadRequest());
        changeStatus(college, false);
        changeStatus(college, true);
        accept(token).andExpect(status().isBadRequest());
        mvc.perform(get(invites(college)).header("Authorization", platform)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$[1].status").value("REVOKED"));
    }

    @Test
    void explicitRevocationIsIdempotentAndRejectsRedemption() throws Exception {
        long college = college("PHASE53_EXPLICIT");
        changeStatus(college, true);
        var response = issue(college, "explicit@example.invalid").andExpect(status().isCreated()).andReturn();
        var body = json.readTree(response.getResponse().getContentAsString());
        long invitationId = body.path("invitation").path("id").asLong();
        String path = invites(college) + "/" + invitationId + "/revoke";
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(path).header("Authorization", platform)).andExpect(status().isNoContent());
        }
        accept(body.path("token").asText()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM public.privileged_audit_events
                WHERE action = 'INVITATION_REVOKED' AND target_id = ?
                """, Integer.class, invitationId));
    }

    @Test
    void accountCreatedAfterIssuanceIsNeverUpgraded() throws Exception {
        long college = college("PHASE53_EXISTING");
        changeStatus(college, true);
        String token = token(college, "existing@example.invalid");
        account("existing", null, "STUDENT", true);
        issue(college, "existing@example.invalid").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
        accept(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM public.privileged_audit_events
                WHERE action = 'INVITATION_ACCEPTED'
                """, Integer.class));
        assertNull(jdbc.queryForObject("SELECT college_id FROM public.users WHERE email = 'existing@example.invalid'",
                Long.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM public.user_roles ur
                JOIN public.roles r ON r.id = ur.role_id JOIN public.users u ON u.id = ur.user_id
                WHERE u.email = 'existing@example.invalid' AND r.name = 'COLLEGE_ADMIN'
                """, Integer.class));
    }

    @Test
    void auditRejectsUpdatesEvenForDatabaseOwner() throws Exception {
        long id = college("PHASE53_AUDIT");
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM public.privileged_audit_events
                WHERE action = 'COLLEGE_CREATED' AND target_id = ?
                """, Integer.class, id));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                UPDATE public.privileged_audit_events SET target_id = 1
                WHERE action = 'COLLEGE_CREATED' AND target_id = ?
                """, id));
    }

    private long college(String code) throws Exception {
        var response = create(code).andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.createdAt").exists()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("id").asLong();
    }

    private ResultActions create(String code) throws Exception {
        return mvc.perform(post(COLLEGES).header("Authorization", platform).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Synthetic College\",\"code\":\"" + code + "\"}"));
    }

    private void changeStatus(long id, boolean active) throws Exception {
        mvc.perform(put(COLLEGES + "/" + id + "/status").header("Authorization", platform)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":" + active + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(active));
        // MockMvc requests share the test transaction. The status write uses JdbcTemplate.
        entities.clear();
    }

    private static String invites(long college) {
        return COLLEGES + "/" + college + "/admin-invitations";
    }

    private ResultActions issue(long college, String email) throws Exception {
        return mvc.perform(post(invites(college)).header("Authorization", platform)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"));
    }

    private String token(long college, String email) throws Exception {
        var response = issue(college, email).andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).path("token").asText();
    }

    private ResultActions accept(String token) throws Exception {
        return mvc.perform(post(ACCEPT).contentType(MediaType.APPLICATION_JSON).content("""
                {"token":"%s","fullName":"Synthetic Recipient","password":"synthetic-only-password"}
                """.formatted(token)));
    }

    private String account(String name, Long college, String role, boolean active) {
        String email = name + "@example.invalid";
        long userId = jdbc.queryForObject("""
                INSERT INTO public.users (full_name, email, password, college_id, is_active, is_verified)
                VALUES (?, ?, 'fixture-not-a-password', ?, ?, TRUE) RETURNING id
                """, Long.class, name, email, college, active);
        jdbc.update("""
                INSERT INTO public.user_roles (user_id, role_id) SELECT ?, id FROM public.roles WHERE name = ?
                """, userId, role);
        return "Bearer " + jwt.generateToken(email, userId);
    }
}
