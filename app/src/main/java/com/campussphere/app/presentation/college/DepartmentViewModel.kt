package com.campussphere.app.presentation.college

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.dto.DepartmentInput
import com.campussphere.app.data.remote.dto.DepartmentSummary
import com.campussphere.app.data.repository.AcademicCatalogFailure
import com.campussphere.app.data.repository.AcademicCatalogRepository
import com.campussphere.app.data.repository.DepartmentRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal fun validatedDepartment(code: String, name: String): DepartmentInput? {
    val cleanCode = normalizedCode(code)
    val cleanName = name.trim()
    if (!cleanCode.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,29}")) ||
        cleanName.isEmpty() || cleanName.length > 150) return null
    return DepartmentInput(cleanCode, cleanName)
}

data class DepartmentForm(val id: Long? = null, val code: String = "", val name: String = "")

data class DepartmentState(
    val list: List<DepartmentSummary>? = null,
    val form: DepartmentForm? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null
)

class DepartmentViewModel(repository: DepartmentRepository, authorization: () -> String, accessLost: (Int) -> Unit) :
    AcademicCatalogViewModel(repository, authorization, accessLost, "Department")

open class AcademicCatalogViewModel(
    private val repository: AcademicCatalogRepository,
    private val authorization: () -> String,
    private val accessLost: (Int) -> Unit,
    private val resource: String,
    private val validate: (String, String) -> DepartmentInput? = ::validatedDepartment,
    private val validationMessage: String =
        "Enter a name up to 150 characters and a code of 1–30 letters, digits, underscores or hyphens starting with a letter or digit.",
    private val order: Comparator<DepartmentSummary> = compareBy { it.code },
    // What a new form starts with (for example the next academic year), and whether the code follows the name.
    private val newForm: (List<DepartmentSummary>) -> DepartmentForm = { DepartmentForm() },
    private val suggestCode: Boolean = true,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DepartmentState())
    val state = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() = request {
        val departments = repository.list(it)
        mutableState.value = DepartmentState(list = departments, busy = true)
    }

    fun edit(department: DepartmentSummary? = null) {
        if (mutableState.value.busy) return
        val form = department?.let { DepartmentForm(it.id, it.code, it.name) } ?: newForm(mutableState.value.list.orEmpty())
        mutableState.value = mutableState.value.copy(form = form, error = null, notice = null)
    }

    fun changeCode(code: String) = changeForm { it.copy(code = code) }

    /** A new item's code follows its name until the code is typed by hand. */
    fun changeName(name: String) = changeForm {
        val following = suggestCode && it.id == null && (it.code.isBlank() || it.code == suggestedCode(it.name))
        it.copy(name = name, code = if (following) suggestedCode(name) else it.code)
    }

    private fun changeForm(update: (DepartmentForm) -> DepartmentForm) {
        val currentState = mutableState.value
        val form = currentState.form ?: return
        if (!currentState.busy) mutableState.value = currentState.copy(form = update(form), error = null)
    }

    fun cancelEdit() {
        if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(form = null, error = null)
    }

    fun save() {
        val form = mutableState.value.form ?: return
        val input = validate(form.code, form.name)
        if (input == null) {
            mutableState.value = mutableState.value.copy(error = validationMessage)
            return
        }
        request { header ->
            val saved = repository.save(header, form.id, input)
            val list = mutableState.value.list.orEmpty().filterNot { it.id == saved.id } + saved
            mutableState.value = DepartmentState(
                list = list.sortedWith(order), busy = true,
                notice = if (form.id == null) "$resource added." else "$resource updated."
            )
        }
    }

    private fun request(action: suspend (String) -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, error = null, notice = null)
        viewModelScope.launch {
            try {
                action(authorization())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val status = (failure as? AcademicCatalogFailure)?.status
                val error = (failure as? AcademicCatalogFailure)?.message ?: "Could not reach CampusSphere. Try again."
                if (status == 401 || status == 403 || status == 503) {
                    mutableState.value = DepartmentState(busy = true, error = error)
                    if (status != 503) accessLost(status)
                } else if (status == 404) {
                    mutableState.value = DepartmentState(busy = true, error = error)
                } else {
                    mutableState.value = mutableState.value.copy(error = error)
                }
            } finally {
                mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }
}
