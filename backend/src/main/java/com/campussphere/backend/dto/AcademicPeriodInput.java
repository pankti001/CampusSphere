package com.campussphere.backend.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;

public record AcademicPeriodInput(@Size(max = 9) String code, @Size(max = 150) String name) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported academic period field");
    }
}
