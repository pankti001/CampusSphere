package com.campussphere.app.presentation.college

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.StudentAffiliation
import com.campussphere.app.data.remote.api.StudentEnrollment
import com.campussphere.app.data.remote.api.StudentMember
import com.campussphere.app.data.repository.StudentRepository

@Composable
fun StudentScreen(repository: StudentRepository, authorization: () -> String, onAccessLost: (Int) -> Unit,
                  onExit: () -> Unit, mode: StudentMode = StudentMode.ADMIN, onAttendance: (() -> Unit)? = null, onMaterials: (() -> Unit)? = null) {
    val owner = remember(mode) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val model = viewModel<StudentViewModel>(viewModelStoreOwner = owner) {
        StudentViewModel(repository, authorization, onAccessLost, mode)
    }
    val state by model.state.collectAsStateWithLifecycle()
    var query by remember(state.page) { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var endConfirmation by remember { mutableStateOf<StudentEnrollment?>(null) }
    var review by remember { mutableStateOf<StudentAffiliation?>(null) }
    val back = {
        if (mode != StudentMode.ADMIN || state.page == StudentPage.DIRECTORY) onExit() else model.back()
    }
    BackHandler(enabled = mode != StudentMode.STUDENT) { if (!state.busy) back() }

    StudentWorkspaceTheme {
        // A fresh list position per screen keeps the next wizard step and its actions in view.
        key(state.page) {
            LazyColumn(
                Modifier.fillMaxSize().background(StudentColors.Canvas).safeDrawingPadding().imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item { StudentHero(state.page, mode, state.busy, back) }
                if (mode == StudentMode.STUDENT && onMaterials != null) item { WorkspaceItem { Button(onClick = onMaterials) { Text("Study materials") } } }
                if (mode == StudentMode.STUDENT && onAttendance != null) item {
                    WorkspaceItem {
                        Button(onClick = onAttendance, enabled = !state.busy && state.error == null,
                            modifier = Modifier.fillMaxWidth()) { Text("My attendance") }
                    }
                }
                if (state.busy) item {
                    WorkspaceItem {
                        StudentPanel {
                            Column(Modifier.fillMaxWidth().padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Loading student information", style = MaterialTheme.typography.titleMedium)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                state.error?.let { message ->
                    item {
                        WorkspaceItem {
                            StudentNotice(message, success = false, action = if (state.canRetry) "Retry" else "Refresh",
                                onAction = if (state.canRetry) model::retry else model::refresh)
                        }
                    }
                }
                state.success?.let { message ->
                    item { WorkspaceItem { StudentNotice(message, success = true) } }
                }

                when (state.page) {
                    StudentPage.DIRECTORY -> if (!state.busy) {
                        item {
                            WorkspaceItem {
                                StudentTile("Affiliation requests", "Review student admission requests.",
                                    StudentMark.List, onClick = model::queue)
                            }
                        }
                        if (state.error == null) {
                            item {
                                WorkspaceItem {
                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        StudentSection("Students", model::refresh)
                                        StudentField(query, { query = it }, "Search students", StudentMark.Search)
                                    }
                                }
                            }
                            val visible = state.students.filter { student ->
                                listOfNotNull(student.name, student.email, student.department)
                                    .any { it.contains(query.trim(), true) }
                            }
                            if (visible.isEmpty()) item {
                                WorkspaceItem {
                                    if (query.isBlank()) {
                                        StudentEmpty("No students yet", "No verified students belong to this college.",
                                            StudentMark.Person)
                                    } else {
                                        StudentEmpty("No search results", "No students match your search.", StudentMark.Search)
                                    }
                                }
                            }
                            items(visible, key = { it.id }) { student ->
                                WorkspaceItem {
                                    StudentTile(student.name, student.department ?: "Department unassigned",
                                        StudentMark.Person, studentInitials(student.name)) { model.profile(student.id) }
                                }
                            }
                        }
                    }
                    StudentPage.PROFILE -> state.student?.let { student ->
                        item {
                            WorkspaceItem {
                                StudentProfile(student, state.busy, model::selectDepartment, model::startEnrollment)
                            }
                        }
                        item { WorkspaceItem { StudentSection("Enrollment history") } }
                        if (state.enrollments.isEmpty()) item {
                            WorkspaceItem {
                                StudentEmpty("No enrollments yet.", "Enroll this student after setting their department.",
                                    StudentMark.Study)
                            }
                        }
                        items(state.enrollments, key = { it.id }) { enrollment ->
                            WorkspaceItem {
                                EnrollmentCard(enrollment, showStatus = true,
                                    onEnd = if (state.busy || !enrollment.current) null else { { endConfirmation = enrollment } })
                            }
                        }
                    }
                    StudentPage.DASHBOARD -> if (!state.busy && state.error == null) {
                        item { WorkspaceItem { StudentSection("My current enrollment", model::refresh) } }
                        if (state.enrollments.isEmpty()) item {
                            WorkspaceItem {
                                StudentEmpty("No active enrollment", "You do not have a current enrollment.",
                                    StudentMark.Study)
                            }
                        }
                        items(state.enrollments, key = { it.id }) { enrollment ->
                            WorkspaceItem { EnrollmentCard(enrollment) }
                        }
                    }
                    StudentPage.REQUEST -> if (!state.busy) {
                        val request = state.requests.singleOrNull()
                        request?.let {
                            item { WorkspaceItem { AffiliationStatus(it, model::refresh) } }
                        }
                        if (request?.status != "PENDING" && !state.canRetry) item {
                            WorkspaceItem {
                                StudentPanel {
                                    Column(Modifier.fillMaxWidth().padding(19.dp),
                                        verticalArrangement = Arrangement.spacedBy(13.dp)) {
                                        StudentIconTile(StudentMark.College)
                                        Text("College details", style = MaterialTheme.typography.titleLarge)
                                        Text("Enter the code provided by your college.", color = StudentColors.Muted,
                                            style = MaterialTheme.typography.bodyMedium)
                                        StudentField(code, { code = it }, "College code", StudentMark.College)
                                        Button(onClick = { model.submit(code) }, modifier = Modifier.fillMaxWidth()) {
                                            Text("Send request")
                                            Spacer(Modifier.width(6.dp))
                                            StudentIcon(StudentMark.Next, MaterialTheme.colorScheme.onPrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    StudentPage.QUEUE -> if (!state.busy && state.error == null) {
                        item { WorkspaceItem { StudentSection("Pending review", model::refresh) } }
                        if (state.requests.isEmpty()) item {
                            WorkspaceItem {
                                StudentEmpty("You're all caught up", "No pending affiliation requests.", StudentMark.Check)
                            }
                        }
                        items(state.requests, key = { it.id }) { request ->
                            WorkspaceItem {
                                StudentTile(request.name, request.email, StudentMark.Person, studentInitials(request.name)) {
                                    review = request
                                }
                            }
                        }
                    }
                    StudentPage.REVIEW -> state.student?.let { student ->
                        item {
                            WorkspaceItem {
                                StudentPanel {
                                    Column(Modifier.fillMaxWidth().padding(19.dp),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Text("Enrollment details", style = MaterialTheme.typography.titleLarge)
                                        AcademicValue("Student", student.name)
                                        HorizontalDivider(color = StudentColors.Line)
                                        with(state.selection) {
                                            AcademicRow("Department", department?.name.orEmpty(), "Program", program?.name.orEmpty())
                                            AcademicRow("Academic year", year?.name.orEmpty(), "Semester", semester?.name.orEmpty())
                                            AcademicValue("Division", division?.name.orEmpty())
                                        }
                                        Button(onClick = model::save, enabled = !state.busy,
                                            modifier = Modifier.fillMaxWidth()) { Text("Save enrollment") }
                                    }
                                }
                            }
                        }
                    }
                    else -> if (!state.busy && state.student != null) {
                        val mark = when (state.page) {
                            StudentPage.DEPARTMENT -> StudentMark.College
                            StudentPage.YEAR, StudentPage.SEMESTER -> StudentMark.Calendar
                            StudentPage.DIVISION -> StudentMark.Person
                            else -> StudentMark.Study
                        }
                        val context = with(state.selection) {
                            listOfNotNull(department, program, year, semester).joinToString("  ·  ") { it.code.ifBlank { it.name } }
                        }
                        if (context.isNotBlank()) item {
                            WorkspaceItem { StudentPill(context, StudentColors.Tint, StudentColors.Indigo) }
                        }
                        if (state.options.isEmpty() && state.error == null) item {
                            WorkspaceItem {
                                StudentEmpty("No options are available.",
                                    "Return to the profile and check the academic setup for this student.", mark)
                            }
                        }
                        items(state.options, key = { it.id }) { option ->
                            WorkspaceItem { StudentTile(option.name, option.code, mark) { model.choose(option) } }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }

        endConfirmation?.let { enrollment ->
            StudentDialog("End current enrollment?", StudentMark.Alert, onClose = { endConfirmation = null }) {
                Text("This removes the enrollment from the student dashboard and keeps it in history.",
                    style = MaterialTheme.typography.bodyLarge, color = StudentColors.Muted)
                AcademicValue("Program", enrollment.program)
                Button(onClick = { endConfirmation = null; model.end(enrollment) }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = StudentColors.Red)) { Text("End enrollment") }
                OutlinedButton(onClick = { endConfirmation = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
        review?.let { request ->
            AffiliationReview(request, onClose = { review = null }) { approved, confirmed ->
                review = null
                model.decide(request, approved, confirmed)
            }
        }
    }
}

@Composable
private fun WorkspaceItem(content: @Composable () -> Unit) {
    Box(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudentProfile(student: StudentMember, busy: Boolean, onDepartment: () -> Unit, onEnroll: () -> Unit) {
    StudentPanel {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StudentIconTile(StudentMark.Person, studentInitials(student.name))
                Column(Modifier.weight(1f)) {
                    Text(student.name, style = MaterialTheme.typography.titleLarge)
                    Text(student.email, color = StudentColors.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            HorizontalDivider(color = StudentColors.Line)
            AcademicValue("Department", student.department ?: "Department unassigned")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEnroll, enabled = !busy) { Text("Enroll student") }
                OutlinedButton(onClick = onDepartment, enabled = !busy) { Text("Set department") }
            }
        }
    }
}

@Composable
private fun AffiliationStatus(request: StudentAffiliation, onRefresh: () -> Unit) {
    val pending = request.status == "PENDING"
    StudentPanel {
        Column(Modifier.fillMaxWidth().padding(19.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StudentIconTile(if (pending) StudentMark.Clock else StudentMark.College)
            StudentPill(if (pending) "Admission review pending" else "Previous request rejected",
                if (pending) StudentColors.AmberTint else StudentColors.RedTint,
                if (pending) StudentColors.Amber else StudentColors.Red)
            Text(request.college, style = MaterialTheme.typography.titleLarge)
            if (pending) {
                Text("Your college must confirm your admission before granting access.",
                    color = StudentColors.Muted, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Check request") }
            }
        }
    }
}

@Composable
private fun StudentDialog(title: String, mark: StudentMark, onClose: () -> Unit,
                          content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 22.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 460.dp).fillMaxWidth(), shape = RoundedCornerShape(25.dp),
                color = StudentColors.Paper, contentColor = StudentColors.Ink, shadowElevation = 8.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    StudentIconTile(mark)
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    content()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AffiliationReview(request: StudentAffiliation, onClose: () -> Unit, decide: (Boolean, Boolean) -> Unit) {
    var confirmed by remember(request.id) { mutableStateOf(false) }
    StudentDialog("Review affiliation", StudentMark.List, onClose) {
        AcademicValue("Student", request.name)
        Text(request.email, color = StudentColors.Muted, style = MaterialTheme.typography.bodyMedium)
        val shape = RoundedCornerShape(14.dp)
        Surface(shape = shape, color = if (confirmed) StudentColors.GreenTint else StudentColors.Canvas,
            border = BorderStroke(1.dp, if (confirmed) StudentColors.Green else StudentColors.Line)) {
            Row(Modifier.fillMaxWidth().clip(shape)
                .toggleable(confirmed, role = Role.Checkbox, onValueChange = { confirmed = it })
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(confirmed, onCheckedChange = null)
                Text("I verified this student's admission in our college records.", Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (confirmed) Text("Admission confirmation recorded for this decision.", color = StudentColors.Green,
            style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { decide(true, confirmed) }, enabled = confirmed, modifier = Modifier.fillMaxWidth()) {
            Text("Approve affiliation")
        }
        FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClose) { Text("Cancel") }
            OutlinedButton(onClick = { decide(false, false) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StudentColors.Red)) { Text("Reject") }
        }
    }
}

@Composable
private fun EnrollmentCard(enrollment: StudentEnrollment, showStatus: Boolean = false, onEnd: (() -> Unit)? = null) {
    StudentPanel {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StudentIconTile(StudentMark.Study)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (showStatus) {
                        StudentPill(if (enrollment.current) "Current enrollment" else "Past enrollment",
                            if (enrollment.current) StudentColors.GreenTint else StudentColors.Tint,
                            if (enrollment.current) StudentColors.Green else StudentColors.Indigo)
                    }
                    Text(enrollment.program, style = MaterialTheme.typography.titleLarge)
                }
            }
            HorizontalDivider(color = StudentColors.Line)
            AcademicRow("College", enrollment.college, "Department", enrollment.department)
            AcademicRow("Academic year", enrollment.academicYear, "Semester", enrollment.semester)
            AcademicValue("Division", enrollment.division)
            onEnd?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StudentColors.Red)) {
                    Text("End current enrollment")
                }
            }
        }
    }
}

@Composable
private fun AcademicRow(firstLabel: String, first: String, secondLabel: String, second: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        AcademicValue(firstLabel, first, Modifier.weight(1f))
        AcademicValue(secondLabel, second, Modifier.weight(1f))
    }
}

@Composable
private fun AcademicValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = StudentColors.Muted, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
