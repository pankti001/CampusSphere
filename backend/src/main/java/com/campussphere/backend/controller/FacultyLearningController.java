package com.campussphere.backend.controller;

import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.SkillFaculty;
import com.campussphere.backend.service.SkillFaculty.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Students' requests to learn from the signed-in faculty member. Under /api/faculty/**, which Spring Security limits to
 * the FACULTY role; a faculty member only ever sees requests sent to them, in their own college.
 */
@RestController
@RequestMapping("/api/faculty/learning-requests")
public class FacultyLearningController {
    private static final String ID = "/{id:\\d+}";

    private final SkillFaculty faculty;

    public FacultyLearningController(SkillFaculty faculty) {
        this.faculty = faculty;
    }

    public record Count(long count) {}

    @GetMapping
    List<FacultyRequest> list(@AuthenticationPrincipal Account actor) {
        return faculty.facultyRequests(actor);
    }

    @GetMapping(ID)
    FacultyRequestDetail one(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return faculty.facultyRequest(actor, id);
    }

    @PostMapping(path = ID + "/accept", consumes = MediaType.APPLICATION_JSON_VALUE)
    FacultyRequestDetail accept(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody AcceptInput input) {
        return faculty.accept(actor, id, input);
    }

    @PostMapping(path = ID + "/decline", consumes = MediaType.APPLICATION_JSON_VALUE)
    FacultyRequestDetail decline(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody ReasonInput input) {
        return faculty.decline(actor, id, input);
    }

    @PostMapping(path = ID + "/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    FacultyRequestDetail cancel(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody ReasonInput input) {
        return faculty.act(actor, id, "cancel", input);
    }

    @PostMapping(ID + "/complete")
    FacultyRequestDetail complete(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return faculty.act(actor, id, "complete", null);
    }

    @GetMapping("/notices")
    List<FacultyNotice> notices(@AuthenticationPrincipal Account actor) {
        return faculty.notices(actor);
    }

    @GetMapping("/notices/unread")
    Count unread(@AuthenticationPrincipal Account actor) {
        return new Count(faculty.unread(actor));
    }

    @PostMapping("/notices" + ID + "/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        faculty.read(actor, id);
    }
}
