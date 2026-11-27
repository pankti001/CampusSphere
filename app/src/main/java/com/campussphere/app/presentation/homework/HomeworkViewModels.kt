package com.campussphere.app.presentation.homework

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.HomeworkFailure
import com.campussphere.app.data.repository.HomeworkRepository
import com.campussphere.app.data.repository.MaterialUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

const val HOMEWORK_PAGE_SIZE = 30

/** Shared error handling: an expired or changed session (401/403) is reported so the app can refresh it. */
abstract class HomeworkBaseViewModel(protected val repo: HomeworkRepository, protected val auth: () -> String,
                                     private val lost: (Int) -> Unit) : ViewModel() {
    protected fun message(e: Exception): String {
        val failure = e as? HomeworkFailure
        if (failure != null && (failure.status == 401 || failure.status == 403)) lost(failure.status)
        return failure?.message ?: "Something went wrong. Try again."
    }

    /** Runs [block], turning failures into a message for [onError]. Cancellation is never swallowed. */
    protected fun launchSafely(onError: (String) -> Unit, done: () -> Unit = {}, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(message(e))
            } finally {
                done()
            }
        }
    }
}

// ---------------------------------------------------------------- Faculty

enum class FacultyHomeworkPage { LIST, FORM, DETAIL, PREVIEW, SUBMISSIONS, REVIEW, HISTORY }

data class FacultyHomeworkUi(
    val page: FacultyHomeworkPage = FacultyHomeworkPage.LIST,
    val loading: Boolean = false, val busy: Boolean = false, val progress: Float? = null,
    val error: String? = null, val notice: String? = null, val loaded: Boolean = false,
    val items: List<HomeworkItem> = emptyList(), val listPage: Int = 0, val hasMore: Boolean = false,
    val classes: List<HomeworkClass> = emptyList(),
    val statusFilter: String? = null, val classFilter: Long? = null, val query: String = "",
    val form: HomeworkForm? = null, val formError: String? = null,
    val selected: HomeworkItem? = null, val history: List<HomeworkEvent> = emptyList(),
    val roster: List<HomeworkRosterRow> = emptyList(), val rosterFilter: String = "ALL", val rosterPage: Int = 0,
    val rosterHasMore: Boolean = false,
    val review: HomeworkReview? = null, val marks: String = "", val feedback: String = "", val returnNote: String = "",
)

class FacultyHomeworkViewModel(repo: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit,
                               private val zone: ZoneId = ZoneId.systemDefault(),
                               private val clock: () -> ZonedDateTime = { ZonedDateTime.now(zone) })
    : HomeworkBaseViewModel(repo, auth, lost) {
    private val state = MutableStateFlow(FacultyHomeworkUi())
    val ui: StateFlow<FacultyHomeworkUi> = state.asStateFlow()

    private fun fail(text: String) = state.update { it.copy(error = text, notice = null) }
    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, notice = null) }
        launchSafely(::fail, { state.update { it.copy(busy = false, progress = null) } }, block)
    }

    fun clearMessages() = state.update { it.copy(error = null, notice = null) }

    fun load() {
        if (state.value.loading) return
        state.update { it.copy(loading = true, error = null) }
        launchSafely(::fail, { state.update { it.copy(loading = false) } }) {
            val classes = repo.classes(auth())
            state.update { it.copy(classes = classes) }
            fetch(0)
            state.update { it.copy(loaded = true) }
        }
    }

    private suspend fun fetch(page: Int) {
        val s = state.value
        val cls = s.classes.firstOrNull { it.classId == s.classFilter }
        val rows = repo.facultyList(auth(), cls?.subjectId, cls?.divisionId, s.statusFilter, s.query, page)
        state.update {
            it.copy(items = if (page == 0) rows else it.items + rows, listPage = page,
                hasMore = rows.size >= HOMEWORK_PAGE_SIZE)
        }
    }

    fun search() = work { fetch(0) }
    fun loadMore() = work { fetch(state.value.listPage + 1) }
    fun setQuery(text: String) = state.update { it.copy(query = text) }
    fun setStatusFilter(status: String?) { state.update { it.copy(statusFilter = status) }; search() }
    fun setClassFilter(classId: Long?) { state.update { it.copy(classFilter = classId) }; search() }

    fun newForm() = state.update {
        it.copy(page = FacultyHomeworkPage.FORM, formError = null, error = null, notice = null,
            form = HomeworkForm(classId = it.classes.singleOrNull()?.classId))
    }

    fun editForm() {
        val item = state.value.selected ?: return
        state.update { it.copy(page = FacultyHomeworkPage.FORM, formError = null, form = homeworkFormOf(item, zone)) }
    }

    fun updateForm(change: (HomeworkForm) -> HomeworkForm) =
        state.update { s -> s.copy(form = s.form?.let(change), formError = null) }

    fun save() {
        val form = state.value.form ?: return
        homeworkFormProblem(form, clock())?.let { problem -> state.update { it.copy(formError = problem) }; return }
        work {
            val saved = repo.save(auth(), form.id, form.toInput(zone))
            state.update {
                it.copy(selected = saved, form = null, page = FacultyHomeworkPage.DETAIL,
                    notice = if (form.id == null) "Saved as a draft. Preview it, then publish when ready." else "Changes saved.")
            }
            fetch(0)
        }
    }

    fun open(item: HomeworkItem) = work {
        val fresh = repo.detail(auth(), "faculty", item.id)
        state.update { it.copy(selected = fresh, page = FacultyHomeworkPage.DETAIL, review = null) }
    }

    fun preview() = state.update { it.copy(page = FacultyHomeworkPage.PREVIEW, error = null, notice = null) }

    /** action is "publish", "close" or "archive". */
    fun lifecycle(action: String) {
        val item = state.value.selected ?: return
        work {
            val updated = repo.lifecycle(auth(), item.id, action)
            state.update {
                it.copy(selected = updated, page = FacultyHomeworkPage.DETAIL, notice = when (action) {
                    "publish" -> "Published. Students in ${updated.division} have been notified."
                    "close" -> "Closed. No new submissions will be accepted."
                    else -> "Archived."
                })
            }
            fetch(0)
        }
    }

    fun attach(file: MaterialUpload) {
        val item = state.value.selected ?: return
        work {
            val updated = repo.attach(auth(), item.id, file) { p -> state.update { it.copy(progress = p) } }
            state.update { it.copy(selected = updated, notice = "Attached ${file.fileName}.") }
        }
    }

    fun detach() {
        val item = state.value.selected ?: return
        work {
            val updated = repo.detach(auth(), item.id)
            state.update { it.copy(selected = updated, notice = "Attachment removed.") }
        }
    }

    fun history() {
        val item = state.value.selected ?: return
        work {
            val events = repo.history(auth(), "faculty", item.id)
            state.update { it.copy(history = events, page = FacultyHomeworkPage.HISTORY) }
        }
    }

    fun submissions(filter: String = state.value.rosterFilter) {
        val item = state.value.selected ?: return
        work {
            val rows = repo.roster(auth(), item.id, filter, 0)
            state.update {
                it.copy(roster = rows, rosterFilter = filter, rosterPage = 0, rosterHasMore = rows.size >= HOMEWORK_PAGE_SIZE,
                    page = FacultyHomeworkPage.SUBMISSIONS)
            }
        }
    }

    fun moreSubmissions() {
        val s = state.value
        val item = s.selected ?: return
        work {
            val rows = repo.roster(auth(), item.id, s.rosterFilter, s.rosterPage + 1)
            state.update {
                it.copy(roster = it.roster + rows, rosterPage = s.rosterPage + 1, rosterHasMore = rows.size >= HOMEWORK_PAGE_SIZE)
            }
        }
    }

    fun openReview(row: HomeworkRosterRow) {
        val item = state.value.selected ?: return
        val submission = row.submissionId ?: return
        work {
            val review = repo.review(auth(), item.id, submission)
            state.update {
                it.copy(review = review, page = FacultyHomeworkPage.REVIEW,
                    marks = review.submission.marks?.let(::homeworkMarks).orEmpty(),
                    feedback = review.submission.feedback.orEmpty(), returnNote = "")
            }
        }
    }

    fun setMarks(text: String) = state.update { it.copy(marks = text) }
    fun setFeedback(text: String) = state.update { it.copy(feedback = text) }
    fun setReturnNote(text: String) = state.update { it.copy(returnNote = text) }

    fun evaluate() {
        val s = state.value
        val item = s.selected ?: return
        val review = s.review ?: return
        homeworkMarksProblem(s.marks, review.maxMarks)?.let { fail(it); return }
        if (s.feedback.trim().length > 4000) { fail("Feedback is limited to 4000 characters."); return }
        work {
            repo.evaluate(auth(), item.id, review.submission.id, s.marks.trim().toDouble(), s.feedback)
            val fresh = repo.review(auth(), item.id, review.submission.id)
            val detail = repo.detail(auth(), "faculty", item.id)
            state.update { it.copy(review = fresh, selected = detail, notice = "Marks saved. The student has been notified.") }
        }
    }

    fun returnWork() {
        val s = state.value
        val item = s.selected ?: return
        val review = s.review ?: return
        val note = s.returnNote.trim()
        if (note.length < 3) { fail("Tell the student what to correct (at least 3 characters)."); return }
        work {
            repo.returnWork(auth(), item.id, review.submission.id, note)
            val fresh = repo.review(auth(), item.id, review.submission.id)
            state.update { it.copy(review = fresh, returnNote = "", notice = "Returned for correction. The student has been notified.") }
        }
    }

    /** Runs a file download chosen in the screen. */
    fun download(notice: String, block: suspend () -> Unit) = work {
        block()
        state.update { it.copy(notice = notice) }
    }

    /** Back one step; returns false when the list itself should close. */
    fun back(): Boolean {
        val s = state.value
        val next = when (s.page) {
            FacultyHomeworkPage.LIST -> return false
            FacultyHomeworkPage.FORM -> if (s.form?.id != null) FacultyHomeworkPage.DETAIL else FacultyHomeworkPage.LIST
            FacultyHomeworkPage.DETAIL -> FacultyHomeworkPage.LIST
            FacultyHomeworkPage.PREVIEW, FacultyHomeworkPage.SUBMISSIONS, FacultyHomeworkPage.HISTORY -> FacultyHomeworkPage.DETAIL
            FacultyHomeworkPage.REVIEW -> FacultyHomeworkPage.SUBMISSIONS
        }
        state.update { it.copy(page = next, form = if (next == FacultyHomeworkPage.FORM) it.form else null, error = null, notice = null) }
        if (next == FacultyHomeworkPage.SUBMISSIONS) submissions()
        return true
    }
}

// ---------------------------------------------------------------- Students

enum class StudentHomeworkPage { LIST, DETAIL, NOTICES }

val STUDENT_TABS = listOf("PENDING" to "Pending", "SUBMITTED" to "Submitted", "LATE" to "Late",
    "EVALUATED" to "Results", "ALL" to "All")

data class StudentHomeworkUi(
    val page: StudentHomeworkPage = StudentHomeworkPage.LIST,
    val loading: Boolean = false, val busy: Boolean = false, val progress: Float? = null,
    val error: String? = null, val notice: String? = null, val loaded: Boolean = false,
    val tab: String = "PENDING", val items: List<HomeworkItem> = emptyList(), val listPage: Int = 0,
    val hasMore: Boolean = false, val summary: StudentHomeworkSummary? = null,
    val view: HomeworkStudentView? = null, val file: MaterialUpload? = null, val comment: String = "",
    val notices: List<HomeworkNotice> = emptyList(), val noticePage: Int = 0, val noticesHasMore: Boolean = false,
)

class StudentHomeworkViewModel(repo: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit, initialTab: String = "PENDING")
    : HomeworkBaseViewModel(repo, auth, lost) {
    private val state = MutableStateFlow(StudentHomeworkUi(tab = initialTab))
    val ui: StateFlow<StudentHomeworkUi> = state.asStateFlow()

    private fun fail(text: String) = state.update { it.copy(error = text, notice = null) }
    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, notice = null) }
        launchSafely(::fail, { state.update { it.copy(busy = false, progress = null) } }, block)
    }

    fun clearMessages() = state.update { it.copy(error = null, notice = null) }

    fun load() {
        if (state.value.loading) return
        state.update { it.copy(loading = true, error = null) }
        launchSafely(::fail, { state.update { it.copy(loading = false) } }) {
            val summary = repo.studentSummary(auth())
            state.update { it.copy(summary = summary) }
            fetch(0)
            state.update { it.copy(loaded = true) }
        }
    }

    private suspend fun fetch(page: Int) {
        val rows = repo.studentList(auth(), state.value.tab, page)
        state.update {
            it.copy(items = if (page == 0) rows else it.items + rows, listPage = page, hasMore = rows.size >= HOMEWORK_PAGE_SIZE)
        }
    }

    fun setTab(tab: String) { state.update { it.copy(tab = tab, items = emptyList()) }; work { fetch(0) } }
    fun loadMore() = work { fetch(state.value.listPage + 1) }

    fun open(id: Long) = work {
        val view = repo.studentDetail(auth(), id)
        state.update { it.copy(view = view, page = StudentHomeworkPage.DETAIL, file = null, comment = "") }
    }

    fun chooseFile(file: MaterialUpload) = state.update { it.copy(file = file, error = null) }
    fun setComment(text: String) = state.update { it.copy(comment = text.take(1000)) }

    fun submit() {
        val s = state.value
        val view = s.view ?: return
        val file = s.file ?: run { fail("Choose your completed work first."); return }
        work {
            val saved = repo.submit(auth(), view.assignment.id, file, s.comment) { p -> state.update { it.copy(progress = p) } }
            val fresh = repo.studentDetail(auth(), view.assignment.id)
            state.update {
                it.copy(view = fresh, file = null, comment = "", notice = "Submitted ${saved.fileName} (attempt ${saved.attempt})" +
                    if (saved.late) ", marked late." else ".")
            }
            state.update { it.copy(summary = repo.studentSummary(auth())) }
            fetch(0)
        }
    }

    fun notices() = work {
        val rows = repo.notices(auth(), false, 0)
        state.update {
            it.copy(notices = rows, noticePage = 0, noticesHasMore = rows.size >= HOMEWORK_PAGE_SIZE, page = StudentHomeworkPage.NOTICES)
        }
    }

    fun moreNotices() = work {
        val next = state.value.noticePage + 1
        val rows = repo.notices(auth(), false, next)
        state.update { it.copy(notices = it.notices + rows, noticePage = next, noticesHasMore = rows.size >= HOMEWORK_PAGE_SIZE) }
    }

    fun openNotice(notice: HomeworkNotice) = work {
        if (notice.readAt == null) repo.markRead(auth(), notice.id)
        val view = repo.studentDetail(auth(), notice.assignmentId)
        state.update {
            it.copy(view = view, page = StudentHomeworkPage.DETAIL, file = null, comment = "",
                summary = it.summary?.copy(unreadNotices = maxOf(0L, it.summary.unreadNotices - if (notice.readAt == null) 1 else 0)))
        }
    }

    fun download(notice: String, block: suspend () -> Unit) = work {
        block()
        state.update { it.copy(notice = notice) }
    }

    fun back(): Boolean {
        if (state.value.page == StudentHomeworkPage.LIST) return false
        state.update { it.copy(page = StudentHomeworkPage.LIST, view = null, file = null, error = null, notice = null) }
        load()
        return true
    }
}

// ---------------------------------------------------------------- College Admin

/** Filters chosen on the College Admin screen. Dates are YYYY-MM-DD and apply to deadlines. */
data class AdminHomeworkChoice(val programId: Long? = null, val academicYearId: Long? = null, val semesterId: Long? = null,
                               val divisionId: Long? = null, val subjectId: Long? = null, val facultyId: Long? = null,
                               val from: String = "", val to: String = "", val status: String? = null, val query: String = "")

private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

fun adminChoiceProblem(choice: AdminHomeworkChoice): String? {
    val from = choice.from.trim()
    val to = choice.to.trim()
    if (from.isNotEmpty() && (!ISO_DATE.matches(from) || runCatching { java.time.LocalDate.parse(from) }.isFailure)) return "Use YYYY-MM-DD for the start date."
    if (to.isNotEmpty() && (!ISO_DATE.matches(to) || runCatching { java.time.LocalDate.parse(to) }.isFailure)) return "Use YYYY-MM-DD for the end date."
    if (from.isNotEmpty() && to.isNotEmpty() && java.time.LocalDate.parse(to).isBefore(java.time.LocalDate.parse(from))) {
        return "The end date must not be before the start date."
    }
    return null
}

/** Query parameters for the server; only chosen values are sent. */
fun AdminHomeworkChoice.query(page: Int = 0): Map<String, String> = buildMap {
    programId?.let { put("programId", it.toString()) }
    academicYearId?.let { put("academicYearId", it.toString()) }
    semesterId?.let { put("semesterId", it.toString()) }
    divisionId?.let { put("divisionId", it.toString()) }
    subjectId?.let { put("subjectId", it.toString()) }
    facultyId?.let { put("facultyId", it.toString()) }
    from.trim().takeIf { it.isNotEmpty() }?.let { put("from", it) }
    to.trim().takeIf { it.isNotEmpty() }?.let { put("to", it) }
    status?.let { put("status", it) }
    query.trim().takeIf { it.isNotEmpty() }?.let { put("q", it) }
    if (page > 0) put("page", page.toString())
}

data class AdminHomeworkUi(
    val loading: Boolean = false, val error: String? = null, val loaded: Boolean = false,
    val filters: HomeworkFilters? = null, val choice: AdminHomeworkChoice = AdminHomeworkChoice(),
    val stats: HomeworkAdminStats? = null, val items: List<HomeworkItem> = emptyList(), val listPage: Int = 0,
    val hasMore: Boolean = false, val selected: HomeworkItem? = null, val history: List<HomeworkEvent> = emptyList(),
)

class AdminHomeworkViewModel(repo: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit)
    : HomeworkBaseViewModel(repo, auth, lost) {
    private val state = MutableStateFlow(AdminHomeworkUi())
    val ui: StateFlow<AdminHomeworkUi> = state.asStateFlow()

    private fun work(block: suspend () -> Unit) {
        if (state.value.loading) return
        state.update { it.copy(loading = true, error = null) }
        launchSafely({ text -> state.update { it.copy(error = text) } }, { state.update { it.copy(loading = false) } }, block)
    }

    fun load() = work {
        val filters = repo.adminFilters(auth())
        state.update { it.copy(filters = filters) }
        fetch(0)
        state.update { it.copy(loaded = true) }
    }

    private suspend fun fetch(page: Int) {
        val choice = state.value.choice
        if (page == 0) {
            // Numbers are dropped first so old statistics are never shown for new filters.
            state.update { it.copy(stats = null) }
            val stats = repo.adminStats(auth(), choice.query())
            state.update { it.copy(stats = stats) }
        }
        val rows = repo.adminList(auth(), choice.query(page))
        state.update { it.copy(items = if (page == 0) rows else it.items + rows, listPage = page, hasMore = rows.size >= HOMEWORK_PAGE_SIZE) }
    }

    fun choose(change: (AdminHomeworkChoice) -> AdminHomeworkChoice) = state.update { it.copy(choice = change(it.choice)) }

    fun apply() {
        adminChoiceProblem(state.value.choice)?.let { problem -> state.update { it.copy(error = problem) }; return }
        work { fetch(0) }
    }

    fun clear() { state.update { it.copy(choice = AdminHomeworkChoice()) }; apply() }
    fun loadMore() = work { fetch(state.value.listPage + 1) }

    fun open(item: HomeworkItem) = work {
        val detail = repo.detail(auth(), "college", item.id)
        val history = repo.history(auth(), "college", item.id)
        state.update { it.copy(selected = detail, history = history) }
    }

    fun back(): Boolean {
        if (state.value.selected == null) return false
        state.update { it.copy(selected = null, history = emptyList(), error = null) }
        return true
    }
}
