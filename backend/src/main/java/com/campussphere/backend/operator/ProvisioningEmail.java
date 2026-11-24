package com.campussphere.backend.operator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class ProvisioningEmail {
    private ProvisioningEmail() {}

    static String normalize(String input) {
        return input == null ? "" : input.strip().toLowerCase(Locale.ROOT);
    }

    static boolean valid(String normalized) {
        return normalized.length() <= 150 && normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    }

    // Source-file entry point used by the local PowerShell format preflight.
    // Only the classification leaves the process.
    public static void main(String[] args) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String email = normalize(reader.readLine());
            System.out.println(email.isEmpty() ? "BLANK" : valid(email) ? "VALID" : "MALFORMED");
        }
    }
}
