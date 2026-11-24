package com.campussphere.app.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class PreviewDepartment(val id: Int, val code: String, val name: String)

internal enum class PreviewPage { DASHBOARD, DEPARTMENTS, ADD, EDIT }

internal data class DepartmentDraft(
    val departmentId: Int? = null,
    val name: String = "",
    val code: String = "",
    val nameError: String? = null,
    val codeError: String? = null,
    val formError: String? = null
)

internal data class AdminPreviewState(
    val page: PreviewPage = PreviewPage.DASHBOARD,
    val isLoading: Boolean = true,
    val departments: List<PreviewDepartment> = emptyList(),
    val search: String = "",
    val draft: DepartmentDraft = DepartmentDraft(),
    val notice: String? = null
) {
    val results: List<PreviewDepartment>
        get() {
            val term = search.trim()
            return departments.filter {
                it.name.contains(term, ignoreCase = true) || it.code.contains(term, ignoreCase = true)
            }.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
}

internal class AdminPreviewViewModel : ViewModel() {
    private val _state = MutableStateFlow(AdminPreviewState())
    val state = _state.asStateFlow()

    init {
        loadSamples()
    }

    private fun loadSamples() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            // Sample data belongs to this ViewModel only. There is no network or persistence layer.
            val departments = withContext(Dispatchers.Default) {
                listOf(
                    PreviewDepartment(1, "ASC", "Applied Sciences"),
                    PreviewDepartment(2, "BBA", "Business Administration"),
                    PreviewDepartment(3, "CSE", "Computer Science"),
                    PreviewDepartment(4, "EEE", "Electrical Engineering")
                )
            }
            _state.update { it.copy(isLoading = false, departments = departments) }
        }
    }

    fun dashboard() {
        _state.update { it.copy(page = PreviewPage.DASHBOARD, notice = null) }
    }

    fun departments() {
        _state.update { it.copy(page = PreviewPage.DEPARTMENTS, notice = null) }
    }

    fun search(value: String) {
        _state.update { it.copy(search = value, notice = null) }
    }

    fun add() {
        _state.update { it.copy(page = PreviewPage.ADD, draft = DepartmentDraft(), notice = null) }
    }

    fun edit(id: Int) {
        _state.update { current ->
            val item = current.departments.firstOrNull { it.id == id }
            if (item == null) current.copy(notice = "This preview department is no longer available.")
            else current.copy(
                page = PreviewPage.EDIT,
                draft = DepartmentDraft(departmentId = id, name = item.name, code = item.code),
                notice = null
            )
        }
    }

    fun name(value: String) {
        _state.update { it.copy(draft = it.draft.copy(name = value, nameError = null, formError = null)) }
    }

    fun code(value: String) {
        _state.update { it.copy(draft = it.draft.copy(code = value, codeError = null, formError = null)) }
    }

    fun save() {
        _state.update { current ->
            val draft = current.draft
            val name = draft.name.trim()
            val code = draft.code.trim().uppercase(Locale.ROOT)
            val nameError = when {
                name.isEmpty() -> "Enter a department name."
                name.length > 150 -> "Use 150 characters or fewer."
                else -> null
            }
            val codeError = when {
                code.isEmpty() -> "Enter a department code."
                !CodeFormat.matches(code) -> "Use 1–30 letters, numbers, hyphens, or underscores. Start with a letter or number."
                current.departments.any { it.code == code && it.id != draft.departmentId } ->
                    "This code is already used by a preview department."
                else -> null
            }
            if (nameError != null || codeError != null) {
                return@update current.copy(draft = draft.copy(nameError = nameError, codeError = codeError))
            }

            val id = draft.departmentId
            if (id != null && current.departments.none { it.id == id }) {
                return@update current.copy(draft = draft.copy(formError = "This preview department is no longer available."))
            }
            val departments = if (id == null) {
                val nextId = (current.departments.maxOfOrNull { it.id } ?: 0) + 1
                current.departments + PreviewDepartment(nextId, code, name)
            } else {
                current.departments.map { if (it.id == id) PreviewDepartment(id, code, name) else it }
            }
            current.copy(
                page = PreviewPage.DEPARTMENTS, departments = departments,
                search = "", draft = DepartmentDraft(),
                notice = if (id == null) "Preview department added." else "Preview department updated."
            )
        }
    }
}

private val CodeFormat = Regex("[A-Z0-9][A-Z0-9_-]{0,29}")
