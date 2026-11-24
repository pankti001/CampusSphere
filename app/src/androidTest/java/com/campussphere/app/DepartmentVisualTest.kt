package com.campussphere.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.campussphere.app.data.remote.api.AuthApi

import com.campussphere.app.data.remote.api.RetrofitClient
import com.campussphere.app.data.remote.api.StudentCollegeInput
import com.campussphere.app.data.remote.api.StudentDecisionInput
import com.campussphere.app.data.remote.api.StudentDepartmentInput
import com.campussphere.app.data.remote.api.StudentEnrollmentInput
import com.campussphere.app.data.remote.dto.AcademicCatalogInput
import com.campussphere.app.data.remote.dto.LoginRequest
import com.campussphere.app.data.remote.dto.RegisterRequest
import com.campussphere.app.data.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import com.campussphere.app.data.remote.api.StudentApi
import com.campussphere.app.data.repository.StudentRepository
import com.campussphere.app.presentation.college.StudentMode
import com.campussphere.app.presentation.college.StudentScreen
import com.campussphere.app.data.remote.api.FacultyApi
import com.campussphere.app.data.repository.FacultyRepository
import com.campussphere.app.data.repository.AuthorizedAccount
import com.campussphere.app.data.repository.DashboardRole
import com.campussphere.app.presentation.auth.SessionScreen
import com.campussphere.app.presentation.auth.viewmodel.AuthUiState
import com.campussphere.app.presentation.auth.viewmodel.AuthRoute
import com.campussphere.app.presentation.college.FacultyScreen
import com.campussphere.app.data.remote.api.AcademicPeriodApi
import com.campussphere.app.data.remote.api.AcademicOfferingApi
import com.campussphere.app.data.remote.api.ProgramApi
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import com.campussphere.app.data.repository.DepartmentRepository
import com.campussphere.app.data.repository.ProgramRepository
import com.campussphere.app.presentation.college.AcademicCatalogViewModel
import com.campussphere.app.presentation.college.CollegeAcademicStructure
import com.campussphere.app.presentation.college.DepartmentScreen
import com.campussphere.app.presentation.college.DepartmentViewModel
import com.campussphere.app.presentation.college.ProgramScreen
import com.campussphere.app.ui.theme.CampusSphereTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class DepartmentVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()




    @Test fun studentLiveEnrollment() {
        val live = LiveStudent(loadLiveCredentials())
        val alphaAdmin = live.login("alphaAdmin", DashboardRole.COLLEGE_ADMIN)
        val betaAdmin = live.login("betaAdmin", DashboardRole.COLLEGE_ADMIN)
        val faculty = live.login("faculty", DashboardRole.FACULTY)
        val alpha = live.catalog(alphaAdmin, "SCI", "Science", "BIO", "Biology program")
        val beta = live.catalog(betaAdmin, "ART", "Arts", "HUM", "Humanities program")
        live.registerStudent("alphaStudent", "Student Alpha")
        live.registerStudent("betaStudent", "Student Beta")
        val (_, betaStudent) = live.studentLogin("betaStudent", unbound = true)
        val (alphaId, alphaStudent) = live.studentLogin("alphaStudent", unbound = true)
        liveCheck("MISSING_JWT", 401, runBlocking { live.students.students("") })
        liveCheck("MALFORMED_JWT", 401, runBlocking { live.students.students("Bearer invalid") })
        liveCheck("FACULTY_DENIED", 403, runBlocking { live.students.students(faculty) })
        liveCheck("APPLICANT_DENIED_ENROLLMENT", 403, runBlocking { live.students.current(alphaStudent) })
        liveCheck("INVALID_COLLEGE_CODE", 404,
            runBlocking { live.students.request(alphaStudent, StudentCollegeInput("SME-NONEXISTENT")) })
        check(liveBody(runBlocking { live.students.myRequest(alphaStudent) }, "own affiliation empty").isEmpty())
        val betaRequest = liveBody(runBlocking { live.students.request(betaStudent, StudentCollegeInput("SME-BETA")) }, "Beta request", 201)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            showLiveStudent(scenario, live, alphaStudent, StudentMode.AFFILIATION)
            input("College code", "SME-ALPHA")
            click("Send request")
            node("Admission review pending")
            screenshot("01-affiliation-pending", LIVE_FOLDER)
            val alphaRequest = liveBody(runBlocking { live.students.myRequest(alphaStudent) }, "Alpha request").single()
            check(alphaRequest.status == "PENDING")
            check(liveBody(runBlocking { live.students.pending(alphaAdmin) }, "Alpha queue").map { it.id } == listOf(alphaRequest.id))
            check(liveBody(runBlocking { live.students.pending(betaAdmin) }, "Beta queue").map { it.id } == listOf(betaRequest.id))
            liveCheck("FOREIGN_APPROVAL_DENIED", 404, runBlocking {
                live.students.decide(betaAdmin, alphaRequest.id, StudentDecisionInput(true, true))
            })
            liveCheck("UNCONFIRMED_ADMISSION_DENIED", 400, runBlocking {
                live.students.decide(alphaAdmin, alphaRequest.id, StudentDecisionInput(true, false))
            })
            liveCheck("DUPLICATE_AFFILIATION_DENIED", 409, runBlocking {
                live.students.request(alphaStudent, StudentCollegeInput("SME-ALPHA"))
            })

            showLiveStudent(scenario, live, alphaAdmin, StudentMode.ADMIN)
            click("Affiliation requests")
            click("Student Alpha")
            node("Review affiliation")
            screenshot("02-admission-review", LIVE_FOLDER)
            click("I verified this student's admission in our college records.")
            click("Approve affiliation")
            node("Student affiliation approved.")
            screenshot("03-affiliation-approved", LIVE_FOLDER)
            val betaApproved = liveBody(runBlocking {
                live.students.decide(betaAdmin, betaRequest.id, StudentDecisionInput(true, true))
            }, "Beta admission approved")
            check(betaApproved.status == "APPROVED")
            liveCheck("BETA_CANNOT_READ_ALPHA_STUDENT", 404, runBlocking { live.students.profile(betaAdmin, alphaId) })
            liveCheck("BETA_CANNOT_ASSIGN_ALPHA_DEPARTMENT", 404, runBlocking {
                live.students.department(betaAdmin, alphaId, StudentDepartmentInput(beta.departmentId))
            })
            liveCheck("ALPHA_CANNOT_USE_BETA_DEPARTMENT", 404, runBlocking {
                live.students.department(alphaAdmin, alphaId, StudentDepartmentInput(beta.departmentId))
            })
            click("Back")
            click("Student Alpha")
            click("Set department")
            click("Science")
            node("Department saved.")
            screenshot("04-department-assigned", LIVE_FOLDER)
            liveCheck("MISMATCHED_SEMESTER_DENIED", 400, runBlocking {
                live.students.enroll(alphaAdmin, alphaId, alpha.copy(semesterId = beta.semesterId))
            })
            click("Enroll student")
            click("Biology program")
            click("Academic Year 2026")
            click("First semester")
            click("Division A")
            node("Review enrollment")
            screenshot("05-enrollment-review", LIVE_FOLDER)
            click("Save enrollment")
            node("Enrollment saved.")
            screenshot("06-enrollment-saved", LIVE_FOLDER)
            liveCheck("DUPLICATE_ENROLLMENT_DENIED", 409, runBlocking { live.students.enroll(alphaAdmin, alphaId, alpha) })
            liveCheck("FOREIGN_HISTORY_DENIED", 404, runBlocking { live.students.history(betaAdmin, alphaId) })
            val (_, boundToken) = live.studentLogin("alphaStudent", unbound = false)
            liveCheck("STUDENT_ADMIN_API_DENIED", 403, runBlocking { live.students.students(boundToken) })
            // The pre-approval JWT is still signed and now resolves current Student permissions from PostgreSQL.
            val enrollment = liveBody(runBlocking { live.students.current(alphaStudent) }, "existing JWT reads current enrollment").single()
            check(enrollment.current && enrollment.program == "Biology program" && enrollment.division == "Division A")
            val (_, betaBoundToken) = live.studentLogin("betaStudent", unbound = false)
            check(liveBody(runBlocking { live.students.current(betaBoundToken) }, "Beta own current enrollment").isEmpty())
            showLiveStudent(scenario, live, boundToken, StudentMode.STUDENT)
            node("My current enrollment")
            node("Division A")
            screenshot("07-student-dashboard", LIVE_FOLDER)
        }
    }

    @Test fun studentLiveRestartAndHistory() {
        val live = LiveStudent(loadLiveCredentials())
        val admin = live.login("alphaAdmin", DashboardRole.COLLEGE_ADMIN)
        val (studentId, student) = live.studentLogin("alphaStudent", unbound = false)
        val persisted = liveBody(runBlocking { live.students.current(student) }, "enrollment after real restart").single()
        check(persisted.current && persisted.college == "Student Rollout Alpha")
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            showLiveStudent(scenario, live, student, StudentMode.STUDENT)
            node("My current enrollment")
            node("Division A")
            screenshot("08-persisted-after-restarts", LIVE_FOLDER)
            signalLiveHost("student-live-resume-ready-outage")
            awaitLiveHost("student-live-resume-backend-down")
            click("Refresh")
            // Allow the production Retrofit connection/read timeout to expire before checking recovery.
            node("CampusSphere could not complete the request. Refresh to check the current state.", timeoutMillis = 70_000)
            check(tree(instrumentation.uiAutomation.rootInActiveWindow).none { it.text?.toString() == "Division A" })
            screenshot("09-backend-unavailable", LIVE_FOLDER)
            signalLiveHost("student-live-resume-outage-observed")
            awaitLiveHost("student-live-resume-backend-up")
            click("Retry")
            node("Division A")
            screenshot("10-backend-recovered", LIVE_FOLDER)
            showLiveStudent(scenario, live, admin, StudentMode.ADMIN)
            click("Student Alpha")
            click("End current enrollment")
            node("End current enrollment?")
            click("End enrollment")
            node("Past enrollment")
            screenshot("11-history-retained", LIVE_FOLDER)
            val old = liveBody(runBlocking { live.students.history(admin, studentId) }, "persisted history").single()
            check(old.id == persisted.id && !old.current)
            showLiveStudent(scenario, live, student, StudentMode.STUDENT)
            node("You do not have a current enrollment.")
            screenshot("12-no-current-enrollment", LIVE_FOLDER)
        }
    }

    @Test fun studentLiveVerifyHistoryAfterRestart() {
        val live = LiveStudent(loadLiveCredentials())
        val admin = live.login("alphaAdmin", DashboardRole.COLLEGE_ADMIN)
        val (studentId, student) = live.studentLogin("alphaStudent", unbound = false)
        val past = liveBody(runBlocking { live.students.history(admin, studentId) },
            "history after backend and app restart").single()
        check(!past.current && past.college == "Student Rollout Alpha" &&
            past.program == "Biology program" && past.division == "Division A")
        check(liveBody(runBlocking { live.students.current(student) },
            "no current enrollment after history restart").isEmpty())
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            showLiveStudent(scenario, live, admin, StudentMode.ADMIN)
            click("Student Alpha")
            node("Past enrollment")
            screenshot("13-history-after-restarts", LIVE_FOLDER)
        }
    }

    private fun loadLiveCredentials(): com.google.gson.JsonObject {
        val directory = instrumentation.targetContext.filesDir
        val file = File(directory, "student-live-credentials")
        check(file.isFile) { "Run the isolated live launcher for private fixture credentials" }
        try {
            return JsonParser.parseString(file.readText()).asJsonObject
        } finally {
            check(file.delete()) { "Could not remove temporary device credentials" }
        }
    }

    private fun showLiveStudent(scenario: ActivityScenario<ComponentActivity>, live: LiveStudent, token: String, mode: StudentMode) {
        scenario.onActivity { activity ->
            activity.setContent {
                key(mode) {
                    CampusSphereTheme {
                        Column(Modifier.background(Color(0xFFEBEFFE)).safeDrawingPadding()) {
                            Text("Live PostgreSQL · Synthetic accounts",
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
                                color = Color(0xFF3447A6), style = MaterialTheme.typography.labelMedium)
                            StudentScreen(live.repository, { token }, { error("Unexpected live access failure: $it") }, {}, mode)
                        }
                    }
                }
            }
        }
    }

    private fun signalLiveHost(name: String) {
        File(instrumentation.targetContext.filesDir, name).writeText("ready")
    }

    private fun awaitLiveHost(name: String) {
        val signal = File(instrumentation.targetContext.filesDir, name)
        repeat(240) {
            if (signal.isFile) return
            Thread.sleep(250)
        }
        error("Timed out waiting for live test checkpoint $name")
    }

    private fun liveCheck(label: String, expected: Int, response: retrofit2.Response<*>) {
        response.errorBody()?.close()
        check(response.code() == expected) { "$label expected HTTP $expected, received ${response.code()}" }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "LIVE_CHECK=$label|$expected\n") })
    }

    private fun <T> liveBody(response: retrofit2.Response<T>, label: String, expected: Int = 200): T {
        liveCheck(label, expected, response)
        return checkNotNull(response.body()) { "$label response body missing" }
    }

    private inner class LiveStudent(private val fixtures: com.google.gson.JsonObject) {
        private val auth = RetrofitClient.createService(AuthApi::class.java)
        private val periods = RetrofitClient.createService(AcademicPeriodApi::class.java)
        private val offerings = RetrofitClient.createService(AcademicOfferingApi::class.java)
        private val programs = RetrofitClient.createService(ProgramApi::class.java)
        val students = RetrofitClient.createService(StudentApi::class.java)
        val repository = StudentRepository(students, DepartmentRepository(auth), programs, periods, offerings)

        private fun email(key: String): String = when (key) {
            "alphaAdmin" -> "sme-alpha-admin@example.invalid"
            "betaAdmin" -> "sme-beta-admin@example.invalid"
            "faculty" -> "sme-alpha-faculty@example.invalid"
            "alphaStudent" -> "sme-alpha-student@example.invalid"
            "betaStudent" -> "sme-beta-student@example.invalid"
            else -> error("Unknown live fixture key")
        }

        fun login(key: String, role: DashboardRole): String = runBlocking {
            val response = liveBody(auth.login(LoginRequest(email(key), fixtures.get(key).asString)), "fixture login")
            val status = AuthRepository(auth).account(response.token).getOrThrow()
            check(status.dashboardRole == role) { "Unexpected current role" }
            "Bearer " + response.token
        }

        fun registerStudent(key: String, name: String) = runBlocking {
            val phone = if (key == "alphaStudent") "0000000131" else "0000000132"
            liveCheck("REGISTER_STUDENT", 201, auth.register(RegisterRequest(name, email(key), phone,
                fixtures.get(key).asString, "STUDENT")))
        }

        fun studentLogin(key: String, unbound: Boolean): Pair<Long, String> = runBlocking {
            val response = liveBody(auth.login(LoginRequest(email(key), fixtures.get(key).asString)), "student login")
            val status = AuthRepository(auth).account(response.token).getOrThrow()
            check(status.studentAffiliationEligible == unbound && status.verified == !unbound)
            check(status.dashboardRole == if (unbound) null else DashboardRole.STUDENT)
            response.userId to ("Bearer " + response.token)
        }

        fun catalog(token: String, departmentCode: String, department: String, programCode: String,
                    program: String): StudentEnrollmentInput = runBlocking {
            val dept = liveBody(auth.createDepartment(token, AcademicCatalogInput(departmentCode, department)), "create department", 201)
            val course = liveBody(programs.create(token, dept.id, AcademicCatalogInput(programCode, program)), "create program", 201)
            val year = liveBody(periods.createYear(token, AcademicCatalogInput("2026-2027", "Academic Year 2026")), "create year", 201)
            val semester = liveBody(periods.createSemester(token, course.id, year.id,
                AcademicCatalogInput("1", "First semester")), "create semester", 201)
            val division = liveBody(offerings.createDivision(token, course.id, year.id,
                AcademicCatalogInput("A", "Division A")), "create division", 201)
            StudentEnrollmentInput(dept.id, course.id, year.id, semester.id, division.id)
        }
    }

    private companion object { const val LIVE_FOLDER = "student-live-postgresql" }

    @Test fun studentEnrollmentWalkthrough() {
        val students = "/api/college/students"
        val enrollments = "$students/24/enrollments"
        val affiliation = "/api/auth/student-affiliation"
        val queue = "/api/college/student-affiliation-requests"
        val self = "/api/student/enrollment"
        val release = CountDownLatch(1)
        val listCalls = AtomicInteger()
        val affiliationState = AtomicReference("NONE")
        val alternatePending = java.util.concurrent.atomic.AtomicBoolean(true)
        val departmentSet = java.util.concurrent.atomic.AtomicBoolean()
        val enrollmentState = AtomicReference("NONE")
        val catalog = mapOf(
            "/api/college/departments" to """[{"id":2,"code":"SCI","name":"Science"}]""",
            "/api/college/departments/2/programs" to """[{"id":3,"code":"BIO","name":"Biology program"}]""",
            "/api/college/academic-years" to """[{"id":4,"code":"2026-2027","name":"Academic Year 2026"}]""",
            "/api/college/programs/3/academic-years/4/semesters" to """[{"id":5,"code":"1","name":"First semester"}]""",
            "/api/college/programs/3/academic-years/4/divisions" to """[{"id":6,"code":"A","name":"Division A"}]"""
        )
        fun member(): String = if (departmentSet.get()) {
            """{"id":24,"name":"Student Sample","email":"student@example.invalid","departmentId":2,"department":"Science"}"""
        } else """{"id":24,"name":"Student Sample","email":"student@example.invalid"}"""
        val request = """{"id":90,"status":"PENDING","college":"Campus A","collegeCode":"CAMPUS-A","name":"Student Sample","email":"student@example.invalid"}"""
        val alternative = """{"id":91,"status":"PENDING","college":"Campus A","collegeCode":"CAMPUS-A","name":"Student Alternate","email":"alternate@example.invalid"}"""
        val row = """{"id":99,"college":"Campus A","department":"Science","program":"Biology program","academicYear":"Academic Year 2026","semester":"First semester","division":"Division A","current":true}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val call = chain.request()
            val path = call.url.encodedPath
            check(call.header("Authorization") == when (path) {
                affiliation, self -> "Bearer synthetic-student"
                else -> "Bearer synthetic-admin"
            }) { "Wrong synthetic role for ${call.method} $path" }
            check(call.url.query == null) { "Unexpected query arguments for ${call.method} $path" }
            fun body() = JsonParser.parseString(Buffer().also { call.body!!.writeTo(it) }.readUtf8()).asJsonObject
            val (status, payload) = when (call.method to path) {
                "GET" to affiliation -> 200 to if (affiliationState.get() == "PENDING") "[$request]" else "[]"
                "POST" to affiliation -> {
                    val input = body()
                    check(input.keySet() == setOf("collegeCode") && input.get("collegeCode").asString == "CAMPUS-A") {
                        "Affiliation request did not send the selected code"
                    }
                    check(affiliationState.compareAndSet("NONE", "PENDING")) { "Unexpected extra affiliation request" }
                    201 to request
                }
                "GET" to queue -> 200 to buildList {
                    if (affiliationState.get() == "PENDING") add(request)
                    if (alternatePending.get()) add(alternative)
                }.joinToString(",", "[", "]")
                "POST" to "$queue/90/decision" -> {
                    val input = body()
                    check(input.keySet() == setOf("approved", "admissionConfirmed"))
                    check(input.get("approved").asBoolean && input.get("admissionConfirmed").asBoolean)
                    check(affiliationState.compareAndSet("PENDING", "APPROVED"))
                    200 to request.replace("PENDING", "APPROVED")
                }
                "POST" to "$queue/91/decision" -> {
                    val input = body()
                    check(!input.get("approved").asBoolean && !input.get("admissionConfirmed").asBoolean)
                    check(alternatePending.getAndSet(false))
                    200 to alternative.replace("PENDING", "REJECTED")
                }
                "GET" to students -> when (listCalls.incrementAndGet()) {
                    1 -> {
                        check(release.await(20, TimeUnit.SECONDS)) { "Directory loading capture did not release the fixture" }
                        503 to "{}"
                    }
                    else -> 200 to if (affiliationState.get() == "APPROVED") "[${member()}]" else "[]"
                }
                "GET" to "$students/24" -> 200 to member()
                "PUT" to "$students/24/department" -> {
                    check(body().get("departmentId").asLong == 2L)
                    departmentSet.set(true)
                    200 to member()
                }
                "GET" to enrollments -> 200 to when (enrollmentState.get()) {
                    "ACTIVE" -> "[$row]"
                    "ENDED" -> "[" + row.replace("true", "false") + "]"
                    else -> "[]"
                }
                "POST" to enrollments -> {
                    val input = body()
                    val expected = mapOf("departmentId" to 2, "programId" to 3, "academicYearId" to 4,
                        "semesterId" to 5, "divisionId" to 6)
                    check(input.keySet() == expected.keys && expected.all { (key, value) -> input.get(key).asInt == value })
                    check(departmentSet.get() && affiliationState.get() == "APPROVED")
                    check(enrollmentState.compareAndSet("NONE", "ACTIVE"))
                    201 to row
                }
                "DELETE" to "$enrollments/99" -> {
                    check(enrollmentState.compareAndSet("ACTIVE", "ENDED"))
                    204 to ""
                }
                "GET" to self -> {
                    check(affiliationState.get() == "APPROVED")
                    200 to if (enrollmentState.get() == "ACTIVE") "[$row]" else "[]"
                }
                else -> if (call.method == "GET" && path in catalog) 200 to catalog.getValue(path)
                    else throw AssertionError("Unexpected synthetic call " + call.method + " " + path)
            }
            Response.Builder().request(call).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic response").body(payload.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        val repo = StudentRepository(retrofit.create(StudentApi::class.java),
            DepartmentRepository(retrofit.create(AuthApi::class.java)), retrofit.create(ProgramApi::class.java),
            retrofit.create(AcademicPeriodApi::class.java), retrofit.create(AcademicOfferingApi::class.java))
        val folder = "student-enrollment-polished-demo"
        fun shot(label: String) = screenshot(label, folder)
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                fun show(mode: StudentMode, openFromDashboard: Boolean = false) {
                    scenario.onActivity { activity ->
                        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                            .isAppearanceLightStatusBars = true
                        activity.setContent {
                            var open by remember(mode, openFromDashboard) { mutableStateOf(!openFromDashboard) }
                            CampusSphereTheme {
                                Column(Modifier.background(Color(0xFFEBEFFE)).safeDrawingPadding()) {
                                    Text("Visual test • synthetic data",
                                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
                                        color = Color(0xFF3447A6), style = MaterialTheme.typography.labelMedium)
                                    if (open) StudentScreen(repo,
                                        { if (mode == StudentMode.ADMIN) "Bearer synthetic-admin" else "Bearer synthetic-student" },
                                        { error("Unexpected access failure $it") }, { open = false }, mode)
                                    else if (mode == StudentMode.AFFILIATION) {
                                        SessionScreen(AuthUiState(route = AuthRoute.ACCOUNT,
                                            account = AuthorizedAccount(false, null, null, null, true)), {}, {}, {},
                                            onStudentAffiliation = { open = true })
                                    } else {
                                        SessionScreen(AuthUiState(route = AuthRoute.ACCOUNT,
                                            account = AuthorizedAccount(true, null, DashboardRole.COLLEGE_ADMIN, "Synthetic college")),
                                            {}, {}, {}, onStudents = { open = true })
                                    }
                                }
                            }
                        }
                    }
                }
                show(StudentMode.AFFILIATION, openFromDashboard = true)
                node("Verification pending")
                click("Request college affiliation")
                node("College code")
                shot("01-affiliation-form")
                click("Send request")
                node("Enter a valid college code.")
                shot("02-invalid-code")
                input("College code", "campus-a")
                click("Send request")
                node("Admission review pending")
                shot("03-admission-pending")
                click("Check request")
                node("Admission review pending")

                show(StudentMode.ADMIN, openFromDashboard = true)
                click("Manage students")
                node("Loading student information")
                shot("04-directory-loading")
                release.countDown()
                node("CampusSphere is temporarily unavailable. Try again.")
                shot("05-directory-error")
                click("Retry")
                node("No verified students belong to this college.")
                shot("06-directory-empty")

                click("Affiliation requests")
                click("Student Sample")
                node("Review affiliation")
                check(generateSequence(node("Approve affiliation")) { it.parent }
                    .none { it.isEnabled && it.isClickable }) { "Unconfirmed approval is actionable" }
                shot("07-admission-unconfirmed")
                click("I verified this student's admission in our college records.")
                node("Admission confirmation recorded for this decision.")
                shot("08-admission-confirmed")
                click("Approve affiliation")
                node("Student affiliation approved.")
                shot("09-affiliation-approved")
                click("Student Alternate")
                click("Reject")
                node("Student affiliation rejected.")
                node("No pending affiliation requests.")
                shot("10-affiliation-rejected")
                click("Back")
                node("Student Sample")
                shot("11-directory")
                input("Search students", "missing")
                node("No students match your search.")
                shot("12-search")
                input("Search students", "Sample")
                click("Student Sample")
                node("No enrollments yet.")
                shot("13-profile")
                click("Enroll student")
                node("Choose a department before enrolling.")
                shot("14-department-required")
                click("Set department")
                node("Science")
                shot("15-choose-department")
                click("Science")
                node("Department saved.")
                shot("16-department-saved")
                click("Enroll student")
                for ((title, option, image) in listOf(
                    Triple("Choose program", "Biology program", "17-program"),
                    Triple("Choose academic year", "Academic Year 2026", "18-year"),
                    Triple("Choose semester", "First semester", "19-semester"),
                    Triple("Choose division", "Division A", "20-division")
                )) {
                    node(title)
                    node(option)
                    check(tree(instrumentation.uiAutomation.rootInActiveWindow)
                        .none { it.text?.toString() == "Department saved." })
                    shot(image)
                    click(option)
                }
                node("Review enrollment")
                shot("21-review-enrollment")
                click("Save enrollment")
                node("Enrollment saved.")
                shot("22-enrollment-saved")
                click("Enroll student")
                node("This student already has a current enrollment.")
                shot("23-duplicate-prevented")

                show(StudentMode.STUDENT)
                node("My current enrollment")
                node("Division A")
                shot("24-student-dashboard")
                show(StudentMode.ADMIN)
                click("Student Sample")
                click("End current enrollment")
                node("End current enrollment?")
                shot("25-end-confirmation")
                click("End enrollment")
                node("Past enrollment")
                shot("26-enrollment-history")
                show(StudentMode.STUDENT)
                node("You do not have a current enrollment.")
                shot("27-student-empty")
            }
        } finally {
            release.countDown()
        }
    }

    @Test fun facultyManagementWalkthrough() {
        val base = "/api/college/faculty"
        val assignments = "$base/12/assignments"
        val mine = "/api/faculty/assignments"
        val release = CountDownLatch(1)
        val listCalls = AtomicInteger()
        val memberSaved = java.util.concurrent.atomic.AtomicBoolean()
        val assignmentSaved = java.util.concurrent.atomic.AtomicBoolean()
        val catalog = mapOf(
            "/api/college/departments" to """[{"id":2,"code":"SCI","name":"Science"}]""",
            "/api/college/departments/2/programs" to """[{"id":3,"code":"BIO","name":"Biology program"}]""",
            "/api/college/academic-years" to """[{"id":4,"code":"2026-2027","name":"Academic Year 2026"}]""",
            "/api/college/programs/3/academic-years/4/semesters" to """[{"id":5,"code":"1","name":"First semester"}]""",
            "/api/college/programs/3/academic-years/4/divisions" to """[{"id":6,"code":"A","name":"Division A"}]""",
            "/api/college/programs/3/semesters/5/subjects" to """[{"id":7,"code":"BIO","name":"Biology Lab"}]"""
        )
        fun member() = if (memberSaved.get()) {
            """{"id":12,"name":"Faculty Example","email":"faculty@example.invalid","departmentId":2,"department":"Science"}"""
        } else """{"id":12,"name":"Faculty Example","email":"faculty@example.invalid"}"""
        val assignment = """{"id":99,"department":"Science","program":"Biology program","academicYear":"Academic Year 2026","semester":"First semester","division":"Division A","subject":"Biology Lab","subjectCode":"BIO"}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            check(request.header("Authorization") == if (path == mine) "Bearer synthetic-faculty" else "Bearer synthetic-admin")
            check(request.url.query == null)
            val (status, body) = when (request.method to path) {
                "GET" to base -> when (listCalls.incrementAndGet()) {
                    1 -> {
                        check(release.await(10, TimeUnit.SECONDS))
                        503 to "{}"
                    }
                    2 -> 200 to "[]"
                    else -> 200 to "[${member()}]"
                }
                "GET" to "$base/12" -> 200 to member()
                "PUT" to "$base/12/department" -> {
                    val payload = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
                    check(payload.keySet() == setOf("departmentId") && payload.get("departmentId").asLong == 2L)
                    memberSaved.set(true)
                    200 to member()
                }
                "GET" to assignments, "GET" to mine -> 200 to if (assignmentSaved.get()) "[$assignment]" else "[]"
                "POST" to assignments -> {
                    val payload = JsonParser.parseString(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).asJsonObject
                    val expected = mapOf("departmentId" to 2, "programId" to 3, "academicYearId" to 4,
                        "semesterId" to 5, "divisionId" to 6, "subjectId" to 7)
                    check(payload.keySet() == expected.keys && expected.all { (key, value) ->
                        payload.get(key).asInt == value
                    })
                    if (assignmentSaved.getAndSet(true)) 409 to "{}" else 201 to assignment
                }
                "DELETE" to "$assignments/99" -> {
                    check(assignmentSaved.getAndSet(false))
                    204 to ""
                }
                else -> if (request.method == "GET" && path in catalog) 200 to catalog.getValue(path)
                    else throw AssertionError("Unexpected synthetic call " + request.method + " " + path)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic response").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        val repo = FacultyRepository(retrofit.create(FacultyApi::class.java),
            DepartmentRepository(retrofit.create(AuthApi::class.java)), retrofit.create(ProgramApi::class.java),
            retrofit.create(AcademicPeriodApi::class.java), retrofit.create(AcademicOfferingApi::class.java))
        val account = AuthUiState(route = AuthRoute.ACCOUNT,
            account = AuthorizedAccount(true, null, DashboardRole.COLLEGE_ADMIN, "Synthetic college"))
        val folder = "faculty-management-demo"
        fun shot(name: String) = screenshot(name, folder)
        fun chooseAssignment(capture: Boolean) {
            click("Add assignment")
            val choices = listOf(
                Triple("Choose program", "Biology program", "10-program"),
                Triple("Choose academic year", "Academic Year 2026", "11-year"),
                Triple("Choose semester", "First semester", "12-semester"),
                Triple("Choose division", "Division A", "13-division"),
                Triple("Choose subject", "Biology Lab", "14-subject")
            )
            choices.forEach { (title, option, image) ->
                node(title)
                node(option)
                check(tree(instrumentation.uiAutomation.rootInActiveWindow)
                    .none { it.text?.toString() == "Department saved." })
                if (capture) shot(image)
                click(option)
            }
            node("Review assignment")
            if (capture) shot("15-review")
            click("Save assignment")
        }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        var page by remember { mutableStateOf("college") }
                        CampusSphereTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                Text("Visual test • synthetic data")
                                when (page) {
                                    "college" -> SessionScreen(account, {}, {}, {},
                                        onFaculty = { page = "management" })
                                    "management" -> FacultyScreen(repo, { "Bearer synthetic-admin" },
                                        { error("Unexpected access failure $it") }, { page = "college" })
                                }
                            }
                        }
                    }
                }
                click("Manage faculty")
                node("Loading faculty information")
                shot("01-directory-loading")
                release.countDown()
                node("CampusSphere is temporarily unavailable. Try again.")
                shot("02-directory-error")
                click("Retry")
                node("No active, verified faculty belong to this college.")
                shot("03-directory-empty")
                click("Refresh")
                node("Faculty Example")
                shot("04-directory")
                input("Search faculty", "missing")
                node("No faculty match your search.")
                shot("05-search")
                input("Search faculty", "Faculty")
                click("Faculty Example")
                node("No active assignments.")
                shot("06-profile")
                click("Add assignment")
                node("Choose a department before adding an assignment.")
                shot("07-validation")
                click("Set department")
                node("Choose department")
                node("Science")
                shot("08-department")
                click("Science")
                node("Department saved.")
                shot("09-department-saved")
                chooseAssignment(true)
                node("Assignment added.")
                shot("16-assigned")
                chooseAssignment(false)
                node("This faculty member already has that active subject assignment in this division.")
                shot("17-duplicate")
                scenario.onActivity { activity ->
                    activity.setContent {
                        CampusSphereTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                Text("Visual test • synthetic data")
                                FacultyScreen(repo, { "Bearer synthetic-faculty" }, { error("Unexpected access failure $it") },
                                    {}, dashboard = true)
                            }
                        }
                    }
                }
                node("My subjects and classes")
                node("Biology Lab")
                shot("18-faculty-dashboard")
                scenario.onActivity { activity ->
                    activity.setContent {
                        CampusSphereTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                Text("Visual test • synthetic data")
                                FacultyScreen(repo, { "Bearer synthetic-admin" }, { error("Unexpected access failure $it") }, {})
                            }
                        }
                    }
                }
                click("Faculty Example")
                click("End Biology Lab")
                node("End assignment?")
                shot("19-end-confirmation")
                click("End assignment")
                node("Assignment ended.")
                shot("20-assignment-ended")
                scenario.onActivity { activity ->
                    activity.setContent {
                        CampusSphereTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                Text("Visual test • synthetic data")
                                FacultyScreen(repo, { "Bearer synthetic-faculty" }, { error("Unexpected access failure $it") },
                                    {}, dashboard = true)
                            }
                        }
                    }
                }
                node("No classes have been assigned to you.")
                shot("21-dashboard-empty")
            }
        } finally {
            release.countDown()
        }
    }

    @Test fun divisionsAndSubjectsWalkthrough() {
        val divisionPath = "/api/college/programs/8/academic-years/57/divisions"
        val subjectPath = "/api/college/programs/8/semesters/9/subjects"
        val scopes = mapOf(
            divisionPath to VisualOffering("division", "Divisions", "A", "Division A", "Division A Revised", 21,
                "No divisions have been added for this program and year.",
                "This program already uses that division code in this academic year."),
            subjectPath to VisualOffering("subject", "Subjects", "BIO", "Biology Lab", "Biology Laboratory", 22,
                "No subjects have been added for this program and semester.",
                "This program already uses that subject code in this semester.")
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.header("Authorization") == "Bearer synthetic-visual-session")
            val path = request.url.encodedPath
            val method = request.method
            val (status, body) = when (method to path) {
                "GET" to "/api/college/departments" -> 200 to """[{"id":42,"code":"SCI","name":"Science"}]"""
                "GET" to "/api/college/departments/42/programs" -> 200 to """[{"id":8,"code":"BIO","name":"Biology"}]"""
                "GET" to "/api/college/academic-years" -> 200 to """[{"id":57,"code":"2026-2027","name":"Academic Year 2026"}]"""
                "GET" to "/api/college/programs/8/academic-years/57/semesters" ->
                    200 to """[{"id":9,"code":"1","name":"First Semester"}]"""
                else -> {
                    val scope = requireNotNull(scopes[if (method == "PATCH") path.substringBeforeLast("/") else path])
                    when (method) {
                        "GET" -> if (scope.reads.incrementAndGet() == 1) {
                            check(scope.release.await(45, TimeUnit.SECONDS))
                            503 to """{"code":"DATABASE_UNAVAILABLE"}"""
                        } else 200 to "[${scope.saved.get().orEmpty()}]"
                        "POST", "PATCH" -> {
                            val bodySent = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
                            val json = JsonParser.parseString(bodySent).asJsonObject
                            check(json.keySet() == setOf("code", "name") && json["code"].asString == scope.code)
                            if (method == "POST" && scope.saved.get() != null) {
                                409 to """{"code":"${scope.title.dropLast(1).uppercase()}_CONFLICT"}"""
                            } else {
                                val expectedName = if (method == "POST") scope.name else scope.edited
                                check(json["name"].asString == expectedName)
                                if (method == "PATCH") check(path.substringAfterLast("/") == scope.id.toString())
                                val row = """{"id":${scope.id},"code":"${scope.code}","name":"$expectedName"}"""
                                scope.saved.set(row)
                                (if (method == "POST") 201 else 200) to row
                            }
                        }
                        else -> error("Unexpected offering method")
                    }
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic response").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        val departments = DepartmentRepository(retrofit.create(AuthApi::class.java))
        val programs = retrofit.create(ProgramApi::class.java)
        val periods = retrofit.create(AcademicPeriodApi::class.java)
        val offerings = retrofit.create(AcademicOfferingApi::class.java)
        val dashboard = com.campussphere.app.presentation.auth.viewmodel.AuthUiState(
            route = com.campussphere.app.presentation.auth.viewmodel.AuthRoute.ACCOUNT,
            account = com.campussphere.app.data.repository.AuthorizedAccount(true, null,
                com.campussphere.app.data.repository.DashboardRole.COLLEGE_ADMIN, "Synthetic college"))
        val folder = "division-subject-demo"
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        var showStructure by remember { mutableStateOf(false) }
                        CampusSphereTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                Text("Visual test • synthetic data")
                                if (showStructure) {
                                    CollegeAcademicStructure(false, departments, programs, periods, offerings,
                                        { "Bearer synthetic-visual-session" }, { error("Unexpected access failure") }) {
                                        showStructure = false
                                    }
                                } else {
                                    com.campussphere.app.presentation.auth.SessionScreen(dashboard, {}, {}, {},
                                        onDepartments = { showStructure = true })
                                }
                            }
                        }
                    }
                }
                node("College Admin dashboard")
                screenshot("01-dashboard", folder)
                click("Manage departments")
                node("Manage programs")
                screenshot("02-departments", folder)
                click("Manage programs")
                node("Manage semesters")
                screenshot("03-programs", folder)
                click("Manage semesters")
                node("Manage divisions")
                screenshot("04-year-picker", folder)
                click("Manage divisions")
                demonstrateOffering(scopes.getValue(divisionPath), 5, folder)
                click("Back")
                node("Manage divisions")
                click("Academic Year 2026")
                node("Manage subjects")
                screenshot("15-semesters", folder)
                click("Manage subjects")
                demonstrateOffering(scopes.getValue(subjectPath), 16, folder)
                click("Back")
                node("Manage subjects")
                click("Back")
                node("Manage divisions")
                click("Back")
                node("Manage semesters")
                click("Back")
                node("Manage programs")
                click("Back")
                node("College Admin dashboard")
                screenshot("26-return-dashboard", folder)
            }
        } finally {
            scopes.values.forEach { it.release.countDown() }
        }
    }

    private fun demonstrateOffering(scope: VisualOffering, start: Int, folder: String) {
        var index = start
        fun shot(step: String) {
            screenshot("${(index++).toString().padStart(2, '0')}-${scope.resource}-$step", folder)
        }
        val label = scope.resource.replaceFirstChar { it.uppercaseChar() }
        node("Loading ${scope.title}")
        shot("loading")
        scope.release.countDown()
        node("CampusSphere is temporarily unavailable. Try again.")
        shot("error-retry")
        click("Retry")
        node(scope.empty)
        shot("empty")
        click("Add ${scope.resource}")
        input("$label code", "_bad")
        click("Save ${scope.resource}")
        node("Enter a name up to 150 characters and a code of 1–30 letters, digits, underscores or hyphens starting with a letter or digit.")
        shot("validation")
        input("$label code", scope.code)
        input("$label name", scope.name)
        shot("add")
        click("Save ${scope.resource}")
        node("$label added.")
        shot("added")
        click("Add ${scope.resource}")
        input("$label code", scope.code)
        input("$label name", "Duplicate")
        click("Save ${scope.resource}")
        node(scope.conflict)
        shot("duplicate")
        click("Back")
        input("Search ${scope.resource}s", "missing")
        node("No ${scope.resource}s match your search.")
        shot("search")
        input("Search ${scope.resource}s", scope.code)
        click(scope.name)
        input("$label name", scope.edited)
        shot("edit")
        click("Save ${scope.resource}")
        node("$label updated.")
        node(scope.edited)
        shot("updated")
    }

    private data class VisualOffering(
        val resource: String, val title: String, val code: String, val name: String, val edited: String,
        val id: Long, val empty: String, val conflict: String,
        val reads: AtomicInteger = AtomicInteger(),
        val release: CountDownLatch = CountDownLatch(1),
        val saved: AtomicReference<String?> = AtomicReference()
    )

    @Test fun academicYearsAndSemestersWalkthrough() {
        val yearsPath = "/api/college/academic-years"
        val semesterPath = "/api/college/programs/8/academic-years/57/semesters"
        val year = AtomicReference<String?>()
        val semester = AtomicReference<String?>()
        val semesterReads = AtomicInteger()
        val releaseFirstSemesterRead = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.header("Authorization") == "Bearer synthetic-visual-session")
            val path = request.url.encodedPath
            val method = request.method
            val (status, body) = when (method to path) {
                "GET" to "/api/college/departments" -> 200 to """[{"id":42,"code":"SCI","name":"Science"}]"""
                "GET" to "/api/college/departments/42/programs" -> 200 to """[{"id":8,"code":"BIO","name":"Biology"}]"""
                "GET" to yearsPath -> 200 to "[${year.get().orEmpty()}]"
                "GET" to semesterPath -> if (semesterReads.incrementAndGet() == 1) {
                    check(releaseFirstSemesterRead.await(45, TimeUnit.SECONDS))
                    503 to """{"code":"DATABASE_UNAVAILABLE"}"""
                } else 200 to "[${semester.get().orEmpty()}]"
                "POST" to yearsPath, "PATCH" to "$yearsPath/57",
                "POST" to semesterPath, "PATCH" to "$semesterPath/9" -> {
                    val payload = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
                    val json = JsonParser.parseString(payload).asJsonObject
                    check(json.keySet() == setOf("code", "name"))
                    val isYear = path.startsWith(yearsPath)
                    val existing = if (isYear) year else semester
                    val code = if (isYear) "2026-2027" else "1"
                    val expectedName = when (path) {
                        yearsPath -> "Academic Year 2026"
                        "$yearsPath/57" -> "Academic Year 2026 Revised"
                        semesterPath -> "First Semester"
                        else -> "First Semester Revised"
                    }
                    check(json["code"].asString == code)
                    if (method == "POST" && existing.get() != null) {
                        409 to if (isYear) """{"code":"ACADEMIC_YEAR_CONFLICT"}""" else """{"code":"SEMESTER_CONFLICT"}"""
                    } else {
                        check(json["name"].asString == expectedName)
                        val row = """{"id":${if (isYear) 57 else 9},"code":"$code","name":"$expectedName"}"""
                        existing.set(row)
                        (if (method == "POST") 201 else 200) to row
                    }
                }
                else -> error("Unexpected academic period route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic response").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        val departments = DepartmentRepository(retrofit.create(AuthApi::class.java))
        val programs = retrofit.create(ProgramApi::class.java)
        val periods = retrofit.create(AcademicPeriodApi::class.java)
        val account = com.campussphere.app.data.repository.AuthorizedAccount(true, null,
            com.campussphere.app.data.repository.DashboardRole.COLLEGE_ADMIN, "Synthetic college")
        val dashboard = com.campussphere.app.presentation.auth.viewmodel.AuthUiState(
            com.campussphere.app.presentation.auth.viewmodel.AuthRoute.ACCOUNT, account = account)
        val folder = "academic-period-demo"
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        var manageYears by remember { mutableStateOf<Boolean?>(null) }
                        CampusSphereTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                Text("Visual test • synthetic data")
                                val section = manageYears
                                if (section == null) {
                                    com.campussphere.app.presentation.auth.SessionScreen(dashboard, {}, {}, {},
                                        onAcademicYears = { manageYears = true }, onDepartments = { manageYears = false })
                                } else {
                                    CollegeAcademicStructure(section, departments, programs, periods,
                                        retrofit.create(AcademicOfferingApi::class.java),
                                        { "Bearer synthetic-visual-session" }, { error("Unexpected access failure") }) {
                                        manageYears = null
                                    }
                                }
                            }
                        }
                    }
                }
                node("College Admin dashboard")
                screenshot("01-dashboard", folder)
                click("Academic years")
                node("No academic years have been added.")
                screenshot("02-years-empty", folder)
                click("Add academic year")
                input("Year range (YYYY-YYYY)", "2026-2028")
                click("Save academic year")
                node("Enter two consecutive four-digit years in YYYY-YYYY format and a name up to 150 characters.")
                screenshot("03-year-validation", folder)
                input("Year range (YYYY-YYYY)", "2026-2027")
                input("Academic year name", "Academic Year 2026")
                screenshot("04-year-add", folder)
                click("Save academic year")
                node("Academic year added.")
                screenshot("05-year-added", folder)
                input("Search academic years", "missing")
                node("No academic years match your search.")
                screenshot("06-years-search", folder)
                input("Search academic years", "2026")
                click("Academic Year 2026")
                input("Academic year name", "Academic Year 2026 Revised")
                screenshot("07-year-edit", folder)
                click("Save academic year")
                node("Academic year updated.")
                screenshot("08-year-updated", folder)
                click("Back")
                click("Manage departments")
                node("Manage programs")
                screenshot("09-departments", folder)
                click("Manage programs")
                node("Manage semesters")
                screenshot("10-programs", folder)
                click("Manage semesters")
                node("Select academic year")
                screenshot("11-year-picker", folder)
                click("Academic Year 2026 Revised")
                node("Loading Semesters")
                screenshot("12-semesters-loading", folder)
                releaseFirstSemesterRead.countDown()
                node("CampusSphere is temporarily unavailable. Try again.")
                screenshot("13-semesters-error", folder)
                click("Retry")
                node("No semesters have been added for this program and year.")
                screenshot("14-semesters-empty", folder)
                click("Add semester")
                input("Semester number", "0")
                click("Save semester")
                node("Enter a semester number from 1 to 99 and a name up to 150 characters.")
                screenshot("15-semester-validation", folder)
                input("Semester number", "1")
                input("Semester name", "First Semester")
                screenshot("16-semester-add", folder)
                click("Save semester")
                node("Semester added.")
                screenshot("17-semester-added", folder)
                click("Add semester")
                input("Semester number", "1")
                input("Semester name", "Duplicate")
                click("Save semester")
                node("This program already has that semester number in this academic year.")
                screenshot("18-semester-duplicate", folder)
                click("Back")
                input("Search semesters", "missing")
                node("No semesters match your search.")
                screenshot("19-semesters-search", folder)
                input("Search semesters", "1")
                click("First Semester")
                input("Semester name", "First Semester Revised")
                screenshot("20-semester-edit", folder)
                click("Save semester")
                node("Semester updated.")
                screenshot("21-semester-updated", folder)
                click("Back")
                node("Select academic year")
                click("Back")
                node("Manage semesters")
                click("Back")
                node("Manage programs")
                click("Back")
                node("College Admin dashboard")
                screenshot("22-return-dashboard", folder)
            }
        } finally {
            releaseFirstSemesterRead.countDown()
        }
    }

    @Test fun programsOpenFromDepartmentAndUseNestedRetrofitRoutes() {
        val base = "/api/college/departments/42/programs"
        val reads = AtomicInteger()
        val releaseFirstRead = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.header("Authorization") == "Bearer synthetic-visual-session")
            val payload = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
            val (status, body) = when (request.method to request.url.encodedPath) {
                "GET" to "/api/college/departments" ->
                    200 to """[{"id":42,"code":"SCI","name":"Science"}]"""
                "GET" to base -> when (reads.incrementAndGet()) {
                    1 -> {
                        check(releaseFirstRead.await(40, TimeUnit.SECONDS))
                        503 to """{"code":"DATABASE_UNAVAILABLE"}"""
                    }
                    2 -> 200 to "[]"
                    else -> 200 to """[{"id":8,"code":"BIO","name":"Biology"}]"""
                }
                "POST" to base -> {
                    check(payload.contains(""""name":"Chemistry""""))
                    when {
                        payload.contains(""""code":"BIO"""") -> 409 to """{"code":"PROGRAM_CODE_CONFLICT"}"""
                        payload.contains(""""code":"CHEM"""") -> 201 to """{"id":9,"code":"CHEM","name":"Chemistry"}"""
                        else -> error("Unexpected program code")
                    }
                }
                "PATCH" to "$base/9" -> {
                    check(payload.contains(""""code":"CHEM"""") && payload.contains(""""name":"Applied Chemistry""""))
                    200 to """{"id":9,"code":"CHEM","name":"Applied Chemistry"}"""
                }
                else -> error("Unexpected program route")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic response").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val authorization = { "Bearer synthetic-visual-session" }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val departmentFactory = viewModelFactory {
                        initializer {
                            DepartmentViewModel(DepartmentRepository(retrofit.create(AuthApi::class.java)),
                                authorization) { error("Unexpected access failure") }
                        }
                    }
                    val departments = ViewModelProvider(owner, departmentFactory)[DepartmentViewModel::class.java]
                    activity.setContent {
                        var selected by remember { mutableStateOf<AcademicCatalogItem?>(null) }
                        CampusSphereTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                Text("Visual test • synthetic data")
                                val department = selected
                                if (department == null) {
                                    DepartmentScreen(departments.state.collectAsStateWithLifecycle().value,
                                        departments, onPrograms = { selected = it }) {}
                                } else {
                                    val programModel = remember(department.id) {
                                        val factory = viewModelFactory {
                                            initializer {
                                                AcademicCatalogViewModel(ProgramRepository(retrofit.create(ProgramApi::class.java),
                                                    department.id), authorization, { error("Unexpected access failure") }, "Program")
                                            }
                                        }
                                        ViewModelProvider(owner, factory)[AcademicCatalogViewModel::class.java]
                                    }
                                    ProgramScreen(programModel.state.collectAsStateWithLifecycle().value,
                                        programModel, department) { selected = null }
                                }
                            }
                        }
                    }
                }
                node("Science")
                screenshot("01-departments", "program-demo")
                click("Manage programs")
                node("Loading Programs")
                screenshot("02-loading", "program-demo")
                releaseFirstRead.countDown()
                node("CampusSphere is temporarily unavailable. Try again.")
                screenshot("03-error-retry", "program-demo")
                click("Retry")
                node("No programs have been added to this department yet.")
                screenshot("04-empty", "program-demo")
                click("Refresh")
                node("Biology")
                screenshot("05-programs", "program-demo")
                input("Search programs", "BIO")
                node("Biology")
                screenshot("06-search-match", "program-demo")
                input("Search programs", "No such program")
                node("No programs match your search.")
                screenshot("07-search-empty", "program-demo")
                input("Search programs", "")
                node("Biology")
                click("Add program")
                click("Save program")
                node("Enter a name up to 150 characters and a code of 1–30 letters, digits, underscores or hyphens starting with a letter or digit.")
                screenshot("08-validation", "program-demo")
                input("Program code", "BIO")
                input("Program name", "Chemistry")
                click("Save program")
                node("This college already has a program with that code.")
                screenshot("09-duplicate-code", "program-demo")
                input("Program code", "CHEM")
                screenshot("10-add", "program-demo")
                click("Save program")
                node("Program added.")
                screenshot("11-added", "program-demo")
                click("Chemistry")
                input("Program name", "Applied Chemistry")
                screenshot("12-edit", "program-demo")
                click("Save program")
                node("Program updated.")
                node("Applied Chemistry")
                screenshot("13-updated", "program-demo")
                click("Add program")
                click("Back")
                node("Applied Chemistry")
                click("Back")
                node("Manage programs")
                screenshot("14-back-to-departments", "program-demo")
            }
        } finally {
            releaseFirstRead.countDown()
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
        }
    }

    @Test fun departmentListCreateAndEditUseInterceptedRetrofit() {
        val fixture = """{"id":1,"code":"SCI","name":"Science"}"""
        val created = """{"id":2,"code":"CS","name":"Computing"}"""
        val edited = """{"id":2,"code":"CS","name":"Computer Science"}"""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.header("Authorization") == "Bearer synthetic-visual-session")
            val bodySent = Buffer().apply { request.body?.writeTo(this) }.readUtf8()
            val (status, body) = when (request.method to request.url.encodedPath) {
                "GET" to "/api/college/departments" -> 200 to "[$fixture]"
                "POST" to "/api/college/departments" -> {
                    check(bodySent.contains(""""code":"CS"""") && bodySent.contains(""""name":"Computing""""))
                    201 to created
                }
                "PATCH" to "/api/college/departments/2" -> {
                    check(bodySent.contains(""""code":"CS"""") && bodySent.contains(""""name":"Computer Science""""))
                    200 to edited
                }
                else -> error("Unexpected department call")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic response").body(body.toResponseBody()).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(AuthApi::class.java)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        try {
            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val factory = viewModelFactory {
                        initializer {
                            DepartmentViewModel(DepartmentRepository(api), { "Bearer synthetic-visual-session" }) {
                                error("No access failure expected")
                            }
                        }
                    }
                    val model = ViewModelProvider(owner, factory)[DepartmentViewModel::class.java]
                    activity.setContent {
                        CampusSphereTheme {
                            Column(Modifier.safeDrawingPadding()) {
                                Text("Visual test • synthetic data")
                                DepartmentScreen(model.state.collectAsStateWithLifecycle().value, model) {}
                            }
                        }
                    }
                }
                node("Science")
                screenshot("01-listed")
                click("Add department")
                input("Department code", "CS")
                input("Department name", "Computing")
                screenshot("02-add")
                click("Save department")
                node("Department added.")
                click("Computing")
                input("Department name", "Computer Science")
                screenshot("03-edit")
                click("Save department")
                node("Department updated.")
                node("Computer Science")
                screenshot("04-edited")
            }
        } finally {
            instrumentation.runOnMainSync { owner.viewModelStore.clear() }
        }
    }

    private fun tree(root: AccessibilityNodeInfo?): Sequence<AccessibilityNodeInfo> = sequence {
        if (root != null) {
            yield(root)
            for (index in 0 until root.childCount) yieldAll(tree(root.getChild(index)))
        }
    }

    private fun node(label: String, timeoutMillis: Long = 6_000): AccessibilityNodeInfo {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        do {
            val match = tree(instrumentation.uiAutomation.rootInActiveWindow)
                .firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
            if (match != null) return match
            Thread.sleep(100)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        error("Control not found: $label")
    }

    private fun click(label: String) {
        repeat(10) {
            var current: AccessibilityNodeInfo? = node(label)
            while (current != null) {
                if (current.isEnabled && current.isClickable &&
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                current = current.parent
            }
            Thread.sleep(150)
        }
        error("Control is not clickable: $label")
    }

    private fun input(label: String, value: String) {
        val labelNode = node(label)
        val field = if (labelNode.isEditable) labelNode else tree(labelNode.parent).first { it.isEditable }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }

    private fun screenshot(name: String, folder: String = "department-visual") {
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        val directory = File(instrumentation.targetContext.filesDir, folder).apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
