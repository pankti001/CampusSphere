package com.campussphere.backend;

import java.util.List;

import com.campussphere.backend.controller.AcademicPeriodController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.service.AcademicPeriodService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AcademicPeriodController.class,
        properties = "logging.level.org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration=OFF")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class AcademicPeriodHttpTest {
    private static final String YEARS = "/api/college/academic-years";
    private static final String SEMESTERS = "/api/college/programs/2/academic-years/4/semesters";
    @Autowired MockMvc mvc;
    @MockitoBean AcademicPeriodService service;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void bothEndpointsDenyAnonymousAndAllOtherRoles() throws Exception {
        for (String route : List.of(YEARS, SEMESTERS)) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
            for (String role : List.of("SUPER_ADMIN","ADMIN","FACULTY","STUDENT")) {
                var principal = user("fixture").roles(role);
                mvc.perform(get(route).with(principal)).andExpect(status().isForbidden());
                mvc.perform(post(route).with(principal)).andExpect(status().isForbidden());
                mvc.perform(patch(route + "/9").with(principal)).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(service);
    }

    @Test void ownershipFieldsAreRejectedBeforeServiceCall() throws Exception {
        var principal = user("fixture").roles("COLLEGE_ADMIN");
        for (String field : List.of("collegeId","programId","academicYearId")) {
            mvc.perform(post(SEMESTERS).with(principal).contentType("application/json").content(
                    "{\"code\":\"1\",\"name\":\"One\",\"" + field + "\":77}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(service);
    }

    @Test void nestedRoutesReturnCatalogRowsWithExpectedStatuses() throws Exception {
        var year = new AcademicPeriodService.Period(4,"2026-2027","Year");
        var semester = new AcademicPeriodService.Period(9,"1","First Semester");
        when(service.years(isNull())).thenReturn(List.of(year));
        when(service.createYear(isNull(),any())).thenReturn(year);
        when(service.updateYear(isNull(),eq(4L),any())).thenReturn(year);
        when(service.semesters(isNull(),eq(2L),eq(4L))).thenReturn(List.of(semester));
        when(service.createSemester(isNull(),eq(2L),eq(4L),any())).thenReturn(semester);
        when(service.updateSemester(isNull(),eq(2L),eq(4L),eq(9L),any())).thenReturn(semester);

        var principal = user("fixture").roles("COLLEGE_ADMIN");
        mvc.perform(get(YEARS).with(principal)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(4));
        mvc.perform(post(YEARS).with(principal).contentType("application/json")
                .content("{\"code\":\"2026-2027\",\"name\":\"Year\"}"))
                .andExpect(status().isCreated());
        mvc.perform(patch(YEARS + "/4").with(principal).contentType("application/json").content("{\"name\":\"Year\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(SEMESTERS).with(principal)).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("1"));
        mvc.perform(post(SEMESTERS).with(principal).contentType("application/json")
                .content("{\"code\":\"1\",\"name\":\"First Semester\"}"))
                .andExpect(status().isCreated());
        mvc.perform(patch(SEMESTERS + "/9").with(principal).contentType("application/json").content("{\"name\":\"First Semester\"}"))
                .andExpect(status().isOk());
    }
}
