package com.campussphere.backend.controller;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.campussphere.backend.dto.LoginRequest;
import com.campussphere.backend.dto.LoginResponse;
import com.campussphere.backend.dto.RegisterRequest;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.security.AccountAccessPolicy.CollegeScope;
import com.campussphere.backend.service.AuthService;
import com.campussphere.backend.service.FacultyAccessService;
import com.campussphere.backend.service.StudentAffiliationService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {
    private final AuthService authService;
    private final FacultyAccessService faculty;

    public AuthController(AuthService authService, FacultyAccessService faculty) {
        this.authService = authService;
        this.faculty = faculty;
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.TEXT_PLAIN)
                .body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/status")
    public AccountStatus status(@AuthenticationPrincipal Account account) {
        var request = faculty.latest(account.id())
                .map(row -> new FacultyStatus(row.id(), row.status())).orElse(null);
        return new AccountStatus(account.verified(), account.roles(), request, account.college(),
                StudentAffiliationService.eligible(account));
    }

    @PostMapping("/faculty/reapply")
    public ResponseEntity<FacultyStatus> reapply(@AuthenticationPrincipal Account account) {
        var request = faculty.reapply(account.id());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new FacultyStatus(request.id(), request.status()));
    }

    public record AccountStatus(boolean verified, Set<String> roles, FacultyStatus facultyRequest,
                                CollegeScope college, boolean studentAffiliationEligible) {}
    public record FacultyStatus(Long id, FacultyAccessService.Status status) {}
}
