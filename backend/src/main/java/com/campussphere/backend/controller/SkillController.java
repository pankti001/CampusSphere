package com.campussphere.backend.controller;

import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.SkillExchange;
import com.campussphere.backend.service.SkillExchange.*;
import com.campussphere.backend.service.SkillFaculty;
import com.campussphere.backend.service.SkillGroups;
import com.campussphere.backend.service.SkillWants;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Skill Exchange for students. Everything is under /api/student/**, which Spring Security limits to the STUDENT
 * role. No route takes a college: the service always uses the signed-in student's own college.
 */
@RestController
@RequestMapping("/api/student/skills")
public class SkillController {
    private static final String ID = "/{id:\\d+}";

    private final SkillExchange skills;
    private final SkillWants wants;
    private final SkillGroups groups;
    private final SkillFaculty faculty;

    public SkillController(SkillExchange skills, SkillWants wants, SkillGroups groups, SkillFaculty faculty) {
        this.skills = skills;
        this.wants = wants;
        this.groups = groups;
        this.faculty = faculty;
    }

    // ---- "I want to learn" posts ----

    @GetMapping("/wants")
    List<SkillWants.Want> wants(@AuthenticationPrincipal Account actor, @RequestParam(defaultValue = "false") boolean mine,
                                @RequestParam(required = false) String q, @RequestParam(required = false) String category,
                                @RequestParam(defaultValue = "0") int page) {
        return wants.list(actor, mine, q, blankToNull(category), page);
    }

    @PostMapping(path = "/wants", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SkillWants.Want post(@AuthenticationPrincipal Account actor, @RequestBody SkillWants.WantInput input) {
        return wants.create(actor, input);
    }

    @GetMapping("/wants" + ID)
    SkillWants.WantDetail want(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return wants.detail(actor, id);
    }

    @PostMapping("/wants" + ID + "/close")
    SkillWants.Want closeWant(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return wants.close(actor, id);
    }

    @PostMapping(path = "/wants" + ID + "/comments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SkillWants.Comment comment(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody SkillWants.TextInput input) {
        return wants.comment(actor, id, input);
    }

    @DeleteMapping("/wants" + ID + "/comments/{commentId:\\d+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteComment(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long commentId) {
        wants.deleteComment(actor, id, commentId);
    }

    @PostMapping("/wants" + ID + "/me-too")
    SkillWants.Want meToo(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return wants.meToo(actor, id, true);
    }

    @DeleteMapping("/wants" + ID + "/me-too")
    SkillWants.Want notMeToo(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return wants.meToo(actor, id, false);
    }

    @PostMapping(path = "/wants" + ID + "/help", consumes = MediaType.APPLICATION_JSON_VALUE)
    SkillWants.Want help(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody SkillWants.TextInput input) {
        return wants.help(actor, id, input);
    }

    @DeleteMapping("/wants" + ID + "/help")
    SkillWants.Want withdraw(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return wants.withdraw(actor, id);
    }

    @PostMapping("/wants" + ID + "/helpers/{helperId:\\d+}/accept")
    Request acceptHelper(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long helperId) {
        return wants.accept(actor, id, helperId);
    }

    @PostMapping("/wants" + ID + "/helpers/{helperId:\\d+}/decline")
    SkillWants.WantDetail declineHelper(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long helperId) {
        return wants.decline(actor, id, helperId);
    }

    // ---- Learning groups ----

    @GetMapping("/groups")
    List<SkillGroups.Group> groups(@AuthenticationPrincipal Account actor) {
        return groups.groups(actor);
    }

    @GetMapping("/groups" + ID)
    SkillGroups.GroupDetail group(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return groups.detail(actor, id);
    }

    @PostMapping(path = "/groups" + ID + "/sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SkillGroups.GroupDetail groupSession(@AuthenticationPrincipal Account actor, @PathVariable long id,
                                         @RequestBody SkillGroups.GroupSessionInput input) {
        return groups.schedule(actor, id, input);
    }

    @PostMapping("/groups" + ID + "/sessions/{sessionId:\\d+}/complete")
    SkillGroups.GroupDetail completeGroupSession(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long sessionId) {
        return groups.act(actor, id, sessionId, "complete", null);
    }

    @PostMapping(path = "/groups" + ID + "/sessions/{sessionId:\\d+}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    SkillGroups.GroupDetail cancelGroupSession(@AuthenticationPrincipal Account actor, @PathVariable long id,
                                               @PathVariable long sessionId, @RequestBody CancelInput input) {
        return groups.act(actor, id, sessionId, "cancel", input.reason());
    }

    @PostMapping("/groups" + ID + "/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void leaveGroup(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        groups.leave(actor, id);
    }

    // ---- Learning from faculty ----

    @GetMapping("/faculty")
    List<SkillFaculty.FacultyChoice> facultyChoices(@AuthenticationPrincipal Account actor) {
        return faculty.faculty(actor);
    }

    @GetMapping("/faculty-requests")
    List<SkillFaculty.FacultyRequest> facultyRequests(@AuthenticationPrincipal Account actor) {
        return faculty.requests(actor);
    }

    @PostMapping(path = "/faculty-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SkillFaculty.FacultyRequest askFaculty(@AuthenticationPrincipal Account actor, @RequestBody SkillFaculty.AskInput input) {
        return faculty.ask(actor, input);
    }

    @GetMapping("/faculty-requests" + ID)
    SkillFaculty.FacultyRequestDetail facultyRequest(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return faculty.request(actor, id);
    }

    @PostMapping("/faculty-requests" + ID + "/{action:join|leave|cancel}")
    SkillFaculty.FacultyRequest facultyRequestAction(@AuthenticationPrincipal Account actor, @PathVariable long id,
                                                     @PathVariable String action) {
        return switch (action) {
            case "join" -> faculty.join(actor, id);
            case "leave" -> faculty.leave(actor, id);
            default -> faculty.cancel(actor, id);
        };
    }

    public record Count(long count) {}

    private static <E extends Enum<E>> E choice(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException bad) {
            throw ApiError.SKILL_INVALID.exception();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ---- Marketplace and own offers ----

    @GetMapping("/summary")
    Summary summary(@AuthenticationPrincipal Account actor) {
        return skills.summary(actor);
    }

    @GetMapping("/offers")
    List<Offer> browse(@AuthenticationPrincipal Account actor, @RequestParam(required = false) String q,
                       @RequestParam(required = false) String category, @RequestParam(required = false) String level,
                       @RequestParam(required = false) String mode, @RequestParam(defaultValue = "0") int page) {
        return skills.browse(actor, new Filter(q, blankToNull(category), blankToNull(level), blankToNull(mode), page));
    }

    @GetMapping("/offers" + ID)
    Offer offer(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return skills.offer(actor, id);
    }

    @GetMapping("/mine")
    List<Offer> mine(@AuthenticationPrincipal Account actor) {
        return skills.myOffers(actor);
    }

    @PostMapping(path = "/mine", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Offer create(@AuthenticationPrincipal Account actor, @RequestBody OfferInput input) {
        return skills.createOffer(actor, input);
    }

    @PutMapping(path = "/mine" + ID, consumes = MediaType.APPLICATION_JSON_VALUE)
    Offer update(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody OfferInput input) {
        return skills.updateOffer(actor, id, input);
    }

    @PostMapping("/mine" + ID + "/{action:pause|resume}")
    Offer pause(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable String action) {
        return skills.setOfferStatus(actor, id, action);
    }

    @DeleteMapping("/mine" + ID)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        skills.setOfferStatus(actor, id, "archive");
    }

    // ---- Requests ----

    @GetMapping("/requests")
    List<Request> requests(@AuthenticationPrincipal Account actor, @RequestParam(defaultValue = "all") String box,
                           @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page) {
        return skills.requests(actor, choice(Box.class, box), blankToNull(status), page);
    }

    @GetMapping("/requests" + ID)
    Request request(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return skills.request(actor, id);
    }

    @PostMapping(path = "/requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Request ask(@AuthenticationPrincipal Account actor, @RequestBody RequestInput input) {
        return skills.createRequest(actor, input);
    }

    @PostMapping("/requests" + ID + "/{action:accept|reject|cancel}")
    Request decide(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable String action) {
        return skills.decide(actor, id, action);
    }

    @PostMapping(path = "/requests" + ID + "/sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Session propose(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody SessionInput input) {
        return skills.propose(actor, id, input);
    }

    // ---- Sessions and reviews ----

    @GetMapping("/sessions")
    List<Session> sessions(@AuthenticationPrincipal Account actor, @RequestParam(defaultValue = "upcoming") String when) {
        return skills.sessions(actor, choice(When.class, when));
    }

    @GetMapping("/sessions" + ID)
    Session session(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return skills.session(actor, id);
    }

    @PostMapping("/sessions" + ID + "/{action:confirm|complete}")
    Session act(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable String action) {
        return skills.act(actor, id, action, null);
    }

    @PostMapping(path = "/sessions" + ID + "/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    Session cancel(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody CancelInput input) {
        return skills.act(actor, id, "cancel", input);
    }

    @PostMapping(path = "/sessions" + ID + "/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Review review(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody ReviewInput input) {
        return skills.review(actor, id, input);
    }

    @GetMapping("/students" + ID + "/reviews")
    List<Review> reviews(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return skills.reviews(actor, id);
    }

    // ---- Notifications ----

    @GetMapping("/notifications")
    List<Notice> notices(@AuthenticationPrincipal Account actor, @RequestParam(defaultValue = "0") int page) {
        return skills.notices(actor, page);
    }

    @GetMapping("/notifications/unread")
    Count unread(@AuthenticationPrincipal Account actor) {
        return new Count(skills.unread(actor));
    }

    @PostMapping("/notifications" + ID + "/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        skills.markRead(actor, id);
    }
}
