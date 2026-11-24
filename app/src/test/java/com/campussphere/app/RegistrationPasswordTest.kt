package com.campussphere.app

import com.campussphere.app.presentation.auth.validRegistrationPassword
import org.junit.Assert.*
import org.junit.Test

class RegistrationPasswordTest {
    @Test fun checksLengthAndConfirmation() {
        assertFalse(validRegistrationPassword("short","short"))
        assertFalse(validRegistrationPassword(" ".repeat(12)," ".repeat(12)))
        assertFalse(validRegistrationPassword("a".repeat(12),"b".repeat(12)))
        assertTrue(validRegistrationPassword("a".repeat(12),"a".repeat(12)))
    }
    @Test fun limitsUtf8Bytes() {
        assertTrue(validRegistrationPassword("é".repeat(36),"é".repeat(36)))
        assertFalse(validRegistrationPassword("é".repeat(37),"é".repeat(37)))
    }
}