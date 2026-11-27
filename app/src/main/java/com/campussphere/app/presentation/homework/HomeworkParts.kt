package com.campussphere.app.presentation.homework

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.api.HomeworkItem
import com.campussphere.app.data.repository.HOMEWORK_FORMATS
import com.campussphere.app.data.repository.homeworkSize
import com.campussphere.app.ui.brand.appear

@Composable
internal fun HomeworkMessages(error: String?, notice: String?, onDismiss: () -> Unit) {
    error?.let {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                TextButton(onDismiss) { Text("OK") }
            }
        }
    }
    notice?.let {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(it, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                TextButton(onDismiss) { Text("OK") }
            }
        }
    }
}

@Composable
internal fun HomeworkBadge(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun statusColor(status: String): Color = when (status) {
    "PUBLISHED", "EVALUATED" -> MaterialTheme.colorScheme.primaryContainer
    "DRAFT", "SUBMITTED" -> MaterialTheme.colorScheme.tertiaryContainer
    "RETURNED", "NOT_SUBMITTED" -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

/** One assignment in a list: what it is, when it is due, and either the class's progress or the student's own state. */
@Composable
internal fun HomeworkCard(item: HomeworkItem, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().appear(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                if (item.mine == null || item.counts != null) HomeworkBadge(homeworkStatusLabel(item.status), statusColor(item.status))
            }
            Text("${item.subjectCode} · ${item.subject} · ${item.division}", style = MaterialTheme.typography.bodyMedium)
            Text("By ${item.faculty} · ${homeworkMarks(item.maxMarks)} marks", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Issued ${homeworkTime(item.issueAt)} · Due ${homeworkTime(item.dueAt)}", style = MaterialTheme.typography.bodySmall)
            if (item.status == "PUBLISHED") {
                Text(homeworkRemaining(item.dueAt), style = MaterialTheme.typography.labelLarge,
                    color = if (item.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            item.counts?.let { c ->
                if (item.status != "DRAFT") Text("${c.submitted} submitted · ${c.pending} pending · ${c.late} late · " +
                    "${c.toMark} to mark · ${c.evaluated} marked", style = MaterialTheme.typography.bodySmall)
            }
            if (item.counts == null) Text(studentWorkLabel(item), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The full assignment as students see it (also used for the faculty preview). */
@Composable
internal fun HomeworkDetails(item: HomeworkItem, onAttachment: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("${item.subjectCode} · ${item.subject}", style = MaterialTheme.typography.titleSmall)
        Text("${item.program} · ${item.academicYear} · ${item.semester} · ${item.division}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Set by ${item.faculty}", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Label("Issued", homeworkTime(item.issueAt))
        Label("Deadline", homeworkTime(item.dueAt) + if (item.status == "PUBLISHED") " (${homeworkRemaining(item.dueAt)})" else "")
        Label("Maximum marks", homeworkMarks(item.maxMarks))
        Label("Late work", if (item.latePolicy == "ALLOWED") "Accepted until ${homeworkTime(item.lateUntil)}, marked late" else "Not accepted")
        Label("Resubmission", if (item.resubmission == "UNTIL_DEADLINE") "Allowed until the deadline" else "One submission only")
        Label("File types", HOMEWORK_FORMATS.filter { it.first in item.allowedFormats }.joinToString(", ") { it.second })
        Label("Size limit", homeworkSize(item.maxFileBytes.toLong()))
        if (item.description.isNotBlank()) {
            HorizontalDivider()
            Text("Description", style = MaterialTheme.typography.titleSmall)
            Text(item.description)
        }
        if (item.instructions.isNotBlank()) {
            Text("Instructions", style = MaterialTheme.typography.titleSmall)
            Text(item.instructions)
        }
        item.attachmentName?.let { name ->
            HorizontalDivider()
            Text("Attachment: $name" + (item.attachmentSize?.let { " (${homeworkSize(it)})" } ?: ""))
            onAttachment?.let { OutlinedButton(it) { Text("Download attachment") } }
        }
    }
}

@Composable
private fun Label(name: String, value: String) {
    Row {
        Text("$name: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A drop-down choice; null means "All". */
@Composable
internal fun <K> HomeworkChoice(label: String, options: List<Pair<K, String>>, value: K?, enabled: Boolean = true,
                                allLabel: String? = "All", select: (K?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("$label: " + (options.firstOrNull { it.first == value }?.second ?: allLabel ?: "Choose"), maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            allLabel?.let { DropdownMenuItem({ Text(it) }, { select(null); open = false }) }
            options.forEach { (key, text) -> DropdownMenuItem({ Text(text) }, { select(key); open = false }) }
        }
    }
}

@Composable
internal fun HomeworkStat(label: String, value: Long?, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(value?.toString() ?: "–", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}
