package com.campussphere.backend;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.controller.FacultyManagementController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.FacultyManagementService;
import com.campussphere.backend.service.FacultyManagementService.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = FacultyManagementController.class,
        properties = "logging.level.org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration=OFF")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class FacultyManagementHttpTest {
    private static final String BASE = "/api/college/faculty";
    private static final String MINE = "/api/faculty/assignments";
    private static final String ASSIGN = BASE + "/12/assignments";
    private static final String INPUT = """
            {"departmentId":2,"programId":3,"academicYearId":4,"semesterId":5,"divisionId":6,"subjectId":7}
            """;
    private final Account admin = account(1L, "COLLEGE_ADMIN");
    private final Account member = account(12L, "FACULTY");
    @Autowired MockMvc mvc;
    @MockitoBean FacultyManagementService service;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void enforcesSeparateRolesBeforeController() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(MINE)).andExpect(status().isUnauthorized());
        for (String role : List.of("SUPER_ADMIN", "ADMIN", "STUDENT", "FACULTY")) {
            mvc.perform(get(BASE).with(as(account(12L, role)))).andExpect(status().isForbidden());
            mvc.perform(post(ASSIGN).with(as(account(12L, role)))).andExpect(status().isForbidden());
        }
        mvc.perform(get(MINE).with(as(admin))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void rejectsForgedCollegeAndFacultyFieldsAndMissingSelections() throws Exception {
        for (String field : List.of("collegeId", "facultyId")) {
            mvc.perform(post(ASSIGN).with(as(admin)).contentType("application/json")
                    .content(INPUT.strip().replace("}", ",\"" + field + "\":111}")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post(ASSIGN).with(as(admin)).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void mapsListProfileDepartmentAssignmentAndEnd() throws Exception {
        var profile = new Member(12, "Faculty Example", "faculty@example.invalid", 2L, "Science");
        var assignment = new Assignment(99, "Science", "Biology", "2026", "Semester 1", "Division A", "Biology Lab", "BIO");
        when(service.list(admin)).thenReturn(List.of(profile));
        when(service.profile(admin, 12)).thenReturn(profile);
        when(service.changeDepartment(admin, 12, 2)).thenReturn(profile);
        when(service.assignments(admin, 12)).thenReturn(List.of(assignment));
        when(service.assign(admin, 12, new AssignmentInput(2, 3, 4, 5, 6, 7))).thenReturn(assignment);
        when(service.dashboard(member)).thenReturn(List.of(assignment));
        mvc.perform(get(BASE).with(as(admin))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(12));
        mvc.perform(get(BASE + "/12").with(as(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(12));
        mvc.perform(put(BASE + "/12/department").with(as(admin)).contentType("application/json")
                .content("{\"departmentId\":2}")).andExpect(status().isOk()).andExpect(jsonPath("$.departmentId").value(2));
        mvc.perform(get(ASSIGN).with(as(admin))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(99));
        mvc.perform(post(ASSIGN).with(as(admin)).contentType("application/json").content(INPUT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.subjectCode").value("BIO"));
        mvc.perform(delete(ASSIGN + "/99").with(as(admin))).andExpect(status().isNoContent());
        verify(service).end(admin, 12, 99);
        mvc.perform(get(MINE + "?facultyId=30&collegeId=200").with(as(member)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(99));
        verify(service).dashboard(member);
    }

    private static Account account(long id, String role) {
        return new Account(id, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
