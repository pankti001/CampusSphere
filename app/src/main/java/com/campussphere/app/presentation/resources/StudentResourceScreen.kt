package com.campussphere.app.presentation.resources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.ResourceItem
import com.campussphere.app.data.remote.api.ResourceUnit
import com.campussphere.app.data.repository.EXAM_TYPES
import com.campussphere.app.data.repository.ResourceRepository
import com.campussphere.app.data.repository.examLabel
import com.campussphere.app.presentation.materials.materialSize
import com.campussphere.app.ui.brand.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun resourceDate(iso: String?): String? = iso?.let {
    runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)) }.getOrDefault(it)
}

/** A server timestamp shown in the phone's own time zone, e.g. "28 Nov 2026, 14:13". */
fun resourceTime(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK))
}.getOrDefault(iso)

/** One line describing a question paper, e.g. "Final exam 2025 · May". */
fun paperHeading(item: ResourceItem): String =
    listOfNotNull(examLabel(item.examType, item.examTypeLabel) + (item.examYear?.let { " $it" } ?: ""),
        item.examSession?.takeIf { it.isNotBlank() }).joinToString(" · ")

/** Status chip colours shared by the student, faculty and admin screens. */
fun resourceTone(status: String) = when (status) {
    "PUBLISHED" -> Tone.Positive
    "ARCHIVED" -> Tone.Neutral
    "HIDDEN" -> Tone.Warning
    else -> Tone.Danger
}

@Composable
fun StudentResourceScreen(repo: ResourceRepository, auth: () -> String, lost: (Int) -> Unit, exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<StudentResourceViewModel>(viewModelStoreOwner = owner) { StudentResourceViewModel(repo, auth, lost) }
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val item = vm.selected
        if (uri != null && item != null) vm.download({
            repo.download(auth(), "student", StudentResourceViewModel.kindOf(item), item.id, context.contentResolver, uri)
        }, "Saved ${item.fileName}")
    }

    LaunchedEffect(vm) {
        // Remove any preview copy left behind if the app was closed while a PDF was open.
        if (vm.preview == null) withContext(Dispatchers.IO) { ResourceRepository.clearPreviews(context.cacheDir) }
        if (!vm.loaded) vm.load()
    }
    val back: () -> Unit = {
        when {
            vm.preview != null -> vm.closePreview()
            vm.selected != null -> vm.close()
            vm.subject != null -> vm.closeSubject()
            else -> exit()
        }
    }
    BackHandler { if (!vm.busy) back() }

    // The list keeps its place while a PDF preview is open, because each view keeps its own saved state.
    val views = rememberSaveableStateHolder()
    val previewFile = vm.preview
    val item = vm.selected
    if (previewFile != null && item != null) {
        views.SaveableStateProvider("preview") {
            PdfPreview(previewFile, item.title, vm::closePreview, vm::previewFailed, Modifier.safeDrawingPadding())
        }
        return
    }

    val subject = vm.subject
    views.SaveableStateProvider("page") {
        CampusPage(
            title = when {
                item != null -> if (item.kind == "PAPER") paperHeading(item) else "Syllabus"
                subject != null -> subject.name
                else -> "Syllabus & papers"
            },
            subtitle = when {
                item != null -> "${item.subjectCode} · ${item.subject}"
                subject != null -> "${subject.code} · ${subject.semester}"
                else -> vm.semesters.firstOrNull { it.semesterId == vm.semesterId }?.let { "${it.semester} · ${it.academicYear}" }
            },
            onBack = back, backEnabled = !vm.busy, busy = vm.loading || vm.busy,
            stateKey = when {
                item != null -> "detail-${item.kind}-${item.id}"
                subject != null -> "subject-${subject.subjectId}-$tab"
                else -> "subjects-${vm.semesterId}"
            },
            error = vm.error,
            onRetry = {
                when {
                    item != null -> vm.open(item)
                    subject != null -> vm.refreshSubject()
                    else -> vm.load()
                }
            },
            notice = vm.notice,
        ) {
            when {
                item != null -> item(key = "detail") {
                    ResourceDetail(item, vm.busy, preview = { vm.startPreview(context.cacheDir) }, download = { save.launch(item.fileName) })
                }

                subject != null -> {
                    item(key = "tabs") {
                        CampusTabs(listOf("Syllabus (${vm.syllabus.size})", "Papers (${vm.papers.size}${if (vm.morePapers) "+" else ""})"), tab) { tab = it }
                    }
                    if (tab == 0) {
                        if (vm.syllabus.isEmpty() && !vm.busy) item(key = "empty") {
                            EmptyState("No syllabus yet", "Your teacher hasn't shared the syllabus for this subject.")
                        }
                        items(vm.syllabus, key = { "s" + it.id }) { s ->
                            ListRow(s.title, subtitle = listOfNotNull("Version ${s.versionLabel}", resourceDate(s.effectiveFrom)?.let { "from $it" }).joinToString(" · "),
                                meta = "${s.faculty} · ${materialSize(s.sizeBytes)}", icon = CampusIcons.File,
                                chip = { if (s.current) StatusChip("Current", Tone.Accent) }, onClick = { vm.open(s) })
                        }
                    } else {
                        item(key = "filters") {
                            var search by rememberSaveable { mutableStateOf(vm.paperFilter.query) }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SearchField(search, { search = it.take(80); vm.paperFilter = vm.paperFilter.copy(query = search) }, "Search papers")
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ChoiceChip("Year", paperYears(vm.papers).map { it.toString() to it.toString() }, vm.paperFilter.year?.toString(),
                                        allLabel = "All years") { vm.paperFilter = vm.paperFilter.copy(year = it?.toInt()) }
                                    ChoiceChip("Exam", EXAM_TYPES, vm.paperFilter.examType, allLabel = "All exams") {
                                        vm.paperFilter = vm.paperFilter.copy(examType = it)
                                    }
                                }
                            }
                        }
                        val shown = vm.visiblePapers
                        if (shown.isEmpty() && !vm.busy) item(key = "empty") {
                            if (vm.papers.isEmpty()) EmptyState("No question papers yet", "Previous papers for this subject will appear here.")
                            else EmptyState("No matching papers", "Try another year, exam or search.")
                        }
                        items(shown, key = { "p" + it.id }) { p ->
                            ListRow(paperHeading(p), subtitle = p.title, meta = "${p.faculty} · ${materialSize(p.sizeBytes)}",
                                icon = CampusIcons.File, onClick = { vm.open(p) })
                        }
                        if (vm.morePapers) item(key = "more") {
                            SecondaryButton("Load older papers", vm::loadMorePapers, enabled = !vm.busy)
                        }
                    }
                }

                else -> {
                    if (vm.semesters.size > 1) item(key = "semester") {
                        ChoiceChip("Semester", vm.semesters.map {
                            it.semesterId.toString() to "${it.semester} · ${it.academicYear}" + if (it.current) " (current)" else ""
                        }, vm.semesterId?.toString(), allLabel = null) { id -> id?.toLong()?.let(vm::chooseSemester) }
                    }
                    if (vm.loaded && vm.semesters.isEmpty()) item(key = "empty") {
                        EmptyState("No class yet", "Once you're enrolled in a class, its subjects appear here.")
                    } else if (vm.loaded && vm.subjects.isEmpty() && !vm.busy) item(key = "empty") {
                        EmptyState("No subjects yet", "Subjects for this semester haven't been set up.")
                    }
                    items(vm.subjects, key = { it.subjectId }) { s ->
                        ListRow("${s.code} · ${s.name}", subtitle = listOf(
                            if (s.syllabusVersions == 0L) "No syllabus yet" else "Syllabus available",
                            when (s.papers) { 0L -> "no papers"; 1L -> "1 paper"; else -> "${s.papers} papers" }
                        ).joinToString(" · "), icon = CampusIcons.Book, onClick = { tab = 0; vm.openSubject(s) })
                    }
                }
            }
        }
    }
}

/** The detail page of one syllabus or paper, used by students, faculty and admins. */
@Composable
internal fun ResourceSummary(item: ResourceItem) {
    InfoCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.kind == "PAPER") StatusChip(examLabel(item.examType, item.examTypeLabel), Tone.Accent)
            else if (item.current) StatusChip("Current syllabus", Tone.Accent) else StatusChip("Older version")
            if (item.status != "PUBLISHED") StatusChip(resourceStatusLabel(item.status), resourceTone(item.status))
        }
        Text(item.title, style = MaterialTheme.typography.titleMedium)
        if (item.kind == "PAPER") InfoRow("Exam", paperHeading(item))
        else InfoRow("Version", listOfNotNull(item.versionLabel, resourceDate(item.effectiveFrom)?.let { "effective from $it" }).joinToString(" · "))
        InfoRow("Subject", "${item.subjectCode} · ${item.subject}")
        InfoRow("Class", "${item.program} · ${item.semester} · ${item.academicYear}")
        InfoRow("Shared by", "${item.faculty} · ${resourceTime(item.createdAt)}")
        InfoRow("File", "${item.fileName} · ${materialSize(item.sizeBytes)}")
        item.description?.takeIf { it.isNotBlank() }?.let { InfoRow("About", it) }
    }
    item.moderationNote?.let { MessageBanner("College Admin note: $it", MessageKind.Info) }
}

@Composable
private fun ResourceDetail(item: ResourceItem, busy: Boolean, preview: () -> Unit, download: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ResourceSummary(item)
        PrimaryButton("Preview", preview, enabled = !busy, icon = CampusIcons.Eye)
        SecondaryButton("Download", download, enabled = !busy, icon = CampusIcons.Download)
        val units = item.units.orEmpty()
        if (item.kind != "PAPER" && units.isNotEmpty()) {
            SectionHeader("Units")
            units.forEach { UnitCard(it) }
        }
    }
}

@Composable
internal fun UnitCard(unit: ResourceUnit) {
    InfoCard {
        Text("Unit ${unit.number}: ${unit.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        unit.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        unit.topics?.takeIf { it.isNotBlank() }?.let { InfoRow("Topics", it) }
        unit.objectives?.takeIf { it.isNotBlank() }?.let { InfoRow("Objectives", it) }
        unit.references?.takeIf { it.isNotBlank() }?.let { InfoRow("References", it) }
    }
}
