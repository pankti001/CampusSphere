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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.campussphere.app.data.remote.api.AnnouncementApi
import com.campussphere.app.data.repository.AnnouncementRepository
import com.campussphere.app.presentation.announcements.AnnouncementScreen
import com.campussphere.app.data.remote.api.TimetableApi
import com.campussphere.app.data.repository.TimetableRepository
import com.campussphere.app.presentation.timetable.MyTimetableScreen
import com.campussphere.app.presentation.timetable.TimetableAdminScreen
import com.campussphere.app.data.remote.api.TimetableImportApi
import com.campussphere.app.data.remote.api.FacultyNotificationApi
import com.campussphere.app.data.repository.TimetableImportRepository
import com.campussphere.app.data.repository.FacultyNotificationRepository
import com.campussphere.app.presentation.notifications.FacultyNotificationEffects
import com.campussphere.app.presentation.notifications.FacultyNotificationsScreen
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
        val chats = com.campussphere.app.data.repository.ChatRepository(
            RetrofitClient.createService(com.campussphere.app.data.remote.api.ChatApi::class.java),
            RetrofitClient.streamClient, RetrofitClient.baseUrl)
        val skills = com.campussphere.app.data.repository.SkillRepository(
            RetrofitClient.createService(com.campussphere.app.data.remote.api.SkillApi::class.java))
        val facultyLearning = com.campussphere.app.data.repository.FacultyLearningRepository(
            RetrofitClient.createService(com.campussphere.app.data.remote.api.FacultyLearningApi::class.java))
        val homework = com.campussphere.app.data.repository.HomeworkRepository(
            RetrofitClient.createService(com.campussphere.app.data.remote.api.HomeworkApi::class.java))
        val announcements = AnnouncementRepository(RetrofitClient.createService(AnnouncementApi::class.java), materials)
        val timetables = TimetableRepository(RetrofitClient.createService(TimetableApi::class.java))
        val timetableImports = TimetableImportRepository(RetrofitClient.createService(TimetableImportApi::class.java))
        val facultyNotices = FacultyNotificationRepository(RetrofitClient.createService(FacultyNotificationApi::class.java))
        val collegeAdmin = com.campussphere.app.data.repository.CollegeAdminRepository(RetrofitClient.createService(com.campussphere.app.data.remote.api.CollegeAdminApi::class.java))
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

                var application by rememberSaveable { mutableStateOf(false) }
                // Dark bar icons over the light welcome, sign-in and register screens; otherwise they follow the theme.
                com.campussphere.app.ui.theme.SystemBarIcons(dark = !androidx.compose.foundation.isSystemInDarkTheme() ||
                    (!application && state.route in setOf(AuthRoute.ONBOARDING, AuthRoute.LOGIN, AuthRoute.REGISTER)))
                if (state.route == AuthRoute.LOGIN && application) {
                    com.campussphere.app.presentation.auth.CollegeApplicationScreen(registrationApi) { application = false }
                    return@CampusSphereTheme
                }
                // Every change of route (sign in, sign out, register) fades and lifts into place.
                com.campussphere.app.ui.brand.ScreenSwitch(state.route) { route -> when (route) {
                    AuthRoute.RESTORING -> SplashScreen()
                    // Welcome, sign-in and register always draw a light background, so they always use the light
                    // colours too; otherwise in dark mode the typed text and labels turn light grey and vanish.
                    AuthRoute.ONBOARDING -> com.campussphere.app.ui.theme.LightBrandScreen { OnboardingScreen(onGetStarted = auth::showLogin) }
                    AuthRoute.LOGIN -> com.campussphere.app.ui.theme.LightBrandScreen { LoginScreen(
                        isLoading = state.isLoading,
                        onLogin = { email, password, rememberMe ->
                            auth.login(LoginRequest(email.trim(), password), rememberMe)
                        },
                        onRegister = auth::showRegister,
                        onRegisterCollege = { application = true }, onAcceptInvitation = auth::showInvitationAcceptance,
                        onForgotPassword = {
                            Toast.makeText(this, "Forgot password coming soon", Toast.LENGTH_SHORT).show()
                        }
                    ) }
                    AuthRoute.REGISTER -> com.campussphere.app.ui.theme.LightBrandScreen { RegisterScreen(
                        isLoading = state.isLoading,
                        onRegister = { name, email, phone, password, role, collegeCode ->
                            auth.register(RegisterRequest(name.trim(), email.trim(), phone.trim(), password, role, collegeCode))
                        },
                        onLogin = auth::showLogin
                    ) }
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
                        var academicSection by rememberSaveable { mutableStateOf<AcademicSection?>(null) }
                        com.campussphere.app.ui.brand.ScreenSwitch(academicSection) { section ->
                        if (section == AcademicSection.ASSIGNMENTS) com.campussphere.app.presentation.homework.AdminHomeworkScreen(homework, auth::departmentAuthorization, { if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { academicSection = null }
                        else if (section == AcademicSection.ACCOUNTS) com.campussphere.app.presentation.college.CollegeAccountsScreen(collegeAdmin, auth::departmentAuthorization, { if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { academicSection = null }
                        else                         if (section == AcademicSection.TIMETABLES) TimetableAdminScreen(timetables, timetableImports, auth::departmentAuthorization, { if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { academicSection = null }
                        else if (section == AcademicSection.ANNOUNCEMENTS) AnnouncementScreen(announcements, "college", auth::departmentAuthorization, { if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { academicSection = null }
                        else if (section == AcademicSection.MATERIALS) MaterialScreen(materials,"college",auth::departmentAuthorization,{ if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { academicSection = null }
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
                        } else com.campussphere.app.presentation.college.CollegeAdminHome(collegeAdmin, auth::departmentAuthorization,
                            { if (it == 401) auth.expirePlatformSession() else auth.refresh() }, state.isLoading, state.error?.message,
                            auth::refresh, auth::logout) { area ->
                            academicSection = when (area) {
                                com.campussphere.app.presentation.college.AdminArea.DEPARTMENTS -> AcademicSection.DEPARTMENTS
                                com.campussphere.app.presentation.college.AdminArea.YEARS -> AcademicSection.YEARS
                                com.campussphere.app.presentation.college.AdminArea.FACULTY -> AcademicSection.FACULTY
                                com.campussphere.app.presentation.college.AdminArea.STUDENTS -> AcademicSection.STUDENTS
                                com.campussphere.app.presentation.college.AdminArea.ACCOUNTS -> AcademicSection.ACCOUNTS
                                com.campussphere.app.presentation.college.AdminArea.TIMETABLES -> AcademicSection.TIMETABLES
                                com.campussphere.app.presentation.college.AdminArea.ATTENDANCE -> AcademicSection.ATTENDANCE
                                com.campussphere.app.presentation.college.AdminArea.MATERIALS -> AcademicSection.MATERIALS
                                com.campussphere.app.presentation.college.AdminArea.ANNOUNCEMENTS -> AcademicSection.ANNOUNCEMENTS
                                com.campussphere.app.presentation.college.AdminArea.ASSIGNMENTS -> AcademicSection.ASSIGNMENTS
                            }
                        }
                        }
                    } else if (state.account?.dashboardRole == DashboardRole.FACULTY) {
                        var showAttendance by rememberSaveable { mutableStateOf(false) }
                        var showMaterials by rememberSaveable { mutableStateOf(false) }
                        var showAnnouncements by rememberSaveable { mutableStateOf(false) }
                        var showTimetable by rememberSaveable { mutableStateOf(false) }
                        var showNotifications by rememberSaveable { mutableStateOf(false) }
                        var unreadNotices by rememberSaveable { mutableStateOf(0) }
                        var noticeRefresh by rememberSaveable { mutableStateOf(0) }
                        var showHomework by rememberSaveable { mutableStateOf(false) }
                        var homeworkCreate by rememberSaveable { mutableStateOf(false) }
                        var showLearning by rememberSaveable { mutableStateOf(false) }
                        FacultyNotificationEffects(facultyNotices, auth::facultyAuthorization, noticeRefresh) { unreadNotices = it }
                        val facultyPage = when {
                            showLearning -> "learning"; showHomework -> "homework"; showNotifications -> "notifications"; showTimetable -> "timetable"
                            showAnnouncements -> "announcements"; showMaterials -> "materials"; showAttendance -> "attendance"
                            else -> "home"
                        }
                        com.campussphere.app.ui.brand.ScreenSwitch(facultyPage) { page -> when (page) {
                        "learning" -> com.campussphere.app.presentation.skills.FacultyLearningScreen(facultyLearning, auth::facultyAuthorization,
                            { if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { showLearning = false }
                        "homework" -> com.campussphere.app.presentation.homework.FacultyHomeworkScreen(homework, auth::facultyAuthorization,
                            { if (it == 401) auth.expirePlatformSession() else auth.refresh() }, homeworkCreate) { showHomework = false; homeworkCreate = false }
                        "notifications" -> FacultyNotificationsScreen(facultyNotices, auth::facultyAuthorization, { if (it == 401) auth.expirePlatformSession() else auth.refresh() },
                            { showNotifications = false; noticeRefresh++ }) { showNotifications = false; showTimetable = true; noticeRefresh++ }
                        "timetable" -> MyTimetableScreen(timetables, "faculty", auth::facultyAuthorization, { if (it == 401) auth.expirePlatformSession() else auth.refresh() },
                            unreadNotices = unreadNotices, onNotices = { showTimetable = false; showNotifications = true }) { showTimetable = false }
                        "announcements" -> AnnouncementScreen(announcements, "faculty", auth::facultyAuthorization, { if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { showAnnouncements = false }
                        "materials" -> MaterialScreen(materials,"faculty",auth::facultyAuthorization,{ if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { showMaterials = false }
                        "attendance" -> AttendanceScreen(attendanceRepository, auth::facultyAuthorization, { status ->
                            if (status == 401) auth.expirePlatformSession() else auth.refresh()
                        }, AttendanceMode.FACULTY) { showAttendance = false }
                        else -> FacultyScreen(facultyRepository, auth::facultyAuthorization, { status ->
                            if (status == 401) auth.expirePlatformSession() else auth.refresh()
                        }, auth::logout, dashboard = true, onMaterials = { showMaterials = true }, onAttendance = { showAttendance = true },
                            onAnnouncements = { showAnnouncements = true }, onTimetable = { showTimetable = true },
                            onNotifications = { showNotifications = true }, unreadNotifications = unreadNotices,
                            homeworkCard = {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    com.campussphere.app.presentation.homework.FacultyHomeworkCard(homework, auth::facultyAuthorization,
                                        { if (it == 401) auth.expirePlatformSession() else auth.refresh() },
                                        { homeworkCreate = false; showHomework = true }) { homeworkCreate = true; showHomework = true }
                                    com.campussphere.app.presentation.skills.FacultyLearningCard(facultyLearning, auth::facultyAuthorization) { showLearning = true }
                                }
                            })
                        } }
                    } else if (state.account?.studentAffiliationEligible == true) {
                        var showRequest by rememberSaveable { mutableStateOf(false) }
                        if (showRequest) StudentScreen(studentRepository, auth::studentAffiliationAuthorization, { status ->
                            if (status == 401) auth.expirePlatformSession() else auth.refresh()
                        }, { showRequest = false; auth.refresh() }, StudentMode.AFFILIATION)
                        else SessionScreen(state, auth::refresh, auth::reapplyForFaculty, auth::logout,
                            onStudentAffiliation = { showRequest = true })
                    } else if (state.account?.dashboardRole == DashboardRole.STUDENT) {
                        var showAttendance by rememberSaveable { mutableStateOf(false) }
                        var showMaterials by rememberSaveable { mutableStateOf(false) }
                        var showAnnouncements by rememberSaveable { mutableStateOf(false) }
                        var showTimetable by rememberSaveable { mutableStateOf(false) }
                        var homeworkTab by rememberSaveable { mutableStateOf<String?>(null) }
                        val openTab = homeworkTab
                        var chatsOpen by rememberSaveable { mutableStateOf(false) }
                        var chatId by rememberSaveable { mutableStateOf<Long?>(null) }
                        var skillsOpen by rememberSaveable { mutableStateOf(false) }
                        val studentPage = when {
                            chatsOpen -> "chats"; skillsOpen -> "skills"; openTab != null -> "homework"; showTimetable -> "timetable"
                            showAnnouncements -> "announcements"; showMaterials -> "materials"; showAttendance -> "attendance"
                            else -> "home"
                        }
                        com.campussphere.app.ui.brand.ScreenSwitch(studentPage) { page -> when (page) {
                        "chats" -> com.campussphere.app.presentation.chat.ChatScreen(chats, homework, auth::studentAuthorization,
                            { if (it == 401) auth.expirePlatformSession() else auth.refresh() }, chatId) { chatsOpen = false; chatId = null }
                        // "Message" in Skill Exchange opens the private chat; Back from that chat returns to Skill Exchange.
                        "skills" -> com.campussphere.app.presentation.skills.SkillScreen(skills, chats, auth::studentAuthorization,
                            { if (it == 401) auth.expirePlatformSession() else auth.refresh() }, { id -> chatId = id; chatsOpen = true }) { skillsOpen = false }
                        "homework" -> com.campussphere.app.presentation.homework.StudentHomeworkScreen(homework, auth::studentAuthorization,
                            { if (it == 401) auth.expirePlatformSession() else auth.refresh() }, openTab ?: "PENDING") { homeworkTab = null }
                        "timetable" -> MyTimetableScreen(timetables, "student", auth::studentAuthorization, { if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { showTimetable = false }
                        "announcements" -> AnnouncementScreen(announcements, "student", auth::studentAuthorization, { if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { showAnnouncements = false }
                        "materials" -> MaterialScreen(materials,"student",auth::studentAuthorization,{ if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { showMaterials = false }
                        "attendance" -> AttendanceScreen(attendanceRepository, auth::studentAuthorization, { status ->
                            if (status == 401) auth.expirePlatformSession() else auth.refresh()
                        }, AttendanceMode.STUDENT) { showAttendance = false }
                        else -> com.campussphere.app.presentation.student.StudentHomeScreen(
                            vmFactory = {
                                com.campussphere.app.presentation.student.StudentHomeViewModel(chats, attendanceRepository, timetables,
                                    homework, materials, announcements, skills, auth::studentAuthorization,
                                    { if (it == 401) auth.expirePlatformSession() else auth.refresh() })
                            },
                            homeworkCard = {
                                com.campussphere.app.presentation.homework.StudentHomeworkCard(homework, auth::studentAuthorization,
                                    { if (it == 401) auth.expirePlatformSession() else auth.refresh() }) { homeworkTab = it }
                            },
                            open = { area, id ->
                                when (area) {
                                    com.campussphere.app.presentation.student.StudentArea.TIMETABLE -> showTimetable = true
                                    com.campussphere.app.presentation.student.StudentArea.ATTENDANCE -> showAttendance = true
                                    com.campussphere.app.presentation.student.StudentArea.MATERIALS -> showMaterials = true
                                    com.campussphere.app.presentation.student.StudentArea.ANNOUNCEMENTS -> showAnnouncements = true
                                    com.campussphere.app.presentation.student.StudentArea.CHATS -> { chatId = id; chatsOpen = true }
                                    com.campussphere.app.presentation.student.StudentArea.SKILLS -> skillsOpen = true
                                }
                            },
                            onRefreshAccount = auth::refresh, onLogout = auth::logout)
                        } }
                    } else SessionScreen(
                        state = state, onRefresh = auth::refresh, onReapply = auth::reapplyForFaculty, onLogout = auth::logout
                    )
                    AuthRoute.RETRY_SESSION, AuthRoute.CLEAR_SESSION -> SessionScreen(
                        state = state,
                        onRefresh = auth::refresh,
                        onReapply = auth::reapplyForFaculty,
                        onLogout = auth::logout
                    )
                } }
            }
        }
    }

    private fun hasLocalNetworkAccess(): Boolean =
        !BuildConfig.DEBUG || Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private enum class AcademicSection { YEARS, DEPARTMENTS, FACULTY, STUDENTS, ATTENDANCE, MATERIALS, ANNOUNCEMENTS, TIMETABLES, ACCOUNTS, ASSIGNMENTS }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
