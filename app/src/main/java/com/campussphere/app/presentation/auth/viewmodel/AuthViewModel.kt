package com.campussphere.app.presentation.auth.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.local.SessionStore
import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.CollegeAdminInvitationAcceptance
import com.campussphere.app.data.remote.dto.RegisterRequest
import com.campussphere.app.data.repository.AuthError
import com.campussphere.app.data.repository.AuthFailure
import com.campussphere.app.data.repository.AuthRepository
import com.campussphere.app.data.repository.AuthorizedAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.GeneralSecurityException

enum class AuthRoute { RESTORING, ONBOARDING, LOGIN, REGISTER, ACCEPT_INVITATION, ACCOUNT, RETRY_SESSION, CLEAR_SESSION }

/** What a background account check on resume should do. */
internal sealed interface SilentRefresh {
    data class Update(val account: AuthorizedAccount) : SilentRefresh
    data object SignOut : SilentRefresh
    data object Keep : SilentRefresh
}

/** Success updates the account, an expired login signs out, anything else keeps the current screen. */
internal fun silentRefreshOutcome(result: Result<AuthorizedAccount>): SilentRefresh {
    result.getOrNull()?.let { return SilentRefresh.Update(it) }
    val error = (result.exceptionOrNull() as? AuthFailure)?.error
    return if (error == AuthError.INVALID_AUTHENTICATION) SilentRefresh.SignOut else SilentRefresh.Keep
}

data class AuthUiState(
    val route: AuthRoute = AuthRoute.RESTORING,
    val isLoading: Boolean = false,
    val account: AuthorizedAccount? = null,
    val error: AuthError? = null,
    val registrationComplete: Boolean = false,
    val invitationAccepted: Boolean = false
)

class AuthViewModel(
    private val authRepository: AuthRepository,
    private val sessionStore: SessionStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState = _uiState.asStateFlow()
    private var token: String? = null
    private var silentCheck: Job? = null

    init {
        restore()
    }

    fun showLogin() = goTo(AuthRoute.LOGIN)
    fun showRegister() = goTo(AuthRoute.REGISTER)
    fun showInvitationAcceptance() = goTo(AuthRoute.ACCEPT_INVITATION)

    fun acceptInvitation(input: CollegeAdminInvitationAcceptance) {
        if (_uiState.value.route != AuthRoute.ACCEPT_INVITATION) return
        perform {
            authRepository.acceptCollegeInvitation(input).getOrThrow()
            _uiState.value = AuthUiState(route = AuthRoute.LOGIN, invitationAccepted = true)
        }
    }

    fun clearNotice() {
        _uiState.value = _uiState.value.copy(error = null, registrationComplete = false, invitationAccepted = false)
    }

    fun register(request: RegisterRequest) {
        if (_uiState.value.route != AuthRoute.REGISTER) return
        perform {
            authRepository.register(request).getOrThrow()
            _uiState.value = AuthUiState(route = AuthRoute.LOGIN, registrationComplete = true)
        }
    }

    fun login(request: LoginRequest, rememberMe: Boolean) {
        if (_uiState.value.route != AuthRoute.LOGIN) return
        perform {
            val newToken = authRepository.login(request).getOrThrow()
            if (rememberMe) sessionStore.save(newToken)
            token = newToken
            checkStatus(newToken)
        }
    }

    /**
     * Runs when the app returns to the foreground (for example after a file picker, a viewer or an app switch).
     * The account is re-checked in the background while the current screen stays open, so forms, uploads and
     * unsaved attendance marks are kept. Only an expired login signs the user out; a changed account updates
     * the dashboard; a network problem is ignored here and shown by the open screen when it next calls the server.
     */
    fun refreshIfSignedIn() {
        val state = _uiState.value
        val current = token
        if (state.route != AuthRoute.ACCOUNT || state.isLoading || current == null || silentCheck?.isActive == true) return
        silentCheck = viewModelScope.launch {
            val outcome = try {
                silentRefreshOutcome(authRepository.account(current))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SilentRefresh.Keep
            }
            val now = _uiState.value
            if (token != current || now.route != AuthRoute.ACCOUNT || now.isLoading) return@launch
            when (outcome) {
                is SilentRefresh.Update -> if (outcome.account != now.account) _uiState.value = now.copy(account = outcome.account)
                SilentRefresh.SignOut -> perform { clearSession(AuthError.INVALID_AUTHENTICATION) }
                SilentRefresh.Keep -> Unit
            }
        }
    }

    fun refresh() {
        val current = token
        if (current == null) restore() else perform { checkStatus(current) }
    }

    fun reapplyForFaculty() {
        val current = token ?: return
        if (_uiState.value.account?.facultyStatus != "REJECTED") return
        perform {
            val result = authRepository.reapply(current)
            if ((result.exceptionOrNull() as? AuthFailure)?.error == AuthError.INVALID_AUTHENTICATION) {
                clearSession(AuthError.INVALID_AUTHENTICATION)
            } else {
                checkStatus(current)
                if (result.isFailure && _uiState.value.route == AuthRoute.ACCOUNT) {
                    _uiState.value = _uiState.value.copy(error = result.error())
                }
            }
        }
    }

    fun platformAuthorization(): String = authorizationFor(com.campussphere.app.data.repository.DashboardRole.SUPER_ADMIN)

    fun departmentAuthorization(): String = authorizationFor(com.campussphere.app.data.repository.DashboardRole.COLLEGE_ADMIN)

    fun facultyAuthorization(): String = authorizationFor(com.campussphere.app.data.repository.DashboardRole.FACULTY)

    fun studentAuthorization(): String = authorizationFor(com.campussphere.app.data.repository.DashboardRole.STUDENT)

    fun studentAffiliationAuthorization(): String {
        val state = _uiState.value
        val current = token
        if (state.route != AuthRoute.ACCOUNT || state.account?.studentAffiliationEligible != true || current == null) {
            throw AuthFailure(AuthError.ACCESS_DENIED)
        }
        return "Bearer $current"
    }

    private fun authorizationFor(role: com.campussphere.app.data.repository.DashboardRole): String {
        val state = _uiState.value
        val current = token
        if (state.route != AuthRoute.ACCOUNT ||
            state.account?.dashboardRole != role ||
            current == null) throw AuthFailure(AuthError.ACCESS_DENIED)
        return "Bearer $current"
    }

    fun expirePlatformSession() = perform { clearSession(AuthError.INVALID_AUTHENTICATION) }

    fun logout() = perform { clearSession() }

    private fun restore() = perform {
        _uiState.value = AuthUiState(route = AuthRoute.RESTORING, isLoading = true)
        val saved = sessionStore.load()
        token = saved
        if (saved == null) {
            _uiState.value = AuthUiState(route = AuthRoute.ONBOARDING)
        } else {
            checkStatus(saved)
        }
    }

    private suspend fun checkStatus(current: String) {
        _uiState.value = AuthUiState(route = AuthRoute.RESTORING, isLoading = true)
        val result = authRepository.account(current)
        val account = result.getOrNull()
        when {
            account != null -> _uiState.value = AuthUiState(route = AuthRoute.ACCOUNT, account = account)
            result.error() == AuthError.INVALID_AUTHENTICATION -> clearSession(AuthError.INVALID_AUTHENTICATION)
            else -> _uiState.value = AuthUiState(route = AuthRoute.RETRY_SESSION, error = result.error())
        }
    }

    private suspend fun clearSession(error: AuthError? = null) {
        token = null
        _uiState.value = AuthUiState(route = AuthRoute.CLEAR_SESSION, isLoading = true)
        sessionStore.clear()
        _uiState.value = AuthUiState(route = AuthRoute.LOGIN, error = error)
    }

    private fun goTo(route: AuthRoute) {
        val state = _uiState.value
        if (!state.isLoading && state.route in setOf(AuthRoute.ONBOARDING, AuthRoute.LOGIN, AuthRoute.REGISTER, AuthRoute.ACCEPT_INVITATION)) {
            _uiState.value = AuthUiState(route = route)
        }
    }

    private fun perform(action: suspend () -> Unit) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthFailure) {
                showError(e.error)
            } catch (_: GeneralSecurityException) {
                showError(AuthError.SESSION_STORAGE)
            } catch (_: IOException) {
                showError(AuthError.SESSION_STORAGE)
            } catch (_: SecurityException) {
                showError(AuthError.SESSION_STORAGE)
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private fun showError(error: AuthError) {
        val state = _uiState.value
        _uiState.value = state.copy(
            route = if (state.route == AuthRoute.RESTORING) AuthRoute.RETRY_SESSION else state.route,
            error = error
        )
    }

    private fun Result<*>.error(): AuthError =
        (exceptionOrNull() as? AuthFailure)?.error ?: AuthError.REQUEST_FAILED
}
