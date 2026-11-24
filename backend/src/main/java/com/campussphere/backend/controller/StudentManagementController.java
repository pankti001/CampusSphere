package com.campussphere.backend.controller;

import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.StudentAffiliationService;
import com.campussphere.backend.service.StudentAffiliationService.Affiliation;
import com.campussphere.backend.service.StudentEnrollmentService;
import com.campussphere.backend.service.StudentEnrollmentService.*;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class StudentManagementController {
    private static final String STUDENTS = "/api/college/students";
    private static final String STUDENT = STUDENTS + "/{studentId}";
    private static final String ENROLLMENTS = STUDENT + "/enrollments";
    private static final String QUEUE = "/api/college/student-affiliation-requests";
    private static final String REQUEST = "/api/auth/student-affiliation";
    private final StudentEnrollmentService enrollment;
    private final StudentAffiliationService affiliation;

    public StudentManagementController(StudentEnrollmentService enrollment, StudentAffiliationService affiliation) {
        this.enrollment = enrollment;
        this.affiliation = affiliation;
    }

    @GetMapping(STUDENTS)
    List<Member> list(@AuthenticationPrincipal Account account) {
        return enrollment.list(account);
    }

    @GetMapping(STUDENT)
    Member profile(@AuthenticationPrincipal Account account, @PathVariable long studentId) {
        return enrollment.profile(account, studentId);
    }

    @PutMapping(STUDENT + "/department")
    Member department(@AuthenticationPrincipal Account account, @PathVariable long studentId,
                      @Valid @RequestBody DepartmentInput input) {
        return enrollment.changeDepartment(account, studentId, input.departmentId());
    }

    @GetMapping(ENROLLMENTS)
    List<Enrollment> history(@AuthenticationPrincipal Account account, @PathVariable long studentId) {
        return enrollment.history(account, studentId);
    }

    @PostMapping(ENROLLMENTS)
    @ResponseStatus(HttpStatus.CREATED)
    Enrollment enroll(@AuthenticationPrincipal Account account, @PathVariable long studentId,
                      @Valid @RequestBody EnrollmentInput input) {
        return enrollment.enroll(account, studentId, input);
    }

    @DeleteMapping(ENROLLMENTS + "/{enrollmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void end(@AuthenticationPrincipal Account account, @PathVariable long studentId, @PathVariable long enrollmentId) {
        enrollment.end(account, studentId, enrollmentId);
    }

    @GetMapping("/api/student/enrollment")
    List<Enrollment> current(@AuthenticationPrincipal Account account) {
        return enrollment.current(account);
    }

    @GetMapping(REQUEST)
    List<Affiliation> myRequest(@AuthenticationPrincipal Account account) {
        return affiliation.mine(account);
    }

    @PostMapping(REQUEST)
    @ResponseStatus(HttpStatus.CREATED)
    Affiliation request(@AuthenticationPrincipal Account account, @Valid @RequestBody CollegeInput input) {
        return affiliation.submit(account, input.collegeCode());
    }

    @GetMapping(QUEUE)
    List<Affiliation> pending(@AuthenticationPrincipal Account account) {
        return affiliation.pending(account);
    }

    @PostMapping(QUEUE + "/{requestId}/decision")
    Affiliation decide(@AuthenticationPrincipal Account account, @PathVariable long requestId,
                       @Valid @RequestBody DecisionInput input) {
        return affiliation.decide(account, requestId, input.approved(), input.admissionConfirmed());
    }

    public record DepartmentInput(@Positive long departmentId) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw unsupported(); }
    }

    public record CollegeInput(@NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,29}") String collegeCode) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw unsupported(); }
    }

    public record DecisionInput(@NotNull Boolean approved, @NotNull Boolean admissionConfirmed) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw unsupported(); }
    }

    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("Unsupported student management field");
    }
}
