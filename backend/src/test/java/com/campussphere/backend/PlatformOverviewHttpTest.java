package com.campussphere.backend;

import com.campussphere.backend.controller.PlatformOverviewController;
import com.campussphere.backend.controller.PlatformCollegeController;
import com.campussphere.backend.controller.CollegeInvitationAcceptanceController;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.PlatformCollegeService;
import com.campussphere.backend.service.CollegeAdminInvitationService;
import com.campussphere.backend.security.ApiSecurityErrors;
import com.campussphere.backend.security.CustomUserDetailsService;
import com.campussphere.backend.security.JwtAuthenticationFilter;
import com.campussphere.backend.security.JwtService;
import com.campussphere.backend.security.AccountAccessPolicy;
import com.campussphere.backend.security.SecurityConfig;
import com.campussphere.backend.service.PlatformOverviewService;
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

@WebMvcTest(controllers = {PlatformOverviewController.class, PlatformCollegeController.class,
        CollegeInvitationAcceptanceController.class, com.campussphere.backend.controller.CollegeRegistrationController.class},
        properties = "logging.level.org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration=OFF")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class PlatformOverviewHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean PlatformOverviewService overview;
    @MockitoBean PlatformCollegeService colleges;
    @MockitoBean CollegeAdminInvitationService invitations;
    @MockitoBean com.campussphere.backend.service.CollegeRegistrationService registrations;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test
    void collegeCodeValidationUsesTheSameAsciiAlphabetAsAndroid() throws Exception {
        String input = """
                {"name":"Example","code":"ß","email":"rep@example.invalid","position":"Registrar",
                 "fullName":"Representative","password":"synthetic-fixture-only"}
                """;
        mvc.perform(post("/api/college-registration-requests").contentType("application/json").content(input))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(registrations);
    }

    @Test
    void approvalConflictAndExpiryPreserveStructuredApiErrors() throws Exception {
        String input = """
                {"method":"OFFICIAL_CALLBACK","institution":true,"representative":true,"mailbox":true}
                """;
        for (ApiError error : new ApiError[]{ApiError.REGISTRATION_CONFLICT, ApiError.REGISTRATION_EXPIRED}) {
            doThrow(error.exception()).when(registrations).approve(isNull(), eq(7L), any());
            mvc.perform(post("/api/platform/college-registration-requests/7/approve")
                    .with(user("platform").roles("SUPER_ADMIN")).contentType("application/json").content(input))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(error.name()));
        }
    }


    @Test
    void publicRegistrationReturnsGenericReceiptAndRejectsInvalidPayload() throws Exception {
        String route="/api/college-registration-requests";
        mvc.perform(post(route).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        String valid="""
                {"name":"Example","code":"EX","email":"rep@example.invalid","position":"Registrar",
                 "fullName":"Representative","password":"synthetic-fixture-only"}
                """;
        for (int i=0;i<2;i++) mvc.perform(post(route).contentType("application/json").content(valid))
            .andExpect(status().isAccepted()).andExpect(content().string(""));
        verify(registrations,times(2)).submit(any());
    }
    @Test
    void returnsTheActualSummaryContractForSuperAdmin() throws Exception {
        when(overview.load()).thenReturn(new PlatformOverviewService.Overview(3, 2, 1, 4));
        mvc.perform(get("/api/platform/overview").with(user("platform").roles("SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalColleges").value(3))
                .andExpect(jsonPath("$.activeColleges").value(2))
                .andExpect(jsonPath("$.inactiveColleges").value(1))
                .andExpect(jsonPath("$.pendingInvitations").value(4));
    }

    @Test
    void collegeListAndDetailRequirePlatformRole() throws Exception {
        var date = java.time.OffsetDateTime.parse("2026-11-23T00:00:00Z");
        var college = new PlatformCollegeService.CollegeDetails(7L, "COL7", "Test College", false, date, date);
        when(colleges.list(null, null, 0, 50)).thenReturn(java.util.List.of(college));
        when(colleges.get(7L)).thenReturn(college);
        mvc.perform(get("/api/platform/colleges").with(user("platform").roles("SUPER_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(7));
        mvc.perform(get("/api/platform/colleges/7").with(user("platform").roles("SUPER_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        for (String role : new String[]{"ADMIN", "COLLEGE_ADMIN", "FACULTY", "STUDENT"}) {
            mvc.perform(get("/api/platform/colleges").with(user("tenant").roles(role)))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/platform/colleges/7").with(user("tenant").roles(role)))
                    .andExpect(status().isForbidden());
        }
        verify(colleges).list(null, null, 0, 50);
        verify(colleges).get(7L);
        verifyNoMoreInteractions(colleges);
    }

    @Test
    void everyPlatformEndpointRejectsAnonymousAndNonPlatformRoles() throws Exception {
        var methods = java.util.List.of(
                new String[]{"GET", "/api/platform/college-registration-requests"},
                new String[]{"GET", "/api/platform/college-registration-requests/1"},
                new String[]{"POST", "/api/platform/college-registration-requests/1/approve"},
                new String[]{"POST", "/api/platform/college-registration-requests/1/reject"},
                new String[]{"GET", "/api/platform/overview"},
                new String[]{"GET", "/api/platform/colleges"},
                new String[]{"GET", "/api/platform/colleges/7"},
                new String[]{"POST", "/api/platform/colleges"},
                new String[]{"PATCH", "/api/platform/colleges/7"},
                new String[]{"PUT", "/api/platform/colleges/7/status"},
                new String[]{"GET", "/api/platform/colleges/7/admin-invitations"},
                new String[]{"POST", "/api/platform/colleges/7/admin-invitations"},
                new String[]{"POST", "/api/platform/colleges/7/admin-invitations/1/revoke"}
        );
        for (var endpoint : methods) {
            var method = org.springframework.http.HttpMethod.valueOf(endpoint[0]);
            String path = endpoint[1];
            mvc.perform(request(method, path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_AUTHENTICATION"));
            for (String role : new String[]{"ADMIN", "COLLEGE_ADMIN", "FACULTY", "STUDENT"}) {
                mvc.perform(request(method, path).with(user("tenant").roles(role)))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            }
        }
        verifyNoInteractions(overview, colleges, invitations);
    }

    @Test
    void acceptanceIsPublicButRequiresValidFieldsAndUsesNoStore() throws Exception {
        String route = "/api/auth/college-admin-invitations/accept";
        mvc.perform(post(route).contentType("application/json").content("""
                {"token":"invalid","fullName":"Synthetic","password":"synthetic-passphrase"}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(invitations);

        mvc.perform(post(route).contentType("application/json").content("""
                {"token":"%s","fullName":"Synthetic","password":"synthetic-passphrase"}
                """.formatted("x".repeat(43))))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        verify(invitations).accept(any());
    }

    @Test
    void unusableInvitationsReturnGenericError() throws Exception {
        doThrow(ApiError.INVITATION_INVALID.exception()).when(invitations).accept(any());
        mvc.perform(post("/api/auth/college-admin-invitations/accept").contentType("application/json").content("""
                {"token":"%s","fullName":"Synthetic","password":"synthetic-passphrase"}
                """.formatted("x".repeat(43))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void rejectsAnonymousAndTenantRolesBeforeLoadingData() throws Exception {
        mvc.perform(get("/api/platform/overview")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_AUTHENTICATION"));
        for (String role : new String[]{"ADMIN", "COLLEGE_ADMIN", "FACULTY", "STUDENT"}) {
            mvc.perform(get("/api/platform/overview").with(user("different-role").roles(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
        verifyNoInteractions(overview);
    }
}
