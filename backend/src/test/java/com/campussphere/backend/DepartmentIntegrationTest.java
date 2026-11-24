package com.campussphere.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.campussphere.backend.dto.DepartmentInput;
import com.campussphere.backend.security.JwtService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DepartmentIntegrationTest {
    private static final String PATH = "/api/college/departments";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entities;
    @Autowired private JwtService jwt;
    @Autowired private ObjectMapper mapper;

    private long alpha;
    private long beta;
    private AccountFixture alphaAdmin;
    private AccountFixture betaAdmin;

    @BeforeEach
    void setUp() {
        assertEquals("campus_sphere_test", jdbc.queryForObject("SELECT current_database()", String.class));
        alpha = college("DEPT_ALPHA");
        beta = college("DEPT_BETA");
        alphaAdmin = account("alpha-admin", alpha, true, "COLLEGE_ADMIN");
        betaAdmin = account("beta-admin", beta, true, "COLLEGE_ADMIN");
    }

    @Test
    void eachAdminCanManageTheirOwnDepartmentsAndReuseCodesAcrossColleges() throws Exception {
        long alphaId = departmentId(create(alphaAdmin, "cse", "Alpha Computing")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("CSE")));
        long betaId = departmentId(create(betaAdmin, "CSE", "Beta Computing")
                .andExpect(status().isCreated()));
        assertNotEquals(alphaId, betaId);
        assertEquals(alpha, storedCollege(alphaId));
        assertEquals(beta, storedCollege(betaId));

        list(alphaAdmin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Alpha Computing"));
        list(betaAdmin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Beta Computing"));
        fetch(alphaAdmin, alphaId).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alpha Computing"));
        update(alphaAdmin, alphaId, "{\"name\":\"Alpha Computer Science\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("CSE"))
                .andExpect(jsonPath("$.name").value("Alpha Computer Science"));
        fetch(alphaAdmin, alphaId).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alpha Computer Science"));
        create(alphaAdmin, "CsE", "Duplicate").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPARTMENT_CODE_CONFLICT"));
    }

    @Test
    void betaRecordsAndMissingRecordsReturnSame404WithoutMutation() throws Exception {
        long betaId = departmentId(create(betaAdmin, "CHEM", "Beta Chemistry").andExpect(status().isCreated()));
        var unavailable = fetch(alphaAdmin, betaId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEPARTMENT_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        fetch(alphaAdmin, Long.MAX_VALUE).andExpect(status().isNotFound()).andExpect(content().json(unavailable));
        update(alphaAdmin, betaId, "{\"name\":\"Cross-college edit\"}")
                .andExpect(status().isNotFound()).andExpect(content().json(unavailable));
        list(alphaAdmin).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        fetch(betaAdmin, betaId).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Beta Chemistry"));
    }

    @Test
    void forgedCollegeIdentifiersDoNotAffectServerScope() throws Exception {
        long alphaId = departmentId(create(alphaAdmin, "A", "Alpha Department").andExpect(status().isCreated()));
        long betaId = departmentId(create(betaAdmin, "B", "Beta Department").andExpect(status().isCreated()));
        mvc.perform(get(PATH).queryParam("collegeId", String.valueOf(beta)).header("Authorization", alphaAdmin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Alpha Department"));
        mvc.perform(get(PATH + "/{id}", betaId).queryParam("collegeId", String.valueOf(beta))
                        .header("Authorization", alphaAdmin.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DEPARTMENT_NOT_FOUND"));
        mvc.perform(post(PATH).header("Authorization", alphaAdmin.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"FORGED\",\"name\":\"Forged\",\"collegeId\":" + beta + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        update(alphaAdmin, alphaId, "{\"name\":\"Forged\",\"collegeId\":" + beta + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/college/{collegeId}/departments/{id}", beta, betaId)
                        .header("Authorization", alphaAdmin.bearer()))
                .andExpect(status().isForbidden());
        assertEquals(alpha, storedCollege(alphaId));
        assertEquals(beta, storedCollege(betaId));
        fetch(alphaAdmin, alphaId).andExpect(jsonPath("$.name").value("Alpha Department"));
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM public.departments", Long.class));
    }

    @Test
    void rejectsUnauthorizedRolesUnassignedInactiveAndDisabledAccounts() throws Exception {
        assertHttpDenied(account("student", alpha, true, "STUDENT"));
        assertHttpDenied(account("faculty", alpha, true, "FACULTY"));
        assertHttpDenied(account("legacy", null, true, "ADMIN"));
        assertHttpDenied(account("platform", null, true, "SUPER_ADMIN"));
        assertHttpDenied(account("unassigned", null, true, "COLLEGE_ADMIN"));
        assertHttpDenied(account("mixed", alpha, true, "ADMIN", "COLLEGE_ADMIN"));
        assertHttpDenied(account("mixed-platform", alpha, true, "SUPER_ADMIN", "COLLEGE_ADMIN"));

        list(alphaAdmin).andExpect(status().isOk());
        jdbc.update("UPDATE public.colleges SET is_active = false WHERE id = ?", alpha);
        entities.clear();
        assertHttpDenied(alphaAdmin);
        list(account("disabled", beta, false, "COLLEGE_ADMIN")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_AUTHENTICATION"));
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsEmptyAndMalformedChanges() throws Exception {
        long id = departmentId(create(alphaAdmin, "BIO", "Biology").andExpect(status().isCreated()));
        create(alphaAdmin, "BAD CODE", "Biology").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        update(alphaAdmin, id, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        update(alphaAdmin, id, "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        fetch(alphaAdmin, id).andExpect(jsonPath("$.name").value("Biology"));
    }

    @Test
    void postgresqlRejectsDepartmentWithNonexistentCollege() {
        var failure = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO public.departments (college_id, code, name)
                VALUES (?, 'INVALID', 'Invalid owner')
                """, Long.MAX_VALUE));
        assertTrue(failure.getMostSpecificCause().getMessage().contains("departments_college_id_fkey"));
    }

    @Test
    void postgresqlRejectsDepartmentWithoutCollege() {
        var failure = assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO public.departments (code, name) VALUES ('INVALID', 'Missing owner')
                """));
        assertTrue(failure.getMostSpecificCause().getMessage().contains("college_id"));
    }

    private void assertHttpDenied(AccountFixture account) throws Exception {
        list(account).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        create(account, "DENIED", "Denied").andExpect(status().isForbidden());
    }

    private ResultActions list(AccountFixture account) throws Exception {
        return mvc.perform(get(PATH).header("Authorization", account.bearer()));
    }

    private ResultActions fetch(AccountFixture account, long id) throws Exception {
        return mvc.perform(get(PATH + "/{id}", id).header("Authorization", account.bearer()));
    }

    private ResultActions create(AccountFixture account, String code, String name) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", account.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new DepartmentInput(code, name))));
    }

    private ResultActions update(AccountFixture account, long id, String json) throws Exception {
        return mvc.perform(patch(PATH + "/{id}", id).header("Authorization", account.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long departmentId(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).path("id").asLong();
    }

    private long storedCollege(long departmentId) {
        return jdbc.queryForObject("SELECT college_id FROM public.departments WHERE id = ?", Long.class, departmentId);
    }

    private long college(String code) {
        return jdbc.queryForObject("INSERT INTO public.colleges (code, name, is_active) VALUES (?, ?, TRUE) RETURNING id",
                Long.class, code, code);
    }

    private AccountFixture account(String name, Long collegeId, boolean active, String... roles) {
        String email = "department-" + name + "@example.invalid";
        long id = jdbc.queryForObject("""
                INSERT INTO public.users (full_name, email, password, is_active, is_verified, college_id)
                VALUES (?, ?, 'unused-test-fixture', ?, true, ?) RETURNING id
                """, Long.class, name, email, active, collegeId);
        for (String role : roles) {
            assertEquals(1, jdbc.update("""
                    INSERT INTO public.user_roles (user_id, role_id)
                    SELECT ?, id FROM public.roles WHERE name = ?
                    """, id, role));
        }
        return new AccountFixture("Bearer " + jwt.generateToken(email, id));
    }

    private record AccountFixture(String bearer) {}
}
