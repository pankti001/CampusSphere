package com.campussphere.app.presentation.timetable

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Small building blocks shared by the timetable screens. */

@Composable
internal fun ChoicePicker(label: String, options: List<Pair<Long, String>>, value: Long?, enabled: Boolean = true,
                          modifier: Modifier = Modifier, select: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("$label: " + (options.firstOrNull { it.first == value }?.second ?: "Choose"), maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (id, text) -> DropdownMenuItem({ Text(text) }, { select(id); open = false }) }
        }
    }
}

@Composable
internal fun DayChips(days: List<Int>, selected: Int, select: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        days.forEach { d -> FilterChip(selected = d == selected, onClick = { select(d) }, label = { Text(dayShort(d)) }) }
    }
}

@Composable
internal fun StatusBadge(status: String) {
    val color = when (status) {
        "PUBLISHED" -> MaterialTheme.colorScheme.primaryContainer
        "DRAFT" -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Badge(status.lowercase().replaceFirstChar { it.uppercase() }, color)
}

@Composable
internal fun Badge(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun rowColor(kind: RowKind) = when (kind) {
    RowKind.LESSON -> MaterialTheme.colorScheme.surface
    RowKind.LUNCH -> MaterialTheme.colorScheme.secondaryContainer
    RowKind.BREAK -> MaterialTheme.colorScheme.surfaceVariant
    RowKind.FREE -> MaterialTheme.colorScheme.background
}

/** One period line: time on the left, what happens on the right. */
@Composable
internal fun PeriodCard(start: String, end: String, kind: RowKind, title: String, lines: List<String>,
                        highlight: Boolean = false, badge: String? = null, onClick: (() -> Unit)? = null) {
    val colors = CardDefaults.outlinedCardColors(containerColor = rowColor(kind))
    val content: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(56.dp)) {
                Text(start, fontWeight = FontWeight.SemiBold)
                Text(end, style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false),
                        fontWeight = if (kind == RowKind.LESSON) FontWeight.SemiBold else FontWeight.Normal)
                    if (highlight) Badge("Now", MaterialTheme.colorScheme.primaryContainer)
                    badge?.let { Badge(it, MaterialTheme.colorScheme.tertiaryContainer) }
                }
                lines.filter { it.isNotBlank() }.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (onClick != null) OutlinedCard(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth(), content = content)
    else OutlinedCard(colors = colors, modifier = Modifier.fillMaxWidth(), content = content)
}

@Composable
internal fun MessageCard(text: String, error: Boolean, action: Pair<String, () -> Unit>? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer
                                                          else MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(text, color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            action?.let { (label, run) -> TextButton(run) { Text(label) } }
        }
    }
}
