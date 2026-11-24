package com.campussphere.app.presentation.college

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.AcademicPeriodApi
import com.campussphere.app.data.remote.api.AcademicOfferingApi
import com.campussphere.app.data.remote.api.ProgramApi
import com.campussphere.app.data.remote.dto.AcademicCatalogItem
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
    var selectedDepartment by remember { mutableStateOf<AcademicCatalogItem?>(null) }
    var selectedProgram by remember { mutableStateOf<AcademicCatalogItem?>(null) }
    var selectedYear by remember { mutableStateOf<AcademicCatalogItem?>(null) }
    var selectedDivisionYear by remember { mutableStateOf<AcademicCatalogItem?>(null) }
    var selectedSemester by remember { mutableStateOf<AcademicCatalogItem?>(null) }
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
                "No academic years have been added.", codeLabel = "Year range (YYYY-YYYY)", onBack = onBack)
        }
        program != null && divisionYear != null -> {
            val model = scopedCatalog(program.id to divisionYear.id) {
                AcademicCatalogViewModel(DivisionRepository(offeringApi, program.id, divisionYear.id),
                    authorization, accessLost, "Division")
            }
            val state by model.state.collectAsStateWithLifecycle()
            AcademicCatalogScreen(state, model, "division", "Divisions",
                "No divisions have been added for this program and year.",
                "${program.name} · ${divisionYear.code}", onBack = { selectedDivisionYear = null })
        }
        program != null && year != null && semester != null -> {
            val model = scopedCatalog(program.id to semester.id) {
                AcademicCatalogViewModel(SubjectRepository(offeringApi, program.id, semester.id),
                    authorization, accessLost, "Subject")
            }
            val state by model.state.collectAsStateWithLifecycle()
            AcademicCatalogScreen(state, model, "subject", "Subjects",
                "No subjects have been added for this program and semester.",
                "${program.name} · ${year.code} · ${semester.name}", onBack = { selectedSemester = null })
        }
        program != null && year != null -> {
            val model = scopedCatalog(program.id to year.id) {
                AcademicCatalogViewModel(SemesterRepository(periodApi, program.id, year.id),
                    authorization, accessLost, "Semester", ::validatedSemester,
                    "Enter a semester number from 1 to 99 and a name up to 150 characters.",
                    compareBy { it.code.toIntOrNull() ?: Int.MAX_VALUE })
            }
            val state by model.state.collectAsStateWithLifecycle()
            AcademicCatalogScreen(state, model, "semester", "Semesters",
                "No semesters have been added for this program and year.",
                "${program.name} · ${year.code}", codeLabel = "Semester number",
                actionLabel = "Manage subjects", itemAction = { selectedSemester = it },
                onBack = { selectedYear = null })
        }
        program != null -> {
            val model = scopedCatalog(program.id) { yearModel(periodApi, authorization, accessLost) }
            val state by model.state.collectAsStateWithLifecycle()
            AcademicCatalogScreen(state, model, "academic year", "Select academic year",
                "No academic years are available. Add one from the College Admin dashboard.",
                program.name, actionLabel = "Manage divisions", itemAction = { selectedDivisionYear = it },
                selectItem = { selectedYear = it }, onBack = { selectedProgram = null })
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

private fun yearModel(api: AcademicPeriodApi, authorization: () -> String, accessLost: (Int) -> Unit) =
    AcademicCatalogViewModel(AcademicYearRepository(api), authorization, accessLost, "Academic year",
        ::validatedAcademicYear, "Enter two consecutive four-digit years in YYYY-YYYY format and a name up to 150 characters.")

@Composable
private inline fun <reified T : AcademicCatalogViewModel> scopedCatalog(scope: Any, noinline factory: () -> T): T {
    val owner = remember(scope) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return viewModel(viewModelStoreOwner = owner) { factory() }
}
