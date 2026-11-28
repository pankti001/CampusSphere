package com.campussphere.app.ui.brand

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Dashboard parts shared by the student, faculty and College Admin home screens: key-number tiles, titled cards
 * and the grid of shortcuts with a count badge.
 */

/** One shortcut on a dashboard grid; [badge] shows a small count when something is new (0 hides it). */
class Shortcut(val label: String, val icon: ImageVector, val badge: Long, val onClick: () -> Unit)

/** Lays shortcuts out three to a row, every tile in a row the same height. */
@Composable
fun ShortcutGrid(shortcuts: List<Shortcut>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shortcuts.chunked(3).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { ShortcutTile(it, Modifier.weight(1f).fillMaxHeight()) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A square shortcut: icon in a soft circle, label below, a small count when something is new. */
@Composable
fun ShortcutTile(shortcut: Shortcut, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = shortcut.onClick, modifier = modifier.heightIn(min = 100.dp), shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLowest, border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))) {
        Column(Modifier.padding(vertical = 14.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Surface(shape = MaterialTheme.shapes.small, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                    Icon(shortcut.icon, null, Modifier.padding(10.dp).size(22.dp))
                }
                if (shortcut.badge > 0) Surface(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp),
                    shape = RoundedCornerShape(50), color = scheme.error, contentColor = scheme.onError) {
                    Text(if (shortcut.badge > 99) "99+" else shortcut.badge.toString(), Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(shortcut.label, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A key number with a label and a short line under it; tapping opens the related page. */
@Composable
fun NumberTile(label: String, value: String, caption: String, tone: Tone, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = when (tone) {
        Tone.Danger -> scheme.error
        Tone.Warning -> scheme.secondary
        Tone.Positive -> scheme.tertiary
        else -> scheme.primary
    }
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = accent, maxLines = 1)
            Text(caption, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A titled card on the dashboard with an optional link on the right. */
@Composable
fun HomeCard(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null,
                      content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (action != null && onAction != null) TextButton(onAction) { Text(action) }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

