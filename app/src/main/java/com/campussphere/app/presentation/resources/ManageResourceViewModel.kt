package com.campussphere.app.presentation.resources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.materials.UploadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

val RESOURCE_STATUSES = listOf("PUBLISHED" to "Published", "ARCHIVED" to "Archived", "HIDDEN" to "Hidden", "REMOVED" to "Removed")
fun resourceStatusLabel(status: String) = RESOURCE_STATUSES.firstOrNull { it.first == status }?.second ?: status

/** What a College Admin may do from each status (the server checks again). */
fun moderationActions(status: String): List<String> = when (status) {
    "PUBLISHED" -> listOf("ARCHIVE", "HIDE", "REMOVE")
    "ARCHIVED" -> listOf("HIDE", "REMOVE")
    "HIDDEN" -> listOf("RESTORE", "REMOVE")
    else -> emptyList()
}

fun moderationNeedsNote(action: String) = action == "HIDE" || action == "REMOVE"

/** Checks a syllabus form before sending; returns the problem to show, or null. */
fun syllabusProblem(title: String, versionLabel: String, effectiveFrom: String): String? = when {
    title.isBlank() -> "Enter a title."
    title.trim().length > 160 -> "The title is too long (160 characters at most)."
    versionLabel.isBlank() -> "Enter a version label, for example 2026-27."
    versionLabel.trim().length > 40 -> "The version label is too long (40 characters at most)."
    effectiveFrom.isNotBlank() && runCatching { java.time.LocalDate.parse(effectiveFrom.trim()) }.isFailure ->
        "Write the effective date as YYYY-MM-DD, or leave it empty."
    else -> null
}

/** Checks a question paper form before sending; returns the problem to show, or null. */
fun paperProblem(examType: String?, label: String, year: String, title: String): String? {
    val y = year.trim().toIntOrNull()
    return when {
        examType == null -> "Choose the exam."
        examType == "OTHER" && label.isBlank() -> "Name the exam, for example Class test."
        label.trim().length > 60 -> "The exam name is too long (60 characters at most)."
        y == null || y < 1990 || y > 2100 -> "Enter the exam year, for example 2025."
        title.isBlank() -> "Enter a title."
        title.trim().length > 160 -> "The title is too long (160 characters at most)."
        else -> null
    }
}

/**
 * Faculty ("faculty": their own uploads) and College Admin ("college": the whole college) management of syllabus
 * versions and question papers. The server limits every list to what the account may see and checks every action.
 */
class ManageResourceViewModel(
    private val repo: ResourceRepository,
    val area: String,
    private val auth: () -> String,
    private val lost: (Int) -> Unit,
) : ViewModel() {
    var kind by mutableStateOf(SYLLABUS); private set
    var items by mutableStateOf(listOf<ResourceItem>()); private set
    var more by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set
    var query by mutableStateOf(""); private set
    var subjects by mutableStateOf(listOf<TeachableSubject>()); private set
    var coverage by mutableStateOf(listOf<ResourceCoverage>()); private set
    var stats by mutableStateOf<ResourceStats?>(null); private set
    var selected by mutableStateOf<ResourceItem?>(null); private set
    var events by mutableStateOf(listOf<ResourceEvent>()); private set
    var preview by mutableStateOf<File?>(null); private set
    var upload by mutableStateOf<UploadState>(UploadState.Idle); private set
    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    private var page = 0
    private var pendingUpload: (() -> Unit)? = null

    private fun filters(p: Int = 0) = ResourceQuery(status = status, q = query, page = p)

    fun load() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                page = 0
                val first = repo.list(auth(), area, kind, filters())
                items = first
                more = first.size >= RESOURCE_PAGE
                if (area == "faculty" && subjects.isEmpty()) subjects = repo.teachable(auth())
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun show(newKind: String) { if (newKind != kind) { kind = newKind; items = emptyList(); load() } }
    fun filter(newStatus: String?, newQuery: String) { status = newStatus; query = newQuery.take(80); load() }

    fun loadMore() = action {
        val next = repo.list(auth(), area, kind, filters(page + 1))
        page += 1
        items = items + next.filterNot { n -> items.any { it.id == n.id } }
        more = next.size >= RESOURCE_PAGE
    }

    fun loadCoverage() = action { coverage = repo.coverage(auth()) }
    fun loadStats() = action { stats = repo.stats(auth()) }

    fun open(item: ResourceItem) {
        selected = item
        events = emptyList()
        refreshSelected()
    }

    fun close() { selected = null; events = emptyList() }

    fun refreshSelected() {
        val item = selected ?: return
        action {
            try {
                selected = repo.detail(auth(), area, kindOf(item), item.id)
                if (area == "college") events = repo.events(auth(), kindOf(item), item.id)
            } catch (e: ResourceFailure) {
                if (e.status == 404) { items = items.filterNot { it.id == item.id }; selected = null }
                throw e
            }
        }
    }

    fun startPreview(cacheDir: File) {
        val item = selected ?: return
        action { preview = repo.preview(auth(), area, kindOf(item), item.id, cacheDir) }
    }

    fun closePreview() { preview?.delete(); preview = null }

    fun previewFailed() {
        closePreview()
        error = "This PDF could not be shown here. Use Download to open it in another app."
    }

    fun download(block: suspend () -> Unit, success: String) = action(success) { block() }
    fun readFile(read: suspend () -> MaterialUpload, onRead: (MaterialUpload) -> Unit) = action { onRead(read()) }

    // ---- Faculty ----

    /** Uploads a new syllabus version or question paper; a failed upload can be retried as it was. */
    fun startUpload(uploadKind: String, subjectId: Long, fields: Map<String, String?>, file: MaterialUpload, done: () -> Unit) =
        transfer("Uploaded. Students of this subject can now see it.", done) { progress ->
            val created = repo.upload(auth(), uploadKind, subjectId, fields, file, progress)
            if (uploadKind == kind) items = listOf(created) + items.filterNot { it.id == created.id }
        }

    fun replaceFile(file: MaterialUpload, done: () -> Unit) {
        val item = selected ?: return
        transfer("File replaced.", done) { progress -> replace(repo.replaceFile(auth(), kindOf(item), item.id, file, progress)) }
    }

    fun retryUpload() { if (upload is UploadState.Failed) pendingUpload?.invoke() }
    fun resetUpload() { if (upload !is UploadState.Uploading) { upload = UploadState.Idle; pendingUpload = null } }

    fun editSyllabus(id: Long, fields: SyllabusEdit, done: () -> Unit) = action("Changes saved.") {
        replace(repo.editSyllabus(auth(), id, fields)); done()
    }

    fun editPaper(id: Long, fields: PaperEdit, done: () -> Unit) = action("Changes saved.") {
        replace(repo.editPaper(auth(), id, fields)); done()
    }

    fun saveUnits(id: Long, units: List<ResourceUnit>, done: () -> Unit) = action("Units saved.") {
        replace(repo.saveUnits(auth(), id, units)); done()
    }

    fun archive(archive: Boolean) {
        val item = selected ?: return
        action(if (archive) "Archived. Students no longer see it." else "Published again for students.") {
            replace(repo.archive(auth(), kindOf(item), item.id, archive))
        }
    }

    // ---- College Admin ----

    fun moderate(actionName: String, note: String?) {
        val item = selected ?: return
        action(when (actionName) {
            "HIDE" -> "Hidden from students."
            "RESTORE" -> "Restored for students."
            "ARCHIVE" -> "Archived. Students no longer see it."
            else -> "Removed."
        }) {
            replace(repo.moderate(auth(), kindOf(item), item.id, actionName, note))
            events = repo.events(auth(), kindOf(item), item.id)
        }
    }

    override fun onCleared() {
        preview?.delete()
        super.onCleared()
    }

    private fun transfer(success: String, done: () -> Unit, block: suspend ((Float) -> Unit) -> Unit) {
        if (upload is UploadState.Uploading) return
        val attempt = {
            upload = UploadState.Uploading(0f)
            error = null
            notice = null
            viewModelScope.launch {
                try {
                    var shown = 0f
                    block { progress ->
                        // Called on the network thread: post to the main thread about once per percent.
                        if (progress - shown >= 0.01f || progress >= 1f) {
                            shown = progress
                            viewModelScope.launch { if (upload is UploadState.Uploading) upload = UploadState.Uploading(progress) }
                        }
                    }
                    upload = UploadState.Idle
                    pendingUpload = null
                    notice = success
                    done()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is ResourceFailure && (e.status == 401 || e.status == 403)) lost(e.status)
                    upload = UploadState.Failed(message(e))
                }
            }
        }
        pendingUpload = { attempt() }
        attempt()
    }

    private fun replace(item: ResourceItem) {
        items = items.map { if (it.id == item.id) item else it }
        if (selected?.id == item.id) selected = item
    }

    private fun action(success: String? = null, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        viewModelScope.launch {
            try {
                block()
                notice = success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    private fun fail(e: Exception) {
        if (e is ResourceFailure && (e.status == 401 || e.status == 403)) {
            items = emptyList(); subjects = emptyList(); coverage = emptyList(); stats = null
            selected = null; events = emptyList(); closePreview()
            lost(e.status)
        }
        error = message(e)
    }

    private fun message(e: Exception) = (e as? ResourceFailure)?.message ?: "Request failed. Check your connection and try again."

    companion object {
        fun kindOf(item: ResourceItem) = if (item.kind == "PAPER") PAPERS else SYLLABUS
    }
}
