package com.campussphere.backend.operator;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProvisioningEmailTest {
    @Test
    void acceptsStandardAddress() {
        assertTrue(ProvisioningEmail.valid(ProvisioningEmail.normalize("admin@example.com")));
    }

    @Test
    void normalizesUppercaseAddress() {
        String email = ProvisioningEmail.normalize("ADMIN@EXAMPLE.COM");
        assertEquals("admin@example.com", email);
        assertTrue(ProvisioningEmail.valid(email));
    }

    @Test
    void acceptsPlusTag() {
        assertTrue(ProvisioningEmail.valid(ProvisioningEmail.normalize("admin+ops@example.com")));
    }

    @Test
    void trimsSurroundingSpacesAndTabs() {
        String email = ProvisioningEmail.normalize(" \tadmin@example.com\t ");
        assertEquals("admin@example.com", email);
        assertTrue(ProvisioningEmail.valid(email));
    }

    @Test
    void rejectsBlankAndMissingInput() {
        for (String input : new String[] {null, "", " \t "}) {
            String email = ProvisioningEmail.normalize(input);
            assertTrue(email.isEmpty());
            assertFalse(ProvisioningEmail.valid(email));
        }
    }

    @Test
    void rejectsMalformedAndOversizedAddresses() {
        for (String input : new String[] {"admin", "admin@example", "admin@@example.com",
                "admin @example.com", "[admin@example.com](mailto:admin@example.com)",
                "a".repeat(151) + "@example.com"}) {
            assertFalse(ProvisioningEmail.valid(ProvisioningEmail.normalize(input)));
        }
    }
}
