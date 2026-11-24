package com.campussphere.backend.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AcademicOfferingInput(@Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,29}") String code,
                                    @Size(max = 150) String name) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported academic offering field");
    }
}
