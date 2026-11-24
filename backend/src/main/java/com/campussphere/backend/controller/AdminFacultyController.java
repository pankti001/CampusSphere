package com.campussphere.backend.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.FacultyAccessService;
import com.campussphere.backend.service.FacultyAccessService.Status;

@RestController
@RequestMapping("/api/admin/faculty-requests")
class AdminFacultyController {
    private final FacultyAccessService faculty;

    AdminFacultyController(FacultyAccessService faculty) {
        this.faculty = faculty;
    }

    @PostMapping("/{requestId}/decision")
    FacultyAccessService.Request decide(@PathVariable Long requestId, @RequestBody Decision decision,
                                        @AuthenticationPrincipal Account reviewer) {
        return faculty.decide(requestId, reviewer.id(), decision.status());
    }

    record Decision(Status status) {}
}
