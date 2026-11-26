package com.campussphere.backend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.campussphere.backend.controller.StudyMaterialController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.StudyMaterials;
import com.campussphere.backend.service.StudyMaterials.*;
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

@WebMvcTest(controllers = StudyMaterialController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class StudyMaterialHttpTest {
    @Autowired MockMvc http;
    @MockitoBean StudyMaterials materials;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void eachRoleIsLimitedToItsOwnMaterialArea() throws Exception {
        http.perform(get("/api/student/materials")).andExpect(status().isUnauthorized());
        http.perform(get("/api/faculty/materials").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(get("/api/student/materials").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/materials").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/materials").with(as(account("SUPER_ADMIN")))).andExpect(status().isForbidden());
        http.perform(post("/api/college/materials/4/moderation").with(as(account("STUDENT")))
                .contentType("application/json").content("{\"action\":\"HIDE\",\"note\":\"nope\"}"))
                .andExpect(status().isForbidden());
        http.perform(multipart("/api/faculty/materials").file(pdf()).param("assignmentId", "3")
                .param("title", "T").param("type", "NOTES").with(as(account("COLLEGE_ADMIN"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(materials);
    }

    @Test void clientSuppliedCollegeOrFacultyIdsAreIgnoredForStudents() throws Exception {
        var student = account("STUDENT");
        when(materials.list(any(), any(), any())).thenReturn(List.of());
        http.perform(get("/api/student/materials?collegeId=99&facultyId=77&divisionId=5&subjectId=7&type=NOTES&q=cell")
                .with(as(student))).andExpect(status().isOk());
        verify(materials).list(student, CollegeRole.STUDENT, new Filter(7L, null, null, "NOTES", null, "cell"));
    }

    @Test void invalidFiltersAndModerationRequestsAreRejected() throws Exception {
        http.perform(get("/api/college/materials?type=VIDEO").with(as(account("COLLEGE_ADMIN"))))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/college/materials?status=ANY").with(as(account("COLLEGE_ADMIN"))))
                .andExpect(status().isBadRequest());
        http.perform(get("/api/college/materials?subjectId=abc").with(as(account("COLLEGE_ADMIN"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(materials);
    }

    @Test void uploadNeedsAFileAndPassesTheMultipartFieldsToTheService() throws Exception {
        var faculty = account("FACULTY");
        http.perform(multipart("/api/faculty/materials").param("assignmentId", "3").param("title", "Unit 1")
                .param("type", "NOTES").with(as(faculty))).andExpect(status().isBadRequest());
        verifyNoInteractions(materials);

        when(materials.upload(eq(faculty), eq(3L), any(), any())).thenReturn(sample("PUBLISHED"));
        http.perform(multipart("/api/faculty/materials").file(pdf()).param("assignmentId", "3")
                .param("title", "Unit 1").param("description", "Cells").param("topic", "Unit 1").param("type", "NOTES")
                .param("collegeId", "99").with(as(faculty)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        verify(materials).upload(eq(faculty), eq(3L), eq(new Fields("Unit 1", "Cells", "Unit 1", "NOTES")), any());
    }

    @Test void serviceFailuresKeepTheirStatusAndCode() throws Exception {
        when(materials.detail(any(), eq(CollegeRole.STUDENT), eq(9L))).thenThrow(ApiError.MATERIAL_NOT_FOUND.exception());
        http.perform(get("/api/student/materials/9").with(as(account("STUDENT"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MATERIAL_NOT_FOUND"));
        when(materials.moderate(any(), eq(4L), any())).thenThrow(ApiError.MATERIAL_STATE_CONFLICT.exception());
        http.perform(post("/api/college/materials/4/moderation").with(as(account("COLLEGE_ADMIN")))
                .contentType("application/json").content("{\"action\":\"RESTORE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MATERIAL_STATE_CONFLICT"));
        when(materials.upload(any(), anyLong(), any(), any())).thenThrow(ApiError.MATERIAL_FILE_INVALID.exception());
        http.perform(multipart("/api/faculty/materials").file(pdf()).param("assignmentId", "3")
                .param("title", "T").param("type", "NOTES").with(as(account("FACULTY"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MATERIAL_FILE_INVALID"));
    }

    @Test void downloadsAreAttachmentsThatCannotBeSniffedOrCached(@TempDir Path dir) throws Exception {
        Path stored = Files.write(dir.resolve("stored"), "%PDF-1.4\n%%EOF".getBytes());
        when(materials.download(any(), eq(CollegeRole.STUDENT), eq(4L)))
                .thenReturn(new FileDownload(stored, "Unit 1 notes.pdf", "application/pdf", Files.size(stored)));
        http.perform(get("/api/student/materials/4/file").with(as(account("STUDENT"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes(Files.readAllBytes(stored)));
    }

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("file", "notes.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes());
    }

    private static Material sample(String status) {
        var now = OffsetDateTime.parse("2026-11-26T10:00:00+05:30");
        return new Material(4, "Unit 1", "Cells", "Unit 1", "NOTES", 7, "Biology", 5, "A", "First", "BSc", "2026-2027",
                null, "Synthetic Faculty", 3, "notes.pdf", "pdf", "application/pdf", 14, status, null, now, now, true, false);
    }

    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
