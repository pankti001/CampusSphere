package com.campussphere.backend.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeRole;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.Announcements;
import com.campussphere.backend.service.Announcements.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Announcement routes for /api/college (COLLEGE_ADMIN), /api/faculty (FACULTY) and /api/student (STUDENT).
 * Spring Security limits each area to its role; the service re-checks every action and derives the college,
 * author and reader from the authenticated account. Students can only read and mark as read.
 */
@RestController
@RequestMapping("/api/{area:college|faculty|student}/announcements")
public class AnnouncementController {
    private final Announcements announcements;

    public AnnouncementController(Announcements announcements) {
        this.announcements = announcements;
    }

    private static CollegeRole role(String area) {
        return switch (area) {
            case "college" -> CollegeRole.COLLEGE_ADMIN;
            case "faculty" -> CollegeRole.FACULTY;
            default -> CollegeRole.STUDENT;
        };
    }

    @GetMapping
    List<Announcement> list(@AuthenticationPrincipal Account actor, @PathVariable String area,
                            @RequestParam(required = false) String category, @RequestParam(required = false) String priority,
                            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                            @RequestParam(required = false) Boolean unread, @RequestParam(defaultValue = "0") int page) {
        return announcements.list(actor, role(area), new Filter(category, priority, status, q, unread, page));
    }

    /** Faculty: announcements they wrote, in every status. */
    @GetMapping("/mine")
    List<Announcement> mine(@AuthenticationPrincipal Account actor, @PathVariable String area,
                            @RequestParam(required = false) String category, @RequestParam(required = false) String priority,
                            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page) {
        if (role(area) != CollegeRole.FACULTY) throw ApiError.ACCESS_DENIED.exception();
        return announcements.mine(actor, new Filter(category, priority, status, q, null, page));
    }

    @GetMapping("/targets")
    List<TargetOption> targets(@AuthenticationPrincipal Account actor, @PathVariable String area) {
        return announcements.targets(actor, role(area));
    }

    @GetMapping("/stats")
    Overview overview(@AuthenticationPrincipal Account actor, @PathVariable String area) {
        if (role(area) != CollegeRole.COLLEGE_ADMIN) throw ApiError.ACCESS_DENIED.exception();
        return announcements.overview(actor);
    }

    @GetMapping("/{id}")
    Announcement detail(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id) {
        return announcements.detail(actor, role(area), id);
    }

    @GetMapping("/{id}/stats")
    Stats stats(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id) {
        if (role(area) != CollegeRole.COLLEGE_ADMIN) throw ApiError.ACCESS_DENIED.exception();
        return announcements.stats(actor, id);
    }

    /** Creates a DRAFT. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Announcement create(@AuthenticationPrincipal Account actor, @PathVariable String area, @RequestBody Input input) {
        return announcements.create(actor, role(area), input);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Announcement update(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id,
                        @RequestBody Input input) {
        return announcements.update(actor, role(area), id, input);
    }

    /** Publishes now (no body or no publishAt) or schedules for a future publishAt. */
    @PostMapping("/{id}/publish")
    Announcement publish(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id,
                         @RequestBody(required = false) Publish request) {
        return announcements.publish(actor, role(area), id, request);
    }

    @PostMapping("/{id}/archive")
    Announcement archive(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id) {
        return announcements.archive(actor, role(area), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id) {
        announcements.delete(actor, role(area), id);
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id) {
        announcements.markRead(actor, role(area), id);
    }

    @PostMapping(path = "/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Announcement attach(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id,
                        @RequestParam("file") MultipartFile file) throws IOException {
        return announcements.attach(actor, role(area), id, file);
    }

    @DeleteMapping("/{id}/attachment")
    Announcement detach(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id) {
        return announcements.detach(actor, role(area), id);
    }

    @GetMapping("/{id}/attachment")
    ResponseEntity<Resource> download(@AuthenticationPrincipal Account actor, @PathVariable String area, @PathVariable long id) {
        FileDownload file = announcements.attachment(actor, role(area), id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(file.mimeType()))
                .contentLength(file.sizeBytes())
                .body(new FileSystemResource(file.path()));
    }
}
