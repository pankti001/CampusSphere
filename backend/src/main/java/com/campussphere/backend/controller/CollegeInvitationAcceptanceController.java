package com.campussphere.backend.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.campussphere.backend.dto.CollegeInvitationInput;
import com.campussphere.backend.service.CollegeAdminInvitationService;

import jakarta.validation.Valid;

@RestController
public class CollegeInvitationAcceptanceController {
    private final CollegeAdminInvitationService invitations;

    public CollegeInvitationAcceptanceController(CollegeAdminInvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/api/auth/college-admin-invitations/accept")
    public ResponseEntity<Void> accept(@Valid @RequestBody CollegeInvitationInput.Accept input) {
        invitations.accept(input);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
