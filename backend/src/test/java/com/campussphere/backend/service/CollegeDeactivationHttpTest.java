package com.campussphere.backend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.campussphere.backend.security.JwtService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/campus_sphere_test"
})
@ActiveProfiles("test")
class CollegeDeactivationHttpTest {
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JwtService jwt;
    @MockitoSpyBean private PlatformAccess platformAccess;

    @Test
    void sameJwtLosesAndRegainsAccessAcrossIndependentHttpRequests() throws Exception {
        assertEquals("campus_sphere_test", jdbc.queryForObject("SELECT current_database()", String.class));
        var tag = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        var fixture = transactions.execute(status -> seed(tag));
        try (var client = HttpClient.newHttpClient()) {
            assertNotNull(fixture);
            // Audit rows are append-only. Suppress only writes for this synthetic actor
            // so committed request transactions can be verified and the fixtures removed.
            doNothing().when(platformAccess).audit(eq(fixture.platformId()), anyString(), anyLong());
            assertStatus(client, "GET", "/api/college/departments", fixture.tenantJwt(), null, 200);
            assertStatus(client, "PUT", statusPath(fixture), fixture.platformJwt(), "{\"active\":false}", 200);
            assertEquals(Boolean.FALSE, jdbc.queryForObject(
                    "SELECT is_active FROM public.colleges WHERE id = ?", Boolean.class, fixture.collegeId()));
            assertStatus(client, "GET", "/api/college/departments", fixture.tenantJwt(), null, 403);
            assertStatus(client, "PUT", statusPath(fixture), fixture.platformJwt(), "{\"active\":true}", 200);
            assertStatus(client, "GET", "/api/college/departments", fixture.tenantJwt(), null, 200);
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM public.college_admin_invitations
                    WHERE college_id = ? AND revoked_at IS NOT NULL AND revoked_by = ?
                    """, Integer.class, fixture.collegeId(), fixture.platformId()));
        } finally {
            transactions.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM public.college_admin_invitations WHERE college_id = ?", fixture.collegeId());
                jdbc.update("DELETE FROM public.user_roles WHERE user_id IN (?, ?)", fixture.platformId(), fixture.tenantId());
                jdbc.update("DELETE FROM public.users WHERE id IN (?, ?)", fixture.platformId(), fixture.tenantId());
                jdbc.update("DELETE FROM public.colleges WHERE id = ?", fixture.collegeId());
            });
        }
    }

    private Fixture seed(String tag) {
        long college = jdbc.queryForObject("""
                INSERT INTO public.colleges (code, name, is_active)
                VALUES (?, 'Synthetic Deactivation Rehearsal', TRUE) RETURNING id
                """, Long.class, "DIAG_" + tag.toUpperCase(java.util.Locale.ROOT));
        String platformEmail = "diag-platform-" + tag + "@example.invalid";
        String tenantEmail = "diag-tenant-" + tag + "@example.invalid";
        long platform = user(platformEmail, null, "SUPER_ADMIN");
        long tenant = user(tenantEmail, college, "COLLEGE_ADMIN");
        String code = InvitationToken.generate();
        String email = "diag-invite-" + tag + "@example.invalid";
        long invitation = jdbc.queryForObject("""
                INSERT INTO public.college_admin_invitations
                    (college_id, email, token_hash, issued_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '24 hours') RETURNING id
                """, Long.class, college, email, InvitationToken.hash(code), platform);
        return new Fixture(college, platform, tenant, invitation, email, code,
                jwt.generateToken(platformEmail, platform), jwt.generateToken(tenantEmail, tenant));
    }

    private long user(String email, Long college, String role) {
        long id = jdbc.queryForObject("""
                INSERT INTO public.users (full_name, email, password, college_id, is_active, is_verified)
                VALUES ('Synthetic Deactivation Fixture', ?, 'fixture-not-a-password', ?, TRUE, TRUE) RETURNING id
                """, Long.class, email, college);
        jdbc.update("""
                INSERT INTO public.user_roles (user_id, role_id) SELECT ?, id FROM public.roles WHERE name = ?
                """, id, role);
        return id;
    }

    private String statusPath(Fixture fixture) {
        return "/api/platform/colleges/" + fixture.collegeId() + "/status";
    }

    private void assertStatus(HttpClient client, String method, String path, String token, String body,
                              int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token);
        if (body != null) request.header("Content-Type", "application/json");
        var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(expected, response.statusCode(), method + " " + path);
    }

    @Test
    void simultaneousRedemptionCreatesOneAccountAndRollsBackOnAuditFailure() throws Exception {
        assertEquals("campus_sphere_test", jdbc.queryForObject("SELECT current_database()", String.class));
        var fixture = transactions.execute(status -> seed(UUID.randomUUID().toString().replace("-", "").substring(0, 16)));
        try (var client = HttpClient.newHttpClient()) {
            doAnswer(call -> {
                call.callRealMethod();
                throw com.campussphere.backend.security.ApiError.DATABASE_UNAVAILABLE.exception();
            }).when(platformAccess).audit(anyLong(), eq("INVITATION_ACCEPTED"), eq(fixture.invitationId()));

            assertEquals(503, client.send(redemption(fixture), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(0, redeemedUsers(fixture));
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM public.privileged_audit_events
                    WHERE action = 'INVITATION_ACCEPTED' AND target_id = ?
                    """, Integer.class, fixture.invitationId()));
            assertEquals(0, jdbc.queryForObject("""
                    SELECT count(*) FROM public.college_admin_invitations WHERE id = ? AND accepted_at IS NOT NULL
                    """, Integer.class, fixture.invitationId()));

            // The transaction above verified rollback of a real audit write. Suppress only
            // this fixture's success audit for the commit race to allow normal cleanup.
            doNothing().when(platformAccess).audit(anyLong(), eq("INVITATION_ACCEPTED"), eq(fixture.invitationId()));
            var first = client.sendAsync(redemption(fixture), HttpResponse.BodyHandlers.discarding());
            var second = client.sendAsync(redemption(fixture), HttpResponse.BodyHandlers.discarding());
            java.util.concurrent.CompletableFuture.allOf(first, second).get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(java.util.List.of(204, 400),
                    java.util.stream.Stream.of(first.join().statusCode(), second.join().statusCode()).sorted().toList());
            assertEquals(1, redeemedUsers(fixture));
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM public.college_admin_invitations i
                    JOIN public.users u ON u.id = i.accepted_by AND u.college_id = i.college_id
                    JOIN public.user_roles ur ON ur.user_id = u.id
                    JOIN public.roles r ON r.id = ur.role_id
                    WHERE i.id = ? AND i.accepted_at IS NOT NULL AND r.name = 'COLLEGE_ADMIN'
                    """, Integer.class, fixture.invitationId()));
        } finally {
            transactions.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM public.college_admin_invitations WHERE id = ?", fixture.invitationId());
                jdbc.update("""
                        DELETE FROM public.user_roles WHERE user_id IN (
                            SELECT id FROM public.users WHERE id IN (?, ?) OR (email = ? AND college_id = ?)
                        )
                        """, fixture.platformId(), fixture.tenantId(), fixture.email(), fixture.collegeId());
                jdbc.update("DELETE FROM public.users WHERE id IN (?, ?) OR (email = ? AND college_id = ?)",
                        fixture.platformId(), fixture.tenantId(), fixture.email(), fixture.collegeId());
                jdbc.update("DELETE FROM public.colleges WHERE id = ?", fixture.collegeId());
            });
        }
    }

    private int redeemedUsers(Fixture fixture) {
        return jdbc.queryForObject("SELECT count(*) FROM public.users WHERE email = ? AND college_id = ?",
                Integer.class, fixture.email(), fixture.collegeId());
    }

    private HttpRequest redemption(Fixture fixture) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/college-admin-invitations/accept"))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"token":"%s","fullName":"Synthetic Recipient","password":"synthetic-only-password"}
                        """.formatted(fixture.invitationCode())))
                .build();
    }

    private record Fixture(long collegeId, long platformId, long tenantId, long invitationId,
                           String email, String invitationCode, String platformJwt, String tenantJwt) {}

}
