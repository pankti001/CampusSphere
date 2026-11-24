package com.campussphere.app.presentation.college

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import com.campussphere.app.data.repository.AcademicCatalogFailure
import com.campussphere.app.data.repository.AuthFailure
import com.campussphere.app.data.repository.StudentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class StudentMode { ADMIN, STUDENT, AFFILIATION }

enum class StudentPage(val title: String) {
    DIRECTORY("Student management"), PROFILE("Student profile"), DEPARTMENT("Choose department"),
    PROGRAM("Choose program"), YEAR("Choose academic year"), SEMESTER("Choose semester"),
    DIVISION("Choose division"), REVIEW("Review enrollment"), DASHBOARD("Student dashboard"),
    REQUEST("Request college affiliation"), QUEUE("Student affiliation requests")
}

data class EnrollmentSelection(
    val department: AcademicCatalogItem? = null,
    val program: AcademicCatalogItem? = null,
    val year: AcademicCatalogItem? = null,
    val semester: AcademicCatalogItem? = null,
    val division: AcademicCatalogItem? = null
) {
    fun input(): StudentEnrollmentInput? {
        if (department == null || program == null || year == null || semester == null || division == null) return null
        return StudentEnrollmentInput(department.id, program.id, year.id, semester.id, division.id)
    }
}

data class StudentState(
    val page: StudentPage,
    val busy: Boolean = true,
    val students: List<StudentMember> = emptyList(),
    val student: StudentMember? = null,
    val enrollments: List<StudentEnrollment> = emptyList(),
    val requests: List<StudentAffiliation> = emptyList(),
    val options: List<AcademicCatalogItem> = emptyList(),
    val selection: EnrollmentSelection = EnrollmentSelection(),
    val error: String? = null,
    val success: String? = null,
    val canRetry: Boolean = false
)

class StudentViewModel(
    private val repository: StudentRepository,
    private val authorization: () -> String,
    private val onAccessLost: (Int) -> Unit,
    private val mode: StudentMode
) : ViewModel() {
    private val mutable = MutableStateFlow(StudentState(when (mode) {
        StudentMode.ADMIN -> StudentPage.DIRECTORY
        StudentMode.STUDENT -> StudentPage.DASHBOARD
        StudentMode.AFFILIATION -> StudentPage.REQUEST
    }))
    val state = mutable.asStateFlow()
    private var running: Job? = null
    private var retryAction: (() -> Unit)? = null
    private var studentId: Long? = null

    init { refresh() }

    fun refresh() {
        when (mode) {
            StudentMode.STUDENT -> read(StudentState(StudentPage.DASHBOARD)) { draft, auth ->
                draft.copy(enrollments = repository.current(auth))
            }
            StudentMode.AFFILIATION -> read(StudentState(StudentPage.REQUEST)) { draft, auth ->
                draft.copy(requests = listOfNotNull(repository.myRequest(auth)))
            }
            StudentMode.ADMIN -> when {
                mutable.value.page == StudentPage.QUEUE -> queue()
                studentId != null -> profile(requireNotNull(studentId))
                else -> directory()
            }
        }
    }

    fun directory() {
        studentId = null
        read(StudentState(StudentPage.DIRECTORY)) { draft, auth -> draft.copy(students = repository.students(auth)) }
    }

    fun profile(id: Long) {
        studentId = id
        read(StudentState(StudentPage.PROFILE)) { draft, auth ->
            draft.copy(student = repository.profile(auth, id), enrollments = repository.history(auth, id))
        }
    }

    fun queue() {
        read(StudentState(StudentPage.QUEUE)) { draft, auth -> draft.copy(requests = repository.pending(auth)) }
    }

    fun submit(rawCode: String) {
        val current = mutable.value
        if (current.page != StudentPage.REQUEST || current.busy || current.requests.any { it.status == "PENDING" }) return
        val code = normalizedCollegeCode(rawCode)
        if (code == null) {
            mutable.value = current.copy(error = "Enter a valid college code.")
            return
        }
        write { auth ->
            current.copy(requests = listOf(repository.request(auth, code)), success = "Affiliation request sent.")
        }
    }

    fun decide(request: StudentAffiliation, approved: Boolean, confirmed: Boolean) {
        val current = mutable.value
        if (current.page != StudentPage.QUEUE || request !in current.requests) return
        if (approved && !confirmed) {
            mutable.value = current.copy(error = "Confirm the college admission record before approving.")
            return
        }
        write { auth ->
            repository.decide(auth, request.id, approved, confirmed)
            current.copy(requests = current.requests.filterNot { it.id == request.id },
                success = if (approved) "Student affiliation approved." else "Student affiliation rejected.")
        }
    }

    fun selectDepartment() = choices(StudentPage.DEPARTMENT, EnrollmentSelection()) { repository.departments(it) }

    fun startEnrollment() {
        val current = mutable.value
        val student = current.student ?: return
        val department = student.departmentId
        if (department == null || current.enrollments.any { it.current }) {
            mutable.value = current.copy(error = if (department == null) "Choose a department before enrolling."
                else "This student already has a current enrollment.", success = null)
            return
        }
        choices(StudentPage.PROGRAM, EnrollmentSelection(department = AcademicCatalogItem(
            department, "", student.department.orEmpty()))) { repository.programs(it, department) }
    }

    fun choose(item: AcademicCatalogItem) {
        val current = mutable.value
        if (current.busy || item !in current.options) return
        val selection = current.selection
        when (current.page) {
            StudentPage.DEPARTMENT -> {
                val id = studentId ?: return
                write { auth ->
                    current.copy(page = StudentPage.PROFILE, options = emptyList(),
                        student = repository.department(auth, id, item.id), success = "Department saved.")
                }
            }
            StudentPage.PROGRAM -> choices(StudentPage.YEAR, selection.copy(program = item)) { repository.years(it) }
            StudentPage.YEAR -> choices(StudentPage.SEMESTER, selection.copy(year = item)) {
                repository.semesters(it, requireNotNull(selection.program).id, item.id)
            }
            StudentPage.SEMESTER -> choices(StudentPage.DIVISION, selection.copy(semester = item)) {
                repository.divisions(it, requireNotNull(selection.program).id, requireNotNull(selection.year).id)
            }
            StudentPage.DIVISION -> mutable.value = current.copy(page = StudentPage.REVIEW,
                selection = selection.copy(division = item), options = emptyList())
            else -> Unit
        }
    }

    fun save() {
        val current = mutable.value
        val id = studentId ?: return
        val input = current.selection.input()
        if (current.page != StudentPage.REVIEW || input == null) return
        write { auth ->
            val saved = repository.enroll(auth, id, input)
            current.copy(page = StudentPage.PROFILE, enrollments = listOf(saved) + current.enrollments,
                selection = EnrollmentSelection(), success = "Enrollment saved.")
        }
    }

    fun end(enrollment: StudentEnrollment) {
        val id = studentId ?: return
        val current = mutable.value
        if (!enrollment.current || enrollment !in current.enrollments) return
        write { auth ->
            repository.end(auth, id, enrollment.id)
            current.copy(enrollments = current.enrollments.map {
                if (it.id == enrollment.id) it.copy(current = false) else it
            }, success = "Enrollment ended. History kept.")
        }
    }

    fun back() {
        val id = studentId
        if (id == null || mutable.value.page == StudentPage.PROFILE) directory() else profile(id)
    }

    fun retry() { retryAction?.invoke() }

    private fun choices(page: StudentPage, selection: EnrollmentSelection,
                        fetch: suspend (String) -> List<AcademicCatalogItem>) {
        read(mutable.value.copy(page = page, selection = selection, options = emptyList(), success = null)) { draft, auth ->
            draft.copy(options = fetch(auth))
        }
    }

    private fun read(draft: StudentState, fetch: suspend (StudentState, String) -> StudentState) {
        retryAction = { read(draft, fetch) }
        run(draft, retryable = true) { fetch(draft, it) }
    }

    private fun write(operation: suspend (String) -> StudentState) {
        if (mutable.value.busy) return
        retryAction = null
        run(mutable.value, retryable = false, operation)
    }

    private fun run(previous: StudentState, retryable: Boolean, operation: suspend (String) -> StudentState) {
        running?.cancel()
        val pending = previous.copy(busy = true, error = null, success = null, canRetry = false)
        mutable.value = pending
        running = viewModelScope.launch {
            try {
                mutable.value = operation(authorization()).copy(busy = false, error = null, canRetry = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val status = (failure as? AcademicCatalogFailure)?.status
                if (status == 401 || status == 403 || failure is AuthFailure) {
                    retryAction = null
                    mutable.value = StudentState(pending.page, busy = false, error = "Your access could not be verified.")
                    onAccessLost(status ?: 403)
                } else {
                    val message = when {
                        status == 400 && pending.page == StudentPage.REVIEW ->
                            "Choose a matching department, program, year, semester and division."
                        status != null -> failure.message
                        else -> "CampusSphere could not complete the request. Refresh to check the current state."
                    }
                    mutable.value = pending.copy(busy = false, error = message, canRetry = retryable)
                }
            }
        }
    }
}

internal fun normalizedCollegeCode(raw: String): String? =
    raw.trim().takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,29}")) }?.uppercase(java.util.Locale.ROOT)
