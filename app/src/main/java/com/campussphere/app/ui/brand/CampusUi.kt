package com.campussphere.app.ui.brand

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The CampusSphere page kit. Every screen is built from the same few parts so the whole app looks and behaves alike:
 * a top bar with a back arrow, one scrolling column with 16 dp margins, bordered rows for lists, label/value rows for
 * details, chips for status, one style of message for errors and confirmations, and full-width buttons.
 */

/** Same top bar on every page: back arrow (when there is somewhere to go back to), title, optional actions. */
@Composable
fun CampusTopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, backEnabled: Boolean = true,
                 actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onBack, enabled = backEnabled) { Icon(CampusIcons.Back, "Back") }
                else Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                actions()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
}

/**
 * A whole page: top bar, a thin progress line while [busy], then one scrolling list. Errors and confirmations
 * appear at the top of the list in the same style everywhere.
 *
 * Each [stateKey] keeps its own scroll position, so a page that switches between a list and a detail (pass the
 * mode as the key) comes back to the same place in the list. The position also survives opening another page.
 */
@Composable
fun CampusPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    stateKey: Any = "page",
    busy: Boolean = false,
    backEnabled: Boolean = true,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    notice: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        CampusTopBar(title, onBack, subtitle, backEnabled, actions)
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxSize())
        }
        val memory = rememberSaveable(saver = ScrollMemory.Saver) { ScrollMemory() }
        val state = memory.state(stateKey.toString())
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (error != null) item(key = "campus-error") {
                MessageBanner(error, MessageKind.Error, actionLabel = if (onRetry != null) "Retry" else null,
                    onAction = onRetry, enabled = !busy)
            }
            if (notice != null) item(key = "campus-notice") { MessageBanner(notice, MessageKind.Success) }
            content()
        }
        bottomBar?.let { bar ->
            Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) { bar() }
            }
        }
    }
}

/**
 * Remembers one scroll position per view of a page (list, detail, a tab…). Saved as a flat list of
 * key, index, offset so it survives the page being closed while another page is open.
 */
internal class ScrollMemory(restored: Map<String, Pair<Int, Int>> = emptyMap()) {
    private val waiting = HashMap(restored)
    private val states = HashMap<String, LazyListState>()

    fun state(key: String): LazyListState = states.getOrPut(key) {
        waiting.remove(key)?.let { (index, offset) -> LazyListState(index, offset) } ?: LazyListState()
    }

    companion object {
        val Saver: androidx.compose.runtime.saveable.Saver<ScrollMemory, ArrayList<Any>> = androidx.compose.runtime.saveable.Saver(
            save = { memory ->
                val all = HashMap(memory.waiting)
                memory.states.forEach { (key, s) -> all[key] = s.firstVisibleItemIndex to s.firstVisibleItemScrollOffset }
                ArrayList<Any>().apply { all.forEach { (key, pos) -> add(key); add(pos.first); add(pos.second) } }
            },
            restore = { flat ->
                ScrollMemory(flat.chunked(3).associate { (key, index, offset) -> key as String to ((index as Int) to (offset as Int)) })
            })
    }
}

enum class MessageKind { Error, Success, Info }

/** One look for errors, confirmations and hints across the app. */
@Composable
fun MessageBanner(text: String, kind: MessageKind = MessageKind.Info, modifier: Modifier = Modifier,
                  actionLabel: String? = null, onAction: (() -> Unit)? = null, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val (background, foreground, icon) = when (kind) {
        MessageKind.Error -> Triple(scheme.errorContainer, scheme.onErrorContainer, CampusIcons.Warning)
        MessageKind.Success -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, CampusIcons.Check)
        MessageKind.Info -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, CampusIcons.Info)
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = background, contentColor = foreground) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(text, Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onAction, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/** A small heading above a group of rows, with an optional action on the right. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

enum class Tone { Neutral, Accent, Positive, Warning, Danger }

/** A rounded status label such as "Published", "Hidden" or "Current". */
@Composable
fun StatusChip(text: String, tone: Tone = Tone.Neutral) {
    val scheme = MaterialTheme.colorScheme
    val (background, foreground) = when (tone) {
        Tone.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        Tone.Accent -> scheme.primaryContainer to scheme.onPrimaryContainer
        Tone.Positive -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        Tone.Warning -> scheme.secondaryContainer to scheme.onSecondaryContainer
        Tone.Danger -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(shape = RoundedPill, color = background, contentColor = foreground) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private val RoundedPill = androidx.compose.foundation.shape.RoundedCornerShape(50)

/**
 * One row of a list: optional icon, a title, up to two lines under it, an optional chip and a chevron when it opens
 * something. Rows are bordered tiles so long lists stay easy to scan.
 */
@Composable
fun ListRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, meta: String? = null,
            icon: ImageVector? = null, chip: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))
    val inner: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) {
                Surface(shape = MaterialTheme.shapes.small, color = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer) {
                    Icon(icon, null, Modifier.padding(8.dp).size(20.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                meta?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            chip?.invoke()
            if (onClick != null) Icon(CampusIcons.ChevronRight, null, Modifier.size(20.dp), tint = scheme.outline)
        }
    }
    if (onClick != null) Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape,
        color = scheme.surfaceContainerLowest, border = border, content = inner)
    else Surface(modifier.fillMaxWidth(), shape = shape, color = scheme.surfaceContainerLowest, border = border, content = inner)
}

/** A bordered block holding details (label/value rows) or a form. */
@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A detail line: small grey label above its value. */
@Composable
fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The main action of a page: full width, 50 dp tall. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = enabled, shape = MaterialTheme.shapes.small) {
        icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Other actions: outlined, same size as [PrimaryButton]. Set [danger] for actions that remove or hide things. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                    icon: ImageVector? = null, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = enabled, shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color),
        border = BorderStroke(1.dp, if (enabled) color.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant)) {
        icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Search box with a magnifier; [onSearch] runs from the keyboard's search key (live filtering can use [onChange]). */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
                onSearch: (() -> Unit)? = null) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.small,
        placeholder = { Text(placeholder) }, leadingIcon = { Icon(CampusIcons.Search, null) },
        trailingIcon = if (value.isNotEmpty()) ({ IconButton({ onChange(""); onSearch?.invoke() }) { Icon(CampusIcons.Close, "Clear") } }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }))
}

/** A filter chip that opens a short list of choices; "All" (when [allLabel] is set) clears it. */
@Composable
fun ChoiceChip(label: String, options: List<Pair<String, String>>, value: String?, allLabel: String? = "All",
               select: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = options.firstOrNull { it.first == value }?.second
    Box {
        FilterChip(selected = chosen != null, onClick = { open = true },
            label = { Text(chosen ?: label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(CampusIcons.ChevronDown, null, Modifier.size(18.dp)) })
        DropdownMenu(open, { open = false }) {
            if (allLabel != null) DropdownMenuItem({ Text(allLabel) }, { select(null); open = false })
            options.forEach { (key, text) ->
                DropdownMenuItem({ Text(text, fontWeight = if (key == value) FontWeight.SemiBold else FontWeight.Normal) },
                    { select(key); open = false })
            }
        }
    }
}

/** Two to four tabs under the top bar, the same everywhere. */
@Composable
fun CampusTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    PrimaryTabRow(selected, containerColor = Color.Transparent) {
        titles.forEachIndexed { index, title ->
            Tab(selected == index, { onSelect(index) }, text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}

/** A thin wrapper so dialogs everywhere use the same wording layout and button order (cancel left, action right). */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
                  danger: Boolean = false, confirmEnabled: Boolean = true, extra: (@Composable () -> Unit)? = null) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(body); extra?.invoke() } },
        confirmButton = {
            TextButton(onConfirm, enabled = confirmEnabled, colors = if (danger) ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()) { Text(confirm) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
