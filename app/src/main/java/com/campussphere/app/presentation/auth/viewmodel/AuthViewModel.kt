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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.GeneralSecurityException

enum class AuthRoute { RESTORING, ONBOARDING, LOGIN, REGISTER, ACCEPT_INVITATION, ACCOUNT, RETRY_SESSION, CLEAR_SESSION }

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

    fun refreshIfSignedIn() {
        if (_uiState.value.route == AuthRoute.ACCOUNT) refresh()
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
