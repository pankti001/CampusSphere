package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.HomeworkFailure
import com.campussphere.app.data.repository.HomeworkRepository
import com.campussphere.app.data.repository.MaterialUpload
import com.campussphere.app.data.repository.homeworkErrorMessage
import com.campussphere.app.data.repository.homeworkFileProblem
import com.campussphere.app.data.repository.homeworkPickerTypes
import com.campussphere.app.presentation.homework.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 12, 1, 10, 0, 0, 0, zone)

    private fun item(id: Long = 5, status: String = "DRAFT", counts: HomeworkCounts? = HomeworkCounts(3, 0, 3, 0, 0, 0, 0),
                     mine: HomeworkMine? = null, issueAt: String = "2026-12-01T10:15:37+05:30") = HomeworkItem(
        id, "Essay", "About Java", "Two pages", issueAt, "2026-12-15T23:59:00+05:30", 20.0, listOf("pdf", "docx"),
        10_485_760, "NONE", null, "SINGLE", status, null, null, 4, 11, "JAVA101", "Java", 21, "Class A", 31,
        "Semester 1", 41, "BSc CS", 51, "2026-2027", null, "Krishna", null, null, null, "2026-12-01T10:15:37+05:30",
        "2026-12-01T10:15:37+05:30", status == "PUBLISHED", false, status == "DRAFT" || status == "PUBLISHED", counts, mine)

    private fun form() = HomeworkForm(classId = 4, title = " Essay ", description = "About", instructions = "Two pages",
        dueDate = "2026-12-15", dueTime = "23:59", maxMarks = "20", formats = setOf("docx", "pdf"), maxSizeMb = "5")

    /** A fake server: only the calls a test sets up answer; anything else fails the test. */
    private open class FakeApi : HomeworkApi {
        val calls = mutableListOf<String>()
        override suspend fun classes(auth: String): Response<List<HomeworkClass>> = fail("classes")
        override suspend fun facultySummary(auth: String): Response<FacultyHomeworkSummary> = fail("facultySummary")
        override suspend fun facultyList(auth: String, subjectId: Long?, divisionId: Long?, status: String?, q: String?, page: Int): Response<List<HomeworkItem>> = fail("facultyList")
        override suspend fun create(auth: String, input: HomeworkInput): Response<HomeworkItem> = fail("create")
        override suspend fun detail(auth: String, area: String, id: Long): Response<HomeworkItem> = fail("detail")
        override suspend fun update(auth: String, id: Long, input: HomeworkInput): Response<HomeworkItem> = fail("update")
        override suspend fun lifecycle(auth: String, id: Long, action: String): Response<HomeworkItem> = fail("lifecycle")
        override suspend fun attach(auth: String, id: Long, file: MultipartBody.Part): Response<HomeworkItem> = fail("attach")
        override suspend fun detach(auth: String, id: Long): Response<HomeworkItem> = fail("detach")
        override suspend fun attachment(auth: String, area: String, id: Long): Response<ResponseBody> = fail("attachment")
        override suspend fun history(auth: String, area: String, id: Long): Response<List<HomeworkEvent>> = fail("history")
        override suspend fun roster(auth: String, id: Long, filter: String, page: Int): Response<List<HomeworkRosterRow>> = fail("roster")
        override suspend fun review(auth: String, id: Long, submissionId: Long): Response<HomeworkReview> = fail("review")
        override suspend fun submissionFile(auth: String, area: String, id: Long, submissionId: Long): Response<ResponseBody> = fail("submissionFile")
        override suspend fun evaluate(auth: String, id: Long, submissionId: Long, evaluation: HomeworkEvaluation): Response<HomeworkSubmission> = fail("evaluate")
        override suspend fun returnWork(auth: String, id: Long, submissionId: Long, request: HomeworkReturn): Response<HomeworkSubmission> = fail("returnWork")
        override suspend fun studentSummary(auth: String): Response<StudentHomeworkSummary> = fail("studentSummary")
        override suspend fun studentList(auth: String, tab: String, page: Int): Response<List<HomeworkItem>> = fail("studentList")
        override suspend fun studentDetail(auth: String, id: Long): Response<HomeworkStudentView> = fail("studentDetail")
        override suspend fun submit(auth: String, id: Long, file: MultipartBody.Part, comment: RequestBody?): Response<HomeworkSubmission> = fail("submit")
        override suspend fun notices(auth: String, unreadOnly: Boolean, page: Int): Response<List<HomeworkNotice>> = fail("notices")
        override suspend fun markRead(auth: String, id: Long): Response<Unit> = fail("markRead")
        override suspend fun adminFilters(auth: String): Response<HomeworkFilters> = fail("adminFilters")
        override suspend fun adminStats(auth: String, filters: Map<String, String>): Response<HomeworkAdminStats> = fail("adminStats")
        override suspend fun adminList(auth: String, filters: Map<String, String>): Response<List<HomeworkItem>> = fail("adminList")
        private fun fail(name: String): Nothing = throw AssertionError("Unexpected call: $name")
    }

    private val classes = listOf(HomeworkClass(4, 11, "JAVA101", "Java", 21, "Class A", 31, "Semester 1", 41, "BSc CS", 51, "2026-2027"))

    @Test fun formChecksEveryRuleBeforeSending() {
        assertNull(homeworkFormProblem(form(), now))
        assertEquals("Choose one of your classes.", homeworkFormProblem(form().copy(classId = null), now))
        assertEquals("Enter a title.", homeworkFormProblem(form().copy(title = "  "), now))
        assertEquals("Keep the title to 160 characters.", homeworkFormProblem(form().copy(title = "t".repeat(161)), now))
        assertEquals("Enter the deadline as YYYY-MM-DD and HH:MM.", homeworkFormProblem(form().copy(dueDate = "15/12/2026"), now))
        assertEquals("The deadline must be after the issue date.", homeworkFormProblem(form().copy(issueDate = "2026-12-16", issueTime = "09:00"), now))
        assertEquals("The deadline must be in the future.", homeworkFormProblem(form().copy(dueDate = "2026-11-30", issueDate = "2026-11-01"), now))
        assertEquals("The deadline must be within a year of the issue date.", homeworkFormProblem(form().copy(dueDate = "2028-01-01"), now))
        for (marks in listOf("0", "-2", "1001", "10.555")) assertNotNull(homeworkFormProblem(form().copy(maxMarks = marks), now))
        assertEquals("Enter the maximum marks as a number.", homeworkFormProblem(form().copy(maxMarks = "ten"), now))
        assertEquals("Choose at least one file type students may upload.", homeworkFormProblem(form().copy(formats = emptySet()), now))
        assertEquals("The file size limit must be between 0.1 and 10 MB.", homeworkFormProblem(form().copy(maxSizeMb = "11"), now))
        assertEquals("Enter the late cut-off as YYYY-MM-DD and HH:MM.", homeworkFormProblem(form().copy(lateAllowed = true, lateDate = ""), now))
        assertEquals("The late cut-off must be after the deadline.", homeworkFormProblem(form().copy(lateAllowed = true, lateDate = "2026-12-15", lateTime = "23:00"), now))
        assertEquals("The late cut-off must be within 60 days of the deadline.", homeworkFormProblem(form().copy(lateAllowed = true, lateDate = "2027-03-01"), now))
    }

    @Test fun formBecomesTheExactRequestBody() {
        val input = form().copy(lateAllowed = true, lateDate = "2026-12-18", lateTime = "18:00", resubmit = true).toInput(zone)
        assertEquals("""{"classId":4,"title":"Essay","description":"About","instructions":"Two pages","dueAt":"2026-12-15T23:59:00+05:30","maxMarks":20.0,"allowedFormats":["pdf","docx"],"maxFileBytes":5242880,"latePolicy":"ALLOWED","lateUntil":"2026-12-18T18:00:00+05:30","resubmission":"UNTIL_DEADLINE"}""",
            Gson().toJson(input))
        val plain = form().copy(issueDate = "2026-12-02", issueTime = "08:30").toInput(zone)
        assertEquals("2026-12-02T08:30:00+05:30", plain.issueAt)
        assertEquals("NONE", plain.latePolicy)
        assertNull(plain.lateUntil)
        assertEquals("SINGLE", plain.resubmission)
    }

    @Test fun editingAPublishedAssignmentSendsUntouchedDatesExactly() {
        val loaded = homeworkFormOf(item(status = "PUBLISHED"), zone)
        assertEquals("2026-12-01", loaded.issueDate)
        assertEquals("10:15", loaded.issueTime)
        assertEquals("20", loaded.maxMarks)
        assertEquals("10", loaded.maxSizeMb)
        val same = loaded.copy(title = "Essay v2").toInput(zone)
        // The class and the seconds of the stored issue date must not change, so they are left to the server.
        assertNull(same.classId)
        assertNull(same.issueAt)
        assertEquals("2026-12-15T23:59:00+05:30", same.dueAt)
        val later = loaded.copy(dueDate = "2026-12-20").toInput(zone)
        assertEquals("2026-12-20T23:59:00+05:30", later.dueAt)
    }

    @Test fun marksAndFilesAreCheckedOnTheDevice() {
        assertNull(homeworkMarksProblem("18.5", 20.0))
        assertNull(homeworkMarksProblem("0", 20.0))
        assertEquals("Marks cannot be negative.", homeworkMarksProblem("-1", 20.0))
        assertEquals("Marks cannot be more than 20.", homeworkMarksProblem("21", 20.0))
        assertEquals("Use at most two decimals.", homeworkMarksProblem("1.234", 20.0))
        assertEquals("Enter the marks as a number.", homeworkMarksProblem("", 20.0))
        assertNull(homeworkFileProblem("essay.PDF", 10, listOf("pdf"), 1000))
        assertNull(homeworkFileProblem("photo.jpeg", 10, listOf("jpg"), 1000))
        assertEquals("This assignment accepts: PDF.", homeworkFileProblem("run.exe", 10, listOf("pdf"), 1000))
        assertEquals("This assignment accepts: PDF, Word (.docx).", homeworkFileProblem("old.doc", 10, listOf("pdf", "docx"), 1000))
        assertEquals("The selected file is empty.", homeworkFileProblem("a.pdf", 0, listOf("pdf"), 1000))
        assertEquals("The file is larger than 1 MB.", homeworkFileProblem("a.pdf", 2_000_000, listOf("pdf"), 1024 * 1024))
        assertArrayEquals(arrayOf("application/pdf", "image/png"), homeworkPickerTypes(listOf("pdf", "png")))
    }

    @Test fun serverErrorsBecomeFriendlyMessages() {
        assertEquals("This assignment is not accepting submissions now.", homeworkErrorMessage(409, "SUBMISSION_CLOSED"))
        assertEquals("Maximum marks cannot change after work has been marked.", homeworkErrorMessage(409, "ASSIGNMENT_MARKS_LOCKED"))
        assertEquals("Your session expired. Sign in again.", homeworkErrorMessage(401, null))
        assertEquals("Assignments could not be loaded right now. Try again shortly.", homeworkErrorMessage(500, null))
    }

    @Test fun adminFiltersSendOnlyChosenValues() {
        assertEquals(emptyMap<String, String>(), AdminHomeworkChoice().query())
        assertEquals(mapOf("programId" to "41", "from" to "2026-12-01", "q" to "essay", "page" to "2"),
            AdminHomeworkChoice(programId = 41, from = " 2026-12-01 ", query = " essay ").query(2))
        assertEquals("Use YYYY-MM-DD for the start date.", adminChoiceProblem(AdminHomeworkChoice(from = "1/12/2026")))
        assertEquals("The end date must not be before the start date.",
            adminChoiceProblem(AdminHomeworkChoice(from = "2026-12-02", to = "2026-12-01")))
    }

    @Test fun facultyCreatesADraftPreviewsAndPublishes() {
        val api = object : FakeApi() {
            var created: HomeworkInput? = null
            override suspend fun classes(auth: String) = Response.success(classes)
            override suspend fun facultyList(auth: String, subjectId: Long?, divisionId: Long?, status: String?, q: String?, page: Int) =
                Response.success(listOf<HomeworkItem>())
            override suspend fun create(auth: String, input: HomeworkInput): Response<HomeworkItem> {
                created = input; return Response.success(item())
            }
            override suspend fun lifecycle(auth: String, id: Long, action: String): Response<HomeworkItem> {
                calls += "$action $id"; return Response.success(item(status = "PUBLISHED"))
            }
        }
        val vm = FacultyHomeworkViewModel(HomeworkRepository(api), { "Bearer s" }, {}, zone) { now }
        vm.load()
        assertTrue(vm.ui.value.loaded)
        vm.newForm()
        assertEquals(4L, vm.ui.value.form?.classId)
        vm.updateForm { form().copy(classId = it.classId) }
        vm.save()
        assertEquals("Essay", api.created?.title)
        assertEquals(FacultyHomeworkPage.DETAIL, vm.ui.value.page)
        assertEquals("Saved as a draft. Preview it, then publish when ready.", vm.ui.value.notice)
        vm.preview()
        assertEquals(FacultyHomeworkPage.PREVIEW, vm.ui.value.page)
        vm.lifecycle("publish")
        assertEquals(listOf("publish 5"), api.calls)
        assertEquals("PUBLISHED", vm.ui.value.selected?.status)
        assertEquals("Published. Students in Class A have been notified.", vm.ui.value.notice)
    }

    @Test fun invalidFormsAndMarksNeverReachTheServer() {
        val api = object : FakeApi() {
            override suspend fun classes(auth: String) = Response.success(classes)
            override suspend fun facultyList(auth: String, subjectId: Long?, divisionId: Long?, status: String?, q: String?, page: Int) =
                Response.success(listOf<HomeworkItem>())
        }
        val vm = FacultyHomeworkViewModel(HomeworkRepository(api), { "Bearer s" }, {}, zone) { now }
        vm.load()
        vm.newForm()
        vm.updateForm { it.copy(title = "Essay") }
        vm.save()
        assertEquals("Enter the deadline as YYYY-MM-DD and HH:MM.", vm.ui.value.formError)
        assertEquals(FacultyHomeworkPage.FORM, vm.ui.value.page)
    }

    @Test fun anExpiredSessionIsReported() {
        val api = object : FakeApi() {
            override suspend fun classes(auth: String): Response<List<HomeworkClass>> =
                Response.error(401, """{"code":"INVALID_AUTHENTICATION"}""".toResponseBody("application/json".toMediaType()))
        }
        var lost = 0
        val vm = FacultyHomeworkViewModel(HomeworkRepository(api), { "Bearer s" }, { lost = it }, zone) { now }
        vm.load()
        assertEquals(401, lost)
        assertEquals("Your session expired. Sign in again.", vm.ui.value.error)
    }

    @Test fun studentHandsInWorkAndSeesConfirmation() {
        val mine = HomeworkSubmission(8, 5, "Asha", null, 1, "SUBMITTED", false, "2026-12-02T09:00:00+05:30", "essay.pdf",
            "pdf", 20, "", null, null, null, null)
        val api = object : FakeApi() {
            var submitted = false
            override suspend fun studentSummary(auth: String) = Response.success(StudentHomeworkSummary(1, 1, 0, 0, 0, emptyList()))
            override suspend fun studentList(auth: String, tab: String, page: Int): Response<List<HomeworkItem>> {
                calls += "list $tab"; return Response.success(listOf(item(status = "PUBLISHED", counts = null)))
            }
            override suspend fun studentDetail(auth: String, id: Long) = Response.success(HomeworkStudentView(
                item(status = "PUBLISHED", counts = null), if (submitted) listOf(mine) else emptyList()))
            override suspend fun submit(auth: String, id: Long, file: MultipartBody.Part, comment: RequestBody?): Response<HomeworkSubmission> {
                submitted = true
                calls += "submit $id ${file.headers?.get("Content-Disposition")} ${comment?.let { Buffer().also { b -> it.writeTo(b) }.readUtf8() }}"
                return Response.success(mine)
            }
        }
        val vm = StudentHomeworkViewModel(HomeworkRepository(api), { "Bearer s" }, {}, "PENDING")
        vm.load()
        assertEquals(listOf("list PENDING"), api.calls)
        vm.open(5)
        assertEquals(StudentHomeworkPage.DETAIL, vm.ui.value.page)
        vm.submit()
        assertEquals("Choose your completed work first.", vm.ui.value.error)
        vm.chooseFile(MaterialUpload("essay.pdf", "application/pdf", "%PDF-1.4\n%%EOF".toByteArray()))
        vm.setComment(" Done ")
        vm.submit()
        assertTrue(api.calls.any { it.startsWith("submit 5") && it.contains("filename=\"essay.pdf\"") && it.endsWith("Done") })
        assertEquals("Submitted essay.pdf (attempt 1).", vm.ui.value.notice)
        assertEquals(1, vm.ui.value.view?.attempts?.size)
        assertNull(vm.ui.value.file)
    }

    @Test fun requestsUseTheHomeworkRoutesAndOnlyChosenFilters() = runBlocking {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            seen += "${request.method} ${request.url.encodedPath}?${request.url.encodedQuery.orEmpty()}"
            okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body((if (request.url.encodedPath.endsWith("stats")) """{"published":1,"closed":0,"archived":0,"drafts":2,"submissions":3,"pending":4,"toMark":1,"evaluated":2,"late":1}"""
                    else "[]").toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = HomeworkRepository(Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(HomeworkApi::class.java))
        repo.facultyList("Bearer s", 11, null, "PUBLISHED", "  ", 0)
        val stats = repo.adminStats("Bearer s", AdminHomeworkChoice(divisionId = 21).query())
        repo.studentList("Bearer s", "EVALUATED", 1)
        assertEquals(2, stats.drafts)
        assertEquals(listOf("GET /api/faculty/homework?subjectId=11&status=PUBLISHED&page=0",
            "GET /api/college/homework/stats?divisionId=21",
            "GET /api/student/homework?tab=EVALUATED&page=1"), seen)
    }

    @Test fun failuresKeepTheirStatusAndCode() = runBlocking {
        val api = object : FakeApi() {
            override suspend fun studentList(auth: String, tab: String, page: Int): Response<List<HomeworkItem>> =
                Response.error(409, """{"code":"SUBMISSION_CONFLICT","message":"server text"}""".toResponseBody("application/json".toMediaType()))
        }
        val failure = runCatching { HomeworkRepository(api).studentList("Bearer s", "ALL", 0) }.exceptionOrNull() as HomeworkFailure
        assertEquals(409, failure.status)
        assertEquals("SUBMISSION_CONFLICT", failure.code)
        assertFalse(failure.message!!.contains("server text"))
    }

    @Test fun studentsSeeTheStateOfTheirOwnWork() {
        assertEquals("Not submitted", studentWorkLabel(item(status = "PUBLISHED", counts = null)))
        assertEquals("Marked: 18.5 / 20", studentWorkLabel(item(status = "CLOSED", counts = null,
            mine = HomeworkMine(8, 1, "EVALUATED", false, 18.5, null))))
        assertEquals("Submitted late", studentWorkLabel(item(status = "PUBLISHED", counts = null,
            mine = HomeworkMine(8, 1, "SUBMITTED", true, null, null))))
        assertEquals("Due in 2 days", homeworkRemaining("2026-12-03T10:00:00+05:30", now))
        assertEquals("Overdue by 5 hours", homeworkRemaining("2026-12-01T05:00:00+05:30", now))
    }
}
