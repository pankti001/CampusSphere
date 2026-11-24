package com.campussphere.backend.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.campussphere.backend.dto.DepartmentInput;
import com.campussphere.backend.security.AccountAccessPolicy.Account;
import com.campussphere.backend.service.DepartmentService;
import com.campussphere.backend.service.DepartmentService.Department;

@RestController
@RequestMapping("/api/college/departments")
public class DepartmentController {
    private final DepartmentService departments;

    public DepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @GetMapping
    public List<Department> list(@AuthenticationPrincipal Account account) {
        return departments.list(account);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Department create(@AuthenticationPrincipal Account account, @Valid @RequestBody DepartmentInput input) {
        return departments.create(account, input);
    }

    @GetMapping("/{id}")
    public Department get(@AuthenticationPrincipal Account account, @PathVariable long id) {
        return departments.get(account, id);
    }

    @PatchMapping("/{id}")
    public Department update(@AuthenticationPrincipal Account account, @PathVariable long id,
                             @Valid @RequestBody DepartmentInput input) {
        return departments.update(account, id, input);
    }
}
