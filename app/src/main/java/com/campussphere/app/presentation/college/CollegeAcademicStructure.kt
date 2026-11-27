package com.campussphere.app.presentation.college

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.AcademicPeriodApi
import com.campussphere.app.data.remote.api.AcademicOfferingApi
import com.campussphere.app.data.remote.api.ProgramApi
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import com.campussphere.app.data.remote.dto.DepartmentSummary
import com.campussphere.app.data.repository.AcademicYearRepository
import com.campussphere.app.data.repository.DepartmentRepository
import com.campussphere.app.data.repository.DivisionRepository
import com.campussphere.app.data.repository.ProgramRepository
import com.campussphere.app.data.repository.SemesterRepository
import com.campussphere.app.data.repository.SubjectRepository

@Composable
fun CollegeAcademicStructure(
    manageYears: Boolean,
    departments: DepartmentRepository,
    programApi: ProgramApi,
    periodApi: AcademicPeriodApi,
    offeringApi: AcademicOfferingApi,
    authorization: () -> String,
    accessLost: (Int) -> Unit,
    onBack: () -> Unit
) {
    // Saved across rotation and process restarts, so the admin stays on the same level.
    var selectedDepartment by rememberSaveable(stateSaver = ItemSaver) { mutableStateOf<AcademicCatalogItem?>(null) }
    var selectedProgram by rememberSaveable(stateSaver = ItemSaver) { mutableStateOf<AcademicCatalogItem?>(null) }
    var selectedYear by rememberSaveable(stateSaver = ItemSaver) { mutableStateOf<AcademicCatalogItem?>(null) }
    var selectedDivisionYear by rememberSaveable(stateSaver = ItemSaver) { mutableStateOf<AcademicCatalogItem?>(null) }
    var selectedSemester by rememberSaveable(stateSaver = ItemSaver) { mutableStateOf<AcademicCatalogItem?>(null) }
    val department = selectedDepartment
    val program = selectedProgram
    val year = selectedYear
    val divisionYear = selectedDivisionYear
    val semester = selectedSemester

    when {
        manageYears -> {
            val model = scopedCatalog("years") { yearModel(periodApi, authorization, accessLost) }
            val state by model.state.collectAsStateWithLifecycle()
            AcademicCatalogScreen(state, model, "academic year", "Academic years",
                "No academic years have been added yet. Tap Add academic year to create one, for example 2026-2027.",
                codeLabel = "Academic year", codeHint = YEAR_HINT, withDates = true, onBack = onBack)
        }
        program != null && divisionYear != null -> {
            val model = scopedCatalog(program.id to divisionYear.id) {
                AcademicCatalogViewModel(DivisionRepository(offeringApi, program.id, divisionYear.id),
                    authorization, accessLost, "Division")
            }
            val state by model.state.collectAsStateWithLifecycle()
            AcademicCatalogScreen(state, model, "division", "Divisions",
                "No divisions have been added for this program and year.",
                "${program.name} · ${divisionYear.code}", codeLabel = "Division code",
                codeHint = "For example A, B or DIV-A.", onBack = { selectedDivisionYear = null })
        }
        program != null && year != null && semester != null -> {
            val model = scopedCatalog(program.id to semester.id) {
                AcademicCatalogViewModel(SubjectRepository(offeringApi, program.id, semester.id),
                    authorization, accessLost, "Subject")
            }
            val state by model.state.collectAsStateWithLifecycle()
            AcademicCatalogScreen(state, model, "subject", "Subjects",
                "No subjects have been added for this program and semester.",
                "${program.name} · ${year.code} · ${semester.name}", codeLabel = "Subject code",
                codeHint = "For example JAVA101 or CS-201.", onBack = { selectedSemester = null })
        }
        program != null && year != null -> {
            val model = scopedCatalog(program.id to year.id) {
                AcademicCatalogViewModel(SemesterRepository(periodApi, program.id, year.id),
                    authorization, accessLost, "Semester", ::validatedSemester,
                    "Enter a semester number from 1 to 99, for example 3. The name is optional.",
                    compareBy { it.code.toIntOrNull() ?: Int.MAX_VALUE }, ::suggestedSemester, suggestCode = false, withDates = true)
            }
            val state by model.state.collectAsStateWithLifecycle()
            AcademicCatalogScreen(state, model, "semester", "Semesters",
                "No semesters have been added for this program and year.",
                "${program.name} · ${year.code}", codeLabel = "Semester number", codeHint = "1 to 99. The name is filled in for you.", withDates = true,
                actionLabel = "Manage subjects", itemAction = { selectedSemester = it },
                onBack = { selectedYear = null })
        }
        program != null -> {
            val model = scopedCatalog(program.id) { yearModel(periodApi, authorization, accessLost) }
            val state by model.state.collectAsStateWithLifecycle()
            AcademicCatalogScreen(state, model, "academic year", "Select academic year",
                "No academic years yet. Tap Add academic year, then choose it here.",
                program.name + " · tap a year for its semesters", codeLabel = "Academic year", actionLabel = "Manage divisions",
                itemAction = { selectedDivisionYear = it }, selectItem = { selectedYear = it }, codeHint = YEAR_HINT, withDates = true,
                onBack = { selectedProgram = null })
        }
        department != null -> {
            val model = scopedCatalog(department.id) {
                AcademicCatalogViewModel(ProgramRepository(programApi, department.id), authorization, accessLost, "Program")
            }
            val state by model.state.collectAsStateWithLifecycle()
            ProgramScreen(state, model, department, onSemesters = { selectedProgram = it }) { selectedDepartment = null }
        }
        else -> {
            val model = scopedCatalog("departments") { DepartmentViewModel(departments, authorization, accessLost) }
            val state by model.state.collectAsStateWithLifecycle()
            DepartmentScreen(state, model, onPrograms = { selectedDepartment = it }, onBack = onBack)
        }
    }
}

private val ItemSaver = androidx.compose.runtime.saveable.listSaver<AcademicCatalogItem?, Any>(
    save = { item -> item?.let { listOf(it.id, it.code, it.name) } ?: emptyList() },
    restore = { if (it.size == 3) AcademicCatalogItem(it[0] as Long, it[1] as String, it[2] as String) else null })

private fun yearModel(api: AcademicPeriodApi, authorization: () -> String, accessLost: (Int) -> Unit) =
    AcademicCatalogViewModel(AcademicYearRepository(api), authorization, accessLost, "Academic year",
        ::validatedAcademicYear, "Enter two consecutive years, for example 2026-2027 or 2026-27. The name is optional.",
        compareBy<DepartmentSummary> { it.code }.reversed(), ::suggestedAcademicYear, suggestCode = false, withDates = true)

private const val YEAR_HINT = "For example 2026-2027 (2026-27 also works)."

@Composable
private inline fun <reified T : AcademicCatalogViewModel> scopedCatalog(scope: Any, noinline factory: () -> T): T {
    val owner = remember(scope) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return viewModel(viewModelStoreOwner = owner) { factory() }
}
