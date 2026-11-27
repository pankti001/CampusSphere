package com.campussphere.backend;

import java.util.Set;

import com.campussphere.backend.controller.CollegeAdminController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.CollegeAccounts;
import com.campussphere.backend.service.CollegeAccounts.*;
import com.campussphere.backend.service.CollegeOverview;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CollegeAdminController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class CollegeAdminHttpTest {
    @Autowired MockMvc http;
    @MockitoBean CollegeOverview overview;
    @MockitoBean CollegeAccounts accounts;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void onlyCollegeAdminsReachTheseEndpoints() throws Exception {
        http.perform(get("/api/college/overview")).andExpect(status().isUnauthorized());
        for (var role : new String[]{"FACULTY", "STUDENT", "SUPER_ADMIN"}) {
            http.perform(get("/api/college/overview").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(get("/api/college/profile").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(put("/api/college/accounts/faculty/4/active").with(as(account(role)))
                    .contentType("application/json").content("{\"active\":false}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(overview, accounts);
    }

    @Test void adminRequestsUseTheSignedInAccountNotClientIds() throws Exception {
        var admin = account("COLLEGE_ADMIN");
        http.perform(get("/api/college/overview?collegeId=99").with(as(admin))).andExpect(status().isOk());
        verify(overview).counts(admin);
        http.perform(get("/api/college/accounts/students?active=false&q=asha&collegeId=99").with(as(admin))).andExpect(status().isOk());
        verify(accounts).list(admin, Kind.STUDENT, false, "asha");
        http.perform(patch("/api/college/accounts/faculty/4").with(as(admin)).contentType("application/json")
                .content("{\"fullName\":\"Krishna\",\"phone\":\"9876543210\",\"collegeId\":99}")).andExpect(status().isOk());
        verify(accounts).update(admin, Kind.FACULTY, 4L, new Details("Krishna", "9876543210"));
        http.perform(put("/api/college/accounts/students/5/active").with(as(admin)).contentType("application/json")
                .content("{\"active\":true}")).andExpect(status().isOk());
        verify(accounts).setActive(admin, Kind.STUDENT, 5L, new Status(true));
    }

    @Test void unknownKindsAndErrorsAreReported() throws Exception {
        var admin = account("COLLEGE_ADMIN");
        http.perform(get("/api/college/accounts/admins").with(as(admin))).andExpect(status().isNotFound());
        when(accounts.get(any(), eq(Kind.FACULTY), eq(9L))).thenThrow(ApiError.FACULTY_NOT_FOUND.exception());
        http.perform(get("/api/college/accounts/faculty/9").with(as(admin)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FACULTY_NOT_FOUND"));
    }

    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
