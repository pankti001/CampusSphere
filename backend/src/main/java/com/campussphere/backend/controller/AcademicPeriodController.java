package com.campussphere.backend.controller;

import java.util.List;

import com.campussphere.backend.dto.AcademicPeriodInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.AcademicPeriodService;
import com.campussphere.backend.service.AcademicPeriodService.Period;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/college")
public class AcademicPeriodController {
    private static final String YEARS = "/academic-years";
    private static final String SEMESTERS = "/programs/{programId}/academic-years/{yearId}/semesters";
    private final AcademicPeriodService periods;

    public AcademicPeriodController(AcademicPeriodService periods) {
        this.periods = periods;
    }

    @GetMapping(YEARS)
    public List<Period> years(@AuthenticationPrincipal Account account) { return periods.years(account); }

    @PostMapping(YEARS)
    @ResponseStatus(HttpStatus.CREATED)
    public Period createYear(@AuthenticationPrincipal Account account, @Valid @RequestBody AcademicPeriodInput input) {
        return periods.createYear(account, input);
    }

    @PatchMapping(YEARS + "/{id}")
    public Period updateYear(@AuthenticationPrincipal Account account, @PathVariable long id,
                             @Valid @RequestBody AcademicPeriodInput input) {
        return periods.updateYear(account, id, input);
    }

    @GetMapping(SEMESTERS)
    public List<Period> semesters(@AuthenticationPrincipal Account account, @PathVariable long programId,
                                  @PathVariable long yearId) {
        return periods.semesters(account, programId, yearId);
    }

    @PostMapping(SEMESTERS)
    @ResponseStatus(HttpStatus.CREATED)
    public Period createSemester(@AuthenticationPrincipal Account account, @PathVariable long programId,
                                 @PathVariable long yearId, @Valid @RequestBody AcademicPeriodInput input) {
        return periods.createSemester(account, programId, yearId, input);
    }

    @PatchMapping(SEMESTERS + "/{id}")
    public Period updateSemester(@AuthenticationPrincipal Account account, @PathVariable long programId,
                                 @PathVariable long yearId, @PathVariable long id,
                                 @Valid @RequestBody AcademicPeriodInput input) {
        return periods.updateSemester(account, programId, yearId, id, input);
    }
}
