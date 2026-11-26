package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.notifications.FacultyNoticesViewModel
import com.campussphere.app.presentation.notifications.noticeKind
import com.campussphere.app.presentation.notifications.noticesToPost
import com.campussphere.app.presentation.timetable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/** A fake server for the admin timetable API; tests override what they use, anything else fails. */
open class StubTimetableApi : TimetableApi {
    val calls = mutableListOf<String>()
    private fun fail(name: String): Nothing = throw AssertionError("Unexpected call: $name")
    override suspend fun scopes(auth: String): Response<List<TimetableScope>> = fail("scopes")
    override suspend fun list(auth: String, semesterId: Long?, divisionId: Long?, status: String?, page: Int): Response<List<TimetableSummary>> = fail("list")
    override suspend fun create(auth: String, input: TimetableCreate): Response<TimetableDetail> = fail("create")
    override suspend fun detail(auth: String, id: Long): Response<TimetableDetail> = fail("detail")
    override suspend fun preview(auth: String, id: Long): Response<TimetableDetail> = fail("preview")
    override suspend fun options(auth: String, id: Long): Response<TimetableOptions> = fail("options")
    override suspend fun history(auth: String, id: Long, page: Int): Response<List<TimetableEvent>> = fail("history")
    override suspend fun addEntry(auth: String, id: Long, input: TimetableEntryInput): Response<TimetableDetail> = fail("addEntry")
    override suspend fun updateEntry(auth: String, id: Long, entryId: Long, input: TimetableEntryInput): Response<TimetableDetail> = fail("updateEntry")
    override suspend fun deleteEntry(auth: String, id: Long, entryId: Long): Response<TimetableDetail> = fail("deleteEntry")
    override suspend fun publish(auth: String, id: Long, input: TimetablePublish): Response<TimetableDetail> = fail("publish")
    override suspend fun archive(auth: String, id: Long): Response<TimetableDetail> = fail("archive")
    override suspend fun classrooms(auth: String): Response<List<TimetableClassroom>> = fail("classrooms")
    override suspend fun createClassroom(auth: String, input: TimetableClassroomInput): Response<TimetableClassroom> = fail("createClassroom")
    override suspend fun updateClassroom(auth: String, id: Long, input: TimetableClassroomInput): Response<TimetableClassroom> = fail("updateClassroom")
    override suspend fun week(auth: String, area: String): Response<TimetableSchedule> = fail("week")
    override suspend fun day(auth: String, area: String, date: String?): Response<TimetableDay> = fail("day")
}

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableImportViewModelTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private val summary = TimetableSummary(4, 2, "DRAFT", 0, 21, 31, "2026-2027", "Science", "BSc", "Semester 1", "A",
        2, 0, "2026-11-26T10:00:00+05:30", "2026-11-26T10:00:00+05:30", null, null)
    private val scopes = listOf(TimetableScope(21, 31, 1, "2026-2027", 2, "Science", 3, "BSc", 1, "Semester 1", "A"))
    private val file = PickedSheet("week.xlsx", byteArrayOf(1, 2, 3))

    private fun preview(canImport: Boolean) = TimetableImportPreview("week.xlsx", "hash-1", 2, if (canImport) 2 else 1,
        if (canImport) 0 else 1, emptyList(), listOf(TimetableImportDivision(31, "A", 2, 2, 2, 1)),
        listOf(TimetableImportRow(2, "A", "Monday", "09:00", "10:00", "", "JAVA", "k@c.test", "101",
            "", if (canImport) emptyList() else listOf("Classroom '101' does not exist."))), canImport)

    private fun <T> error(status: Int, json: String): Response<T> = Response.error(status, json.toResponseBody("application/json".toMediaType()))

    private open class StubImportApi : TimetableImportApi {
        val calls = mutableListOf<String>()
        override suspend fun template(auth: String, semesterId: Long?): Response<ResponseBody> = throw AssertionError("template")
        override suspend fun preview(auth: String, semesterId: RequestBody, file: MultipartBody.Part): Response<TimetableImportPreview> = throw AssertionError("preview")
        override suspend fun importFile(auth: String, semesterId: RequestBody, sha256: RequestBody, file: MultipartBody.Part): Response<TimetableImported> = throw AssertionError("import")
        override suspend fun copy(auth: String, request: TimetableCopyRequest): Response<TimetableCopyResult> = throw AssertionError("copy")
    }

    private fun text(body: RequestBody) = Buffer().also { body.writeTo(it) }.readUtf8()

    /** An admin who has chosen BSc · Semester 1 · A. */
    private fun admin(api: TimetableApi, imports: TimetableImportApi): TimetableAdminViewModel {
        val vm = TimetableAdminViewModel(TimetableRepository(api), { "Bearer s" }, {}, TimetableImportRepository(imports))
        vm.loadScopes()
        vm.choose(1, 2); vm.choose(2, 3); vm.choose(3, 21)
        return vm
    }

    private val timetableApi = object : StubTimetableApi() {
        override suspend fun scopes(auth: String) = Response.success(scopes)
        override suspend fun list(auth: String, semesterId: Long?, divisionId: Long?, status: String?, page: Int) =
            Response.success(listOf(summary)).also { calls += "list $semesterId $divisionId" }
    }

    @Test fun uploadIsPreviewedThenImportedOnlyAfterConfirmationAsDrafts() {
        val imports = object : StubImportApi() {
            override suspend fun preview(auth: String, semesterId: RequestBody, file: MultipartBody.Part) =
                Response.success(preview(true)).also { calls += "preview ${text(semesterId)}" }
            override suspend fun importFile(auth: String, semesterId: RequestBody, sha256: RequestBody, file: MultipartBody.Part) =
                Response.success(TimetableImported(listOf(summary), 2)).also { calls += "import ${text(semesterId)} ${text(sha256)}" }
        }
        val vm = admin(timetableApi, imports)
        vm.startImport()
        assertEquals(AdminPage.IMPORT, vm.ui.value.page)
        vm.pickImportFile { file }
        assertEquals(listOf("preview 21"), imports.calls)      // nothing is saved by choosing a file
        assertTrue(vm.ui.value.importPreview!!.canImport)
        vm.choose(4, 31)                                        // the admin also picks a division meanwhile
        vm.startImport(); vm.pickImportFile { file }
        vm.confirmImport()
        assertEquals("import 21 hash-1", imports.calls.last())
        val ui = vm.ui.value
        assertEquals(AdminPage.VERSIONS, ui.page)
        assertTrue(ui.notice!!.contains("Imported 2 periods into 1 draft timetable(s): A v2. Nothing is published yet."))
        assertNull(ui.importPreview)
    }

    @Test fun aFileWithErrorsCannotBeImported() {
        val imports = object : StubImportApi() {
            override suspend fun preview(auth: String, semesterId: RequestBody, file: MultipartBody.Part) = Response.success(preview(false))
        }
        val vm = admin(timetableApi, imports)
        vm.startImport()
        vm.pickImportFile { file }
        assertEquals("Classroom '101' does not exist.", vm.ui.value.importPreview!!.details.single().errors.single())
        vm.confirmImport()                                      // importFile would fail the test if it were called
        assertEquals(AdminPage.IMPORT, vm.ui.value.page)
    }

    @Test fun aWrongFileIsExplainedWithoutUploading() {
        val vm = admin(timetableApi, StubImportApi())
        vm.startImport()
        vm.pickImportFile { throw TimetableFailure(0, null, sheetProblem("week.csv", 10)!!) }
        assertEquals("Choose an Excel workbook (.xlsx). Save .xls or .csv files as .xlsx first.", vm.ui.value.error)
        assertNull(vm.ui.value.importPreview)
    }

    @Test fun copyOpensTheNewDraftAndListsWhatWasLeftOut() {
        val detail = TimetableDetail(summary, emptyList(), emptyList(), true)
        val api = object : StubTimetableApi() {
            override suspend fun scopes(auth: String) = Response.success(scopes)
            override suspend fun list(auth: String, semesterId: Long?, divisionId: Long?, status: String?, page: Int) =
                Response.success(listOf(summary.copy(id = 9, status = "ARCHIVED"), summary))
            override suspend fun options(auth: String, id: Long) = Response.success(TimetableOptions(emptyList(), emptyList()))
        }
        val imports = object : StubImportApi() {
            override suspend fun copy(auth: String, request: TimetableCopyRequest) =
                Response.success(TimetableCopyResult(detail, 5, listOf("Mon 09:00–10:00 JAVA: no faculty member teaches this subject to the new class.")))
                    .also { calls += "copy ${request.sourceTimetableId}->${request.semesterId}/${request.divisionId}" }
        }
        val vm = admin(api, imports)
        vm.choose(4, 31)
        vm.startCopy()
        assertEquals(AdminPage.COPY, vm.ui.value.page)
        assertEquals(listOf(9L), vm.ui.value.copySources.map { it.id })   // the class's own draft is not offered
        vm.copyFrom(9)
        assertEquals(listOf("copy 9->21/31"), imports.calls)
        assertEquals(AdminPage.EDITOR, vm.ui.value.page)
        assertEquals(1, vm.ui.value.copySkipped.size)
        assertTrue(vm.ui.value.notice!!.startsWith("Copied 5 periods into draft version 2."))
    }

    @Test fun publishTellsTheAdminHowManyFacultyNotificationsWereSent() {
        val detail = TimetableDetail(summary, emptyList(), emptyList(), true)
        val api = object : StubTimetableApi() {
            override suspend fun detail(auth: String, id: Long) = Response.success(detail)
            override suspend fun options(auth: String, id: Long) = Response.success(TimetableOptions(emptyList(), emptyList()))
            override suspend fun publish(auth: String, id: Long, input: TimetablePublish) =
                Response.success(detail.copy(timetable = summary.copy(status = "PUBLISHED"), notified = 2))
        }
        val vm = TimetableAdminViewModel(TimetableRepository(api), { "Bearer s" }, {})
        vm.open(4)
        vm.publish()
        assertEquals("Version 2 is published. Students and faculty now see it. 2 faculty notification(s) sent.", vm.ui.value.notice)
    }

    // ---- Faculty notifications ----

    private fun notice(id: Long, read: Boolean) = FacultyNotice(id, "ASSIGNED", "New class: Java Programming (JAVA)",
        "Monday 09:00–10:00 · BSc · Semester 1 · Division A · 2026-2027 · Room 101 · from Mon 30 Nov 2026", "JAVA",
        "Java Programming", "BSc", "Semester 1", "A", "2026-2027", 1, "09:00:00", "10:00:00", "101", "2026-11-30",
        "2026-11-26T10:00:00+05:30", if (read) "2026-11-26T11:00:00+05:30" else null)

    @Test fun onlyNewUnreadNoticesGoToThePhoneNotificationBar() {
        val notices = listOf(notice(5, false), notice(3, false), notice(4, true), notice(2, false))
        assertEquals(listOf(3L, 5L), noticesToPost(notices, lastPostedId = 2).map { it.id })
        assertTrue(noticesToPost(notices, lastPostedId = 5).isEmpty())
        val many = (1L..9L).map { notice(it, false) }
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L), noticesToPost(many, 0).map { it.id })
        assertEquals("New class", noticeKind("ASSIGNED"))
        assertEquals("Removed", noticeKind("CANCELLED"))
    }

    @Test fun openingANoticeMarksItReadOnceAndMarkAllUpdatesTheList() {
        val api = object : FacultyNotificationApi {
            val calls = mutableListOf<String>()
            override suspend fun list(auth: String, unread: Boolean, page: Int) = Response.success(listOf(notice(7, false), notice(6, false)))
            override suspend fun unread(auth: String) = Response.success(UnreadCount(2))
            override suspend fun read(auth: String, id: Long) = Response.success(Unit).also { calls += "read $id" }
            override suspend fun readAll(auth: String) = Response.success(mapOf("updated" to 1)).also { calls += "all" }
        }
        val vm = FacultyNoticesViewModel(FacultyNotificationRepository(api), { "Bearer s" }, {})
        vm.load()
        assertEquals(2, vm.ui.value.unread)
        vm.open(vm.ui.value.notices.first())
        vm.open(vm.ui.value.notices.first())                  // already read: no second request
        assertEquals(listOf("read 7"), api.calls)
        assertEquals(1, vm.ui.value.unread)
        vm.markAllRead()
        assertEquals(0, vm.ui.value.unread)
        assertEquals(listOf("read 7", "all"), api.calls)
    }

    @Test fun anExpiredSessionInTheInboxSignsOut() {
        var lost = 0
        val api = object : FacultyNotificationApi {
            override suspend fun list(auth: String, unread: Boolean, page: Int): Response<List<FacultyNotice>> = error(401, "{}")
            override suspend fun unread(auth: String): Response<UnreadCount> = error(401, "{}")
            override suspend fun read(auth: String, id: Long): Response<Unit> = error(401, "{}")
            override suspend fun readAll(auth: String): Response<Map<String, Int>> = error(401, "{}")
        }
        val vm = FacultyNoticesViewModel(FacultyNotificationRepository(api), { "Bearer s" }, { lost = it })
        vm.load()
        assertEquals(401, lost)
        assertEquals("Your session expired. Sign in again.", vm.ui.value.error)
    }
}
