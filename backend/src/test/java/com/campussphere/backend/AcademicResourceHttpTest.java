package com.campussphere.backend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.campussphere.backend.controller.AcademicResourceController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.AcademicResources;
import com.campussphere.backend.service.AcademicResources.*;
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

@WebMvcTest(controllers = AcademicResourceController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class AcademicResourceHttpTest {
    @Autowired MockMvc http;
    @MockitoBean AcademicResources resources;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void eachRoleIsLimitedToItsOwnArea() throws Exception {
        http.perform(get("/api/student/resources/papers")).andExpect(status().isUnauthorized());
        http.perform(get("/api/faculty/resources/subjects").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(get("/api/student/resources/syllabus").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/resources/papers").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/resources/stats").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/resources/coverage").with(as(account("SUPER_ADMIN")))).andExpect(status().isForbidden());
        http.perform(post("/api/college/resources/papers/4/moderation").with(as(account("STUDENT")))
                .contentType("application/json").content("{\"action\":\"HIDE\",\"note\":\"nope\"}"))
                .andExpect(status().isForbidden());
        http.perform(multipart("/api/faculty/resources/papers").file(pdf()).param("subjectId", "3")
                .param("examType", "FINAL").param("examYear", "2025").param("title", "T")
                .with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(get("/api/student/resources/notes").with(as(account("STUDENT")))).andExpect(status().is4xxClientError());
        verifyNoInteractions(resources);
    }

    @Test void clientSuppliedCollegeStudentOrFacultyIdsAreIgnored() throws Exception {
        var student = account("STUDENT");
        when(resources.list(any(), any(), any(), any())).thenReturn(List.of());
        http.perform(get("/api/student/resources/papers?collegeId=99&studentId=77&facultyId=8&status=HIDDEN"
                + "&subjectId=7&semesterId=2&examYear=2025&examType=FINAL&q=trees").with(as(student)))
                .andExpect(status().isOk());
        verify(resources).list(student, CollegeRole.STUDENT, Kind.PAPER,
                new Filter(7L, 2L, 2025, "FINAL", null, null, "trees", 0));
    }

    @Test void invalidFiltersAndUnknownFieldsAreRejected() throws Exception {
        var admin = account("COLLEGE_ADMIN");
        http.perform(get("/api/college/resources/papers?examType=QUIZ").with(as(admin))).andExpect(status().isBadRequest());
        http.perform(get("/api/college/resources/syllabus?status=ANY").with(as(admin))).andExpect(status().isBadRequest());
        http.perform(get("/api/college/resources/syllabus?subjectId=abc").with(as(admin))).andExpect(status().isBadRequest());
        http.perform(patch("/api/faculty/resources/syllabus/4").with(as(account("FACULTY"))).contentType("application/json")
                .content("{\"title\":\"T\",\"versionLabel\":\"2026\",\"collegeId\":99}")).andExpect(status().isBadRequest());
        verifyNoInteractions(resources);
    }

    @Test void uploadNeedsAFileAndPassesOnlyTheFormFields() throws Exception {
        var faculty = account("FACULTY");
        http.perform(multipart("/api/faculty/resources/syllabus").param("subjectId", "3").param("title", "Syllabus")
                .param("versionLabel", "2026").with(as(faculty))).andExpect(status().isBadRequest());
        verifyNoInteractions(resources);

        when(resources.uploadSyllabus(eq(faculty), eq(3L), any(), any())).thenReturn(null);
        http.perform(multipart("/api/faculty/resources/syllabus").file(pdf()).param("subjectId", "3")
                .param("title", "Syllabus").param("versionLabel", "2026-27").param("effectiveFrom", "2026-06-01")
                .param("collegeId", "99").param("facultyId", "77").with(as(faculty)))
                .andExpect(status().isCreated());
        verify(resources).uploadSyllabus(eq(faculty), eq(3L),
                eq(new SyllabusFields("Syllabus", "", "2026-27", java.time.LocalDate.of(2026, 6, 1))), any());
    }

    @Test void serviceFailuresKeepTheirStatusAndCode() throws Exception {
        when(resources.detail(any(), eq(CollegeRole.STUDENT), eq(Kind.SYLLABUS), eq(9L)))
                .thenThrow(ApiError.RESOURCE_NOT_FOUND.exception());
        http.perform(get("/api/student/resources/syllabus/9").with(as(account("STUDENT"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        when(resources.uploadPaper(any(), any(), any(), any())).thenThrow(ApiError.RESOURCE_FILE_INVALID.exception());
        http.perform(multipart("/api/faculty/resources/papers").file(pdf()).param("subjectId", "3")
                .param("examType", "FINAL").param("examYear", "2025").param("title", "T").with(as(account("FACULTY"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESOURCE_FILE_INVALID"));
        when(resources.moderate(any(), eq(Kind.PAPER), eq(4L), any())).thenThrow(ApiError.RESOURCE_STATE_CONFLICT.exception());
        http.perform(post("/api/college/resources/papers/4/moderation").with(as(account("COLLEGE_ADMIN")))
                .contentType("application/json").content("{\"action\":\"RESTORE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"));
    }

    @Test void downloadsAreUncachedAttachmentsThatCannotBeSniffed(@TempDir Path dir) throws Exception {
        Path stored = Files.write(dir.resolve("stored"), "%PDF-1.4\n%%EOF".getBytes());
        when(resources.download(any(), eq(CollegeRole.STUDENT), eq(Kind.PAPER), eq(4L)))
                .thenReturn(new FileDownload(stored, "DS Final 2025.pdf", "application/pdf", Files.size(stored)));
        http.perform(get("/api/student/resources/papers/4/file").with(as(account("STUDENT"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes(Files.readAllBytes(stored)));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes());
    }

    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
