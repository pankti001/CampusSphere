package com.campussphere.backend.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.campussphere.backend.operator.FacultyRehearsalMigrationRunner;
import com.campussphere.backend.security.JwtService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL and Spring Security verification. MockMvc runs on the rollback-only test thread. */
@EnabledIfSystemProperty(named = "campussphere.faculty.rehearsal", matches = "approved")
@SpringBootTest(properties = {
        "spring.datasource.url=" + FacultyRehearsalMigrationRunner.URL,
        "spring.datasource.username=postgres",
        "spring.datasource.hikari.jdbc-url=" + FacultyRehearsalMigrationRunner.URL,
        "spring.datasource.hikari.username=postgres",
        "spring.datasource.hikari.connection-init-sql=SELECT 1 / CASE WHEN (" +
                FacultyRehearsalMigrationRunner.IDENTITY + ") THEN 1 ELSE 0 END",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.sql.init.mode=never",
        "app.registration.expiry-enabled=false",
        "spring.devtools.restart.enabled=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
class FacultyOnboardingRehearsalIT {
    private static final String QUEUE = "/api/college/faculty-requests";
    private static final String APPROVE = """
            {"status":"APPROVED","employmentConfirmed":true}
            """;
    private static final String APPLICANT = "faculty-applicant@example.invalid";
    @Autowired private MockMvc http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtService jwt;
    @Autowired private EntityManager entities;

    @BeforeEach void verifyTargetAndRollbackContext() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(Boolean.TRUE, jdbc.queryForObject(FacultyRehearsalMigrationRunner.IDENTITY, Boolean.class));
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14"),
                jdbc.queryForList("SELECT version FROM public.flyway_schema_history WHERE success ORDER BY installed_rank",
                        String.class));
        assertEquals(14, jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history", Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM public.users) + (SELECT count(*) FROM public.colleges) +
                       (SELECT count(*) FROM public.faculty_access_requests)
                """, Integer.class));
    }

    @Test void collegeReviewBindsTheVerifiedFacultyAndEnablesOnlyTheirOwnAssignment() throws Exception {
        long alpha = id("INSERT INTO public.colleges(code,name,is_active) VALUES ('FAC-ALPHA','Faculty Alpha',true) RETURNING id");
        long beta = id("INSERT INTO public.colleges(code,name,is_active) VALUES ('FAC-BETA','Faculty Beta',true) RETURNING id");
        String admin = admin("alpha-admin@example.invalid", alpha, "COLLEGE_ADMIN");
        String other = admin("beta-admin@example.invalid", beta, "COLLEGE_ADMIN");
        String legacy = admin("legacy-admin@example.invalid", null, "ADMIN");
        String password = UUID.randomUUID().toString();
        http.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "fullName", "Faculty Applicant", "email", APPLICANT, "password", password,
                "role", "FACULTY", "collegeCode", "fac-alpha"))))
                .andExpect(status().isCreated());
        String faculty = login(password);
        http.perform(get("/api/auth/status").header("Authorization", faculty))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles").isEmpty())
                .andExpect(jsonPath("$.facultyRequest.status").value("PENDING"));
        http.perform(get("/api/faculty/assignments").header("Authorization", faculty)).andExpect(status().isForbidden());
        long applicant = id("SELECT id FROM public.users WHERE email=?", APPLICANT);
        long request = id("SELECT id FROM public.faculty_access_requests WHERE applicant_id=?", applicant);
        String decision = QUEUE + "/" + request + "/decision";
        http.perform(get(QUEUE).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(request));
        http.perform(get(QUEUE).header("Authorization", other))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        http.perform(post(decision).header("Authorization", other).contentType(MediaType.APPLICATION_JSON).content(APPROVE))
                .andExpect(status().isNotFound());
        http.perform(post("/api/admin/faculty-requests/" + request + "/decision")
                .header("Authorization", legacy).contentType(MediaType.APPLICATION_JSON).content(APPROVE))
                .andExpect(status().isNotFound());
        http.perform(post(decision).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"APPROVED\",\"employmentConfirmed\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FACULTY_EMPLOYMENT_UNCONFIRMED"));
        http.perform(post(decision).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(APPROVE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        http.perform(post(decision).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(APPROVE))
                .andExpect(status().isConflict());
        assertEquals(alpha, id("SELECT college_id FROM public.users WHERE id=? AND is_verified", applicant));
        assertEquals(List.of("FACULTY"), jdbc.queryForList("""
                SELECT r.name FROM public.roles r JOIN public.user_roles ur ON r.id=ur.role_id WHERE ur.user_id=?
                """, String.class, applicant));
        entities.clear();
        http.perform(get("/api/auth/status").header("Authorization", faculty))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("FACULTY"))
                .andExpect(jsonPath("$.facultyRequest.status").value("APPROVED"));
        http.perform(get("/api/platform/overview").header("Authorization", faculty)).andExpect(status().isForbidden());

        long department = id("INSERT INTO public.departments(college_id,code,name) VALUES (?,'SCI','Science') RETURNING id", alpha);
        long program = id("INSERT INTO public.academic_programs(college_id,department_id,code,name) VALUES (?,?,'BIO','Biology') RETURNING id",
                alpha, department);
        long year = id("INSERT INTO public.academic_years(college_id,code,name) VALUES (?,'2026-2027','2026–2027') RETURNING id", alpha);
        long semester = id("""
                INSERT INTO public.academic_semesters (college_id,program_id,academic_year_id,semester_number,name)
                VALUES (?,?,?,1,'First semester') RETURNING id
                """, alpha, program, year);
        long division = id("""
                INSERT INTO public.academic_divisions (college_id,program_id,academic_year_id,code,name)
                VALUES (?,?,?,'A','Division A') RETURNING id
                """, alpha, program, year);
        long subject = id("""
                INSERT INTO public.academic_subjects (college_id,program_id,semester_id,code,name)
                VALUES (?,?,?,'BIO','Biology Lab') RETURNING id
                """, alpha, program, semester);
        String profile = "/api/college/faculty/" + applicant;
        http.perform(get(profile).header("Authorization", other)).andExpect(status().isNotFound());
        http.perform(put(profile + "/department").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("departmentId", department)))).andExpect(status().isOk());
        http.perform(post(profile + "/assignments").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("departmentId", department, "programId", program,
                        "academicYearId", year, "semesterId", semester, "divisionId", division, "subjectId", subject))))
                .andExpect(status().isCreated());
        http.perform(get("/api/faculty/assignments").header("Authorization", login(password)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].subject").value("Biology Lab"));
    }

    private long id(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Long.class, parameters);
    }

    private String admin(String email, Long college, String role) {
        long user = id("""
                INSERT INTO public.users(full_name,email,password,college_id,is_active,is_verified)
                VALUES ('Synthetic reviewer',?,'unusable-for-password-auth',?,true,true) RETURNING id
                """, email, college);
        assertEquals(1, jdbc.update("INSERT INTO public.user_roles SELECT ?, id FROM public.roles WHERE name=?", user, role));
        return "Bearer " + jwt.generateToken(email, user);
    }

    private String login(String password) throws Exception {
        var result = http.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", APPLICANT, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json.readTree(result.getResponse().getContentAsString()).get("token").asString();
    }
}
