package com.campussphere.backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.CollegeRegistrationService;
import com.campussphere.backend.service.CollegeRegistrationService.*;

@RestController
public class CollegeRegistrationController {
    private static final String REVIEW="/api/platform/college-registration-requests";
    private final CollegeRegistrationService service;
    public CollegeRegistrationController(CollegeRegistrationService service) { this.service=service; }

    @PostMapping("/api/college-registration-requests") @ResponseStatus(HttpStatus.ACCEPTED)
    public void submit(@Valid @RequestBody Application input) { service.submit(input); }

    @GetMapping(REVIEW)
    public Page list(@AuthenticationPrincipal Account actor,@RequestParam(defaultValue="0") long after,
            @RequestParam(defaultValue="PENDING") String status) { return service.list(actor,after,status); }

    @GetMapping(REVIEW+"/{id}")
    public Details detail(@AuthenticationPrincipal Account actor,@PathVariable long id) { return service.detail(actor,id); }

    @PostMapping(REVIEW+"/{id}/approve") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@AuthenticationPrincipal Account actor,@PathVariable long id,@Valid @RequestBody Evidence input) {
        service.approve(actor,id,input);
    }
    @PostMapping(REVIEW+"/{id}/reject") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@AuthenticationPrincipal Account actor,@PathVariable long id,@Valid @RequestBody Rejection input) {
        service.reject(actor,id,input);
    }
}