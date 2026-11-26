package com.campussphere.app.presentation.announcements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.AnnouncementFailure
import com.campussphere.app.data.repository.AnnouncementRepository
import com.campussphere.app.data.repository.MaterialUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

val ANNOUNCEMENT_CATEGORIES = listOf("GENERAL" to "General", "ACADEMIC" to "Academic", "EXAMINATION" to "Examination",
    "EVENTS" to "Events", "HOLIDAYS" to "Holidays", "FEES" to "Fees", "IMPORTANT_NOTICE" to "Important notice",
    "EMERGENCY" to "Emergency")
val ANNOUNCEMENT_PRIORITIES = listOf("LOW" to "Low", "NORMAL" to "Normal", "HIGH" to "High", "URGENT" to "Urgent")
val ANNOUNCEMENT_AUDIENCES = listOf("EVERYONE" to "Everyone", "STUDENTS" to "Students only", "FACULTY" to "Faculty only")
val ANNOUNCEMENT_SCOPES = listOf("COLLEGE" to "Entire college", "DEPARTMENT" to "Department", "PROGRAM" to "Program",
    "ACADEMIC_YEAR" to "Academic year", "SEMESTER" to "Semester", "DIVISION" to "Division")
val ANNOUNCEMENT_STATUSES = listOf("DRAFT" to "Draft", "SCHEDULED" to "Scheduled", "PUBLISHED" to "Published",
    "EXPIRED" to "Expired", "ARCHIVED" to "Archived")

fun label(options: List<Pair<String, String>>, key: String?) = options.firstOrNull { it.first == key }?.second ?: key.orEmpty()

private val INPUT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** "2026-12-01 09:30" in the device time zone → ISO instant with offset; blank → null; invalid → error. */
fun scheduleInstant(text: String, zone: ZoneId = ZoneId.systemDefault()): Result<String?> {
    val value = text.trim()
    if (value.isEmpty()) return Result.success(null)
    return try {
        Result.success(LocalDateTime.parse(value, INPUT_TIME).atZone(zone).toOffsetDateTime().toString())
    } catch (_: DateTimeParseException) {
        Result.failure(IllegalArgumentException("Use the format YYYY-MM-DD HH:MM, for example 2026-12-01 09:30."))
    }
}

/** Form state for creating or editing. Faculty always address the students of one division they teach. */
data class AnnouncementForm(
    val title: String = "", val body: String = "", val category: String = "GENERAL", val priority: String = "NORMAL",
    val audience: String = "EVERYONE", val scope: String = "COLLEGE", val targetId: Long? = null,
    val expires: String = "")

/** Returns a problem to show, or the request to send. */
fun announcementInput(form: AnnouncementForm, faculty: Boolean, zone: ZoneId = ZoneId.systemDefault()): Result<AnnouncementInput> {
    val audience = if (faculty) "STUDENTS" else form.audience
    val scope = if (faculty) "DIVISION" else form.scope
    val problem = when {
        form.title.isBlank() -> "Enter a title."
        form.title.trim().length > 200 -> "The title can be at most 200 characters."
        form.body.isBlank() -> "Enter the announcement text."
        form.body.trim().length > 10_000 -> "The text can be at most 10,000 characters."
        scope != "COLLEGE" && form.targetId == null -> "Choose who should receive it."
        else -> null
    }
    if (problem != null) return Result.failure(IllegalArgumentException(problem))
    val expires = scheduleInstant(form.expires, zone).getOrElse { return Result.failure(it) }
    return Result.success(AnnouncementInput(form.title.trim(), form.body.trim(), form.category, form.priority, audience,
        scope, if (scope == "COLLEGE") null else form.targetId, expires))
}

fun formFrom(item: AnnouncementItem, zone: ZoneId = ZoneId.systemDefault()) = AnnouncementForm(
    item.title, item.body, item.category, item.priority, item.audience, item.scope, item.targetId,
    item.expiresAt?.let { runCatching {
        java.time.OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDateTime().format(INPUT_TIME)
    }.getOrNull() }.orEmpty())

enum class AnnouncementPage { LIST, DETAIL, FORM, PREVIEW }

data class AnnouncementFilter(val q: String = "", val category: String? = null, val priority: String? = null,
                              val status: String? = null, val unread: Boolean = false, val mine: Boolean = false)

data class AnnouncementUi(
    val page: AnnouncementPage = AnnouncementPage.LIST,
    val items: List<AnnouncementItem> = emptyList(),
    val targets: List<AnnouncementTarget> = emptyList(),
    val overview: AnnouncementOverview? = null,
    val selected: AnnouncementItem? = null,
    val stats: AnnouncementStats? = null,
    val filter: AnnouncementFilter = AnnouncementFilter(),
    val form: AnnouncementForm = AnnouncementForm(),
    val editingId: Long? = null,
    val file: MaterialUpload? = null,
    val uploadProgress: Float? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class AnnouncementViewModel(
    private val repo: AnnouncementRepository,
    val area: String,
    private val auth: () -> String,
    private val lost: (Int) -> Unit,
) : ViewModel() {
    private val state = MutableStateFlow(AnnouncementUi())
    val ui: StateFlow<AnnouncementUi> = state.asStateFlow()
    val canWrite get() = area != "student"

    fun load() {
        if (state.value.loading) return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val f = state.value.filter
                val items = if (area == "faculty" && f.mine) repo.mine(auth(), f.status)
                    else repo.list(auth(), area, f.category, f.priority, f.status.takeIf { area == "college" }, f.q,
                        f.unread.takeIf { it && area != "college" })
                val targets = if (canWrite) repo.targets(auth(), area) else emptyList()
                val overview = if (area == "college") repo.overview(auth()) else null
                state.update { it.copy(items = items, targets = targets, overview = overview, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                state.update { it.copy(loading = false) }
            }
        }
    }

    fun setFilter(filter: AnnouncementFilter) {
        state.update { it.copy(filter = filter) }
        load()
    }

    fun open(item: AnnouncementItem) {
        state.update { it.copy(page = AnnouncementPage.DETAIL, selected = item, stats = null, notice = null, error = null) }
        action {
            val fresh = repo.detail(auth(), area, item.id)
            if (area != "college" && fresh.read == false && fresh.status == "PUBLISHED") {
                repo.markRead(auth(), area, item.id)
                replace(fresh.copy(read = true))
            } else replace(fresh)
            if (area == "college") state.update { it.copy(stats = repo.stats(auth(), item.id)) }
        }
    }

    fun back(): Boolean {
        val current = state.value
        if (current.busy || current.uploadProgress != null) return true
        when (current.page) {
            AnnouncementPage.LIST -> return false
            AnnouncementPage.DETAIL -> state.update { it.copy(page = AnnouncementPage.LIST, selected = null, stats = null) }
            AnnouncementPage.FORM -> state.update {
                it.copy(page = if (it.editingId != null) AnnouncementPage.DETAIL else AnnouncementPage.LIST, file = null)
            }
            AnnouncementPage.PREVIEW -> state.update { it.copy(page = AnnouncementPage.DETAIL) }
        }
        return true
    }

    fun startCreate() = state.update {
        val faculty = area == "faculty"
        it.copy(page = AnnouncementPage.FORM, editingId = null, file = null, error = null, notice = null,
            form = AnnouncementForm(audience = if (faculty) "STUDENTS" else "EVERYONE", scope = if (faculty) "DIVISION" else "COLLEGE",
                targetId = if (faculty) it.targets.singleOrNull()?.id else null))
    }

    fun startEdit(item: AnnouncementItem) = state.update {
        it.copy(page = AnnouncementPage.FORM, editingId = item.id, form = formFrom(item), file = null, error = null, notice = null)
    }

    fun startPreview() = state.update {
        if (it.selected?.canPublish == true) it.copy(page = AnnouncementPage.PREVIEW, error = null, notice = null) else it
    }

    fun editForm(change:(AnnouncementForm) -> AnnouncementForm) = state.update { it.copy(form = change(it.form)) }
    fun chooseFile(file: MaterialUpload?) = state.update { it.copy(file = file) }
    fun showError(message: String) = state.update { it.copy(error = message) }

    /** Saves as a draft (or saves edits), uploads a chosen attachment, then shows the preview. */
    fun save() {
        val current = state.value
        val input = announcementInput(current.form, area == "faculty").getOrElse { e ->
            state.update { it.copy(error = e.message) }
            return
        }
        action {
            var saved = current.editingId?.let { repo.update(auth(), area, it, input) } ?: repo.create(auth(), area, input)
            state.value.file?.let { file ->
                state.update { it.copy(uploadProgress = 0f) }
                try {
                    saved = repo.attach(auth(), area, saved.id, file) { p -> viewModelScope.launch { state.update { it.copy(uploadProgress = p) } } }
                } finally {
                    state.update { it.copy(uploadProgress = null) }
                }
            }
            replace(saved)
            state.update { it.copy(page = AnnouncementPage.PREVIEW, editingId = saved.id, file = null,
                notice = if (current.editingId == null) "Saved as a draft." else "Changes saved.") }
        }
    }

    /** Publishes now (blank schedule) or schedules for the given local time. */
    fun publish(scheduleText: String) {
        val id = state.value.selected?.id ?: return
        val at = scheduleInstant(scheduleText).getOrElse { e -> state.update { it.copy(error = e.message) }; return }
        action {
            val result = repo.publish(auth(), area, id, at)
            replace(result)
            state.update { it.copy(page = AnnouncementPage.DETAIL,
                notice = if (result.status == "SCHEDULED") "Announcement scheduled." else "Announcement published.") }
        }
    }

    fun archive() {
        val id = state.value.selected?.id ?: return
        action { replace(repo.archive(auth(), area, id)); state.update { it.copy(notice = "Announcement archived.") } }
    }

    fun delete() {
        val id = state.value.selected?.id ?: return
        action {
            repo.delete(auth(), area, id)
            state.update { it.copy(items = it.items.filterNot { a -> a.id == id }, selected = null,
                page = AnnouncementPage.LIST, notice = "Draft deleted.") }
        }
    }

    fun removeAttachment() {
        val id = state.value.selected?.id ?: return
        action { replace(repo.detach(auth(), area, id)); state.update { it.copy(notice = "Attachment removed.") } }
    }

    fun run(success: String?, block: suspend () -> Unit) = action(success, block)

    private fun replace(item: AnnouncementItem) = state.update { s ->
        s.copy(items = if (s.items.any { it.id == item.id }) s.items.map { if (it.id == item.id) item else it } else listOf(item) + s.items,
            selected = if (s.selected == null || s.selected.id == item.id) item else s.selected)
    }

    private fun action(success: String? = null, block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                block()
                if (success != null) state.update { it.copy(notice = success) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    private fun fail(e: Exception) {
        val failure = e as? AnnouncementFailure
        if (failure != null && (failure.status == 401 || failure.status == 403)) {
            state.update { AnnouncementUi(error = failure.message) }
            lost(failure.status)
            return
        }
        if (failure?.status == 404 && state.value.page != AnnouncementPage.LIST) {
            val gone = state.value.selected?.id
            state.update { it.copy(page = AnnouncementPage.LIST, selected = null, items = it.items.filterNot { a -> a.id == gone }) }
        }
        state.update { it.copy(error = failure?.message ?: "Request failed. Check your connection and try again.") }
    }
}
