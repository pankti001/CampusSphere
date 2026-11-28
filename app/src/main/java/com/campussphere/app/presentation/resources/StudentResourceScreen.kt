package com.campussphere.app.presentation.resources

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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

@Composable
internal fun Choice(label: String, options: List<Pair<String, String>>, value: String?, allLabel: String? = "All",
                   modifier: Modifier = Modifier, select: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: " + (options.firstOrNull { it.first == value }?.second ?: allLabel.orEmpty()), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, { open = false }) {
            if (allLabel != null) DropdownMenuItem({ Text(allLabel) }, { select(null); open = false })
            options.forEach { (key, text) -> DropdownMenuItem({ Text(text) }, { select(key); open = false }) }
        }
    }
}

@Composable
internal fun Tag(text: String, strong: Boolean = false) {
    Surface(color = if (strong) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
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
        }, "Saved ${item.fileName}.")
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

    val previewFile = vm.preview
    val item = vm.selected
    if (previewFile != null && item != null) {
        PdfPreview(previewFile, item.title, vm::closePreview, vm::previewFailed, Modifier.safeDrawingPadding())
        return
    }

    val subject = vm.subject
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(back, enabled = !vm.busy) { Text("Back") }
            Text(when {
                item != null -> if (item.kind == "PAPER") "Question paper" else "Syllabus"
                subject != null -> "${subject.code} · ${subject.name}"
                else -> "Syllabus & question papers"
            }, style = MaterialTheme.typography.headlineMedium)
            if (vm.loading || vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            vm.error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton({
                            when {
                                item != null -> vm.open(item)
                                subject != null -> vm.refreshSubject()
                                else -> vm.load()
                            }
                        }, enabled = !vm.loading && !vm.busy) { Text("Retry") }
                    }
                }
            }
            vm.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        }

        when {
            item != null -> item { ResourceDetail(item, vm.busy, preview = { vm.startPreview(context.cacheDir) },
                download = { save.launch(item.fileName) }) }

            subject != null -> {
                item {
                    Text("${subject.semester} · ${subject.academicYear}", style = MaterialTheme.typography.bodyMedium)
                    TabRow(tab, Modifier.padding(top = 8.dp)) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Syllabus (${vm.syllabus.size})") })
                        Tab(tab == 1, { tab = 1 }, text = { Text("Question papers (${vm.papers.size}${if (vm.morePapers) "+" else ""})") })
                    }
                }
                if (tab == 0) {
                    if (vm.syllabus.isEmpty() && !vm.busy) item { Text("No syllabus has been shared for this subject yet.") }
                    items(vm.syllabus, key = { "s" + it.id }) { s ->
                        OutlinedCard(onClick = { vm.open(s) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                    Tag(if (s.current) "Current" else "Older version", strong = s.current)
                                }
                                Text(listOfNotNull("Version ${s.versionLabel}", resourceDate(s.effectiveFrom)?.let { "from $it" })
                                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                Text("By ${s.faculty} · ${materialSize(s.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                } else {
                    item {
                        var search by rememberSaveable { mutableStateOf(vm.paperFilter.query) }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Choice("Year", paperYears(vm.papers).map { it.toString() to it.toString() },
                                    vm.paperFilter.year?.toString()) { vm.paperFilter = vm.paperFilter.copy(year = it?.toInt()) }
                                Choice("Exam", EXAM_TYPES, vm.paperFilter.examType) { vm.paperFilter = vm.paperFilter.copy(examType = it) }
                            }
                            OutlinedTextField(search, { search = it.take(80); vm.paperFilter = vm.paperFilter.copy(query = search) },
                                Modifier.fillMaxWidth(), label = { Text("Search papers") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
                        }
                    }
                    val shown = vm.visiblePapers
                    if (shown.isEmpty() && !vm.busy) item {
                        Text(if (vm.papers.isEmpty()) "No question papers have been shared for this subject yet."
                            else "No question papers match these filters.")
                    }
                    items(shown, key = { "p" + it.id }) { p ->
                        OutlinedCard(onClick = { vm.open(p) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(paperHeading(p), fontWeight = FontWeight.SemiBold)
                                Text(p.title, style = MaterialTheme.typography.bodyMedium)
                                Text("By ${p.faculty} · ${materialSize(p.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (vm.morePapers) item {
                        OutlinedButton(vm::loadMorePapers, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("Load older papers") }
                    }
                }
            }

            else -> {
                if (vm.semesters.size > 1) item {
                    Choice("Semester", vm.semesters.map {
                        it.semesterId.toString() to "${it.semester} · ${it.academicYear}" + if (it.current) " (current)" else ""
                    }, vm.semesterId?.toString(), allLabel = null) { id -> id?.toLong()?.let(vm::chooseSemester) }
                } else vm.semesters.firstOrNull()?.let { only ->
                    item { Text("${only.program} · ${only.semester} · ${only.academicYear}", style = MaterialTheme.typography.bodyMedium) }
                }
                if (vm.loaded && vm.semesters.isEmpty()) item {
                    Text("You are not enrolled in a class yet, so there are no subjects to show.")
                } else if (vm.loaded && vm.subjects.isEmpty() && !vm.busy) item {
                    Text("No subjects have been set up for this semester yet.")
                }
                items(vm.subjects, key = { it.subjectId }) { s ->
                    OutlinedCard(onClick = { tab = 0; vm.openSubject(s) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${s.code} · ${s.name}", fontWeight = FontWeight.SemiBold)
                            Text(listOf(
                                if (s.syllabusVersions == 0L) "No syllabus yet" else "Syllabus" + if (s.syllabusVersions > 1) " (${s.syllabusVersions} versions)" else "",
                                when (s.papers) { 0L -> "no question papers"; 1L -> "1 question paper"; else -> "${s.papers} question papers" }
                            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceDetail(item: ResourceItem, busy: Boolean, preview: () -> Unit, download: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.kind == "PAPER") {
            Text(paperHeading(item), style = MaterialTheme.typography.titleLarge)
            Text(item.title, style = MaterialTheme.typography.titleMedium)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Tag(if (item.current) "Current" else "Older version", strong = item.current)
            }
            Text(listOfNotNull("Version ${item.versionLabel}", resourceDate(item.effectiveFrom)?.let { "effective from $it" })
                .joinToString(" · "))
        }
        Text("${item.subjectCode} · ${item.subject}", style = MaterialTheme.typography.bodyMedium)
        Text("${item.program} · ${item.semester} · ${item.academicYear}", style = MaterialTheme.typography.bodySmall)
        Text("Shared by ${item.faculty} · ${resourceTime(item.createdAt)}", style = MaterialTheme.typography.bodySmall)
        item.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
        Text("${item.fileName} · ${materialSize(item.sizeBytes)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(preview, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Preview") }
            OutlinedButton(download, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Download") }
        }
        val units = item.units.orEmpty()
        if (item.kind != "PAPER" && units.isNotEmpty()) {
            Text("Units", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            units.forEach { UnitCard(it) }
        }
    }
}

@Composable
private fun UnitCard(unit: ResourceUnit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Unit ${unit.number}: ${unit.title}", fontWeight = FontWeight.SemiBold)
            unit.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            unit.topics?.takeIf { it.isNotBlank() }?.let { Text("Topics: $it", style = MaterialTheme.typography.bodySmall) }
            unit.objectives?.takeIf { it.isNotBlank() }?.let { Text("Objectives: $it", style = MaterialTheme.typography.bodySmall) }
            unit.references?.takeIf { it.isNotBlank() }?.let { Text("References: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
