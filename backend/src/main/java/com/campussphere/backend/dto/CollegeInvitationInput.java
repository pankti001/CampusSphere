package com.campussphere.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CollegeInvitationInput {
    private CollegeInvitationInput() {}

    public record Issue(@NotBlank @Email @Size(max = 150) String email) {}

    public record Accept(
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Size(max = 100) String fullName,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @NotNull @Size(min = 12, max = 72) String password) {
        @Override
        public String toString() {
            return "CollegeAdminInvitationAcceptance[redacted]";
        }
    }
}
