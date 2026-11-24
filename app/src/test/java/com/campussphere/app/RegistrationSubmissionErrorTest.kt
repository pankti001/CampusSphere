package com.campussphere.app

import com.campussphere.app.presentation.auth.registrationSubmissionError
import java.io.IOException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RegistrationSubmissionErrorTest {
    @Test fun invalidFormOffersCorrectionAndHidesServerBody() {
        val message = registrationSubmissionError(httpError(400))
        assertTrue(message.contains("Check the form details"))
        assertTrue(message.contains("Both password fields were cleared"))
        assertFalse(message.contains("private-server-detail"))
    }

    @Test fun throttlingAndOutagesOfferLaterRetry() {
        for (status in listOf(429, 503)) {
            val message = registrationSubmissionError(httpError(status))
            assertTrue(message.contains("try again later"))
            assertFalse(message.contains("private-server-detail"))
        }
    }

    @Test fun connectionFailureDoesNotClaimTheServerRejectedTheSubmission() {
        val message = registrationSubmissionError(IOException("private-server-detail"))
        assertTrue(message.startsWith("Submission could not be confirmed."))
        assertTrue(message.contains("Both password fields were cleared"))
        assertFalse(message.contains("private-server-detail"))
    }

    private fun httpError(status: Int) =
        HttpException(Response.error<Unit>(status, "private-server-detail".toResponseBody()))
}
