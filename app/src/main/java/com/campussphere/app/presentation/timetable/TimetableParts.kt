package com.campussphere.app.presentation.timetable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campussphere.app.ui.brand.*

/** Small building blocks shared by the timetable screens, in the CampusSphere kit's style. */

/** A field-like button showing the label and the chosen value; tapping it opens the choices. */
@Composable
internal fun ChoicePicker(label: String, options: List<Pair<Long, String>>, value: Long?, enabled: Boolean = true,
                          modifier: Modifier = Modifier, select: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = options.firstOrNull { it.first == value }?.second
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled && options.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = 0.7f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chosen ?: "Choose", style = MaterialTheme.typography.bodyLarge,
                    color = if (chosen == null) LocalContentColor.current.copy(alpha = 0.7f) else Color.Unspecified,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(CampusIcons.ChevronDown, null, Modifier.size(20.dp))
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem({ Text(text, fontWeight = if (id == value) FontWeight.SemiBold else FontWeight.Normal) },
                    { select(id); open = false })
            }
        }
    }
}

/** Weekday chips; they scroll sideways when Saturday or Sunday is shown too. */
@Composable
internal fun DayChips(days: List<Int>, selected: Int, select: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { d -> FilterChip(selected = d == selected, onClick = { select(d) }, label = { Text(dayShort(d)) }) }
    }
}

/** Draft, published or archived, as a kit status chip. */
@Composable
internal fun StatusBadge(status: String) {
    val tone = when (status) {
        "PUBLISHED" -> Tone.Positive
        "DRAFT" -> Tone.Accent
        else -> Tone.Neutral
    }
    StatusChip(status.lowercase().replaceFirstChar { it.uppercase() }, tone)
}

/** A small rounded label in the given colour. Kept for older callers; new code uses [StatusChip]. */
@Composable
internal fun Badge(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.extraLarge) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
internal fun rowColor(kind: RowKind) = when (kind) {
    RowKind.LESSON -> MaterialTheme.colorScheme.surfaceContainerLowest
    RowKind.LUNCH, RowKind.BREAK -> MaterialTheme.colorScheme.surfaceContainerLow
    RowKind.FREE -> MaterialTheme.colorScheme.background
}

/**
 * One period line, styled like the personal timetable: time on the left, subject with faculty and room on the
 * right. Lessons are bordered tiles; breaks, lunch and free gaps are light lines so lessons stand out.
 */
@Composable
internal fun PeriodCard(start: String, end: String, kind: RowKind, title: String, lines: List<String>,
                        highlight: Boolean = false, badge: String? = null, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val lesson = kind == RowKind.LESSON
    val border = when {
        highlight -> BorderStroke(2.dp, scheme.primary)
        lesson -> BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))
        else -> null
    }
    val color = if (highlight) scheme.primaryContainer.copy(alpha = 0.35f) else rowColor(kind)
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 14.dp, vertical = if (lesson) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PeriodTime(start, end, strong = lesson)
            if (!lesson) Icon(CampusIcons.Clock, null, Modifier.size(16.dp), tint = scheme.outline)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = if (lesson) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (lesson) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (lesson) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                lines.filter { it.isNotBlank() }.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (highlight || badge != null) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (highlight) StatusChip("Now", Tone.Accent)
                    badge?.let { StatusChip(it, Tone.Danger) }
                }
            }
            if (onClick != null) Icon(CampusIcons.ChevronRight, null, Modifier.size(20.dp), tint = scheme.outline)
        }
    }
    if (onClick != null) Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = color, border = border, content = content)
    else Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = color, border = border, content = content)
}

@Composable
private fun PeriodTime(start: String, end: String, strong: Boolean) {
    Column(Modifier.width(52.dp)) {
        Text(start, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(end, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** An error or a hint, in the kit's message style, with an optional action. */
@Composable
internal fun MessageCard(text: String, error: Boolean, action: Pair<String, () -> Unit>? = null) {
    MessageBanner(text, if (error) MessageKind.Error else MessageKind.Info,
        actionLabel = action?.first, onAction = action?.second)
}
