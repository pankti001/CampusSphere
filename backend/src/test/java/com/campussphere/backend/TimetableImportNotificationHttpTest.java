package com.campussphere.backend;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.controller.FacultyNotificationController;
import com.campussphere.backend.controller.TimetableImportController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.FacultyNotifications;
import com.campussphere.backend.service.TimetableImports;
import com.campussphere.backend.service.TimetableImports.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {TimetableImportController.class, FacultyNotificationController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class TimetableImportNotificationHttpTest {
    @Autowired MockMvc http;
    @MockitoBean TimetableImports imports;
    @MockitoBean FacultyNotifications notifications;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    private static final MockMultipartFile FILE = new MockMultipartFile("file", "week.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{'P', 'K', 3, 4});

    @Test void importIsForCollegeAdminsAndNotificationsForFacultyOnly() throws Exception {
        http.perform(multipart("/api/college/timetables/import/preview").file(FILE).param("semesterId", "21")
                .with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/timetables/import/template").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(post("/api/college/timetables/copy").with(as(account("SUPER_ADMIN"))).contentType("application/json")
                .content("{\"sourceTimetableId\":1,\"semesterId\":2,\"divisionId\":3}")).andExpect(status().isForbidden());
        http.perform(get("/api/faculty/notifications").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(get("/api/faculty/notifications").with(as(account("COLLEGE_ADMIN")))).andExpect(status().isForbidden());
        http.perform(get("/api/faculty/notifications/unread-count")).andExpect(status().isUnauthorized());
        verifyNoInteractions(imports, notifications);
    }

    @Test void previewAndImportPassTheFileAndConfirmationHash() throws Exception {
        var admin = account("COLLEGE_ADMIN");
        when(imports.preview(any(), anyLong(), any(), any())).thenReturn(new Preview("week.xlsx", "ab", 1, 1, 0,
                List.of(), List.of(), List.of(), true));
        http.perform(multipart("/api/college/timetables/import/preview").file(FILE).param("semesterId", "21").with(as(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canImport").value(true));
        verify(imports).preview(eq(admin), eq(21L), eq("week.xlsx"), aryEq(new byte[]{'P', 'K', 3, 4}));

        // Importing needs the hash returned by the preview.
        http.perform(multipart("/api/college/timetables/import").file(FILE).param("semesterId", "21").with(as(admin)))
                .andExpect(status().isBadRequest());
        http.perform(multipart("/api/college/timetables/import").file(FILE).param("semesterId", "21")
                .param("sha256", "ab").with(as(admin))).andExpect(status().isCreated());
        verify(imports).importFile(eq(admin), eq(21L), eq("week.xlsx"), any(), eq("ab"));
    }

    @Test void oversizedFilesAreRefusedBeforeTheService() throws Exception {
        var big = new MockMultipartFile("file", "big.xlsx", "application/octet-stream", new byte[2 * 1024 * 1024 + 1]);
        http.perform(multipart("/api/college/timetables/import/preview").file(big).param("semesterId", "21")
                        .with(as(account("COLLEGE_ADMIN"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TIMETABLE_IMPORT_INVALID"));
        verifyNoInteractions(imports);
    }

    @Test void templateIsDownloadedAsAnAttachment() throws Exception {
        when(imports.template(any(), eq(21L))).thenReturn(new Template("campussphere-timetable-bsc.xlsx", new byte[]{1, 2}));
        http.perform(get("/api/college/timetables/import/template?semesterId=21").with(as(account("COLLEGE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test void facultyInboxUsesTheSignedInFacultyMember() throws Exception {
        var faculty = account("FACULTY");
        when(notifications.unread(faculty)).thenReturn(2L);
        http.perform(get("/api/faculty/notifications/unread-count").with(as(faculty)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(2));
        http.perform(get("/api/faculty/notifications?unread=true&facultyId=99").with(as(faculty))).andExpect(status().isOk());
        verify(notifications).list(faculty, true, 0);
        http.perform(post("/api/faculty/notifications/7/read").with(as(faculty))).andExpect(status().isNoContent());
        verify(notifications).markRead(faculty, 7L);
        http.perform(post("/api/faculty/notifications/read-all").with(as(faculty))).andExpect(status().isOk());
        verify(notifications).markAllRead(faculty);
    }

    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
