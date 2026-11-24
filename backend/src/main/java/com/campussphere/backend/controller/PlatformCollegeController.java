package com.campussphere.backend.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.campussphere.backend.dto.CollegeInput;
import com.campussphere.backend.dto.CollegeInvitationInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.CollegeAdminInvitationService;
import com.campussphere.backend.service.PlatformCollegeService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/platform/colleges")
public class PlatformCollegeController {
    private final PlatformCollegeService colleges;
    private final CollegeAdminInvitationService invitations;

    public PlatformCollegeController(PlatformCollegeService colleges, CollegeAdminInvitationService invitations) {
        this.colleges = colleges;
        this.invitations = invitations;
    }

    @GetMapping
    public java.util.List<PlatformCollegeService.CollegeDetails> list(
            @RequestParam(required = false) String search, @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int limit) {
        return colleges.list(search, active, after, limit);
    }

    @GetMapping("/{collegeId}")
    public PlatformCollegeService.CollegeDetails get(@PathVariable long collegeId) {
        return colleges.get(collegeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformCollegeService.CollegeDetails create(@AuthenticationPrincipal Account actor,
            @Valid @RequestBody CollegeInput input) {
        return colleges.create(actor, input);
    }

    @PatchMapping("/{collegeId}")
    public PlatformCollegeService.CollegeDetails update(@AuthenticationPrincipal Account actor,
            @PathVariable long collegeId, @Valid @RequestBody CollegeInput input) {
        return colleges.update(actor, collegeId, input);
    }

    @PutMapping("/{collegeId}/status")
    public PlatformCollegeService.CollegeDetails status(@AuthenticationPrincipal Account actor,
            @PathVariable long collegeId, @Valid @RequestBody Status input) {
        return colleges.status(actor, collegeId, input.active());
    }

    @GetMapping("/{collegeId}/admin-invitations")
    public java.util.List<CollegeAdminInvitationService.Invitation> invitations(@PathVariable long collegeId,
            @RequestParam(defaultValue = "9223372036854775807") long before,
            @RequestParam(defaultValue = "50") int limit) {
        return invitations.list(collegeId, before, limit);
    }

    @PostMapping("/{collegeId}/admin-invitations")
    public ResponseEntity<CollegeAdminInvitationService.Issued> invite(@AuthenticationPrincipal Account actor,
            @PathVariable long collegeId, @Valid @RequestBody CollegeInvitationInput.Issue input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(invitations.issue(actor, collegeId, input.email()));
    }

    @PostMapping("/{collegeId}/admin-invitations/{invitationId}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Account actor, @PathVariable long collegeId,
            @PathVariable long invitationId) {
        invitations.revoke(actor, collegeId, invitationId);
    }

    public record Status(@NotNull Boolean active) {}
}
