package com.campussphere.app

import com.campussphere.app.data.repository.AuthError
import com.campussphere.app.data.repository.AuthFailure
import com.campussphere.app.data.repository.AuthorizedAccount
import com.campussphere.app.data.repository.DashboardRole
import com.campussphere.app.presentation.auth.viewmodel.SilentRefresh
import com.campussphere.app.presentation.auth.viewmodel.silentRefreshOutcome
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** The background check when the app returns to the foreground must never leave the open screen without a reason. */
class SilentRefreshTest {
    private val faculty = AuthorizedAccount(verified = true, facultyStatus = "APPROVED",
        dashboardRole = DashboardRole.FACULTY, dashboardMessage = null)

    @Test fun successUpdatesTheAccountInPlace() {
        assertEquals(SilentRefresh.Update(faculty), silentRefreshOutcome(Result.success(faculty)))
    }

    @Test fun onlyAnExpiredLoginSignsOut() {
        assertEquals(SilentRefresh.SignOut,
            silentRefreshOutcome(Result.failure(AuthFailure(AuthError.INVALID_AUTHENTICATION))))
    }

    @Test fun networkAndServerProblemsKeepTheCurrentScreen() {
        assertEquals(SilentRefresh.Keep, silentRefreshOutcome(Result.failure(IOException("offline"))))
        assertEquals(SilentRefresh.Keep, silentRefreshOutcome(Result.failure(AuthFailure(AuthError.DATABASE_UNAVAILABLE))))
        assertEquals(SilentRefresh.Keep, silentRefreshOutcome(Result.failure(AuthFailure(AuthError.ACCESS_DENIED))))
    }
}
