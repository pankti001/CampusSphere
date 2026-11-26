package com.campussphere.app

import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.widget.Toast
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import com.campussphere.app.presentation.auth.AcceptInvitationScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.campussphere.app.data.local.SessionStore
import com.campussphere.app.data.remote.api.AuthApi
import com.campussphere.app.data.remote.api.PlatformApi
import com.campussphere.app.data.remote.api.AcademicPeriodApi
import com.campussphere.app.data.remote.api.AcademicOfferingApi
import com.campussphere.app.presentation.college.CollegeAcademicStructure
import com.campussphere.app.presentation.college.FacultyScreen
import com.campussphere.app.presentation.college.StudentScreen
import com.campussphere.app.presentation.college.StudentMode
import com.campussphere.app.data.remote.api.StudentApi
import com.campussphere.app.data.repository.StudentRepository
import com.campussphere.app.data.remote.api.FacultyApi
import com.campussphere.app.data.repository.FacultyRepository
import com.campussphere.app.presentation.materials.MaterialScreen
import com.campussphere.app.data.remote.api.MaterialApi
import com.campussphere.app.data.repository.MaterialRepository
import com.campussphere.app.data.remote.api.AttendanceApi
import com.campussphere.app.data.repository.AttendanceRepository
import com.campussphere.app.presentation.attendance.AttendanceMode
import com.campussphere.app.presentation.attendance.AttendanceScreen
import com.campussphere.app.data.remote.api.ProgramApi
import com.campussphere.app.data.repository.DashboardRole
import com.campussphere.app.data.repository.PlatformRepository
import com.campussphere.app.data.repository.DepartmentRepository
import com.campussphere.app.presentation.platform.PlatformScreen
import com.campussphere.app.data.remote.api.RetrofitClient
import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.RegisterRequest
import com.campussphere.app.data.repository.AuthRepository
import com.campussphere.app.presentation.auth.LoginScreen
import com.campussphere.app.presentation.auth.RegisterScreen
import com.campussphere.app.presentation.auth.SessionScreen
import com.campussphere.app.presentation.auth.viewmodel.AuthRoute
import com.campussphere.app.presentation.auth.viewmodel.AuthViewModel
import com.campussphere.app.presentation.auth.viewmodel.AuthViewModelFactory
import com.campussphere.app.presentation.onboarding.OnboardingScreen
import com.campussphere.app.presentation.splash.SplashScreen
import com.campussphere.app.ui.theme.CampusSphereTheme

class MainActivity : ComponentActivity() {
    private var localNetworkAllowed by mutableStateOf(true)
    private val requestLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        localNetworkAllowed = hasLocalNetworkAccess()
    }

    override fun onResume() {
        super.onResume()
        localNetworkAllowed = hasLocalNetworkAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        localNetworkAllowed = hasLocalNetworkAccess()

        val authApi = RetrofitClient.createService(AuthApi::class.java)
        val repository = AuthRepository(authApi)
        val departmentRepository = DepartmentRepository(authApi)
        val periodApi = RetrofitClient.createService(AcademicPeriodApi::class.java)
        val offeringApi = RetrofitClient.createService(AcademicOfferingApi::class.java)
        val programApi = RetrofitClient.createService(ProgramApi::class.java)
        val facultyRepository = FacultyRepository(RetrofitClient.createService(FacultyApi::class.java),
            departmentRepository, programApi, periodApi, offeringApi)
        val studentRepository = StudentRepository(RetrofitClient.createService(StudentApi::class.java),
            departmentRepository, programApi, periodApi, offeringApi)
        val materials = MaterialRepository(RetrofitClient.createService(MaterialApi::class.java))
        val attendanceRepository = AttendanceRepository(RetrofitClient.createService(AttendanceApi::class.java))
        val platformRepository = PlatformRepository(RetrofitClient.createService(PlatformApi::class.java))
        val registrationApi = RetrofitClient.createService(com.campussphere.app.data.remote.api.CollegeRegistrationApi::class.java)
        val factory = AuthViewModelFactory(repository, SessionStore(applicationContext, BuildConfig.API_BASE_URL))

        setContent {
            CampusSphereTheme {
                if (!localNetworkAllowed) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                    ) {
                        Text("Allow local network access to connect to the CampusSphere development server.",
                            textAlign = TextAlign.Center)
                        Button(onClick = { requestLocalNetwork.launch(LOCAL_NETWORK_PERMISSION) }) {
                            Text("Allow local network access")
                        }
                    }
                    return@CampusSphereTheme
                }
                val auth: AuthViewModel = viewModel(factory = factory)
                val state by auth.uiState.collectAsStateWithLifecycle()
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { auth.refreshIfSignedIn() }

                BackHandler(state.route == AuthRoute.REGISTER || state.route == AuthRoute.ACCEPT_INVITATION) {
                    if (!state.isLoading) auth.showLogin()
                }
                LaunchedEffect(state.route, state.registrationComplete, state.invitationAccepted, state.error) {
                    val canToast = state.route == AuthRoute.LOGIN || state.route == AuthRoute.REGISTER
                    val message = when {
                        state.registrationComplete -> "Registration successful"
                        state.invitationAccepted -> "Invitation accepted. Sign in with your invited email."
                        canToast -> state.error?.message
                        else -> null
                    }
                    if (message != null) {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                        auth.clearNotice()
                    }
                }

                var application by androidx.compose.runtime.remember { mutableStateOf(false) }
                if (state.route == AuthRoute.LOGIN && application) {
                    com.campussphere.app.presentation.auth.CollegeApplicationScreen(registrationApi) { application = false }
                    return@CampusSphereTheme
                }
                when (state.route) {
                    AuthRoute.RESTORING -> SplashScreen()
                    AuthRoute.ONBOARDING -> OnboardingScreen(onGetStarted = auth::showLogin)
                    AuthRoute.LOGIN -> LoginScreen(
                        isLoading = state.isLoading,
                        onLogin = { email, password, rememberMe ->
                            auth.login(LoginRequest(email.trim(), password), rememberMe)
                        },
                        onRegister = auth::showRegister,
                        onRegisterCollege = { application = true }, onAcceptInvitation = auth::showInvitationAcceptance,
                        onForgotPassword = {
                            Toast.makeText(this, "Forgot password coming soon", Toast.LENGTH_SHORT).show()
                        }
                    )
                    AuthRoute.REGISTER -> RegisterScreen(
                        isLoading = state.isLoading,
                        onRegister = { name, email, phone, password, role, collegeCode ->
                            auth.register(RegisterRequest(name.trim(), email.trim(), phone.trim(), password, role, collegeCode))
                        },
                        onLogin = auth::showLogin
                    )
                    AuthRoute.ACCEPT_INVITATION -> {
                        DisposableEffect(Unit) {
                            val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
                            val wasSecure = (window.attributes.flags and secureFlag) != 0
                            window.addFlags(secureFlag)
                            onDispose { if (!wasSecure) window.clearFlags(secureFlag) }
                        }
                        AcceptInvitationScreen(state.isLoading, state.error?.message, auth::showLogin,
                            auth::acceptInvitation)
                    }
                    AuthRoute.ACCOUNT -> if (state.account?.dashboardRole == DashboardRole.SUPER_ADMIN) {
                        PlatformScreen(platformRepository, auth::platformAuthorization, auth::expirePlatformSession,
                            auth::logout)
                    } else if (state.account?.dashboardRole == DashboardRole.COLLEGE_ADMIN) {
                        var academicSection by remember { mutableStateOf<AcademicSection?>(null) }
                        val section = academicSection
                        if (section == AcademicSection.MATERIALS) MaterialScreen(materials,"college",auth::departmentAuthorization,{ if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { academicSection = null }
                        else if (section == AcademicSection.ATTENDANCE) {
                            AttendanceScreen(attendanceRepository, auth::departmentAuthorization, { status ->
                                if (status == 401) auth.expirePlatformSession() else auth.refresh()
                            }, AttendanceMode.ADMIN) { academicSection = null }
                        } else if (section == AcademicSection.FACULTY) {
                            FacultyScreen(facultyRepository, auth::departmentAuthorization, { status ->
                                if (status == 401) auth.expirePlatformSession() else auth.refresh()
                            }, { academicSection = null; auth.refresh() })
                        } else if (section == AcademicSection.STUDENTS) {
                            StudentScreen(studentRepository, auth::departmentAuthorization, { status ->
                                if (status == 401) auth.expirePlatformSession() else auth.refresh()
                            }, { academicSection = null; auth.refresh() })
                        } else if (section != null) {
                            CollegeAcademicStructure(section == AcademicSection.YEARS, departmentRepository,
                                programApi, periodApi, offeringApi, auth::departmentAuthorization, { status ->
                                    if (status == 401) auth.expirePlatformSession() else auth.refresh()
                                }) {
                                academicSection = null
                                auth.refresh()
                            }
                        } else SessionScreen(state, auth::refresh, auth::reapplyForFaculty, auth::logout,
                            onAcademicYears = { academicSection = AcademicSection.YEARS },
                            onDepartments = { academicSection = AcademicSection.DEPARTMENTS },
                            onFaculty = { academicSection = AcademicSection.FACULTY },
                            onStudents = { academicSection = AcademicSection.STUDENTS },
                            onMaterials = { academicSection = AcademicSection.MATERIALS }, onAttendance = { academicSection = AcademicSection.ATTENDANCE })
                    } else if (state.account?.dashboardRole == DashboardRole.FACULTY) {
                        var showAttendance by remember { mutableStateOf(false) }
                        var showMaterials by remember { mutableStateOf(false) }
                        if (showMaterials) MaterialScreen(materials,"faculty",auth::facultyAuthorization,{ if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { showMaterials = false }
                        else if (showAttendance) AttendanceScreen(attendanceRepository, auth::facultyAuthorization, { status ->
                            if (status == 401) auth.expirePlatformSession() else auth.refresh()
                        }, AttendanceMode.FACULTY) { showAttendance = false }
                        else FacultyScreen(facultyRepository, auth::facultyAuthorization, { status ->
                            if (status == 401) auth.expirePlatformSession() else auth.refresh()
                        }, auth::logout, dashboard = true, onMaterials = { showMaterials = true }, onAttendance = { showAttendance = true })
                    } else if (state.account?.studentAffiliationEligible == true) {
                        var showRequest by remember { mutableStateOf(false) }
                        if (showRequest) StudentScreen(studentRepository, auth::studentAffiliationAuthorization, { status ->
                            if (status == 401) auth.expirePlatformSession() else auth.refresh()
                        }, { showRequest = false; auth.refresh() }, StudentMode.AFFILIATION)
                        else SessionScreen(state, auth::refresh, auth::reapplyForFaculty, auth::logout,
                            onStudentAffiliation = { showRequest = true })
                    } else if (state.account?.dashboardRole == DashboardRole.STUDENT) {
                        var showAttendance by remember { mutableStateOf(false) }
                        var showMaterials by remember { mutableStateOf(false) }
                        if (showMaterials) MaterialScreen(materials,"student",auth::studentAuthorization,{ if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { showMaterials = false }
                        else if (showAttendance) AttendanceScreen(attendanceRepository, auth::studentAuthorization, { status ->
                            if (status == 401) auth.expirePlatformSession() else auth.refresh()
                        }, AttendanceMode.STUDENT) { showAttendance = false }
                        else StudentScreen(studentRepository, auth::studentAuthorization, { status ->
                            if (status == 401) auth.expirePlatformSession() else auth.refresh()
                        }, auth::logout, StudentMode.STUDENT, onMaterials = { showMaterials = true }, onAttendance = { showAttendance = true })
                    } else SessionScreen(
                        state = state, onRefresh = auth::refresh, onReapply = auth::reapplyForFaculty, onLogout = auth::logout
                    )
                    AuthRoute.RETRY_SESSION, AuthRoute.CLEAR_SESSION -> SessionScreen(
                        state = state,
                        onRefresh = auth::refresh,
                        onReapply = auth::reapplyForFaculty,
                        onLogout = auth::logout
                    )
                }
            }
        }
    }

    private fun hasLocalNetworkAccess(): Boolean =
        !BuildConfig.DEBUG || Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private enum class AcademicSection { YEARS, DEPARTMENTS, FACULTY, STUDENTS, ATTENDANCE, MATERIALS }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
