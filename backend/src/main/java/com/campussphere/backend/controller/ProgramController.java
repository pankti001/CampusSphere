package com.campussphere.backend.controller;

import java.util.List;

import com.campussphere.backend.dto.ProgramInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.ProgramService;
import com.campussphere.backend.service.ProgramService.Program;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/college/departments/{departmentId}/programs")
public class ProgramController {
    private final ProgramService programs;

    public ProgramController(ProgramService programs) {
        this.programs = programs;
    }

    @GetMapping
    public List<Program> list(@AuthenticationPrincipal Account account, @PathVariable long departmentId) {
        return programs.list(account, departmentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Program create(@AuthenticationPrincipal Account account, @PathVariable long departmentId,
                          @Valid @RequestBody ProgramInput input) {
        return programs.create(account, departmentId, input);
    }

    @PatchMapping("/{programId}")
    public Program update(@AuthenticationPrincipal Account account, @PathVariable long departmentId,
                          @PathVariable long programId, @Valid @RequestBody ProgramInput input) {
        return programs.update(account, departmentId, programId, input);
    }
}
