package com.campussphere.app.presentation.homework

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.api.HomeworkItem
import com.campussphere.app.data.repository.HOMEWORK_FORMATS
import com.campussphere.app.data.repository.homeworkSize
import com.campussphere.app.ui.brand.*

/** Errors and confirmations, each with an OK that clears them. Emits nothing when there is no message. */
@Composable
internal fun HomeworkMessages(error: String?, notice: String?, onDismiss: () -> Unit) {
    if (error == null && notice == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        error?.let { MessageBanner(it, MessageKind.Error, actionLabel = "OK", onAction = onDismiss) }
        notice?.let { MessageBanner(it, MessageKind.Success, actionLabel = "OK", onAction = onDismiss) }
    }
}

/** A rounded status label in the given container colour (see [statusColor]). */
@Composable
internal fun HomeworkBadge(text: String, color: Color) {
    Surface(color = color, contentColor = contentColorFor(color), shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
internal fun statusColor(status: String): Color = when (status) {
    "PUBLISHED", "EVALUATED" -> MaterialTheme.colorScheme.primaryContainer
    "DRAFT", "SUBMITTED" -> MaterialTheme.colorScheme.tertiaryContainer
    "RETURNED", "NOT_SUBMITTED" -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

/** Kit chip tone for an assignment or submission status. */
internal fun homeworkTone(status: String): Tone = when (status) {
    "EVALUATED" -> Tone.Positive
    "PUBLISHED", "SUBMITTED" -> Tone.Accent
    "DRAFT" -> Tone.Warning
    "RETURNED", "NOT_SUBMITTED" -> Tone.Danger
    else -> Tone.Neutral
}

/** Tone for a student's own state of an assignment, matching [studentWorkLabel]. */
private fun studentWorkTone(item: HomeworkItem): Tone {
    val mine = item.mine
    return when {
        mine == null && item.acceptingSubmissions -> if (item.overdue) Tone.Warning else Tone.Neutral
        mine == null -> if (item.overdue) Tone.Danger else Tone.Neutral
        mine.status == "EVALUATED" -> Tone.Positive
        mine.status == "RETURNED" -> Tone.Danger
        mine.late -> Tone.Warning
        else -> Tone.Accent
    }
}

/** One assignment in a list: what it is, when it is due, and either the class's progress or the student's own state. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeworkCard(item: HomeworkItem, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().appear(), shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLowest, border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                Icon(CampusIcons.Homework, null, Modifier.padding(8.dp).size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${item.subjectCode} · ${item.subject} · ${item.division}", style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("By ${item.faculty} · ${homeworkMarks(item.maxMarks)} marks", style = MaterialTheme.typography.bodySmall,
                    color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Issued ${homeworkTime(item.issueAt)} · Due ${homeworkTime(item.dueAt)}", style = MaterialTheme.typography.bodySmall,
                    color = scheme.outline)
                item.counts?.let { c ->
                    if (item.status != "DRAFT") Text("${c.submitted} submitted · ${c.pending} pending · ${c.late} late · " +
                        "${c.toMark} to mark · ${c.evaluated} marked", style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
                val remaining = if (item.status == "PUBLISHED") homeworkRemaining(item.dueAt) else ""
                val showStatus = item.mine == null || item.counts != null
                if (showStatus || remaining.isNotBlank() || item.counts == null) {
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (showStatus) StatusChip(homeworkStatusLabel(item.status), homeworkTone(item.status))
                        if (remaining.isNotBlank()) StatusChip(remaining, if (item.overdue) Tone.Danger else Tone.Accent)
                        if (item.counts == null) StatusChip(studentWorkLabel(item), studentWorkTone(item))
                    }
                }
            }
            Icon(CampusIcons.ChevronRight, null, Modifier.padding(top = 8.dp).size(20.dp), tint = scheme.outline)
        }
    }
}

/** The full assignment as students see it (also used for the faculty preview). */
@Composable
internal fun HomeworkDetails(item: HomeworkItem, onAttachment: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("${item.subjectCode} · ${item.subject}", style = MaterialTheme.typography.titleSmall)
            Text("${item.program} · ${item.academicYear} · ${item.semester} · ${item.division}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Set by ${item.faculty}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.status == "PUBLISHED") {
            val remaining = homeworkRemaining(item.dueAt)
            if (remaining.isNotBlank()) StatusChip(remaining, if (item.overdue) Tone.Danger else Tone.Accent)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        DetailPair("Issued", homeworkTime(item.issueAt), "Deadline", homeworkTime(item.dueAt))
        DetailPair("Maximum marks", homeworkMarks(item.maxMarks), "Size limit", homeworkSize(item.maxFileBytes.toLong()))
        InfoRow("Late work", if (item.latePolicy == "ALLOWED") "Accepted until ${homeworkTime(item.lateUntil)}, marked late" else "Not accepted")
        InfoRow("Resubmission", if (item.resubmission == "UNTIL_DEADLINE") "Allowed until the deadline" else "One submission only")
        InfoRow("File types", HOMEWORK_FORMATS.filter { it.first in item.allowedFormats }.joinToString(", ") { it.second })
        if (item.description.isNotBlank() || item.instructions.isNotBlank()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
        if (item.description.isNotBlank()) InfoRow("Description", item.description)
        if (item.instructions.isNotBlank()) InfoRow("Instructions", item.instructions)
        item.attachmentName?.let { name ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            InfoRow("Attachment", name + (item.attachmentSize?.let { " (${homeworkSize(it)})" } ?: ""))
            onAttachment?.let { SecondaryButton("Download attachment", it, icon = CampusIcons.Download) }
        }
    }
}

/** Two short details side by side. */
@Composable
private fun DetailPair(firstLabel: String, firstValue: String, secondLabel: String, secondValue: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) { InfoRow(firstLabel, firstValue) }
        Box(Modifier.weight(1f)) { InfoRow(secondLabel, secondValue) }
    }
}

/** A drop-down filter chip; null means "All" (or [allLabel]). */
@Composable
internal fun <K> HomeworkChoice(label: String, options: List<Pair<K, String>>, value: K?, enabled: Boolean = true,
                                allLabel: String? = "All", select: (K?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = options.firstOrNull { it.first == value }?.second
    Box {
        FilterChip(selected = chosen != null, onClick = { open = true }, enabled = enabled,
            label = { Text("$label: " + (chosen ?: allLabel ?: "Choose"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(CampusIcons.ChevronDown, null, Modifier.size(18.dp)) })
        DropdownMenu(open, { open = false }) {
            allLabel?.let {
                DropdownMenuItem({ Text(it, fontWeight = if (chosen == null) FontWeight.SemiBold else FontWeight.Normal) },
                    { select(null); open = false })
            }
            options.forEach { (key, text) ->
                DropdownMenuItem({ Text(text, fontWeight = if (key == value) FontWeight.SemiBold else FontWeight.Normal) },
                    { select(key); open = false })
            }
        }
    }
}

/** A stat tile: small label above a big number. */
@Composable
internal fun HomeworkStat(label: String, value: Long?, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value?.toString() ?: "–", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}
