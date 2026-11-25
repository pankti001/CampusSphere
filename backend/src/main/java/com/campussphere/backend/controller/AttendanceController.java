package com.campussphere.backend.controller;

import static com.campussphere.backend.dto.AttendanceModels.*;

import java.time.LocalDate;
import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.service.AttendanceReports;
import com.campussphere.backend.service.AttendanceService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AttendanceController {
    private static final String FACULTY = "/api/faculty/attendance";
    private static final String FACULTY_SESSION = FACULTY + "/sessions/{id}";
    private static final String STUDENT = "/api/student/attendance";
    private static final String ADMIN = "/api/college/attendance";
    private static final String ADMIN_SESSION = ADMIN + "/sessions/{id}";
    private final AttendanceService service;
    private final AttendanceReports reports;

    public AttendanceController(AttendanceService service, AttendanceReports reports) {
        this.service = service;
        this.reports = reports;
    }

    @GetMapping(FACULTY + "/assignments")
    List<Assignment> assignments(@AuthenticationPrincipal Account account) { return service.assignments(account); }

    @GetMapping(FACULTY + "/sessions")
    List<Session> history(@AuthenticationPrincipal Account account, @RequestParam(defaultValue = "0") int page) {
        return service.history(account, page);
    }

    @PostMapping(FACULTY + "/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    SessionDetail create(@AuthenticationPrincipal Account account, @Valid @RequestBody Create input) {
        return service.create(account, input);
    }

    @GetMapping(FACULTY_SESSION)
    SessionDetail facultySession(@AuthenticationPrincipal Account account, @PathVariable long id) {
        return service.detail(account, CollegeRole.FACULTY, id);
    }

    @PutMapping(FACULTY_SESSION + "/draft")
    SessionDetail draft(@AuthenticationPrincipal Account account, @PathVariable long id,
                        @Valid @RequestBody Marks input) {
        return service.save(account, id, input, false);
    }

    @PostMapping(FACULTY_SESSION + "/submit")
    SessionDetail submit(@AuthenticationPrincipal Account account, @PathVariable long id,
                         @Valid @RequestBody Marks input) {
        return service.save(account, id, input, true);
    }

    @PostMapping(FACULTY_SESSION + "/records/{studentId}/corrections")
    SessionDetail facultyCorrection(@AuthenticationPrincipal Account account, @PathVariable long id,
                                     @PathVariable long studentId, @Valid @RequestBody CorrectionInput input) {
        return service.correct(account, CollegeRole.FACULTY, id, studentId, input);
    }

    @GetMapping(STUDENT)
    StudentOverview student(@AuthenticationPrincipal Account account,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) Long subjectId,
                            @RequestParam(defaultValue = "0") int page) {
        return reports.student(account, new Filter(from, to, null, null, null, null, subjectId, null, null, page));
    }

    @GetMapping(STUDENT + "/subjects")
    List<Option> studentSubjects(@AuthenticationPrincipal Account account) { return reports.studentSubjects(account); }

    @GetMapping(ADMIN + "/reports")
    Report report(@AuthenticationPrincipal Account account,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                  @RequestParam(required = false) Long departmentId,
                  @RequestParam(required = false) Long programId, @RequestParam(required = false) Long semesterId,
                  @RequestParam(required = false) Long divisionId, @RequestParam(required = false) Long subjectId,
                  @RequestParam(required = false) Long studentId, @RequestParam(required = false) Long facultyId,
                  @RequestParam(defaultValue = "0") int page) {
        return reports.report(account, new Filter(from, to, departmentId, programId, semesterId, divisionId, subjectId,
                studentId, facultyId, page));
    }

    @GetMapping(ADMIN + "/report-options")
    ReportOptions options(@AuthenticationPrincipal Account account) { return reports.options(account); }

    @GetMapping(ADMIN + "/alerts")
    List<Alert> alerts(@AuthenticationPrincipal Account account, @RequestParam(defaultValue = "0") int page) {
        return reports.lowAttendance(account, page);
    }

    @GetMapping(ADMIN + "/policy")
    Policy policy(@AuthenticationPrincipal Account account) { return reports.policy(account); }

    @PutMapping(ADMIN + "/policy")
    Policy policy(@AuthenticationPrincipal Account account, @Valid @RequestBody Policy input) {
        return service.updatePolicy(account, input);
    }

    @GetMapping(ADMIN_SESSION)
    SessionDetail adminSession(@AuthenticationPrincipal Account account, @PathVariable long id) {
        return service.detail(account, CollegeRole.COLLEGE_ADMIN, id);
    }

    @PostMapping(ADMIN_SESSION + "/lock")
    SessionDetail lock(@AuthenticationPrincipal Account account, @PathVariable long id, @Valid @RequestBody Revision input) {
        return service.lock(account, id, input);
    }

    @PostMapping(ADMIN_SESSION + "/records/{studentId}/corrections")
    SessionDetail adminCorrection(@AuthenticationPrincipal Account account, @PathVariable long id,
                                   @PathVariable long studentId, @Valid @RequestBody CorrectionInput input) {
        return service.correct(account, CollegeRole.COLLEGE_ADMIN, id, studentId, input);
    }

    @GetMapping(ADMIN_SESSION + "/audit")
    List<Correction> audit(@AuthenticationPrincipal Account account, @PathVariable long id,
                           @RequestParam(defaultValue = "0") int page) {
        return service.audit(account, id, page);
    }
}
