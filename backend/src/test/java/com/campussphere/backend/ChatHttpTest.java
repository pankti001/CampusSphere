package com.campussphere.backend;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.campussphere.backend.controller.ChatController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.StudentChats;
import com.campussphere.backend.service.StudentChats.MessageInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Chats are student-only: every other role is refused before the service is reached. */
@WebMvcTest(controllers = ChatController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class ChatHttpTest {
    @Autowired MockMvc http;
    @MockitoBean StudentChats chats;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    @Test void facultyAdminsAndPlatformCannotReachAnyChat() throws Exception {
        http.perform(get("/api/student/chats")).andExpect(status().isUnauthorized());
        for (String role : List.of("FACULTY", "COLLEGE_ADMIN", "SUPER_ADMIN", "ADMIN")) {
            http.perform(get("/api/student/chats").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(get("/api/student/chats/9/messages").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(get("/api/student/chats/9/messages/5/file").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(get("/api/student/chats/stream").with(as(account(role)))).andExpect(status().isForbidden());
            http.perform(get("/api/student/me").with(as(account(role)))).andExpect(status().isForbidden());
        }
        // No route anywhere else exposes chats or reports.
        http.perform(get("/api/college/chats").with(as(account("COLLEGE_ADMIN")))).andExpect(status().isForbidden());
        http.perform(get("/api/faculty/chats").with(as(account("FACULTY")))).andExpect(status().isNotFound());
        verifyNoInteractions(chats);
    }

    @Test void requestsCarryOnlyTheBodyAndTheSignedInStudent() throws Exception {
        var student = account("STUDENT");
        UUID key = UUID.fromString("00000000-0000-0000-0000-000000000042");
        http.perform(post("/api/student/chats/9/messages").with(as(student)).contentType("application/json")
                .content("{\"body\":\"Hi\",\"clientKey\":\"" + key + "\"}")).andExpect(status().isCreated());
        verify(chats).send(student, 9, new MessageInput("Hi", key));
        http.perform(post("/api/student/chats/9/messages").with(as(student)).contentType("application/json")
                .content("{\"body\":\"Hi\",\"clientKey\":\"" + key + "\",\"senderId\":3}")).andExpect(status().isBadRequest());
        http.perform(post("/api/student/chats/groups").with(as(student)).contentType("application/json")
                .content("{\"name\":\"Java\",\"collegeId\":1}")).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(chats);
    }

    @Test void theStreamIsAnEventStreamForStudents() throws Exception {
        when(chats.connect(any())).thenReturn(new SseEmitter(1000L));
        http.perform(get("/api/student/chats/stream").with(as(account("STUDENT"))))
                .andExpect(request().asyncStarted());
    }

    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
