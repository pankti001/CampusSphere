package com.campussphere.app.presentation.resources

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.ResourceItem
import com.campussphere.app.data.remote.api.ResourceSemester
import com.campussphere.app.data.remote.api.ResourceSubject
import com.campussphere.app.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

const val RESOURCE_PAGE = 50

/** Client-side filters over the question papers already loaded for one subject. */
data class PaperFilter(val year: Int? = null, val examType: String? = null, val query: String = "")

fun filterPapers(items: List<ResourceItem>, filter: PaperFilter): List<ResourceItem> {
    val q = filter.query.trim().lowercase()
    return items.filter {
        (filter.year == null || it.examYear == filter.year) &&
            (filter.examType == null || it.examType == filter.examType) &&
            (q.isEmpty() || it.title.lowercase().contains(q) || examLabel(it.examType, it.examTypeLabel).lowercase().contains(q) ||
                it.examSession.orEmpty().lowercase().contains(q))
    }
}

/** Years to offer in the year filter, newest first. */
fun paperYears(items: List<ResourceItem>): List<Int> = items.mapNotNull { it.examYear }.distinct().sortedDescending()

/** The current syllabus first, then older versions newest first. */
fun syllabusOrder(items: List<ResourceItem>): List<ResourceItem> =
    items.sortedWith(compareByDescending<ResourceItem> { it.current }.thenByDescending { it.id })

/** Student view of syllabus and previous-year question papers for the semesters they are or were enrolled in. */
class StudentResourceViewModel(
    private val repo: ResourceRepository,
    private val auth: () -> String,
    private val lost: (Int) -> Unit,
) : ViewModel() {
    var semesters by mutableStateOf(listOf<ResourceSemester>()); private set
    var semesterId by mutableStateOf<Long?>(null); private set
    var subjects by mutableStateOf(listOf<ResourceSubject>()); private set
    var subject by mutableStateOf<ResourceSubject?>(null); private set
    var syllabus by mutableStateOf(listOf<ResourceItem>()); private set
    var papers by mutableStateOf(listOf<ResourceItem>()); private set
    var morePapers by mutableStateOf(false); private set
    var paperFilter by mutableStateOf(PaperFilter())
    var selected by mutableStateOf<ResourceItem?>(null); private set
    var preview by mutableStateOf<File?>(null); private set
    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    private var paperPage = 0

    val visiblePapers get() = filterPapers(papers, paperFilter)

    fun load() {
        if (loading) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                semesters = repo.semesters(auth())
                if (semesterId == null) semesterId = semesters.firstOrNull { it.current }?.semesterId ?: semesters.firstOrNull()?.semesterId
                subjects = repo.subjects(auth(), semesterId)
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

    fun chooseSemester(id: Long) {
        if (id == semesterId) return
        semesterId = id
        subjects = emptyList()
        action { subjects = repo.subjects(auth(), id) }
    }

    fun openSubject(choice: ResourceSubject) {
        subject = choice
        syllabus = emptyList(); papers = emptyList(); paperFilter = PaperFilter(); paperPage = 0; morePapers = false
        refreshSubject()
    }

    fun refreshSubject() {
        val current = subject ?: return
        paperPage = 0
        action {
            syllabus = syllabusOrder(repo.list(auth(), "student", SYLLABUS, ResourceQuery(subjectId = current.subjectId)))
            val first = repo.list(auth(), "student", PAPERS, ResourceQuery(subjectId = current.subjectId))
            papers = first
            morePapers = first.size >= RESOURCE_PAGE
        }
    }

    fun loadMorePapers() {
        val current = subject ?: return
        action {
            val next = repo.list(auth(), "student", PAPERS, ResourceQuery(subjectId = current.subjectId, page = paperPage + 1))
            paperPage += 1
            papers = papers + next.filterNot { n -> papers.any { it.id == n.id } }
            morePapers = next.size >= RESOURCE_PAGE
        }
    }

    fun closeSubject() { subject = null; syllabus = emptyList(); papers = emptyList() }

    /** Opens one syllabus or paper, reloading it so units and status are current. */
    fun open(item: ResourceItem) {
        selected = item
        action {
            try {
                selected = repo.detail(auth(), "student", kindOf(item), item.id)
            } catch (e: ResourceFailure) {
                if (e.status == 404) { forget(item); selected = null }
                throw e
            }
        }
    }

    fun close() { selected = null }

    fun startPreview(cacheDir: File) {
        val item = selected ?: return
        action {
            try {
                preview = repo.preview(auth(), "student", kindOf(item), item.id, cacheDir)
            } catch (e: ResourceFailure) {
                if (e.status == 404) { forget(item); selected = null }
                throw e
            }
        }
    }

    /** Closes the preview and deletes its private copy straight away. */
    fun closePreview() {
        preview?.delete()
        preview = null
    }

    fun download(block: suspend () -> Unit, success: String) = action(success) { block() }

    fun previewFailed() {
        closePreview()
        error = "This PDF could not be shown here. Use Download to open it in another app."
    }

    override fun onCleared() {
        preview?.delete()
        super.onCleared()
    }

    private fun forget(item: ResourceItem) {
        syllabus = syllabus.filterNot { it.id == item.id && it.kind == item.kind }
        papers = papers.filterNot { it.id == item.id && it.kind == item.kind }
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
            semesters = emptyList(); subjects = emptyList(); syllabus = emptyList(); papers = emptyList()
            selected = null; closePreview()
            lost(e.status)
        }
        error = (e as? ResourceFailure)?.message ?: "Request failed. Check your connection and try again."
    }

    companion object {
        fun kindOf(item: ResourceItem) = if (item.kind == "PAPER") PAPERS else SYLLABUS
    }
}
