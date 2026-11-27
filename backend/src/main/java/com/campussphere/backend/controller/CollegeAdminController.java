package com.campussphere.backend.controller;

import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.CollegeAccounts;
import com.campussphere.backend.service.CollegeAccounts.*;
import com.campussphere.backend.service.CollegeOverview;
import com.campussphere.backend.service.CollegeOverview.Counts;
import com.campussphere.backend.service.CollegeOverview.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * College Admin home and account care (COLLEGE_ADMIN only; the college always comes from the signed-in account).
 * /api/college/profile, /api/college/overview, /api/college/accounts/{faculty|students}[/{id}[/active]].
 */
@RestController
@RequestMapping("/api/college")
public class CollegeAdminController {
    private final CollegeOverview overview;
    private final CollegeAccounts accounts;

    public CollegeAdminController(CollegeOverview overview, CollegeAccounts accounts) {
        this.overview = overview;
        this.accounts = accounts;
    }

    @GetMapping("/profile")
    Profile profile(@AuthenticationPrincipal Account actor) {
        return overview.profile(actor);
    }

    @GetMapping("/overview")
    Counts counts(@AuthenticationPrincipal Account actor) {
        return overview.counts(actor);
    }

    @GetMapping("/accounts/{kind:faculty|students}")
    List<Person> list(@AuthenticationPrincipal Account actor, @PathVariable String kind,
                      @RequestParam(required = false) Boolean active, @RequestParam(required = false) String q) {
        return accounts.list(actor, Kind.of(kind), active, q);
    }

    @GetMapping("/accounts/{kind:faculty|students}/{id:\\d+}")
    Person get(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id) {
        return accounts.get(actor, Kind.of(kind), id);
    }

    @PatchMapping("/accounts/{kind:faculty|students}/{id:\\d+}")
    Person update(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id,
                  @RequestBody Details details) {
        return accounts.update(actor, Kind.of(kind), id, details);
    }

    @PutMapping("/accounts/{kind:faculty|students}/{id:\\d+}/active")
    Person setActive(@AuthenticationPrincipal Account actor, @PathVariable String kind, @PathVariable long id,
                     @RequestBody Status status) {
        return accounts.setActive(actor, Kind.of(kind), id, status);
    }
}
