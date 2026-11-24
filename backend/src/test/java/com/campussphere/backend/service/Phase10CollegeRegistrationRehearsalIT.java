package com.campussphere.backend.service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.campussphere.backend.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Explicit, separately approved run only. Commits synthetic rehearsal rows. */
@EnabledIfSystemProperty(named = "campussphere.phase10.rehearsal", matches = "approved")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/campus_sphere_phase9_rehearsal",
        "spring.datasource.username=postgres",
        "spring.datasource.hikari.connection-init-sql=SELECT 1 / CASE WHEN current_database()='campus_sphere_phase9_rehearsal' AND inet_server_addr()='127.0.0.1'::inet AND inet_server_port()=5432 AND current_setting('server_version_num')='190000' AND current_user='postgres' AND session_user='postgres' AND current_schema()='public' THEN 1 ELSE 0 END",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.sql.init.mode=never",
        "app.registration.expiry-enabled=false",
        "spring.devtools.restart.enabled=false"
})
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
class Phase10CollegeRegistrationRehearsalIT {
    private static final String SUBMIT = "/api/college-registration-requests";
    private static final String REVIEW = "/api/platform/college-registration-requests/";
    private static final String REVIEWER = "reviewer.p10@example.invalid";
    private static final String APPROVAL = """
            {"method":"OFFICIAL_CALLBACK","institution":true,"representative":true,"mailbox":true}
            """;
    private static final String REJECTION = """
            {"reason":"Synthetic rehearsal rejection"}
            """;

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired CollegeRegistrationService registrations;
    @MockitoSpyBean PlatformAccess platform;
    private String authorization;

    @BeforeEach void verifyRehearsalAndReviewer() {
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT current_database()='campus_sphere_phase9_rehearsal'
                   AND inet_server_addr()='127.0.0.1'::inet AND inet_server_port()=5432
                   AND current_setting('server_version_num')='190000' AND current_user='postgres'
                """, Boolean.class)));
        assertEquals(java.util.List.of(57123013,-2074273504,278880907,1437205848,1629970249,1981309137,-1832066092,-1533777827),
                jdbc.queryForList("SELECT checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank", Integer.class));
        assertEquals(8L, count("flyway_schema_history"));
        long reviewer = jdbc.queryForObject("""
                SELECT u.id FROM public.users u JOIN public.user_roles ur ON ur.user_id=u.id
                  JOIN public.roles r ON r.id=ur.role_id
                WHERE u.email=? AND u.college_id IS NULL AND u.is_active AND u.is_verified AND r.name='SUPER_ADMIN'
                """, Long.class, REVIEWER);
        authorization = "Bearer " + jwt.generateToken(REVIEWER, reviewer);
    }

    @Test void rejectsAndClearsTheHash() throws Exception {
        var request = submit("Phase 10 Reject Institute","P10-REJECT","Phase 10 Rejection Applicant","reject.p10@example.invalid");
        long audits = count("privileged_audit_events");
        assertEquals(204, decide(request.id(), "reject", REJECTION));
        verifyRequest(request.id(), "REJECTED", false);
        assertEquals(audits + 1, count("privileged_audit_events"));
        loginDenied(request);
    }

    @Test void readProjectsOverdueStatusAndExplicitExpiryClearsHashOnce() throws Exception {
        var request = submit("Phase 10 Expiry Institute","P10-EXPIRE","Phase 10 Expiry Applicant","expiry.p10@example.invalid");
        assertEquals(1, jdbc.update("""
                WITH times AS (SELECT clock_timestamp() - interval '721 hours' AS submitted)
                UPDATE public.college_registration_requests SET submitted_at=times.submitted,
                    expires_at=times.submitted+interval '720 hours'
                FROM times WHERE id=? AND status='PENDING'
                """,request.id()));
        long audits = count("privileged_audit_events");
        http.perform(get(REVIEW + request.id()).header("Authorization",authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        verifyRequest(request.id(), "PENDING", true);
        assertEquals(409, decide(request.id(), "approve", APPROVAL));
        verifyRequest(request.id(), "PENDING", true);
        registrations.expire();
        verifyRequest(request.id(), "EXPIRED", false);
        var decided = jdbc.queryForObject("SELECT decided_at FROM public.college_registration_requests WHERE id=?",
                java.time.OffsetDateTime.class, request.id());
        registrations.expire();
        assertEquals(decided, jdbc.queryForObject("SELECT decided_at FROM public.college_registration_requests WHERE id=?",
                java.time.OffsetDateTime.class, request.id()));
        assertEquals(audits, count("privileged_audit_events"));
        loginDenied(request);
    }

    @Test void simultaneousDecisionsHaveExactlyOneWinner() throws Exception {
        var request = submit("Phase 10 Race Institute","P10-RACE","Phase 10 Race Applicant","race.p10@example.invalid");
        var before = deltasBaseline();
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var approve = pool.submit(decision(barrier, request.id(), "approve", APPROVAL));
            var reject = pool.submit(decision(barrier, request.id(), "reject", REJECTION));
            int approved = approve.get(20,TimeUnit.SECONDS);
            int rejected = reject.get(20,TimeUnit.SECONDS);
            assertTrue(approved == 204 && rejected == 409 || approved == 409 && rejected == 204);
            boolean approvalWon = approved == 204;
            verifyRequest(request.id(), approvalWon ? "APPROVED" : "REJECTED", false);
            verifyDeltas(before, approvalWon ? 1 : 0, approvalWon ? 3 : 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void failureAtFinalAuditRollsBackWholeApproval() throws Exception {
        var request = submit("Phase 10 Rollback Institute","P10-ROLLBACK","Phase 10 Rollback Applicant","rollback.p10@example.invalid");
        var before = deltasBaseline();
        doThrow(new TransientDataAccessResourceException("synthetic final audit failure")).when(platform)
                .audit(anyLong(), eq("COLLEGE_REGISTRATION_APPROVED"), eq(request.id()));
        assertEquals(503, decide(request.id(), "approve", APPROVAL));
        verifyRequest(request.id(), "PENDING", true);
        verifyDeltas(before, 0, 0);
        loginDenied(request);
    }

    private Fixture submit(String name, String code, String fullName, String email) throws Exception {
        assertFalse(Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM public.college_registration_requests WHERE code=? OR email=?)
                """, Boolean.class, code,email)), "Fixture already exists. Stop and reconcile");
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        java.util.Arrays.fill(random, (byte) 0);
        http.perform(post(SUBMIT).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "name",name,"code",code,"fullName",fullName,"email",email,"position","Registrar","password",password))))
                .andExpect(status().isAccepted());
        long id = jdbc.queryForObject("SELECT id FROM public.college_registration_requests WHERE code=? AND email=?",
                Long.class, code,email);
        verifyRequest(id, "PENDING", true);
        return new Fixture(id,email,password);
    }

    private void loginDenied(Fixture request) throws Exception {
        http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email",request.email(),"password",request.password()))))
                .andExpect(status().isUnauthorized());
    }

    private Callable<Integer> decision(CyclicBarrier barrier,long id,String action,String input) {
        return () -> {
            barrier.await(10,TimeUnit.SECONDS);
            return decide(id,action,input);
        };
    }

    private int decide(long id,String action,String input) throws Exception {
        return http.perform(post(REVIEW + id + "/" + action).header("Authorization",authorization)
                .contentType(MediaType.APPLICATION_JSON).content(input)).andReturn().getResponse().getStatus();
    }

    private void verifyRequest(long id,String state,boolean hasHash) {
        assertEquals(state, jdbc.queryForObject("SELECT status FROM public.college_registration_requests WHERE id=?",
                String.class,id));
        assertEquals(hasHash, jdbc.queryForObject("SELECT password_hash IS NOT NULL FROM public.college_registration_requests WHERE id=?",
                Boolean.class,id));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM public." + table, Long.class);
    }

    private long[] deltasBaseline() {
        return new long[]{count("users"),count("user_roles"),count("colleges"),count("privileged_audit_events")};
    }

    private void verifyDeltas(long[] before,int approved,int audits) {
        assertArrayEquals(new long[]{before[0]+approved,before[1]+approved,before[2]+approved,before[3]+audits}, deltasBaseline());
    }

    private record Fixture(long id,String email,String password) {
        @Override public String toString() { return "Fixture[redacted]"; }
    }
}
