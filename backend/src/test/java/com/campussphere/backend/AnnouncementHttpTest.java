package com.campussphere.backend;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.campussphere.backend.controller.AnnouncementController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.Announcements;
import com.campussphere.backend.service.Announcements.*;
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

@WebMvcTest(controllers = AnnouncementController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class AnnouncementHttpTest {
    @Autowired MockMvc http;
    @MockitoBean Announcements announcements;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    private static final String BODY = """
            {"title":"Exam timetable","body":"Read it","category":"EXAMINATION","priority":"HIGH",
             "audience":"STUDENTS","scope":"DIVISION","targetId":9,"collegeId":99,"authorId":1}""";

    @Test void unauthenticatedAndWrongRoleRequestsAreRejected() throws Exception {
        http.perform(get("/api/student/announcements")).andExpect(status().isUnauthorized());
        http.perform(get("/api/college/announcements").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/announcements").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/announcements").with(as(account("SUPER_ADMIN")))).andExpect(status().isForbidden());
        http.perform(get("/api/faculty/announcements").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(post("/api/college/announcements").with(as(account("STUDENT")))
                .contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(announcements);
    }

    @Test void areaOnlyEndpointsCheckTheRole() throws Exception {
        http.perform(get("/api/college/announcements/mine").with(as(account("COLLEGE_ADMIN")))).andExpect(status().isForbidden());
        http.perform(get("/api/faculty/announcements/stats").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/student/announcements/4/stats").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        verifyNoInteractions(announcements);
    }

    @Test void studentWriteAttemptsReachTheServiceAsStudentAndAreRefused() throws Exception {
        var student = account("STUDENT");
        when(announcements.create(any(), eq(CollegeRole.STUDENT), any())).thenThrow(ApiError.ACCESS_DENIED.exception());
        http.perform(post("/api/student/announcements").with(as(student)).contentType("application/json").content(BODY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verify(announcements).create(eq(student), eq(CollegeRole.STUDENT), any());
    }

    @Test void clientSuppliedCollegeAndAuthorAreIgnored() throws Exception {
        var faculty = account("FACULTY");
        when(announcements.create(any(), any(), any())).thenReturn(sample("DRAFT"));
        http.perform(post("/api/faculty/announcements").with(as(faculty)).contentType("application/json").content(BODY))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"));
        verify(announcements).create(faculty, CollegeRole.FACULTY,
                new Input("Exam timetable", "Read it", "EXAMINATION", "HIGH", "STUDENTS", "DIVISION", 9L, null));

        var student = account("STUDENT");
        when(announcements.list(any(), any(), any())).thenReturn(List.of());
        http.perform(get("/api/student/announcements?collegeId=99&category=FEES&unread=true&q=fee").with(as(student)))
                .andExpect(status().isOk());
        verify(announcements).list(student, CollegeRole.STUDENT, new Filter("FEES", null, null, "fee", true, 0));
    }

    @Test void invalidFiltersAndIdsAreRejected() throws Exception {
        http.perform(get("/api/student/announcements?category=PARTY").with(as(account("STUDENT")))).andExpect(status().isBadRequest());
        http.perform(get("/api/college/announcements?status=DELETED").with(as(account("COLLEGE_ADMIN")))).andExpect(status().isBadRequest());
        http.perform(get("/api/student/announcements/abc").with(as(account("STUDENT")))).andExpect(status().isBadRequest());
        verifyNoInteractions(announcements);
    }

    @Test void publishWithoutBodyPublishesNowAndServiceErrorsKeepTheirCodes() throws Exception {
        var admin = account("COLLEGE_ADMIN");
        when(announcements.publish(any(), any(), eq(4L), any())).thenReturn(sample("PUBLISHED"));
        http.perform(post("/api/college/announcements/4/publish").with(as(admin))).andExpect(status().isOk());
        verify(announcements).publish(admin, CollegeRole.COLLEGE_ADMIN, 4L, null);

        when(announcements.detail(any(), eq(CollegeRole.STUDENT), eq(5L))).thenThrow(ApiError.ANNOUNCEMENT_NOT_FOUND.exception());
        http.perform(get("/api/student/announcements/5").with(as(account("STUDENT"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ANNOUNCEMENT_NOT_FOUND"));

        doThrow(ApiError.ANNOUNCEMENT_STATE_CONFLICT.exception()).when(announcements).delete(any(), any(), eq(6L));
        http.perform(delete("/api/college/announcements/6").with(as(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ANNOUNCEMENT_STATE_CONFLICT"));
    }

    @Test void markReadUsesThePrincipal() throws Exception {
        var student = account("STUDENT");
        http.perform(post("/api/student/announcements/4/read").with(as(student))).andExpect(status().isNoContent());
        verify(announcements).markRead(student, CollegeRole.STUDENT, 4L);
    }

    private static Announcement sample(String status) {
        var now = OffsetDateTime.parse("2026-11-26T10:00:00+05:30");
        return new Announcement(4, "Exam timetable", "Read it", "EXAMINATION", "HIGH", "STUDENTS", "DIVISION", 9L,
                "Division A", status, null, null, now, now, "Synthetic Faculty", "FACULTY", null, null, null, null,
                true, true, true, true);
    }

    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
