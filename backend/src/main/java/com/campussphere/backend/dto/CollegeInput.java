package com.campussphere.backend.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CollegeInput(
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,29}") String code,
        @Size(max = 150) String name) {}
