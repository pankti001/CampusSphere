package com.campussphere.app.presentation.timetable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.TimetableFailure
import com.campussphere.app.data.repository.TimetableRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class AdminPage { SCOPE, VERSIONS, EDITOR, ENTRY, PREVIEW, HISTORY, CLASSROOMS }

data class TimetableAdminUi(
    val page: AdminPage = AdminPage.SCOPE,
    val scopes: List<TimetableScope> = emptyList(),
    val choice: ScopeChoice = ScopeChoice(),
    val versions: List<TimetableSummary> = emptyList(),
    val detail: TimetableDetail? = null,
    val options: TimetableOptions? = null,
    val day: Int = 1,
    val form: EntryForm = EntryForm(),
    val editingEntryId: Long? = null,
    val clashes: List<TimetableClash> = emptyList(),
    val publishProblems: List<TimetableProblem> = emptyList(),
    val history: List<TimetableEvent> = emptyList(),
    val classrooms: List<TimetableClassroom> = emptyList(),
    val roomForm: RoomForm = RoomForm(),
    val editingRoomId: Long? = null,
    val classroomsReturn: AdminPage = AdminPage.SCOPE,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val editable get() = detail?.timetable?.status == "DRAFT"
}

/** College Admin timetable management: choose a class, edit a draft, preview, publish, archive, history, classrooms. */
class TimetableAdminViewModel(
    private val repo: TimetableRepository,
    private val auth: () -> String,
    private val lost: (Int) -> Unit,
) : ViewModel() {
    private val state = MutableStateFlow(TimetableAdminUi())
    val ui: StateFlow<TimetableAdminUi> = state.asStateFlow()

    fun loadScopes() = action(loading = true) {
        val scopes = repo.scopes(auth())
        state.update { s ->
            // Preselect when there is only one option at the top level.
            val choice = if (s.choice.yearId == null && scopes.distinctBy { it.academicYearId }.size == 1)
                ScopeChoice(yearId = scopes.first().academicYearId) else s.choice
            s.copy(scopes = scopes, choice = choice, loaded = true)
        }
    }

    fun choose(level: Int, id: Long?) {
        state.update { it.copy(choice = chooseScope(it.choice, level, id), versions = emptyList()) }
        if (state.value.choice.complete) loadVersions()
    }

    fun loadVersions() {
        val c = state.value.choice
        val semester = c.semesterId ?: return
        val division = c.divisionId ?: return
        action(loading = true) {
            val versions = repo.versions(auth(), semester, division)
            state.update { it.copy(versions = versions, page = AdminPage.VERSIONS) }
        }
    }

    /** Starts a draft; when a published version exists the server copies it so the admin edits from there. */
    fun createDraft() {
        val c = state.value.choice
        val semester = c.semesterId ?: return
        val division = c.divisionId ?: return
        action {
            val detail = repo.create(auth(), semester, division)
            showDetail(detail, "Draft version ${detail.timetable.version} created.")
        }
    }

    /** From a published version: start the next draft version as a copy of it. */
    fun startRevision() {
        val t = state.value.detail?.timetable ?: return
        action {
            val detail = repo.create(auth(), t.semesterId, t.divisionId)
            showDetail(detail, "Draft version ${detail.timetable.version} created from version ${t.version}.")
        }
    }

    fun open(id: Long) = action { showDetail(repo.detail(auth(), id), null) }

    private suspend fun showDetail(detail: TimetableDetail, notice: String?) {
        val options = if (detail.timetable.status == "DRAFT") repo.options(auth(), detail.timetable.id) else null
        val firstDay = detail.entries.minOfOrNull { it.day } ?: 1
        state.update {
            it.copy(detail = detail, options = options, page = AdminPage.EDITOR, publishProblems = emptyList(),
                day = if (it.detail?.timetable?.id == detail.timetable.id) it.day else firstDay, notice = notice)
        }
    }

    fun setDay(day: Int) = state.update { it.copy(day = day) }

    fun startAdd(kind: String) = state.update {
        val entries = it.detail?.entries.orEmpty()
        it.copy(page = AdminPage.ENTRY, editingEntryId = null, form = suggestedForm(entries, it.day, kind),
            clashes = emptyList(), error = null, notice = null)
    }

    fun startEdit(entry: TimetableEntry) = state.update {
        it.copy(page = AdminPage.ENTRY, editingEntryId = entry.id, form = formFrom(entry), clashes = emptyList(),
            error = null, notice = null)
    }

    fun editForm(change: (EntryForm) -> EntryForm) = state.update { it.copy(form = change(it.form), clashes = emptyList()) }

    fun saveEntry() {
        val current = state.value
        val id = current.detail?.timetable?.id ?: return
        val input = entryInput(current.form).getOrElse { e -> state.update { it.copy(error = e.message) }; return }
        action {
            val detail = current.editingEntryId?.let { repo.updateEntry(auth(), id, it, input) }
                ?: repo.addEntry(auth(), id, input)
            state.update {
                it.copy(detail = detail, page = AdminPage.EDITOR, day = input.day, editingEntryId = null,
                    notice = "Period saved for ${dayName(input.day)}.")
            }
        }
    }

    fun deleteEntry(entry: TimetableEntry) {
        val id = state.value.detail?.timetable?.id ?: return
        action {
            val detail = repo.deleteEntry(auth(), id, entry.id)
            state.update { it.copy(detail = detail, page = AdminPage.EDITOR, editingEntryId = null, notice = "Period removed.") }
        }
    }

    fun preview() {
        val id = state.value.detail?.timetable?.id ?: return
        action {
            val detail = repo.preview(auth(), id)
            state.update { it.copy(detail = detail, page = AdminPage.PREVIEW, publishProblems = emptyList()) }
        }
    }

    /** Publishes exactly the revision that was previewed. */
    fun publish() {
        val detail = state.value.detail ?: return
        action {
            val published = repo.publish(auth(), detail.timetable.id, detail.timetable.revision)
            state.update {
                it.copy(detail = published, options = null, page = AdminPage.EDITOR,
                    notice = "Version ${published.timetable.version} is published. Students and faculty now see it.")
            }
        }
    }

    fun archive() {
        val detail = state.value.detail ?: return
        val wasDraft = detail.timetable.status == "DRAFT"
        action {
            val archived = repo.archive(auth(), detail.timetable.id)
            state.update {
                it.copy(detail = archived, options = null,
                    notice = if (wasDraft) "Draft discarded." else "Unpublished. Students and faculty no longer see this timetable.")
            }
        }
    }

    fun openHistory() {
        val id = state.value.detail?.timetable?.id ?: return
        action { state.update { it.copy(history = repo.history(auth(), id), page = AdminPage.HISTORY) } }
    }

    fun openClassrooms() {
        val from = state.value.page
        action {
            val rooms = repo.classrooms(auth())
            state.update { it.copy(classrooms = rooms, page = AdminPage.CLASSROOMS, classroomsReturn = from,
                roomForm = RoomForm(), editingRoomId = null) }
        }
    }

    fun startRoomEdit(room: TimetableClassroom?) = state.update {
        it.copy(editingRoomId = room?.id, roomForm = room?.let { r -> RoomForm(r.code, r.name, r.capacity?.toString().orEmpty(), r.active) } ?: RoomForm())
    }

    fun editRoom(change: (RoomForm) -> RoomForm) = state.update { it.copy(roomForm = change(it.roomForm)) }

    fun saveRoom() {
        val current = state.value
        val input = roomInput(current.roomForm).getOrElse { e -> state.update { it.copy(error = e.message) }; return }
        action {
            repo.saveClassroom(auth(), current.editingRoomId, input)
            val rooms = repo.classrooms(auth())
            val timetable = state.value.detail?.timetable
            val options = if (timetable?.status == "DRAFT") repo.options(auth(), timetable.id) else state.value.options
            state.update { it.copy(classrooms = rooms, options = options, roomForm = RoomForm(), editingRoomId = null,
                notice = "Classroom ${input.code} saved.") }
        }
    }

    fun clearMessages() = state.update { it.copy(error = null, notice = null) }

    /** Returns false when the admin should leave the timetable screen. */
    fun back(): Boolean {
        val s = state.value
        if (s.busy) return true
        when (s.page) {
            AdminPage.SCOPE -> return false
            AdminPage.VERSIONS -> state.update { it.copy(page = AdminPage.SCOPE) }
            AdminPage.EDITOR -> { state.update { it.copy(page = AdminPage.VERSIONS, detail = null, options = null) }; loadVersions() }
            AdminPage.ENTRY, AdminPage.PREVIEW, AdminPage.HISTORY ->
                state.update { it.copy(page = AdminPage.EDITOR, clashes = emptyList(), publishProblems = emptyList()) }
            AdminPage.CLASSROOMS -> state.update { it.copy(page = it.classroomsReturn) }
        }
        state.update { it.copy(error = null) }
        return true
    }

    private fun action(loading: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy || state.value.loading) return
        state.update { it.copy(busy = !loading, loading = loading, error = null, notice = if (loading) it.notice else null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                state.update { it.copy(busy = false, loading = false) }
            }
        }
    }

    private fun fail(e: Exception) {
        val failure = e as? TimetableFailure
        if (failure != null && (failure.status == 401 || failure.status == 403)) {
            state.update { TimetableAdminUi(error = failure.message) }
            lost(failure.status)
            return
        }
        if (failure?.status == 404) {
            state.update { it.copy(page = if (it.choice.complete) AdminPage.VERSIONS else AdminPage.SCOPE, detail = null, options = null) }
        }
        state.update {
            it.copy(error = failure?.message ?: "Request failed. Check your connection and try again.",
                clashes = failure?.clashes.orEmpty(),
                publishProblems = failure?.problems.orEmpty())
        }
    }
}

// ---- Faculty and student ----

enum class MyTab { TODAY, WEEK }

data class MyTimetableUi(
    val tab: MyTab = MyTab.TODAY,
    val week: TimetableSchedule? = null,
    val today: TimetableDay? = null,
    val day: Int = 1,
    val selected: TimetableSlot? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

/** A faculty member's teaching timetable or a student's class timetable; read only. area is "faculty" or "student". */
class MyTimetableViewModel(
    private val repo: TimetableRepository,
    val area: String,
    private val auth: () -> String,
    private val lost: (Int) -> Unit,
    private val today: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {
    private val state = MutableStateFlow(MyTimetableUi(day = today().dayOfWeek.value))
    val ui: StateFlow<MyTimetableUi> = state.asStateFlow()

    fun load() {
        if (state.value.loading) return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val date = today()
                val week = repo.week(auth(), area)
                val day = repo.day(auth(), area, date.toString())
                state.update { it.copy(week = week, today = day, loaded = true,
                    selected = it.selected?.let { s -> week.slots.firstOrNull { slot -> slot.id == s.id } }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? TimetableFailure
                if (failure != null && (failure.status == 401 || failure.status == 403)) lost(failure.status)
                state.update { it.copy(error = failure?.message ?: "Request failed. Check your connection and try again.") }
            } finally {
                state.update { it.copy(loading = false) }
            }
        }
    }

    fun setTab(tab: MyTab) = state.update { it.copy(tab = tab) }
    fun setDay(day: Int) = state.update { it.copy(day = day) }
    fun select(slot: TimetableSlot?) = state.update { it.copy(selected = slot) }
}
