package com.campussphere.app.presentation.college

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.FacultyAccessRequest
import com.campussphere.app.data.remote.api.FacultyAssignment
import com.campussphere.app.data.remote.api.FacultyAssignmentInput
import com.campussphere.app.data.remote.api.FacultyMember
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import com.campussphere.app.data.repository.AcademicCatalogFailure
import com.campussphere.app.data.repository.AuthFailure
import com.campussphere.app.data.repository.FacultyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class FacultyPage(val title: String) {
    DIRECTORY("Faculty management"), PROFILE("Faculty profile"), DEPARTMENT("Choose department"),
    PROGRAM("Choose program"), YEAR("Choose academic year"), SEMESTER("Choose semester"),
    DIVISION("Choose division"), SUBJECT("Choose subject"), REVIEW("Review assignment"),
    DASHBOARD("Faculty dashboard"), REQUESTS("Faculty access requests")
}

data class AssignmentSelection(
    val department: AcademicCatalogItem? = null,
    val program: AcademicCatalogItem? = null,
    val year: AcademicCatalogItem? = null,
    val semester: AcademicCatalogItem? = null,
    val division: AcademicCatalogItem? = null,
    val subject: AcademicCatalogItem? = null
) {
    fun input(): FacultyAssignmentInput? {
        if (department == null || program == null || year == null || semester == null ||
            division == null || subject == null) return null
        return FacultyAssignmentInput(department.id, program.id, year.id, semester.id, division.id, subject.id)
    }
}

data class FacultyState(
    val page: FacultyPage,
    val busy: Boolean = true,
    val members: List<FacultyMember> = emptyList(),
    val requests: List<FacultyAccessRequest> = emptyList(),
    val member: FacultyMember? = null,
    val assignments: List<FacultyAssignment> = emptyList(),
    val options: List<AcademicCatalogItem> = emptyList(),
    val selection: AssignmentSelection = AssignmentSelection(),
    val error: String? = null,
    val success: String? = null,
    val canRetry: Boolean = false
)

class FacultyViewModel(
    private val repository: FacultyRepository,
    private val authorization: () -> String,
    private val onAccessLost: (Int) -> Unit,
    private val dashboard: Boolean
) : ViewModel() {
    private val mutable = MutableStateFlow(FacultyState(if (dashboard) FacultyPage.DASHBOARD else FacultyPage.DIRECTORY))
    val state = mutable.asStateFlow()
    private var running: Job? = null
    private var retryAction: (() -> Unit)? = null
    private var memberId: Long? = null

    init { if (dashboard) loadDashboard() else directory() }

    fun directory() {
        memberId = null
        load(FacultyState(FacultyPage.DIRECTORY)) { draft, auth -> draft.copy(members = repository.members(auth)) }
    }

    fun pendingRequests() {
        load(FacultyState(FacultyPage.REQUESTS)) { draft, auth ->
            draft.copy(requests = repository.pending(auth))
        }
    }

    fun decide(request: FacultyAccessRequest, approved: Boolean, employmentConfirmed: Boolean) {
        val current = mutable.value
        if (current.page != FacultyPage.REQUESTS || request !in current.requests) return
        mutate { auth ->
            repository.decide(auth, request.id, approved, employmentConfirmed)
            current.copy(requests = current.requests.filterNot { it.id == request.id },
                success = if (approved) "Faculty access approved." else "Faculty request rejected.")
        }
    }

    fun profile(id: Long) {
        memberId = id
        load(FacultyState(FacultyPage.PROFILE)) { draft, auth -> readProfile(draft, auth, id) }
    }

    fun chooseDepartment() = choices(FacultyPage.DEPARTMENT, AssignmentSelection()) { repository.departments(it) }

    fun startAssignment() {
        val member = mutable.value.member ?: return
        val dept = member.departmentId
        if (dept == null) {
            mutable.value = mutable.value.copy(error = "Choose a department before adding an assignment.")
            return
        }
        val selection = AssignmentSelection(AcademicCatalogItem(dept, "", member.department.orEmpty()))
        choices(FacultyPage.PROGRAM, selection) { repository.programs(it, dept) }
    }

    fun select(item: AcademicCatalogItem) {
        val current = mutable.value
        if (current.busy || item !in current.options) return
        val selection = current.selection
        when (current.page) {
            FacultyPage.DEPARTMENT -> changeDepartment(item)
            FacultyPage.PROGRAM -> choices(FacultyPage.YEAR, selection.copy(program = item)) { repository.years(it) }
            FacultyPage.YEAR -> choices(FacultyPage.SEMESTER, selection.copy(year = item)) {
                repository.semesters(it, requireNotNull(selection.program).id, item.id)
            }
            FacultyPage.SEMESTER -> choices(FacultyPage.DIVISION, selection.copy(semester = item)) {
                repository.divisions(it, requireNotNull(selection.program).id, requireNotNull(selection.year).id)
            }
            FacultyPage.DIVISION -> choices(FacultyPage.SUBJECT, selection.copy(division = item)) {
                repository.subjects(it, requireNotNull(selection.program).id, requireNotNull(selection.semester).id)
            }
            FacultyPage.SUBJECT -> mutable.value = current.copy(page = FacultyPage.REVIEW,
                options = emptyList(), selection = selection.copy(subject = item))
            else -> Unit
        }
    }

    fun save() {
        val current = mutable.value
        val input = current.selection.input()
        if (input == null) {
            mutable.value = current.copy(error = "Select every required field before saving.")
            return
        }
        val id = memberId ?: return
        mutate { auth ->
            val assignment = repository.assign(auth, id, input)
            current.copy(page = FacultyPage.PROFILE, assignments = current.assignments + assignment,
                selection = AssignmentSelection(), success = "Assignment added.")
        }
    }

    fun end(assignment: FacultyAssignment) {
        val id = memberId ?: return
        val current = mutable.value
        mutate { auth ->
            repository.end(auth, id, assignment.id)
            current.copy(assignments = current.assignments.filterNot { it.id == assignment.id },
                success = "Assignment ended.")
        }
    }

    fun cancel() {
        val id = memberId
        if (id == null || mutable.value.page == FacultyPage.PROFILE) directory() else profile(id)
    }

    fun retry() { retryAction?.invoke() }
    fun refresh() {
        when {
            dashboard -> loadDashboard()
            mutable.value.page == FacultyPage.REQUESTS -> pendingRequests()
            else -> memberId?.let(::profile) ?: directory()
        }
    }

    private fun loadDashboard() {
        load(FacultyState(FacultyPage.DASHBOARD)) { draft, auth -> draft.copy(assignments = repository.mine(auth)) }
    }

    private fun changeDepartment(item: AcademicCatalogItem) {
        val id = memberId ?: return
        val current = mutable.value
        mutate { auth ->
            current.copy(page = FacultyPage.PROFILE, member = repository.department(auth, id, item.id),
                options = emptyList(), success = "Department saved.")
        }
    }

    private suspend fun readProfile(draft: FacultyState, auth: String, id: Long) =
        draft.copy(member = repository.profile(auth, id), assignments = repository.assignments(auth, id))

    private fun choices(page: FacultyPage, selection: AssignmentSelection,
                        fetch: suspend (String) -> List<AcademicCatalogItem>) {
        load(mutable.value.copy(page = page, options = emptyList(), selection = selection, success = null)) { draft, auth ->
            draft.copy(options = fetch(auth))
        }
    }

    private fun load(draft: FacultyState, fetch: suspend (FacultyState, String) -> FacultyState) {
        retryAction = { load(draft, fetch) }
        run(draft, retryable = true) { fetch(draft, it) }
    }

    private fun mutate(write: suspend (String) -> FacultyState) {
        if (mutable.value.busy) return
        retryAction = null
        run(mutable.value, retryable = false, action = write)
    }

    private fun run(draft: FacultyState, retryable: Boolean, action: suspend (String) -> FacultyState) {
        running?.cancel()
        val pending = draft.copy(busy = true, error = null, success = null, canRetry = false)
        mutable.value = pending
        running = viewModelScope.launch {
            try {
                mutable.value = action(authorization()).copy(busy = false, error = null, canRetry = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val status = (failure as? AcademicCatalogFailure)?.status
                val lost = status == 401 || status == 403 || failure is AuthFailure
                if (lost) {
                    mutable.value = FacultyState(pending.page, busy = false, error = "Your access could not be verified.")
                    onAccessLost(status ?: 403)
                } else {
                    val message = when {
                        status == 400 && pending.page == FacultyPage.REQUESTS -> "Check the employment confirmation and try again."
                        status == 400 -> "Choose a matching department, program, year, semester, division, and subject."
                        status != null -> failure.message
                        else -> "CampusSphere could not complete the request. Refresh to check the current state."
                    }
                    mutable.value = pending.copy(busy = false, error = message, canRetry = retryable)
                }
            }
        }
    }
}
