package com.campussphere.backend.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.service.AssignmentNotices;
import com.campussphere.backend.service.AssignmentNotices.Notice;
import com.campussphere.backend.service.AssignmentSubmissions;
import com.campussphere.backend.service.AssignmentSubmissions.*;
import com.campussphere.backend.service.Assignments;
import com.campussphere.backend.service.Assignments.*;
import com.campussphere.backend.service.StudyMaterials.FileDownload;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Assignment routes. Spring Security limits /api/faculty/**, /api/student/** and /api/college/homework/** to the
 * matching role; the services then scope every query to the authenticated account's college, classes or enrollment.
 * College Admin routes are read-only and never expose submissions, marks or feedback.
 */
@RestController
public class AssignmentController {
    private static final String FACULTY = "/api/faculty/homework";
    private static final String STUDENT = "/api/student/homework";
    private static final String COLLEGE = "/api/college/homework";
    private static final String ID = "/{id:\\d+}";
    private static final String SUBMISSION = ID + "/submissions/{submissionId:\\d+}";

    private final Assignments assignments;
    private final AssignmentSubmissions submissions;
    private final AssignmentNotices notices;

    public AssignmentController(Assignments assignments, AssignmentSubmissions submissions, AssignmentNotices notices) {
        this.assignments = assignments;
        this.submissions = submissions;
        this.notices = notices;
    }

    public record StudentView(Assignment assignment, List<Submission> attempts) {}
    public record Count(long count) {}

    // ---- Faculty ----

    @GetMapping(FACULTY + "/classes")
    List<ClassOption> classes(@AuthenticationPrincipal Account actor) {
        return assignments.classes(actor);
    }

    @GetMapping(FACULTY + "/summary")
    FacultySummary facultySummary(@AuthenticationPrincipal Account actor) {
        return assignments.facultySummary(actor);
    }

    @GetMapping(FACULTY)
    List<Assignment> facultyList(@AuthenticationPrincipal Account actor, @RequestParam(required = false) Long subjectId,
                                 @RequestParam(required = false) Long divisionId, @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        return assignments.facultyList(actor, new Filter(subjectId, divisionId, status, q, page));
    }

    @PostMapping(path = FACULTY, consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Assignment create(@AuthenticationPrincipal Account actor, @RequestBody Input input) {
        return assignments.create(actor, input);
    }

    @GetMapping(FACULTY + ID)
    Assignment facultyDetail(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return assignments.detail(actor, CollegeRole.FACULTY, id);
    }

    @PutMapping(path = FACULTY + ID, consumes = MediaType.APPLICATION_JSON_VALUE)
    Assignment update(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody Input input) {
        return assignments.update(actor, id, input);
    }

    @PostMapping(FACULTY + ID + "/publish")
    Assignment publish(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return assignments.publish(actor, id);
    }

    @PostMapping(FACULTY + ID + "/close")
    Assignment close(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return assignments.close(actor, id);
    }

    @PostMapping(FACULTY + ID + "/archive")
    Assignment archive(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return assignments.archive(actor, id);
    }

    @PostMapping(path = FACULTY + ID + "/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Assignment attach(@AuthenticationPrincipal Account actor, @PathVariable long id,
                      @RequestParam("file") MultipartFile file) throws IOException {
        return assignments.attach(actor, id, file);
    }

    @DeleteMapping(FACULTY + ID + "/attachment")
    Assignment detach(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return assignments.detach(actor, id);
    }

    @GetMapping(FACULTY + ID + "/attachment")
    ResponseEntity<Resource> facultyAttachment(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return file(assignments.attachment(actor, CollegeRole.FACULTY, id));
    }

    @GetMapping(FACULTY + ID + "/history")
    List<Event> facultyHistory(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return assignments.history(actor, CollegeRole.FACULTY, id);
    }

    @GetMapping(FACULTY + ID + "/submissions")
    List<RosterRow> roster(@AuthenticationPrincipal Account actor, @PathVariable long id,
                           @RequestParam(defaultValue = "ALL") RosterFilter filter,
                           @RequestParam(defaultValue = "0") int page) {
        return submissions.roster(actor, id, filter, page);
    }

    @GetMapping(FACULTY + SUBMISSION)
    Review review(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long submissionId) {
        return submissions.review(actor, id, submissionId);
    }

    @GetMapping(FACULTY + SUBMISSION + "/file")
    ResponseEntity<Resource> facultySubmissionFile(@AuthenticationPrincipal Account actor, @PathVariable long id,
                                                   @PathVariable long submissionId) {
        return file(submissions.file(actor, CollegeRole.FACULTY, id, submissionId));
    }

    @PutMapping(path = FACULTY + SUBMISSION + "/evaluation", consumes = MediaType.APPLICATION_JSON_VALUE)
    Submission evaluate(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long submissionId,
                        @RequestBody Evaluation evaluation) {
        return submissions.evaluate(actor, id, submissionId, evaluation);
    }

    @PostMapping(path = FACULTY + SUBMISSION + "/return", consumes = MediaType.APPLICATION_JSON_VALUE)
    Submission returnForCorrection(@AuthenticationPrincipal Account actor, @PathVariable long id,
                                   @PathVariable long submissionId, @RequestBody ReturnRequest request) {
        return submissions.returnForCorrection(actor, id, submissionId, request);
    }

    // ---- Students ----

    @GetMapping(STUDENT + "/summary")
    StudentSummary studentSummary(@AuthenticationPrincipal Account actor) {
        return assignments.studentSummary(actor);
    }

    @GetMapping(STUDENT)
    List<Assignment> studentList(@AuthenticationPrincipal Account actor,
                                 @RequestParam(defaultValue = "ALL") StudentTab tab,
                                 @RequestParam(defaultValue = "0") int page) {
        return assignments.studentList(actor, tab, page);
    }

    @GetMapping(STUDENT + ID)
    StudentView studentDetail(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return new StudentView(assignments.detail(actor, CollegeRole.STUDENT, id), submissions.mine(actor, id));
    }

    @GetMapping(STUDENT + ID + "/attachment")
    ResponseEntity<Resource> studentAttachment(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return file(assignments.attachment(actor, CollegeRole.STUDENT, id));
    }

    @PostMapping(path = STUDENT + ID + "/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Submission submit(@AuthenticationPrincipal Account actor, @PathVariable long id,
                      @RequestParam("file") MultipartFile file,
                      @RequestParam(required = false) String comment) throws IOException {
        return submissions.submit(actor, id, file, comment);
    }

    @GetMapping(STUDENT + SUBMISSION + "/file")
    ResponseEntity<Resource> studentSubmissionFile(@AuthenticationPrincipal Account actor, @PathVariable long id,
                                                   @PathVariable long submissionId) {
        return file(submissions.file(actor, CollegeRole.STUDENT, id, submissionId));
    }

    @GetMapping("/api/student/homework-notifications")
    List<Notice> notifications(@AuthenticationPrincipal Account actor,
                               @RequestParam(defaultValue = "false") boolean unreadOnly,
                               @RequestParam(defaultValue = "0") int page) {
        return notices.list(actor, unreadOnly, page);
    }

    @GetMapping("/api/student/homework-notifications/unread-count")
    Count unread(@AuthenticationPrincipal Account actor) {
        return new Count(notices.unread(actor));
    }

    @PostMapping("/api/student/homework-notifications/{id:\\d+}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        notices.markRead(actor, id);
    }

    // ---- College Admin (read-only monitoring) ----

    @GetMapping(COLLEGE + "/filters")
    Filters adminFilters(@AuthenticationPrincipal Account actor) {
        return assignments.adminFilters(actor);
    }

    @GetMapping(COLLEGE + "/stats")
    AdminStats adminStats(@AuthenticationPrincipal Account actor, AdminParams params) {
        return assignments.adminStats(actor, params.filter());
    }

    @GetMapping(COLLEGE)
    List<Assignment> adminList(@AuthenticationPrincipal Account actor, AdminParams params) {
        return assignments.adminList(actor, params.filter());
    }

    @GetMapping(COLLEGE + ID)
    Assignment adminDetail(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return assignments.detail(actor, CollegeRole.COLLEGE_ADMIN, id);
    }

    @GetMapping(COLLEGE + ID + "/attachment")
    ResponseEntity<Resource> adminAttachment(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return file(assignments.attachment(actor, CollegeRole.COLLEGE_ADMIN, id));
    }

    @GetMapping(COLLEGE + ID + "/history")
    List<Event> adminHistory(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return assignments.history(actor, CollegeRole.COLLEGE_ADMIN, id);
    }

    /** Query parameters of the College Admin list and statistics. */
    public record AdminParams(Long programId, Long academicYearId, Long semesterId, Long divisionId, Long subjectId,
                              Long facultyId, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, String status, String q,
                              Integer page) {
        AdminFilter filter() {
            return new AdminFilter(programId, academicYearId, semesterId, divisionId, subjectId, facultyId, from, to,
                    status, q, page == null ? 0 : page);
        }
    }

    private static ResponseEntity<Resource> file(FileDownload download) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(download.mimeType()))
                .contentLength(download.sizeBytes())
                .body(new FileSystemResource(download.path()));
    }
}
