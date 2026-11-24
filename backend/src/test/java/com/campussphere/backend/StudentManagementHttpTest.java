package com.campussphere.backend;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.controller.StudentManagementController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.StudentAffiliationService;
import com.campussphere.backend.service.StudentAffiliationService.Affiliation;
import com.campussphere.backend.service.StudentEnrollmentService;
import com.campussphere.backend.service.StudentEnrollmentService.*;
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

@WebMvcTest(controllers = StudentManagementController.class,
        properties = "logging.level.org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration=OFF")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class StudentManagementHttpTest {
    private static final String BASE = "/api/college/students";
    private static final String QUEUE = "/api/college/student-affiliation-requests";
    private static final String MY_REQUEST = "/api/auth/student-affiliation";
    private static final String MINE = "/api/student/enrollment";
    private static final String ENROLL = BASE + "/12/enrollments";
    private static final String INPUT = """
            {"departmentId":2,"programId":3,"academicYearId":4,"semesterId":5,"divisionId":6}
            """;
    private final Account admin = account(1, "COLLEGE_ADMIN");
    private final Account student = account(12, "STUDENT");
    private final Account pending = new Account(12L, false, true, Set.of(), null);
    @Autowired MockMvc mvc;
    @MockitoBean StudentEnrollmentService enrollment;
    @MockitoBean StudentAffiliationService affiliation;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void protectsDirectoryEnrollmentApprovalAndStudentDashboardByRole() throws Exception {
        for (String route : List.of(BASE, MINE, MY_REQUEST, QUEUE)) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized());
        }
        for (String role : List.of("STUDENT", "FACULTY", "ADMIN", "SUPER_ADMIN")) {
            mvc.perform(get(BASE).with(as(account(9, role)))).andExpect(status().isForbidden());
            mvc.perform(post(ENROLL).with(as(account(9, role)))).andExpect(status().isForbidden());
            mvc.perform(post(QUEUE + "/18/decision").with(as(account(9, role)))).andExpect(status().isForbidden());
        }
        mvc.perform(get(MINE).with(as(admin))).andExpect(status().isForbidden());
        mvc.perform(get(MINE).with(as(pending))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("VERIFICATION_REQUIRED"));
        verifyNoInteractions(enrollment, affiliation);
    }

    @Test void validatesFieldsAndRejectsForgedOwnerIds() throws Exception {
        mvc.perform(post(ENROLL).with(as(admin)).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        for (String field : List.of("collegeId", "studentId")) {
            mvc.perform(post(ENROLL).with(as(admin)).contentType("application/json")
                    .content(INPUT.strip().replace("}", ",\"" + field + "\":777}")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post(MY_REQUEST).with(as(pending)).contentType("application/json")
                .content("{\"collegeCode\":\"_BAD\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(QUEUE + "/90/decision").with(as(admin)).contentType("application/json")
                .content("{\"admissionConfirmed\":true}")).andExpect(status().isBadRequest());
        verifyNoInteractions(enrollment, affiliation);
    }

    @Test void connectsAuthorizedStudentAndAdminRoutesToScopedServiceContracts() throws Exception {
        var profile = new Member(12, "Student Sample", "student@example.invalid", 2L, "Science");
        var row = new Enrollment(99, "Alpha", "Science", "Biology", "2026", "First", "Division A", true);
        when(enrollment.list(admin)).thenReturn(List.of(profile));
        when(enrollment.profile(admin, 12)).thenReturn(profile);
        when(enrollment.changeDepartment(admin, 12, 2)).thenReturn(profile);
        when(enrollment.history(admin, 12)).thenReturn(List.of(row));
        when(enrollment.enroll(admin, 12, new EnrollmentInput(2, 3, 4, 5, 6))).thenReturn(row);
        when(enrollment.current(student)).thenReturn(List.of(row));
        mvc.perform(get(BASE).with(as(admin))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(12));
        mvc.perform(get(BASE + "/12").with(as(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(12));
        mvc.perform(put(BASE + "/12/department").with(as(admin)).contentType("application/json")
                .content("{\"departmentId\":2}")).andExpect(status().isOk()).andExpect(jsonPath("$.departmentId").value(2));
        mvc.perform(get(ENROLL).with(as(admin))).andExpect(status().isOk()).andExpect(jsonPath("$[0].current").value(true));
        mvc.perform(post(ENROLL).with(as(admin)).contentType("application/json").content(INPUT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.division").value("Division A"));
        mvc.perform(delete(ENROLL + "/99").with(as(admin))).andExpect(status().isNoContent());
        verify(enrollment).end(admin, 12, 99);
        mvc.perform(get(MINE + "?studentId=80&collegeId=777").with(as(student))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(99));
        verify(enrollment).current(student);
    }

    @Test void verifiedAdmissionIsExplicitAndPendingStudentMayCheckOwnRequest() throws Exception {
        var request = new Affiliation(90, "PENDING", "Alpha", "ALPHA", "Student Sample", "student@example.invalid");
        when(affiliation.mine(pending)).thenReturn(List.of(request));
        when(affiliation.submit(pending, "ALPHA")).thenReturn(request);
        when(affiliation.pending(admin)).thenReturn(List.of(request));
        when(affiliation.decide(admin, 90, true, false)).thenThrow(ApiError.STUDENT_ADMISSION_UNCONFIRMED.exception());
        mvc.perform(get(MY_REQUEST).with(as(pending))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(90));
        mvc.perform(post(MY_REQUEST).with(as(pending)).contentType("application/json")
                .content("{\"collegeCode\":\"ALPHA\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get(QUEUE).with(as(admin))).andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("PENDING"));
        mvc.perform(post(QUEUE + "/90/decision").with(as(admin)).contentType("application/json")
                .content("{\"approved\":true,\"admissionConfirmed\":false}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("STUDENT_ADMISSION_UNCONFIRMED"));
    }

    private static Account account(long id, String role) {
        return new Account(id, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
