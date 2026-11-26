package com.campussphere.backend.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.service.StudyMaterials;
import com.campussphere.backend.service.StudyMaterials.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Study material routes. Spring Security restricts /api/faculty/**, /api/student/** and /api/college/materials/**
 * to the matching role; the service then scopes every query to the authenticated account's college.
 */
@RestController
public class StudyMaterialController {
    private static final String FACULTY = "/api/faculty/materials";
    private static final String STUDENT = "/api/student/materials";
    private static final String COLLEGE = "/api/college/materials";

    private final StudyMaterials materials;

    public StudyMaterialController(StudyMaterials materials) {
        this.materials = materials;
    }

    // ---- Faculty ----
    @GetMapping(FACULTY + "/assignments")
    List<Assignment> assignments(@AuthenticationPrincipal Account actor) {
        return materials.assignments(actor);
    }

    @GetMapping(FACULTY)
    List<Material> facultyList(@AuthenticationPrincipal Account actor, @RequestParam(required = false) Long subjectId,
                               @RequestParam(required = false) Long divisionId, @RequestParam(required = false) String type,
                               @RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return materials.list(actor, CollegeRole.FACULTY, new Filter(subjectId, divisionId, null, type, status, q));
    }

    @GetMapping(FACULTY + "/{id}")
    Material facultyDetail(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return materials.detail(actor, CollegeRole.FACULTY, id);
    }

    @PostMapping(path = FACULTY, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Material upload(@AuthenticationPrincipal Account actor, @RequestParam long assignmentId, @RequestParam String title,
                    @RequestParam(defaultValue = "") String description, @RequestParam(required = false) String topic,
                    @RequestParam String type, @RequestParam("file") MultipartFile file) throws IOException {
        return materials.upload(actor, assignmentId, new Fields(title, description, topic, type), file);
    }

    @PatchMapping(FACULTY + "/{id}")
    Material update(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody Fields fields) {
        return materials.update(actor, id, fields);
    }

    @DeleteMapping(FACULTY + "/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        materials.delete(actor, id);
    }

    @GetMapping(FACULTY + "/{id}/file")
    ResponseEntity<Resource> facultyFile(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return file(materials.download(actor, CollegeRole.FACULTY, id));
    }

    // ---- Student ----
    @GetMapping(STUDENT)
    List<Material> studentList(@AuthenticationPrincipal Account actor, @RequestParam(required = false) Long subjectId,
                               @RequestParam(required = false) String type, @RequestParam(required = false) String q) {
        return materials.list(actor, CollegeRole.STUDENT, new Filter(subjectId, null, null, type, null, q));
    }

    @GetMapping(STUDENT + "/{id}")
    Material studentDetail(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return materials.detail(actor, CollegeRole.STUDENT, id);
    }

    @GetMapping(STUDENT + "/{id}/file")
    ResponseEntity<Resource> studentFile(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return file(materials.download(actor, CollegeRole.STUDENT, id));
    }

    // ---- College Admin ----
    @GetMapping(COLLEGE)
    List<Material> collegeList(@AuthenticationPrincipal Account actor, @RequestParam(required = false) Long subjectId,
                               @RequestParam(required = false) Long divisionId, @RequestParam(required = false) Long facultyId,
                               @RequestParam(required = false) String type, @RequestParam(required = false) String status,
                               @RequestParam(required = false) String q) {
        return materials.list(actor, CollegeRole.COLLEGE_ADMIN, new Filter(subjectId, divisionId, facultyId, type, status, q));
    }

    @GetMapping(COLLEGE + "/{id}")
    Material collegeDetail(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return materials.detail(actor, CollegeRole.COLLEGE_ADMIN, id);
    }

    @GetMapping(COLLEGE + "/{id}/file")
    ResponseEntity<Resource> collegeFile(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return file(materials.download(actor, CollegeRole.COLLEGE_ADMIN, id));
    }

    @GetMapping(COLLEGE + "/{id}/events")
    List<Event> events(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return materials.events(actor, id);
    }

    @PostMapping(COLLEGE + "/{id}/moderation")
    Material moderate(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody Moderation request) {
        return materials.moderate(actor, id, request);
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
