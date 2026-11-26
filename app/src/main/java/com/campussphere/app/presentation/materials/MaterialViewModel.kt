package com.campussphere.app.presentation.materials

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.MaterialFailure
import com.campussphere.app.data.repository.MaterialRepository
import com.campussphere.app.data.repository.MaterialUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

val MATERIAL_TYPES = listOf(
    "NOTES" to "Notes", "SLIDES" to "Slides", "ASSIGNMENT" to "Assignment", "QUESTION_BANK" to "Question bank",
    "LAB_MANUAL" to "Lab manual", "REFERENCE" to "Reference", "OTHER" to "Other")
val MATERIAL_STATUSES = listOf("PUBLISHED" to "Published", "HIDDEN" to "Hidden", "REMOVED" to "Removed", "DELETED" to "Deleted")

fun materialTypeLabel(type: String) = MATERIAL_TYPES.firstOrNull { it.first == type }?.second ?: type
fun materialStatusLabel(status: String) = MATERIAL_STATUSES.firstOrNull { it.first == status }?.second ?: status

/** Client-side filters over the loaded list. The server has already limited the list to what this user may see. */
data class MaterialFilter(val query: String = "", val subjectId: Long? = null, val divisionId: Long? = null,
                          val facultyId: Long? = null, val type: String? = null, val status: String? = null)

fun applyMaterialFilter(items: List<MaterialItem>, filter: MaterialFilter): List<MaterialItem> {
    val q = filter.query.trim().lowercase()
    return items.filter {
        (q.isEmpty() || it.title.lowercase().contains(q)) &&
            (filter.subjectId == null || it.subjectId == filter.subjectId) &&
            (filter.divisionId == null || it.divisionId == filter.divisionId) &&
            (filter.facultyId == null || it.facultyId == filter.facultyId) &&
            (filter.type == null || it.type == filter.type) &&
            (filter.status == null || it.status == filter.status)
    }
}

/** Upload progress shown on the upload screen. A failed upload keeps its request so it can be retried. */
sealed interface UploadState {
    data object Idle : UploadState
    data class Uploading(val progress: Float) : UploadState
    data class Failed(val message: String) : UploadState
}

class MaterialViewModel(
    private val repo: MaterialRepository,
    val area: String,
    private val auth: () -> String,
    private val lost: (Int) -> Unit,
) : ViewModel() {
    var items by mutableStateOf(listOf<MaterialItem>()); private set
    var assignments by mutableStateOf(listOf<MaterialAssignment>()); private set
    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var filter by mutableStateOf(MaterialFilter())
    var selected by mutableStateOf<MaterialItem?>(null); private set
    var events by mutableStateOf(listOf<MaterialEvent>()); private set
    var upload by mutableStateOf<UploadState>(UploadState.Idle); private set
    private var pendingUpload: (() -> Unit)? = null

    val visible get() = applyMaterialFilter(items, filter)

    fun clearMessages() { error = null; notice = null }

    fun load() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                items = repo.list(auth(), area)
                if (area == "faculty") assignments = repo.assignments(auth())
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

    fun open(item: MaterialItem) {
        selected = item
        events = emptyList()
        refreshSelected()
    }

    fun close() { selected = null; events = emptyList() }

    /** Reloads the open material so status changes by others are visible; a missing material closes the detail. */
    fun refreshSelected() {
        val id = selected?.id ?: return
        action {
            try {
                selected = repo.detail(auth(), area, id)
                if (area == "college") events = repo.events(auth(), id)
            } catch (e: MaterialFailure) {
                if (e.status == 404) { items = items.filterNot { it.id == id }; selected = null }
                throw e
            }
        }
    }

    fun startUpload(assignmentId: Long, fields: MaterialFields, file: MaterialUpload, done: () -> Unit) {
        if (upload is UploadState.Uploading) return
        val attempt = {
            upload = UploadState.Uploading(0f)
            error = null
            viewModelScope.launch {
                try {
                    var shown = 0f
                    val created = repo.upload(auth(), assignmentId, fields, file) { progress ->
                        // Called on the network thread: post to the main thread, about once per percent.
                        if (progress - shown >= 0.01f || progress >= 1f) {
                            shown = progress
                            viewModelScope.launch { if (upload is UploadState.Uploading) upload = UploadState.Uploading(progress) }
                        }
                    }
                    items = listOf(created) + items.filterNot { it.id == created.id }
                    upload = UploadState.Idle
                    pendingUpload = null
                    notice = "Material uploaded."
                    done()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = message(e)
                    if (e is MaterialFailure && (e.status == 401 || e.status == 403)) lost(e.status)
                    upload = UploadState.Failed(message)
                }
            }
        }
        pendingUpload = { attempt() }
        attempt()
    }

    fun retryUpload() { if (upload is UploadState.Failed) pendingUpload?.invoke() }
    fun resetUpload() { if (upload !is UploadState.Uploading) { upload = UploadState.Idle; pendingUpload = null } }

    fun update(id: Long, fields: MaterialFields, done: () -> Unit) = action("Changes saved.") {
        val updated = repo.update(auth(), id, fields)
        replace(updated)
        done()
    }

    fun delete(id: Long) = action("Material deleted. Students can no longer see it.") {
        repo.delete(auth(), id)
        items = items.filterNot { it.id == id }
        selected = null
    }

    fun moderate(id: Long, actionName: String, note: String?) = action(when (actionName) {
        "HIDE" -> "Material hidden from students."
        "RESTORE" -> "Material restored for students."
        else -> "Material removed."
    }) {
        replace(repo.moderate(auth(), id, actionName, note))
        events = repo.events(auth(), id)
    }

    fun readFile(read: suspend () -> MaterialUpload, onRead: (MaterialUpload) -> Unit) = action {
        onRead(read())
    }

    fun download(block: suspend () -> Unit, success: String) = action(success) { block() }

    private fun replace(item: MaterialItem) {
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
        if (e is MaterialFailure && (e.status == 401 || e.status == 403)) {
            items = emptyList(); assignments = emptyList(); events = emptyList(); selected = null
            lost(e.status)
        }
        error = message(e)
    }

    private fun message(e: Exception) =
        (e as? MaterialFailure)?.message ?: "Request failed. Check your connection and try again."
}
