package com.campussphere.backend.controller;

import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.ApiError;
import com.campussphere.backend.service.SkillExchange;
import com.campussphere.backend.service.SkillExchange.*;
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

    public SkillController(SkillExchange skills) {
        this.skills = skills;
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
