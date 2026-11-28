package com.campussphere.backend;

import java.util.List;
import java.util.Set;

import com.campussphere.backend.controller.SkillController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.SkillExchange;
import com.campussphere.backend.service.SkillExchange.*;
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

/** Skill Exchange is student-only, and no request can choose a college, student or owner. */
@WebMvcTest(controllers = {SkillController.class, com.campussphere.backend.controller.FacultyLearningController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class SkillHttpTest {
    @Autowired MockMvc http;
    @MockitoBean SkillExchange skills;
    @MockitoBean com.campussphere.backend.service.SkillWants wants;
    @MockitoBean com.campussphere.backend.service.SkillGroups groups;
    @MockitoBean com.campussphere.backend.service.SkillFaculty faculty;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void onlyStudentsReachSkillExchange() throws Exception {
        http.perform(get("/api/student/skills/offers")).andExpect(status().isUnauthorized());
        for (String role : List.of("FACULTY", "COLLEGE_ADMIN", "SUPER_ADMIN", "ADMIN")) {
            for (String path : List.of("/api/student/skills/offers", "/api/student/skills/offers/5", "/api/student/skills/mine",
                    "/api/student/skills/requests", "/api/student/skills/sessions", "/api/student/skills/students/5/reviews",
                    "/api/student/skills/notifications", "/api/student/skills/summary")) {
                http.perform(get(path).with(as(account(role)))).andExpect(status().isForbidden());
            }
            http.perform(post("/api/student/skills/requests/5/accept").with(as(account(role)))).andExpect(status().isForbidden());
        }
        // There is no Skill Exchange route for faculty, College Admins or the platform.
        http.perform(get("/api/college/skills/offers").with(as(account("COLLEGE_ADMIN")))).andExpect(status().isForbidden());
        http.perform(get("/api/faculty/skills/offers").with(as(account("FACULTY")))).andExpect(status().isNotFound());
        verifyNoInteractions(skills);
    }

    @Test void aCollegeSentByTheAppIsIgnoredOrRefused() throws Exception {
        var student = account("STUDENT");
        // A collegeId query parameter is not read: the service gets only the signed-in account and the filters.
        http.perform(get("/api/student/skills/offers").param("collegeId", "404").param("category", "MUSIC").with(as(student)))
                .andExpect(status().isOk());
        verify(skills).browse(student, new Filter(null, "MUSIC", null, null, 0));
        // A college, owner or learner in a body is refused before the service is reached.
        for (String body : List.of("{\"offerId\":5,\"collegeId\":404}", "{\"offerId\":5,\"learnerId\":20}",
                "{\"offerId\":5,\"providerId\":20}")) {
            http.perform(post("/api/student/skills/requests").with(as(student)).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        http.perform(post("/api/student/skills/mine").with(as(student)).contentType("application/json")
                .content("{\"name\":\"Java\",\"category\":\"PROGRAMMING\",\"level\":\"BEGINNER\",\"mode\":\"BOTH\",\"studentId\":20}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/student/skills/sessions/5/review").with(as(student)).contentType("application/json")
                .content("{\"rating\":5,\"revieweeId\":20}")).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(skills);
    }

    @Test void requestsCarryTheSignedInStudentAndTheIdsInThePath() throws Exception {
        var student = account("STUDENT");
        http.perform(post("/api/student/skills/requests").with(as(student)).contentType("application/json")
                .content("{\"offerId\":5,\"exchangeOfferId\":6,\"message\":\"Hi\"}")).andExpect(status().isCreated());
        verify(skills).createRequest(student, new RequestInput(5L, 6L, "Hi"));
        http.perform(post("/api/student/skills/requests/9/accept").with(as(student))).andExpect(status().isOk());
        verify(skills).decide(student, 9, "accept");
        http.perform(post("/api/student/skills/sessions/9/cancel").with(as(student)).contentType("application/json")
                .content("{\"reason\":\"Ill\"}")).andExpect(status().isOk());
        verify(skills).act(student, 9, "cancel", new CancelInput("Ill"));
        http.perform(delete("/api/student/skills/mine/9").with(as(student))).andExpect(status().isNoContent());
        verify(skills).setOfferStatus(student, 9, "archive");
        // Unknown steps and boxes are not routes or are refused.
        http.perform(post("/api/student/skills/requests/9/approve").with(as(student))).andExpect(status().isNotFound());
        http.perform(get("/api/student/skills/requests").param("box", "everyone").with(as(student))).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(skills);
    }

    @Test void postsGroupsAndFacultyRequestsAreForStudentsOnly() throws Exception {
        for (String role : List.of("FACULTY", "COLLEGE_ADMIN", "SUPER_ADMIN", "ADMIN")) {
            for (String path : List.of("/api/student/skills/wants", "/api/student/skills/wants/5", "/api/student/skills/groups",
                    "/api/student/skills/groups/5", "/api/student/skills/faculty", "/api/student/skills/faculty-requests")) {
                http.perform(get(path).with(as(account(role)))).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(wants, groups);
    }

    @Test void theFacultySideIsForFacultyOnly() throws Exception {
        http.perform(get("/api/faculty/learning-requests")).andExpect(status().isUnauthorized());
        for (String role : List.of("STUDENT", "COLLEGE_ADMIN", "SUPER_ADMIN", "ADMIN")) {
            http.perform(get("/api/faculty/learning-requests").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(get("/api/faculty/learning-requests/5").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(post("/api/faculty/learning-requests/5/accept").with(as(account(role))).contentType("application/json")
                    .content("{}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(faculty);
        var teacher = account("FACULTY");
        http.perform(get("/api/faculty/learning-requests").with(as(teacher))).andExpect(status().isOk());
        verify(faculty).facultyRequests(teacher);
    }

    @Test void theNewFormsRefuseACollegeClassOrPerson() throws Exception {
        var student = account("STUDENT");
        for (String body : List.of("{\"title\":\"Excel\",\"category\":\"OTHER\",\"collegeId\":404}",
                "{\"title\":\"Excel\",\"category\":\"OTHER\",\"divisionId\":32}",
                "{\"title\":\"Excel\",\"category\":\"OTHER\",\"studentId\":20}")) {
            http.perform(post("/api/student/skills/wants").with(as(student)).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        http.perform(post("/api/student/skills/faculty-requests").with(as(student)).contentType("application/json")
                .content("{\"facultyId\":50,\"topic\":\"Trees\",\"collegeId\":404}")).andExpect(status().isBadRequest());
        http.perform(post("/api/student/skills/wants/5/comments").with(as(student)).contentType("application/json")
                .content("{\"text\":\"Hi\",\"authorId\":20}")).andExpect(status().isBadRequest());
        http.perform(post("/api/student/skills/groups/5/sessions").with(as(student)).contentType("application/json")
                .content("{\"startsAt\":\"2026-12-03T10:00:00Z\",\"endsAt\":\"2026-12-03T11:00:00Z\",\"mode\":\"ONLINE\",\"groupId\":9}"))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/faculty/learning-requests/5/accept").with(as(account("FACULTY"))).contentType("application/json")
                .content("{\"startsAt\":\"2026-12-03T10:00:00Z\",\"endsAt\":\"2026-12-03T11:00:00Z\",\"mode\":\"ONLINE\",\"facultyId\":60}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(wants, groups, faculty);
        // The audience is the only choice the app makes; the class itself comes from the account.
        http.perform(post("/api/student/skills/wants").with(as(student)).contentType("application/json")
                .content("{\"title\":\"Excel\",\"details\":\"\",\"category\":\"OTHER\",\"audience\":\"CLASS\"}")).andExpect(status().isCreated());
        verify(wants).create(student, new com.campussphere.backend.service.SkillWants.WantInput("Excel", "", "OTHER", "CLASS"));
    }
    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
