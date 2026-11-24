package com.campussphere.backend;

import com.campussphere.backend.controller.ProgramController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.service.ProgramService;
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

@WebMvcTest(controllers = ProgramController.class,
        properties = "logging.level.org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration=OFF")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class ProgramHttpTest {
    private static final String BASE = "/api/college/departments/42/programs";
    @Autowired MockMvc mvc;
    @MockitoBean ProgramService programs;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void nestedRoutesRequireCollegeAdmin() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        for (String role : new String[]{"SUPER_ADMIN", "ADMIN", "FACULTY", "STUDENT"}) {
            mvc.perform(get(BASE).with(user("fixture").roles(role))).andExpect(status().isForbidden());
            mvc.perform(post(BASE).with(user("fixture").roles(role))).andExpect(status().isForbidden());
            mvc.perform(patch(BASE + "/8").with(user("fixture").roles(role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(programs);
    }

    @Test void rejectsBodyOwnershipFieldsAndInvalidCodes() throws Exception {
        for (String input : new String[]{
                "{\"code\":\"CS\",\"name\":\"Computing\",\"collegeId\":10}",
                "{\"code\":\"CS\",\"name\":\"Computing\",\"departmentId\":99}",
                "{\"code\":\"_invalid\",\"name\":\"Computing\"}"}) {
            mvc.perform(post(BASE).with(user("fixture").roles("COLLEGE_ADMIN"))
                    .contentType("application/json").content(input))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(programs);
    }

    @Test void exposesExistingStyleListCreateAndUpdateResponses() throws Exception {
        var row = new ProgramService.Program(8L, "CS", "Computing");
        when(programs.list(isNull(), eq(42L))).thenReturn(java.util.List.of(row));
        when(programs.create(isNull(), eq(42L), any())).thenReturn(row);
        when(programs.update(isNull(), eq(42L), eq(8L), any())).thenReturn(row);
        var admin = user("fixture").roles("COLLEGE_ADMIN");
        mvc.perform(get(BASE).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("CS"));
        mvc.perform(post(BASE).with(admin).contentType("application/json").content("{\"code\":\"CS\",\"name\":\"Computing\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(8));
        mvc.perform(patch(BASE + "/8").with(admin).contentType("application/json").content("{\"name\":\"Computing\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(8));
    }
}
