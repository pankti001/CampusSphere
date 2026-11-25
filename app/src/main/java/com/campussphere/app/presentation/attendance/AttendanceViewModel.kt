package com.campussphere.app.presentation.attendance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.AttendanceFailure
import com.campussphere.app.data.repository.AttendanceRepository
import com.campussphere.app.data.repository.AuthFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

enum class AttendanceMode { FACULTY, STUDENT, ADMIN }
internal enum class AttendancePage { HOME, DETAIL, POLICY, ALERTS, AUDIT }

internal data class AttendanceUi(
    val page: AttendancePage = AttendancePage.HOME,
    val busy: Boolean = false,
    val assignments: List<AttendanceAssignment> = emptyList(),
    val sessions: List<AttendanceSession> = emptyList(),
    val detail: AttendanceDetail? = null,
    val marks: Map<Long, AttendanceMark> = emptyMap(),
    val student: AttendanceStudent? = null,
    val subjectOptions: List<AttendanceOption> = emptyList(),
    val report: AttendanceReport? = null,
    val options: Map<String, List<AttendanceOption>> = emptyMap(),
    val alerts: List<AttendanceAlert> = emptyList(),
    val corrections: List<AttendanceCorrection> = emptyList(),
    val policy: AttendancePolicy? = null,
    val number: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    val success: String? = null
)

internal class AttendanceViewModel(
    private val repository: AttendanceRepository,
    private val authorization: () -> String,
    private val onAccessLost: (Int) -> Unit,
    val mode: AttendanceMode
) : ViewModel() {
    private val mutable = MutableStateFlow(AttendanceUi())
    val state = mutable.asStateFlow()
    private var lastRead: (() -> Unit)? = null
    private var homeFilters = emptyMap<String, String>()
    private var homeNumber = 0

    init { home() }

    fun home(filters: Map<String, String> = homeFilters, page: Int = homeNumber) {
        if (page !in 0..1000) return
        homeFilters = filters
        homeNumber = page
        read(AttendancePage.HOME, retry = { home(filters, page) }) { auth ->
            when (mode) {
                AttendanceMode.FACULTY -> {
                    val sessions = repository.sessions(auth, page)
                    AttendanceUi(assignments = repository.assignments(auth), sessions = sessions, number = page,
                        hasMore = sessions.size == PAGE_SIZE)
                }
                AttendanceMode.STUDENT -> {
                    val student = repository.student(auth, filters + ("page" to page.toString()))
                    AttendanceUi(student = student, subjectOptions = repository.studentSubjects(auth), number = page,
                        hasMore = student.history.size == PAGE_SIZE)
                }
                AttendanceMode.ADMIN -> {
                    val report = repository.report(auth, filters + ("page" to page.toString()))
                    AttendanceUi(report = report, options = repository.options(auth).filters, number = page,
                        hasMore = report.students.size == PAGE_SIZE || report.sessions.size == PAGE_SIZE)
                }
            }
        }
    }

    fun detail(id: Long): Unit = read(AttendancePage.DETAIL, retry = { detail(id) }) { auth ->
        withDetail(repository.session(auth, id, mode == AttendanceMode.ADMIN))
    }

    fun create(assignment: Long, date: String, lecture: String, start: String, end: String): Boolean {
        val input = attendanceCreate(assignment, date, lecture, start, end)
        if (input == null) {
            error("Enter a valid date, a lecture number from 1 to 99, and an end time after the start.")
            return false
        }
        mutate { auth -> withDetail(repository.create(auth, input), "Session draft opened.") }
        return true
    }

    fun mark(student: Long, status: AttendanceMark) {
        val current = mutable.value
        if (!current.busy && current.detail?.session?.status == AttendanceSessionState.DRAFT) {
            mutable.value = current.copy(marks = current.marks + (student to status), error = null, success = null)
        }
    }

    fun markAllPresent() {
        val current = mutable.value
        val detail = current.detail ?: return
        if (current.busy || detail.session.status != AttendanceSessionState.DRAFT) return
        mutable.value = current.copy(marks = detail.roster.associate { it.studentId to AttendanceMark.PRESENT },
            error = null, success = null)
    }

    fun save(submit: Boolean) {
        val current = mutable.value
        val detail = current.detail ?: return
        if (submit && (detail.roster.isEmpty() || detail.roster.any { it.studentId !in current.marks })) {
            error("Mark each enrolled student before submitting.")
            return
        }
        val input = AttendanceMarks(detail.session.revision, current.marks.map { AttendanceMarkInput(it.key, it.value) })
        mutate { auth -> withDetail(repository.save(auth, detail.session.id, input, submit),
            if (submit) "Attendance submitted." else "Draft saved.") }
    }

    fun correct(student: Long, status: AttendanceMark, reason: String) {
        val session = mutable.value.detail?.session ?: return
        val input = AttendanceCorrectionInput(session.revision, status, reason.trim())
        mutate { auth -> withDetail(repository.correct(auth, session.id, student, input, mode == AttendanceMode.ADMIN),
            "Correction recorded in the audit history.") }
    }

    fun lock() {
        val session = mutable.value.detail?.session ?: return
        mutate { auth -> withDetail(repository.lock(auth, session.id, session.revision), "Session locked.") }
    }

    fun audit(id: Long, page: Int = 0): Unit = read(AttendancePage.AUDIT, retry = { audit(id, page) }) { auth ->
        val detail = repository.session(auth, id, admin = true)
        val entries = repository.audit(auth, id, page)
        AttendanceUi(page = AttendancePage.AUDIT, detail = detail, corrections = entries, number = page,
            hasMore = entries.size == PAGE_SIZE)
    }

    fun alerts(page: Int = 0): Unit = read(AttendancePage.ALERTS, retry = { alerts(page) }) { auth ->
        val alerts = repository.alerts(auth, page)
        AttendanceUi(page = AttendancePage.ALERTS, alerts = alerts, number = page, hasMore = alerts.size == PAGE_SIZE)
    }

    fun policy() = read(AttendancePage.POLICY, retry = ::policy) { auth ->
        AttendanceUi(page = AttendancePage.POLICY, policy = repository.policy(auth))
    }

    fun updatePolicy(input: AttendancePolicy) = mutate { auth ->
        AttendanceUi(page = AttendancePage.POLICY, policy = repository.policy(auth, input), success = "Policy saved.")
    }

    fun refresh() { if (!mutable.value.busy) lastRead?.invoke() }

    fun back(): Boolean {
        val current = mutable.value
        if (current.busy) return true
        if (current.page == AttendancePage.HOME) return false
        val session = current.detail?.session
        if (current.page == AttendancePage.AUDIT && session != null) detail(session.id) else home()
        return true
    }

    private fun withDetail(detail: AttendanceDetail, success: String? = null): AttendanceUi {
        lastRead = { detail(detail.session.id) }
        return AttendanceUi(page = AttendancePage.DETAIL, detail = detail,
            marks = detail.roster.mapNotNull { row -> row.status?.let { row.studentId to it } }.toMap(), success = success)
    }

    private fun error(message: String) {
        mutable.value = mutable.value.copy(error = message, success = null)
    }

    private fun read(page: AttendancePage, retry: () -> Unit, fetch: suspend (String) -> AttendanceUi) {
        if (mutable.value.busy) return
        lastRead = retry
        request(AttendanceUi(page = page), fetch)
    }

    private fun mutate(write: suspend (String) -> AttendanceUi) {
        if (!mutable.value.busy) request(mutable.value, write)
    }

    private fun request(initial: AttendanceUi, work: suspend (String) -> AttendanceUi) {
        val pending = initial.copy(busy = true, error = null, success = null)
        mutable.value = pending
        viewModelScope.launch {
            try {
                mutable.value = work(authorization())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val status = (failure as? AttendanceFailure)?.status
                val message = when (failure) {
                    is AttendanceFailure, is AuthFailure -> failure.message
                    else -> "CampusSphere is unreachable. Refresh to check the current state."
                }
                if (status == 401 || status == 403 || failure is AuthFailure) {
                    mutable.value = AttendanceUi(page = initial.page, error = message)
                    onAccessLost(status ?: 403)
                } else {
                    mutable.value = pending.copy(busy = false, error = message)
                }
            }
        }
    }

    companion object { private const val PAGE_SIZE = 50 }
}

internal fun attendanceCreate(assignment: Long, date: String, lecture: String, start: String, end: String): AttendanceCreate? {
    val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull() ?: return null
    val number = lecture.trim().toIntOrNull()?.takeIf { it in 1..99 } ?: return null
    val startTime = if (start.isBlank()) null else runCatching { LocalTime.parse(start.trim()) }.getOrNull() ?: return null
    val endTime = if (end.isBlank()) null else runCatching { LocalTime.parse(end.trim()) }.getOrNull() ?: return null
    if (endTime != null && (startTime == null || endTime <= startTime)) return null
    return AttendanceCreate(assignment, day.toString(), number, startTime?.toString(), endTime?.toString())
}
