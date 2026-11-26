package com.campussphere.backend.controller;

import java.time.LocalDate;
import java.util.List;

import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.Timetables;
import com.campussphere.backend.service.Timetables.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Timetable routes. /api/college/timetables and /api/college/classrooms are for College Admins; /api/faculty/timetable
 * and /api/student/timetable are read-only personal views. Spring Security limits each area to its role and the
 * service derives the college, faculty member and student enrollment from the signed-in account.
 */
@RestController
public class TimetableController {
    private final Timetables timetables;

    public TimetableController(Timetables timetables) {
        this.timetables = timetables;
    }

    /** Body for a rejected save or publication; clashes or problems explain what to fix. */
    record RejectedBody(String code, String message, List<Clash> clashes, List<Problem> problems) {}

    @ExceptionHandler(Timetables.Rejected.class)
    ResponseEntity<RejectedBody> rejected(Timetables.Rejected rejected) {
        return ResponseEntity.status(rejected.error().status()).body(new RejectedBody(rejected.error().name(),
                rejected.getMessage(), rejected.clashes(), rejected.problems()));
    }

    // ---- College Admin ----

    @GetMapping("/api/college/timetables/scopes")
    List<ScopeOption> scopes(@AuthenticationPrincipal Account actor) {
        return timetables.scopes(actor);
    }

    @GetMapping("/api/college/timetables")
    List<Summary> list(@AuthenticationPrincipal Account actor, @RequestParam(required = false) Long academicYearId,
                       @RequestParam(required = false) Long departmentId, @RequestParam(required = false) Long programId,
                       @RequestParam(required = false) Long semesterId, @RequestParam(required = false) Long divisionId,
                       @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page) {
        return timetables.list(actor, new Filter(academicYearId, departmentId, programId, semesterId, divisionId, status, page));
    }

    @PostMapping("/api/college/timetables")
    @ResponseStatus(HttpStatus.CREATED)
    Detail create(@AuthenticationPrincipal Account actor, @RequestBody Create input) {
        return timetables.create(actor, input);
    }

    @GetMapping("/api/college/timetables/published")
    Detail published(@AuthenticationPrincipal Account actor, @RequestParam long semesterId, @RequestParam long divisionId) {
        return timetables.published(actor, semesterId, divisionId);
    }

    @GetMapping("/api/college/timetables/faculty/{facultyId:\\d+}")
    Schedule faculty(@AuthenticationPrincipal Account actor, @PathVariable long facultyId) {
        return timetables.facultySchedule(actor, facultyId);
    }

    @GetMapping("/api/college/timetables/{id:\\d+}")
    Detail detail(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return timetables.detail(actor, id);
    }

    /** The same data as the detail; ready tells whether publishing would succeed now. */
    @GetMapping("/api/college/timetables/{id:\\d+}/preview")
    Detail preview(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return timetables.detail(actor, id);
    }

    @GetMapping("/api/college/timetables/{id:\\d+}/options")
    Options options(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return timetables.options(actor, id);
    }

    @GetMapping("/api/college/timetables/{id:\\d+}/history")
    List<Event> history(@AuthenticationPrincipal Account actor, @PathVariable long id,
                        @RequestParam(defaultValue = "0") int page) {
        return timetables.history(actor, id, page);
    }

    @PostMapping("/api/college/timetables/{id:\\d+}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    Detail addEntry(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody EntryInput input) {
        return timetables.addEntry(actor, id, input);
    }

    @PutMapping("/api/college/timetables/{id:\\d+}/entries/{entryId:\\d+}")
    Detail updateEntry(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long entryId,
                       @RequestBody EntryInput input) {
        return timetables.updateEntry(actor, id, entryId, input);
    }

    @DeleteMapping("/api/college/timetables/{id:\\d+}/entries/{entryId:\\d+}")
    Detail deleteEntry(@AuthenticationPrincipal Account actor, @PathVariable long id, @PathVariable long entryId) {
        return timetables.deleteEntry(actor, id, entryId);
    }

    @PostMapping("/api/college/timetables/{id:\\d+}/publish")
    Detail publish(@AuthenticationPrincipal Account actor, @PathVariable long id, @RequestBody Publish input) {
        return timetables.publish(actor, id, input);
    }

    @PostMapping("/api/college/timetables/{id:\\d+}/archive")
    Detail archive(@AuthenticationPrincipal Account actor, @PathVariable long id) {
        return timetables.archive(actor, id);
    }

    @GetMapping("/api/college/classrooms")
    List<Classroom> classrooms(@AuthenticationPrincipal Account actor) {
        return timetables.classrooms(actor);
    }

    @PostMapping("/api/college/classrooms")
    @ResponseStatus(HttpStatus.CREATED)
    Classroom createClassroom(@AuthenticationPrincipal Account actor, @RequestBody ClassroomInput input) {
        return timetables.createClassroom(actor, input);
    }

    @PutMapping("/api/college/classrooms/{id:\\d+}")
    Classroom updateClassroom(@AuthenticationPrincipal Account actor, @PathVariable long id,
                              @RequestBody ClassroomInput input) {
        return timetables.updateClassroom(actor, id, input);
    }

    // ---- Faculty (read only) ----

    @GetMapping("/api/faculty/timetable")
    Schedule facultyWeek(@AuthenticationPrincipal Account actor) {
        return timetables.facultyWeek(actor);
    }

    @GetMapping("/api/faculty/timetable/day")
    Day facultyDay(@AuthenticationPrincipal Account actor,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return timetables.facultyDay(actor, date);
    }

    // ---- Student (read only; division from the current enrollment) ----

    @GetMapping("/api/student/timetable")
    Schedule studentWeek(@AuthenticationPrincipal Account actor) {
        return timetables.studentWeek(actor);
    }

    @GetMapping("/api/student/timetable/day")
    Day studentDay(@AuthenticationPrincipal Account actor,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return timetables.studentDay(actor, date);
    }
}
