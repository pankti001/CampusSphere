package com.campussphere.backend.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.AcademicResources;
import com.campussphere.backend.service.AcademicResources.*;
import com.campussphere.backend.service.StudyMaterials.FileDownload;
import org.springframework.core.io.FileSystemResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Academic Resources routes: syllabus versions ("syllabus") and previous-year question papers ("papers").
 * Spring Security limits /api/faculty/**, /api/student/** and /api/college/resources/** to the matching role; the
 * service then limits every query to the signed-in account's own college. No college, student or faculty id is ever
 * taken from the request; a subject id is only accepted after checking the account may use it.
 */
@RestController
public class AcademicResourceController {
    private static final String FACULTY = "/api/faculty/resources";
    private static final String STUDENT = "/api/student/resources";
    private static final String COLLEGE = "/api/college/resources";
    private static final String KIND = "/{kind:syllabus|papers}";

    private final AcademicResources resources;

    public AcademicResourceController(AcademicResources resources) {
        this.resources = resources;
    }

    private static Kind kind(String path) {
        return switch (path) {
            case "syllabus" -> Kind.SYLLABUS;
            case "papers" -> Kind.PAPER;
            default -> throw ApiError.RESOURCE_NOT_FOUND.exception();
        };
    }

    // ---- Faculty ----
    @GetMapping(FACULTY + "/subjects")
    List<SubjectChoice> teachable(@AuthenticationPrincipal Account actor) {
        return resources.teachableSubjects(actor);
    }

    @GetMapping(FACULTY + KIND)
    List<Entry> facultyList(@AuthenticationPrincipal Account actor, @PathVariable String kind,
                            @RequestParam(required = false) Long subjectId, @RequestParam(required = false) Integer examYear,
                            @RequestParam(required = false) String examType, @RequestParam(required = false) String status,
                            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        return resources.list(actor, CollegeRole.FACULTY, kind(kind),
                new Filter(subjectId, null, examYear, examType, status, null, q, page));
    }

    @GetMapping(FACULTY + KIND + "/{id}")
    Entry facultyDetail(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id) {
        return resources.detail(actor, CollegeRole.FACULTY, kind(kind), id);
    }

    @GetMapping(FACULTY + KIND + "/{id}/file")
    ResponseEntity<org.springframework.core.io.Resource> facultyFile(@AuthenticationPrincipal Account actor,
                                                                     @PathVariable String kind, @PathVariable long id) {
        return file(resources.download(actor, CollegeRole.FACULTY, kind(kind), id));
    }

    @PostMapping(path = FACULTY + "/syllabus", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Entry uploadSyllabus(@AuthenticationPrincipal Account actor, @RequestParam Long subjectId, @RequestParam String title,
                         @RequestParam(defaultValue = "") String description, @RequestParam String versionLabel,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
                         @RequestParam("file") MultipartFile file) throws IOException {
        return resources.uploadSyllabus(actor, subjectId, new SyllabusFields(title, description, versionLabel, effectiveFrom), file);
    }

    @PostMapping(path = FACULTY + "/papers", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Entry uploadPaper(@AuthenticationPrincipal Account actor, @RequestParam Long subjectId, @RequestParam String examType,
                      @RequestParam(required = false) String examTypeLabel, @RequestParam Integer examYear,
                      @RequestParam(defaultValue = "") String examSession, @RequestParam String title,
                      @RequestParam(defaultValue = "") String description, @RequestParam("file") MultipartFile file)
            throws IOException {
        return resources.uploadPaper(actor, subjectId,
                new PaperFields(examType, examTypeLabel, examYear, examSession, title, description), file);
    }

    @PatchMapping(FACULTY + "/syllabus/{id}")
    Entry editSyllabus(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody SyllabusFields fields) {
        return resources.editSyllabus(actor, id, fields);
    }

    @PatchMapping(FACULTY + "/papers/{id}")
    Entry editPaper(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody PaperFields fields) {
        return resources.editPaper(actor, id, fields);
    }

    @PutMapping(FACULTY + "/syllabus/{id}/units")
    Entry saveUnits(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody List<Unit> units) {
        return resources.saveUnits(actor, id, units);
    }

    @PostMapping(path = FACULTY + KIND + "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Entry replaceFile(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id,
                      @RequestParam("file") MultipartFile file) throws IOException {
        return resources.replaceFile(actor, kind(kind), id, file);
    }

    @PostMapping(FACULTY + KIND + "/{id}/archive")
    Entry archive(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id) {
        return resources.archive(actor, kind(kind), id, true);
    }

    @PostMapping(FACULTY + KIND + "/{id}/unarchive")
    Entry unarchive(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id) {
        return resources.archive(actor, kind(kind), id, false);
    }

    // ---- Student ----
    @GetMapping(STUDENT + "/semesters")
    List<SemesterChoice> semesters(@AuthenticationPrincipal Account actor) {
        return resources.semesters(actor);
    }

    @GetMapping(STUDENT + "/subjects")
    List<StudentSubject> subjects(@AuthenticationPrincipal Account actor, @RequestParam(required = false) Long semesterId) {
        return resources.subjects(actor, semesterId);
    }

    @GetMapping(STUDENT + KIND)
    List<Entry> studentList(@AuthenticationPrincipal Account actor, @PathVariable String kind,
                            @RequestParam(required = false) Long subjectId, @RequestParam(required = false) Long semesterId,
                            @RequestParam(required = false) Integer examYear, @RequestParam(required = false) String examType,
                            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        return resources.list(actor, CollegeRole.STUDENT, kind(kind),
                new Filter(subjectId, semesterId, examYear, examType, null, null, q, page));
    }

    @GetMapping(STUDENT + KIND + "/{id}")
    Entry studentDetail(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id) {
        return resources.detail(actor, CollegeRole.STUDENT, kind(kind), id);
    }

    @GetMapping(STUDENT + KIND + "/{id}/file")
    ResponseEntity<org.springframework.core.io.Resource> studentFile(@AuthenticationPrincipal Account actor,
                                                                     @PathVariable String kind, @PathVariable long id) {
        return file(resources.download(actor, CollegeRole.STUDENT, kind(kind), id));
    }

    // ---- College Admin ----
    @GetMapping(COLLEGE + KIND)
    List<Entry> collegeList(@AuthenticationPrincipal Account actor, @PathVariable String kind,
                            @RequestParam(required = false) Long subjectId, @RequestParam(required = false) Long semesterId,
                            @RequestParam(required = false) Integer examYear, @RequestParam(required = false) String examType,
                            @RequestParam(required = false) String status, @RequestParam(required = false) Long facultyId,
                            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
        return resources.list(actor, CollegeRole.COLLEGE_ADMIN, kind(kind),
                new Filter(subjectId, semesterId, examYear, examType, status, facultyId, q, page));
    }

    @GetMapping(COLLEGE + KIND + "/{id}")
    Entry collegeDetail(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id) {
        return resources.detail(actor, CollegeRole.COLLEGE_ADMIN, kind(kind), id);
    }

    @GetMapping(COLLEGE + KIND + "/{id}/file")
    ResponseEntity<org.springframework.core.io.Resource> collegeFile(@AuthenticationPrincipal Account actor,
                                                                     @PathVariable String kind, @PathVariable long id) {
        return file(resources.download(actor, CollegeRole.COLLEGE_ADMIN, kind(kind), id));
    }

    @GetMapping(COLLEGE + KIND + "/{id}/events")
    List<Event> events(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id) {
        return resources.events(actor, kind(kind), id);
    }

    @PostMapping(COLLEGE + KIND + "/{id}/moderation")
    Entry moderate(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id,
                   @RequestBody Moderation request) {
        return resources.moderate(actor, kind(kind), id, request);
    }

    @GetMapping(COLLEGE + "/coverage")
    List<Coverage> coverage(@AuthenticationPrincipal Account actor, @RequestParam(required = false) Long semesterId) {
        return resources.coverage(actor, semesterId);
    }

    @GetMapping(COLLEGE + "/stats")
    Stats stats(@AuthenticationPrincipal Account actor) {
        return resources.stats(actor);
    }

    /** Never cached, never sniffed as something else, always a download of the stored PDF. */
    private static ResponseEntity<org.springframework.core.io.Resource> file(FileDownload download) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(download.sizeBytes())
                .body(new FileSystemResource(download.path()));
    }
}
