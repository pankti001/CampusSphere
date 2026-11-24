package com.campussphere.backend.controller;

import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.FacultyManagementService;
import com.campussphere.backend.service.FacultyManagementService.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class FacultyManagementController {
    private static final String FACULTY = "/api/college/faculty";
    private static final String PROFILE = FACULTY + "/{facultyId}";
    private static final String ASSIGNMENTS = PROFILE + "/assignments";
    private final FacultyManagementService faculty;

    public FacultyManagementController(FacultyManagementService faculty) {
        this.faculty = faculty;
    }

    @GetMapping(FACULTY)
    List<Member> list(@AuthenticationPrincipal Account account) {
        return faculty.list(account);
    }

    @GetMapping(PROFILE)
    Member profile(@AuthenticationPrincipal Account account, @PathVariable long facultyId) {
        return faculty.profile(account, facultyId);
    }

    @PutMapping(PROFILE + "/department")
    Member department(@AuthenticationPrincipal Account account, @PathVariable long facultyId,
                      @Valid @RequestBody DepartmentInput input) {
        return faculty.changeDepartment(account, facultyId, input.departmentId());
    }

    @GetMapping(ASSIGNMENTS)
    List<Assignment> assignments(@AuthenticationPrincipal Account account, @PathVariable long facultyId) {
        return faculty.assignments(account, facultyId);
    }

    @PostMapping(ASSIGNMENTS)
    @ResponseStatus(HttpStatus.CREATED)
    Assignment assign(@AuthenticationPrincipal Account account, @PathVariable long facultyId,
                      @Valid @RequestBody AssignmentInput input) {
        return faculty.assign(account, facultyId, input);
    }

    @DeleteMapping(ASSIGNMENTS + "/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void end(@AuthenticationPrincipal Account account, @PathVariable long facultyId, @PathVariable long assignmentId) {
        faculty.end(account, facultyId, assignmentId);
    }

    @GetMapping("/api/faculty/assignments")
    List<Assignment> dashboard(@AuthenticationPrincipal Account account) {
        return faculty.dashboard(account);
    }

    public record DepartmentInput(@Positive long departmentId) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new IllegalArgumentException("Unsupported association field");
        }
    }
}
