package com.campussphere.backend;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import com.campussphere.backend.controller.TimetableController;
import com.campussphere.backend.security.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.Timetables;
import com.campussphere.backend.service.Timetables.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TimetableController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, ApiSecurityErrors.class, AccountAccessPolicy.class})
class TimetableHttpTest {
    @Autowired MockMvc http;
    @MockitoBean Timetables timetables;
    @MockitoBean JwtService jwt;
    @MockitoBean CustomUserDetailsService users;

    private static final String LESSON = """
            {"kind":"lesson","day":1,"start":"09:00","end":"10:00","assignmentId":5,"classroomId":8,"collegeId":99}""";

    @Test void everyAreaIsLimitedToItsRole() throws Exception {
        http.perform(get("/api/student/timetable")).andExpect(status().isUnauthorized());
        http.perform(get("/api/college/timetables").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/timetables").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(get("/api/college/classrooms").with(as(account("SUPER_ADMIN")))).andExpect(status().isForbidden());
        http.perform(get("/api/faculty/timetable").with(as(account("STUDENT")))).andExpect(status().isForbidden());
        http.perform(get("/api/student/timetable").with(as(account("FACULTY")))).andExpect(status().isForbidden());
        http.perform(post("/api/college/timetables/4/entries").with(as(account("FACULTY")))
                .contentType("application/json").content(LESSON)).andExpect(status().isForbidden());
        http.perform(post("/api/college/timetables/4/publish").with(as(account("STUDENT")))
                .contentType("application/json").content("{\"revision\":1}")).andExpect(status().isForbidden());
        verifyNoInteractions(timetables);
    }

    @Test void clientSuppliedCollegeIdIsIgnored() throws Exception {
        var admin = account("COLLEGE_ADMIN");
        http.perform(post("/api/college/timetables/4/entries").with(as(admin)).contentType("application/json").content(LESSON))
                .andExpect(status().isCreated());
        verify(timetables).addEntry(admin, 4L, new EntryInput("lesson", 1, LocalTime.of(9, 0), LocalTime.of(10, 0), 5L, 8L, null));
    }

    @Test void conflictsReturnTheClashingBookings() throws Exception {
        when(timetables.addEntry(any(), eq(4L), any())).thenThrow(new Rejected(ApiError.TIMETABLE_CONFLICT,
                List.of(new Clash("FACULTY", 1, LocalTime.of(9, 30), LocalTime.of(10, 30), "BSc · B · CS301")), List.of()));
        http.perform(post("/api/college/timetables/4/entries").with(as(account("COLLEGE_ADMIN")))
                        .contentType("application/json").content(LESSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIMETABLE_CONFLICT"))
                .andExpect(jsonPath("$.clashes[0].type").value("FACULTY"))
                .andExpect(jsonPath("$.clashes[0].day").value(1))
                .andExpect(jsonPath("$.clashes[0].start", startsWith("09:30")))
                .andExpect(jsonPath("$.clashes[0].with").value("BSc · B · CS301"));
    }

    @Test void publishNeedsTheRevisionAndReportsProblems() throws Exception {
        var admin = account("COLLEGE_ADMIN");
        http.perform(post("/api/college/timetables/4/publish").with(as(admin))).andExpect(status().isBadRequest());
        when(timetables.publish(any(), eq(4L), any())).thenThrow(new Rejected(ApiError.TIMETABLE_NOT_READY, List.of(),
                List.of(new Problem("ASSIGNMENT_ENDED", 9L, "Mon 09:00–10:00: Prof A no longer teaches CS201 here."))));
        http.perform(post("/api/college/timetables/4/publish").with(as(admin)).contentType("application/json")
                        .content("{\"revision\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TIMETABLE_NOT_READY"))
                .andExpect(jsonPath("$.problems[0].type").value("ASSIGNMENT_ENDED"))
                .andExpect(jsonPath("$.problems[0].entryId").value(9));
        verify(timetables).publish(admin, 4L, new Publish(3L));
    }

    @Test void invalidParametersAreRejectedBeforeTheService() throws Exception {
        http.perform(get("/api/student/timetable/day?date=27-11-2026").with(as(account("STUDENT")))).andExpect(status().isBadRequest());
        http.perform(get("/api/college/timetables?status=DELETED").with(as(account("COLLEGE_ADMIN")))).andExpect(status().isBadRequest());
        http.perform(get("/api/college/timetables/abc").with(as(account("COLLEGE_ADMIN")))).andExpect(status().isNotFound());
        verifyNoInteractions(timetables);
    }

    @Test void personalViewsUseTheSignedInAccount() throws Exception {
        var student = account("STUDENT");
        when(timetables.studentDay(any(), any())).thenReturn(new Day(LocalDate.of(2026, 11, 27), 5,
                new Schedule(true, "BSc · Semester 3 · A", 2, null, List.of())));
        http.perform(get("/api/student/timetable/day?date=2026-11-27&divisionId=99").with(as(student)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.day").value(5))
                .andExpect(jsonPath("$.schedule.title").value("BSc · Semester 3 · A"));
        verify(timetables).studentDay(student, LocalDate.of(2026, 11, 27));

        var faculty = account("FACULTY");
        http.perform(get("/api/faculty/timetable").with(as(faculty))).andExpect(status().isOk());
        verify(timetables).facultyWeek(faculty);
    }

    private static Account account(String role) {
        return new Account(5L, true, false, Set.of(role), new CollegeScope(10L, true));
    }

    private static RequestPostProcessor as(Account account) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(account, null, account.authorities()));
    }
}
