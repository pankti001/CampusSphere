package com.campussphere.backend.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.campussphere.backend.dto.CollegeInvitationInput.Accept;

class InvitationTokenTest {
    @Test
    void usesUrlSafeUnpadded256BitTokensAndStableDigests() {
        String token = InvitationToken.generate();
        assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, Base64.getUrlDecoder().decode(token).length);
        assertNotEquals(token, InvitationToken.generate());
        assertEquals(32, InvitationToken.hash(token).length);
        assertArrayEquals(InvitationToken.hash(token), InvitationToken.hash(token));
    }

    @Test
    void requestAndResponseToStringExcludeSecrets() {
        String token = InvitationToken.generate();
        String password = "synthetic-only-password";
        var input = new Accept(token, "Synthetic Recipient", password);
        var issued = new CollegeAdminInvitationService.Issued(null, token);
        assertFalse(input.toString().contains(token));
        assertFalse(input.toString().contains(password));
        assertFalse(issued.toString().contains(token));
    }
}
