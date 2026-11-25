package com.campussphere.backend;

import java.time.LocalDate;
import java.util.Set;

import com.campussphere.backend.controller.AttendanceController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.AttendanceReports;
import com.campussphere.backend.service.AttendanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static com.campussphere.backend.dto.AttendanceModels.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AttendanceController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class AttendanceHttpTest {
    @Autowired MockMvc http;
    @MockitoBean AttendanceService attendance;
    @MockitoBean AttendanceReports reports;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void rolesCannotReadOrWriteAnotherAttendanceArea() throws Exception {
        http.perform(get("/api/faculty/attendance/assignments")).andExpect(status().isUnauthorized());
        http.perform(post("/api/faculty/attendance/sessions/7/submit").with(as(account("STUDENT"))))
                .andExpect(status().isForbidden());
        http.perform(get("/api/student/attendance").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/attendance/reports").with(as(account("SUPER_ADMIN")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/attendance/reports").with(as(account("ADMIN")))).andExpect(status().isForbidden());
        verifyNoInteractions(attendance, reports);
    }

    @Test void studentIdentityIsTakenFromThePrincipal() throws Exception {
        var student = account("STUDENT");
        http.perform(get("/api/student/attendance?collegeId=99&studentId=81&subjectId=7&from=2026-11-25").with(as(student)))
                .andExpect(status().isOk());
        verify(reports).student(student, new Filter(LocalDate.of(2026, 11, 25), null, null, null, null, null, 7L, null, null, 0));
    }

    @Test void requiresRevisionAndCorrectionReasonBeforeEnteringService() throws Exception {
        http.perform(post("/api/faculty/attendance/sessions/7/submit").with(as(account("FACULTY")))
                .contentType("application/json").content("{\"records\":[]}")).andExpect(status().isBadRequest());
        http.perform(post("/api/college/attendance/sessions/7/records/8/corrections").with(as(account("COLLEGE_ADMIN")))
                .contentType("application/json").content("{\"revision\":1,\"status\":\"LATE\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(attendance);
    }

    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
