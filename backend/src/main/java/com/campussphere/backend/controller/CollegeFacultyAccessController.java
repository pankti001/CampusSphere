package com.campussphere.backend.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.FacultyAccessService;
import com.campussphere.backend.service.FacultyAccessService.CollegeRequest;
import com.campussphere.backend.service.FacultyAccessService.Status;
import com.fasterxml.jackson.annotation.JsonAnySetter;

@RestController
@RequestMapping("/api/college/faculty-requests")
public class CollegeFacultyAccessController {
    private final FacultyAccessService faculty;

    public CollegeFacultyAccessController(FacultyAccessService faculty) {
        this.faculty = faculty;
    }

    @GetMapping
    public List<CollegeRequest> pending(@AuthenticationPrincipal Account reviewer) {
        return faculty.pending(reviewer);
    }

    @PostMapping("/{requestId}/decision")
    public CollegeRequest decide(@AuthenticationPrincipal Account reviewer, @PathVariable Long requestId,
                                 @Valid @RequestBody Decision input) {
        return faculty.decideForCollege(reviewer, requestId, input.status(), input.employmentConfirmed());
    }

    public record Decision(@NotNull Status status, boolean employmentConfirmed) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported Faculty decision field");
        }
    }
}
