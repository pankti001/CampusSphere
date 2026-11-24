package com.campussphere.backend;

import java.util.List;

import com.campussphere.backend.controller.AcademicOfferingController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.service.AcademicOfferingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.campussphere.backend.service.AcademicOfferingService.Type.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AcademicOfferingController.class,
        properties = "logging.level.org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration=OFF")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class AcademicOfferingHttpTest {
    private static final String DIVISIONS = "/api/college/programs/2/academic-years/4/divisions";
    private static final String SUBJECTS = "/api/college/programs/2/semesters/9/subjects";
    @Autowired MockMvc mvc;
    @MockitoBean AcademicOfferingService service;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void requiresCollegeAdminForBothRouteFamilies() throws Exception {
        for (String path : List.of(DIVISIONS, SUBJECTS)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for (String role : List.of("SUPER_ADMIN", "ADMIN", "FACULTY", "STUDENT")) {
                var principal = user("fixture").roles(role);
                mvc.perform(get(path).with(principal)).andExpect(status().isForbidden());
                mvc.perform(post(path).with(principal)).andExpect(status().isForbidden());
                mvc.perform(patch(path + "/21").with(principal)).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(service);
    }

    @Test void rejectsOwnershipFieldsAndMalformedCodes() throws Exception {
        var principal = user("fixture").roles("COLLEGE_ADMIN");
        for (String field : List.of("collegeId", "programId", "semesterId", "academicYearId")) {
            mvc.perform(post(SUBJECTS).with(principal).contentType("application/json").content(
                    "{\"code\":\"BIO\",\"name\":\"Biology\",\"" + field + "\":77}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post(DIVISIONS).with(principal).contentType("application/json")
                .content("{\"code\":\"_A\",\"name\":\"Division A\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void listCreateAndPatchReturnCatalogRowsForBothRoutes() throws Exception {
        var row = new AcademicOfferingService.Offering(21, "CODE", "Display Name");
        var principal = user("fixture").roles("COLLEGE_ADMIN");
        for (var type : List.of(DIVISION, SUBJECT)) {
            long parent = type == DIVISION ? 4L : 9L;
            String path = type == DIVISION ? DIVISIONS : SUBJECTS;
            when(service.list(isNull(), eq(type), eq(2L), eq(parent))).thenReturn(List.of(row));
            when(service.create(isNull(), eq(type), eq(2L), eq(parent), any())).thenReturn(row);
            when(service.update(isNull(), eq(type), eq(2L), eq(parent), eq(21L), any())).thenReturn(row);
            mvc.perform(get(path).with(principal)).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("CODE"));
            mvc.perform(post(path).with(principal).contentType("application/json")
                    .content("{\"code\":\"CODE\",\"name\":\"Display Name\"}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(21));
            mvc.perform(patch(path + "/21").with(principal).contentType("application/json")
                    .content("{\"name\":\"Display Name\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(21));
        }
    }
}
