package com.campussphere.backend.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;

/**
 * An academic year or semester. Dates are optional; give both or neither. On an update, dates change only when
 * startDate/endDate are sent, and clearDates=true removes them.
 */
public record AcademicPeriodInput(@Size(max = 9) String code, @Size(max = 150) String name,
                                  LocalDate startDate, LocalDate endDate, Boolean clearDates) {
    public AcademicPeriodInput(String code, String name) {
        this(code, name, null, null, null);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported academic period field");
    }
}
