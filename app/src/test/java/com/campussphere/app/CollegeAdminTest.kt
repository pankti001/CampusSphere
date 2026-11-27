package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.CollegeAdminRepository
import com.campussphere.app.presentation.college.*
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

@OptIn(ExperimentalCoroutinesApi::class)
class CollegeAdminTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private fun counts(departments: Long = 0, programs: Long = 0, years: Long = 0, semesters: Long = 0, divisions: Long = 0,
                       subjects: Long = 0, faculty: Long = 0, assignments: Long = 0, students: Long = 0, enrolled: Long = 0,
                       classrooms: Long = 0, published: Long = 0) =
        CollegeCounts(departments, programs, years, semesters, divisions, subjects, classrooms, faculty, students, enrolled,
            assignments, 0, 0, 0, 0, 0, published, 0, 0)

    private fun <T> error(status: Int, code: String): Response<T> =
        Response.error(status, """{"code":"$code"}""".toResponseBody("application/json".toMediaType()))

    private open class FakeApi : CollegeAdminApi {
        val calls = mutableListOf<String>()
        override suspend fun profile(auth: String): Response<CollegeProfile> =
            Response.success(CollegeProfile(1, "P009", "K.S College", true, null, null))
        override suspend fun overview(auth: String): Response<CollegeCounts> = throw AssertionError("overview")
        override suspend fun people(auth: String, kind: String, active: Boolean?, q: String?): Response<List<CollegePerson>> =
            Response.success(emptyList<CollegePerson>()).also { calls += "people $kind $active $q" }
        override suspend fun update(auth: String, kind: String, id: Long, details: CollegePersonDetails): Response<CollegePerson> =
            throw AssertionError("update")
        override suspend fun setActive(auth: String, kind: String, id: Long, status: CollegePersonStatus): Response<CollegePerson> =
            throw AssertionError("setActive")
    }

    @Test fun setupProgressFollowsTheRealCounts() {
        val empty = setupSteps(counts(departments = 1, programs = 1))
        assertEquals(listOf(true, false, false, false, false, false, false), empty.map { it.done })
        assertEquals(AdminArea.YEARS, empty.first { !it.done }.area)   // "Add an academic year" is next
        val full = setupSteps(counts(1, 1, 1, 1, 2, 3, 2, 5, 3, 3, 3, 2))
        assertTrue(full.all { it.done })
        // Faculty without any teaching assignment is not "done".
        assertFalse(setupSteps(counts(faculty = 2, assignments = 0))[4].done)
    }

    @Test fun homeShowsRealNumbersAndNeverAZeroAfterAFailure() {
        var fail = false
        val api = object : FakeApi() {
            override suspend fun overview(auth: String): Response<CollegeCounts> =
                if (fail) error(500, "X") else Response.success(counts(departments = 1, programs = 1))
        }
        val vm = CollegeAdminHomeViewModel(CollegeAdminRepository(api), { "Bearer s" }, {})
        vm.load()
        assertEquals("K.S College", vm.ui.value.profile?.name)
        assertEquals(1L, vm.ui.value.counts?.departments)
        fail = true
        vm.load()
        assertNull(vm.ui.value.counts)   // numbers are dropped, the screen shows "—" and the error
        assertEquals("CampusSphere could not complete the request. Try again shortly.", vm.ui.value.error)
        assertFalse(vm.ui.value.loading)
    }

    @Test fun anExpiredSessionOnTheHomeSignsOut() {
        var lost = 0
        val api = object : FakeApi() {
            override suspend fun profile(auth: String): Response<CollegeProfile> = error(401, "INVALID_AUTHENTICATION")
        }
        CollegeAdminHomeViewModel(CollegeAdminRepository(api), { "Bearer s" }, { lost = it }).load()
        assertEquals(401, lost)
    }

    @Test fun editedDetailsAreCheckedBeforeSending() {
        assertNull(personDetailsProblem("Krishna Test", ""))
        assertNull(personDetailsProblem("Krishna Test", "9876543210"))
        assertEquals("Enter the person's name.", personDetailsProblem(" ", ""))
        assertEquals("Enter a 10-digit phone number, or leave it empty.", personDetailsProblem("Krishna", "12345"))
        val vm = CollegeAccountsViewModel(CollegeAdminRepository(FakeApi()), { "Bearer s" }, {})
        vm.edit(CollegePerson(4, "Krishna Test", "k@c.test", null, true, true, "CS", null))
        vm.changeName(" ")
        vm.save()   // update() would fail the test if it were called
        assertEquals("Enter the person's name.", vm.ui.value.error)
    }

    @Test fun deactivatingUpdatesTheListAndFiltersFollowTheServer() {
        val krishna = CollegePerson(4, "Krishna Test", "k@c.test", null, true, true, "CS", null)
        val api = object : FakeApi() {
            override suspend fun people(auth: String, kind: String, active: Boolean?, q: String?) =
                Response.success(listOf(krishna)).also { calls += "people $kind $active $q" }
            override suspend fun setActive(auth: String, kind: String, id: Long, status: CollegePersonStatus) =
                Response.success(krishna.copy(active = status.active)).also { calls += "active $kind $id ${status.active}" }
        }
        val vm = CollegeAccountsViewModel(CollegeAdminRepository(api), { "Bearer s" }, {})
        vm.setActiveFilter(true)
        vm.edit(vm.ui.value.people.single())
        vm.setActive(false)
        assertEquals("active faculty 4 false", api.calls.last())
        assertTrue(vm.ui.value.people.isEmpty())   // no longer matches the "Active" filter
        assertEquals("Krishna Test is deactivated and can no longer sign in.", vm.ui.value.notice)
        vm.setKind("students")
        assertEquals("people students true null", api.calls.last())
    }
}
