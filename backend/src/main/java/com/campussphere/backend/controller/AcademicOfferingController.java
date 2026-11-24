package com.campussphere.backend.controller;

import java.util.List;

import com.campussphere.backend.dto.AcademicOfferingInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.AcademicOfferingService;
import com.campussphere.backend.service.AcademicOfferingService.Offering;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.campussphere.backend.service.AcademicOfferingService.Type.DIVISION;
import static com.campussphere.backend.service.AcademicOfferingService.Type.SUBJECT;

@RestController
@RequestMapping("/api/college/programs/{programId}")
public class AcademicOfferingController {
    private static final String DIVISIONS = "/academic-years/{yearId}/divisions";
    private static final String SUBJECTS = "/semesters/{semesterId}/subjects";
    private final AcademicOfferingService offerings;

    public AcademicOfferingController(AcademicOfferingService offerings) {
        this.offerings = offerings;
    }

    @GetMapping(DIVISIONS)
    public List<Offering> divisions(@AuthenticationPrincipal Account account, @PathVariable long programId,
                                    @PathVariable long yearId) {
        return offerings.list(account, DIVISION, programId, yearId);
    }

    @PostMapping(DIVISIONS)
    @ResponseStatus(HttpStatus.CREATED)
    public Offering createDivision(@AuthenticationPrincipal Account account, @PathVariable long programId,
                                   @PathVariable long yearId, @Valid @RequestBody AcademicOfferingInput input) {
        return offerings.create(account, DIVISION, programId, yearId, input);
    }

    @PatchMapping(DIVISIONS + "/{id}")
    public Offering updateDivision(@AuthenticationPrincipal Account account, @PathVariable long programId,
                                   @PathVariable long yearId, @PathVariable long id,
                                   @Valid @RequestBody AcademicOfferingInput input) {
        return offerings.update(account, DIVISION, programId, yearId, id, input);
    }

    @GetMapping(SUBJECTS)
    public List<Offering> subjects(@AuthenticationPrincipal Account account, @PathVariable long programId,
                                  @PathVariable long semesterId) {
        return offerings.list(account, SUBJECT, programId, semesterId);
    }

    @PostMapping(SUBJECTS)
    @ResponseStatus(HttpStatus.CREATED)
    public Offering createSubject(@AuthenticationPrincipal Account account, @PathVariable long programId,
                                  @PathVariable long semesterId, @Valid @RequestBody AcademicOfferingInput input) {
        return offerings.create(account, SUBJECT, programId, semesterId, input);
    }

    @PatchMapping(SUBJECTS + "/{id}")
    public Offering updateSubject(@AuthenticationPrincipal Account account, @PathVariable long programId,
                                  @PathVariable long semesterId, @PathVariable long id,
                                  @Valid @RequestBody AcademicOfferingInput input) {
        return offerings.update(account, SUBJECT, programId, semesterId, id, input);
    }
}
