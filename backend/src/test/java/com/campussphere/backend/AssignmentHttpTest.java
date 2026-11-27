package com.campussphere.backend;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.campussphere.backend.controller.AssignmentController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.AssignmentNotices;
import com.campussphere.backend.service.AssignmentSubmissions;
import com.campussphere.backend.service.AssignmentSubmissions.Evaluation;
import com.campussphere.backend.service.Assignments;
import com.campussphere.backend.service.Assignments.AdminFilter;
import com.campussphere.backend.service.Assignments.StudentTab;
import com.campussphere.backend.service.StudyMaterials.FileDownload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Route security and request handling of the Assignments (homework) API, with the services mocked. */
@WebMvcTest(controllers = AssignmentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class AssignmentHttpTest {
    @Autowired MockMvc http;
    @MockitoBean Assignments assignments;
    @MockitoBean AssignmentSubmissions submissions;
    @MockitoBean AssignmentNotices notices;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void eachRoleIsLimitedToItsOwnArea() throws Exception {
        http.perform(get("/api/student/homework")).andExpect(status().isUnauthorized());
        http.perform(get("/api/faculty/homework").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(get("/api/student/homework").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/homework").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/homework/stats").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        // Super Admin and the legacy ADMIN role get no college assignment data.
        for (String role : List.of("SUPER_ADMIN", "ADMIN")) {
            http.perform(get("/api/college/homework/stats").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(get("/api/faculty/homework").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(get("/api/student/homework").with(as(account(role)))).andExpect(status().isForbidden());
        }
        // College Admins cannot create, mark or download student work.
        http.perform(post("/api/faculty/homework").with(as(account("COLLEGE_ADMIN")))
                .contentType("application/json").content("{\"title\":\"x\"}")).andExpect(status().isForbidden());
        http.perform(put("/api/faculty/homework/5/submissions/8/evaluation").with(as(account("COLLEGE_ADMIN")))
                .contentType("application/json").content("{\"marks\":5}")).andExpect(status().isForbidden());
        http.perform(get("/api/college/homework/5/submissions/8/file").with(as(account("COLLEGE_ADMIN"))))
                .andExpect(status().isNotFound());
        // Students cannot mark their own work.
        http.perform(put("/api/faculty/homework/5/submissions/8/evaluation").with(as(account("STUDENT")))
                .contentType("application/json").content("{\"marks\":20}")).andExpect(status().isForbidden());
        verifyNoInteractions(assignments, submissions, notices);
    }

    @Test void clientSuppliedIdsAreIgnored() throws Exception {
        var student = account("STUDENT");
        when(assignments.studentList(any(), any(), anyInt())).thenReturn(List.of());
        http.perform(get("/api/student/homework?tab=PENDING&collegeId=99&studentId=77&divisionId=5").with(as(student)))
                .andExpect(status().isOk());
        verify(assignments).studentList(student, StudentTab.PENDING, 0);
        var admin = account("COLLEGE_ADMIN");
        when(assignments.adminStats(any(), any())).thenReturn(null);
        http.perform(get("/api/college/homework/stats?collegeId=99&programId=4&from=2026-12-01").with(as(admin)))
                .andExpect(status().isOk());
        verify(assignments).adminStats(admin, new AdminFilter(4L, null, null, null, null, null,
                java.time.LocalDate.of(2026, 12, 1), null, null, null, 0));
    }

    @Test void invalidRequestsAreRejectedBeforeTheServices() throws Exception {
        http.perform(get("/api/student/homework?tab=SECRET").with(as(account("STUDENT")))).andExpect(status().isBadRequest());
        http.perform(get("/api/faculty/homework?status=DELETED").with(as(account("FACULTY")))).andExpect(status().isBadRequest());
        http.perform(get("/api/college/homework?status=DRAFT").with(as(account("COLLEGE_ADMIN")))).andExpect(status().isBadRequest());
        http.perform(get("/api/college/homework?from=yesterday").with(as(account("COLLEGE_ADMIN")))).andExpect(status().isBadRequest());
        http.perform(post("/api/faculty/homework").with(as(account("FACULTY"))).contentType("application/json")
                .content("{\"title\":\"Essay\",\"collegeId\":99}")).andExpect(status().isBadRequest());
        http.perform(put("/api/faculty/homework/5/submissions/8/evaluation").with(as(account("FACULTY")))
                .contentType("application/json").content("{\"marks\":5,\"studentId\":3}")).andExpect(status().isBadRequest());
        http.perform(multipart("/api/student/homework/5/submissions").with(as(account("STUDENT"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(assignments, submissions, notices);
    }

    @Test void markingAndSubmittingPassOnlyTheBodyAndTheSignedInAccount() throws Exception {
        var faculty = account("FACULTY");
        http.perform(put("/api/faculty/homework/5/submissions/8/evaluation").with(as(faculty))
                .contentType("application/json").content("{\"marks\":18.5,\"feedback\":\"Good\"}")).andExpect(status().isOk());
        verify(submissions).evaluate(faculty, 5, 8, new Evaluation(new BigDecimal("18.5"), "Good"));
        var student = account("STUDENT");
        var file = new MockMultipartFile("file", "essay.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes());
        http.perform(multipart("/api/student/homework/5/submissions").file(file).param("comment", "Done")
                .with(as(student))).andExpect(status().isCreated());
        verify(submissions).submit(eq(student), eq(5L), any(), eq("Done"));
    }

    @Test void downloadsArePrivateAttachments(@TempDir Path dir) throws Exception {
        Path stored = Files.writeString(dir.resolve("random-key"), "%PDF-1.4\n%%EOF");
        when(submissions.file(any(), eq(CollegeRole.STUDENT), eq(5L), eq(8L)))
                .thenReturn(new FileDownload(stored, "essay.pdf", "application/pdf", Files.size(stored)));
        http.perform(get("/api/student/homework/5/submissions/8/file").with(as(account("STUDENT"))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("essay.pdf")));
    }

    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
