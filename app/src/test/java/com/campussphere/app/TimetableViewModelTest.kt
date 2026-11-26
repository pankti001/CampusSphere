package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.TimetableRepository
import com.campussphere.app.presentation.timetable.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModelTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private val summary = TimetableSummary(4, 1, "DRAFT", 7, 21, 31, "2026-2027", "Science", "BSc", "Semester 1", "A",
        0, 0, "2026-11-26T10:00:00+05:30", "2026-11-26T10:00:00+05:30", null, null)
    private val detail = TimetableDetail(summary, emptyList(), listOf(TimetableProblem("EMPTY", null, "Add at least one lesson.")), false)
    private val scopes = listOf(TimetableScope(21, 31, 1, "2026-2027", 2, "Science", 3, "BSc", 1, "Semester 1", "A"))

    private fun <T> error(status: Int, json: String): Response<T> = Response.error(status, json.toResponseBody("application/json".toMediaType()))

    /** A fake server: only the calls a test sets up answer; anything else fails the test. */
    private open class FakeApi : TimetableApi {
        val calls = mutableListOf<String>()
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
        private fun fail(name: String): Nothing = throw AssertionError("Unexpected call: $name")
    }

    private fun admin(api: FakeApi, lost: (Int) -> Unit = {}) = TimetableAdminViewModel(TimetableRepository(api), { "Bearer s" }, lost)

    /** An admin with draft 4 open in the editor. */
    private fun editing(api: FakeApi): TimetableAdminViewModel {
        val vm = admin(api)
        vm.open(4)
        assertEquals(AdminPage.EDITOR, vm.ui.value.page)
        return vm
    }

    private open class DraftApi(private val detail: TimetableDetail) : FakeApi() {
        override suspend fun detail(auth: String, id: Long) = Response.success(detail)
        override suspend fun options(auth: String, id: Long) = Response.success(TimetableOptions(
            listOf(TimetableAssignment(5, "CS101", "Programming", 3, "Prof A")), listOf(TimetableClassroom(8, "A101", "Room", 40, true))))
    }

    @Test fun choosingAClassLoadsItsVersions() {
        val api = object : FakeApi() {
            override suspend fun scopes(auth: String) = Response.success(scopes)
            override suspend fun list(auth: String, semesterId: Long?, divisionId: Long?, status: String?, page: Int) =
                Response.success(listOf(summary)).also { calls += "list $semesterId $divisionId" }
        }
        val vm = admin(api)
        vm.loadScopes()
        assertEquals(1L, vm.ui.value.choice.yearId) // the only academic year is preselected
        vm.choose(1, 2); vm.choose(2, 3); vm.choose(3, 21)
        assertTrue(api.calls.isEmpty())
        vm.choose(4, 31)
        assertEquals(listOf("list 21 31"), api.calls)
        assertEquals(AdminPage.VERSIONS, vm.ui.value.page)
        assertEquals(listOf(summary), vm.ui.value.versions)
        assertFalse(vm.ui.value.loading)
    }

    @Test fun invalidPeriodIsExplainedWithoutCallingTheServer() {
        val vm = editing(DraftApi(detail))
        vm.startAdd("LESSON")
        assertEquals(AdminPage.ENTRY, vm.ui.value.page)
        assertEquals("09:00", vm.ui.value.form.start)
        vm.editForm { it.copy(end = "08:00") }
        vm.saveEntry()                           // addEntry would fail the test if it were called
        assertEquals("The end time must be after the start time.", vm.ui.value.error)
        assertEquals(AdminPage.ENTRY, vm.ui.value.page)
    }

    @Test fun conflictKeepsTheFormOpenAndListsTheClashes() {
        val api = object : DraftApi(detail) {
            override suspend fun addEntry(auth: String, id: Long, input: TimetableEntryInput): Response<TimetableDetail> =
                error(409, """{"code":"TIMETABLE_CONFLICT","clashes":[{"type":"FACULTY","day":1,"start":"09:30:00","end":"10:30:00","with":"BSc · B · CS301"}],"problems":[]}""")
        }
        val vm = editing(api)
        vm.startAdd("LESSON")
        vm.editForm { it.copy(assignmentId = 5, classroomId = 8) }
        vm.saveEntry()
        val ui = vm.ui.value
        assertEquals(AdminPage.ENTRY, ui.page)
        assertEquals("This time clashes with another booking.", ui.error)
        assertEquals("Mon 09:30–10:30: the faculty member already teaches BSc · B · CS301.", clashMessage(ui.clashes.single()))
        assertFalse(ui.busy)
        // Changing the form clears the old warning.
        vm.editForm { it.copy(start = "11:00", end = "12:00") }
        assertTrue(vm.ui.value.clashes.isEmpty())
    }

    @Test fun savedPeriodReturnsToTheEditorOnItsDay() {
        val saved = detail.copy(entries = listOf(TimetableEntry(9, "LESSON", 3, "09:00", "10:00", 5, "CS101", "Programming", 3, "Prof A", 8, "A101", "Room", null)))
        val api = object : DraftApi(detail) {
            override suspend fun addEntry(auth: String, id: Long, input: TimetableEntryInput) = Response.success(saved).also { calls += "add ${input.day}" }
        }
        val vm = editing(api)
        vm.setDay(3)
        vm.startAdd("LESSON")
        vm.editForm { it.copy(assignmentId = 5, classroomId = 8) }
        vm.saveEntry()
        assertEquals(listOf("add 3"), api.calls)
        assertEquals(AdminPage.EDITOR, vm.ui.value.page)
        assertEquals(3, vm.ui.value.day)
        assertEquals(saved, vm.ui.value.detail)
    }

    @Test fun publishSendsThePreviewedRevisionAndShowsProblems() {
        val api = object : DraftApi(detail) {
            override suspend fun preview(auth: String, id: Long) = Response.success(detail)
            override suspend fun publish(auth: String, id: Long, input: TimetablePublish): Response<TimetableDetail> {
                calls += "publish ${input.revision}"
                return error(409, """{"code":"TIMETABLE_NOT_READY","clashes":[],"problems":[{"type":"FACULTY_CONFLICT","entryId":9,"detail":"Mon 09:00–10:00: Prof A also teaches BSc · B · CS301."}]}""")
            }
        }
        val vm = editing(api)
        vm.preview()
        assertEquals(AdminPage.PREVIEW, vm.ui.value.page)
        vm.publish()
        assertEquals(listOf("publish 7"), api.calls)
        assertEquals(AdminPage.PREVIEW, vm.ui.value.page)
        assertEquals("FACULTY_CONFLICT", vm.ui.value.publishProblems.single().type)
    }

    @Test fun expiredSessionSignsOut() {
        var lost = 0
        val api = object : FakeApi() {
            override suspend fun scopes(auth: String): Response<List<TimetableScope>> = error(401, """{"code":"INVALID_AUTHENTICATION"}""")
        }
        val vm = admin(api) { lost = it }
        vm.loadScopes()
        assertEquals(401, lost)
        assertEquals("Your session expired. Sign in again.", vm.ui.value.error)
    }

    // ---- Personal timetable ----

    private val week = TimetableSchedule(true, "BSc · Semester 1 · A", 2, "2026-11-26T10:00:00+05:30", listOf(
        TimetableSlot(9, "LESSON", 5, "09:00", "10:00", "CS101", "Programming", "Prof A", "A101", "Room", null, "A", "BSc", "Semester 1", 2, null)))

    @Test fun myTimetableShowsLoadingThenTodayAndWeek() {
        val gate = CompletableDeferred<Unit>()
        val api = object : FakeApi() {
            override suspend fun week(auth: String, area: String): Response<TimetableSchedule> {
                calls += "week $area"; gate.await(); return Response.success(week)
            }
            override suspend fun day(auth: String, area: String, date: String?) =
                Response.success(TimetableDay(date!!, 5, week)).also { calls += "day $area $date" }
        }
        val vm = MyTimetableViewModel(TimetableRepository(api), "student", { "Bearer s" }, {}) { LocalDate.of(2026, 11, 27) }
        assertEquals(5, vm.ui.value.day) // opens on today's weekday (Friday)
        vm.load()
        assertTrue(vm.ui.value.loading)
        gate.complete(Unit)
        val ui = vm.ui.value
        assertFalse(ui.loading)
        assertTrue(ui.loaded)
        assertEquals(listOf("week student", "day student 2026-11-27"), api.calls)
        assertEquals(1, dayRows(ui.today!!.schedule.slots, ui.today!!.day).size)
        assertNull(ui.error)
    }

    @Test fun myTimetableShowsErrorsAndKeepsTheLastData() {
        val api = object : FakeApi() {
            override suspend fun week(auth: String, area: String): Response<TimetableSchedule> = error(503, "")
        }
        val vm = MyTimetableViewModel(TimetableRepository(api), "faculty", { "Bearer s" }, {}) { LocalDate.of(2026, 11, 27) }
        vm.load()
        assertEquals("CampusSphere could not complete the request. Try again shortly.", vm.ui.value.error)
        assertFalse(vm.ui.value.loading)
        assertNull(vm.ui.value.week)
    }
}
