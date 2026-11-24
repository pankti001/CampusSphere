package com.campussphere.backend.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DepartmentInput(
        @Size(max = 30)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]*") String code,
        @Size(max = 150) String name) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported department field");
    }
}
