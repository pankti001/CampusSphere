package com.campussphere.app.presentation.timetable

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.TimetableSlot
import com.campussphere.app.data.repository.TimetableRepository
import com.campussphere.app.presentation.materials.materialTime
import com.campussphere.app.ui.brand.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Read-only timetable for faculty ("faculty") and students ("student"): today's schedule and the full week. */
@Composable
fun MyTimetableScreen(repo: TimetableRepository, area: String, auth: () -> String, lost: (Int) -> Unit,
                      unreadNotices: Int = 0, onNotices: (() -> Unit)? = null, exit: () -> Unit) {
    val owner = remember(area) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<MyTimetableViewModel>(viewModelStoreOwner = owner) { MyTimetableViewModel(repo, area, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val faculty = area == "faculty"
    // Reload whenever the screen comes back, so changes published by the college appear.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    BackHandler { if (ui.selected != null) vm.select(null) else exit() }

    val week = ui.week
    val now = LocalTime.now().let { it.hour * 60 + it.minute }
    val updated = when {
        week == null -> false
        faculty -> week.slots.any { recentlyUpdated(it.publishedAt, it.version) }
        else -> recentlyUpdated(week.publishedAt, week.version)
    }

    CampusPage(
        title = if (faculty) "My teaching timetable" else "My class timetable",
        subtitle = week?.title,
        onBack = exit,
        busy = ui.loading,
        // Today and each weekday keep their own scroll position.
        stateKey = "my-$area-" + if (ui.tab == MyTab.WEEK) "week-${ui.day}" else "today",
        error = ui.error,
        onRetry = vm::load,
        actions = {
            IconButton(vm::load, enabled = !ui.loading) { Icon(CampusIcons.Refresh, "Refresh") }
        },
    ) {
        week?.publishedAt?.let { published ->
            item(key = "updated") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last updated ${materialTime(published)}", Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (updated) StatusChip("Updated this week", Tone.Accent)
                }
            }
        }
        if (onNotices != null && unreadNotices > 0) item(key = "notices") {
            MessageBanner("$unreadNotices unread timetable " + (if (unreadNotices == 1) "notification" else "notifications") +
                " about your classes.", MessageKind.Info, actionLabel = "View", onAction = onNotices)
        }
        item(key = "tabs") {
            CampusTabs(listOf("Today", "Week"), if (ui.tab == MyTab.WEEK) 1 else 0) {
                vm.setTab(if (it == 1) MyTab.WEEK else MyTab.TODAY)
            }
        }

        if (week != null) {
            val empty: Pair<String, String>? = when {
                !week.enrolled -> "No class yet" to "You are not enrolled in a class yet. Your college adds you to a division."
                week.slots.isEmpty() && faculty -> "No classes yet" to "No published classes are assigned to you yet."
                week.slots.isEmpty() -> "No timetable yet" to "Your class timetable has not been published yet."
                else -> null
            }
            if (empty != null) item(key = "empty") { EmptyState(empty.first, empty.second) }
            else when (ui.tab) {
                MyTab.TODAY -> {
                    val today = ui.today
                    val date = today?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    item(key = "today-date") {
                        SectionHeader(date?.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) ?: "Today")
                    }
                    val rows = today?.let { dayRows(it.schedule.slots, it.day) }.orEmpty()
                    if (rows.isEmpty()) item(key = "today-empty") {
                        EmptyState("No classes today", if (faculty) "You have no classes today." else "Nothing is scheduled for today.")
                    }
                    items(rows, key = { "today-${it.kind}-${it.start}-${it.slot?.id}" }) { row ->
                        PeriodLine(row, faculty, isNow(row, now)) { vm.select(row.slot) }
                    }
                }
                MyTab.WEEK -> {
                    val days = shownDays(week.slots.map { it.day })
                    item(key = "days") { DaySelector(days, ui.day) { vm.setDay(it) } }
                    val rows = dayRows(week.slots, ui.day)
                    if (rows.isEmpty()) item(key = "week-empty") {
                        EmptyState("Nothing scheduled", "Nothing is scheduled on ${dayName(ui.day)}.")
                    }
                    items(rows, key = { "week-${ui.day}-${it.kind}-${it.start}-${it.slot?.id}" }) { row ->
                        PeriodLine(row, faculty, false) { vm.select(row.slot) }
                    }
                }
            }
        }
    }

    ui.selected?.let { slot -> SlotDetails(slot, week?.slots.orEmpty(), faculty) { vm.select(null) } }
}

/** Weekday chips; they scroll sideways when Saturday or Sunday is shown too. */
@Composable
private fun DaySelector(days: List<Int>, selected: Int, select: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { d ->
            FilterChip(selected = d == selected, onClick = { select(d) }, label = { Text(dayShort(d)) })
        }
    }
}

@Composable
private fun PeriodLine(row: DayRow, faculty: Boolean, now: Boolean, open: () -> Unit) {
    val slot = row.slot
    when {
        slot == null -> BreakRow(row.start, row.end, RowKind.FREE, "Free period")
        row.kind == RowKind.LESSON -> LessonRow(row.start, row.end, slotTitle(slot),
            listOf(
                if (faculty) "${slot.program} · ${slot.semester} · ${slot.division}" else slot.faculty.orEmpty(),
                slot.classroomCode?.let { "Room $it" + (slot.classroom?.let { name -> " · $name" } ?: "") }.orEmpty()),
            now = now,
            updated = recentlyUpdated(slot.publishedAt, slot.version),
            onClick = open)
        else -> BreakRow(row.start, row.end, row.kind, slotTitle(slot), now = now)
    }
}

/** A lesson: time on the left, subject with who teaches it (or which class) and the room on the right. */
@Composable
private fun LessonRow(start: String, end: String, title: String, lines: List<String>, now: Boolean, updated: Boolean,
                      onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val border = if (now) BorderStroke(2.dp, scheme.primary) else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.7f))
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (now) scheme.primaryContainer.copy(alpha = 0.35f) else scheme.surfaceContainerLowest, border = border) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeColumn(start, end, strong = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                lines.filter { it.isNotBlank() }.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (now || updated) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (now) StatusChip("Now", Tone.Accent)
                    if (updated) StatusChip("Updated", Tone.Positive)
                }
            }
            Icon(CampusIcons.ChevronRight, null, Modifier.size(20.dp), tint = scheme.outline)
        }
    }
}

/** Breaks, lunch and free gaps: a light, borderless line so lessons stand out. */
@Composable
private fun BreakRow(start: String, end: String, kind: RowKind, title: String, now: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (kind == RowKind.FREE) scheme.background else scheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeColumn(start, end, strong = false)
            Icon(CampusIcons.Clock, null, Modifier.size(16.dp), tint = scheme.outline)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now) StatusChip("Now", Tone.Accent)
        }
    }
}

@Composable
private fun TimeColumn(start: String, end: String, strong: Boolean) {
    Column(Modifier.width(52.dp)) {
        Text(start, style = MaterialTheme.typography.labelLarge,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(end, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** Subject or class details, with the other times this subject meets during the week. */
@Composable
private fun SlotDetails(slot: TimetableSlot, all: List<TimetableSlot>, faculty: Boolean, close: () -> Unit) {
    val others = all.filter { it.kind == "LESSON" && it.id != slot.id && it.subjectCode == slot.subjectCode
            && it.division == slot.division }
        .sortedWith(compareBy({ it.day }, { minutesOf(it.start) ?: 0 }))
    AlertDialog(onDismissRequest = close, confirmButton = { TextButton(close) { Text("Close") } },
        title = { Text(slotTitle(slot)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoRow("When", "${dayName(slot.day)} ${hhmm(slot.start)}–${hhmm(slot.end)}")
                if (!faculty) slot.faculty?.let { InfoRow("Faculty", it) }
                slot.classroomCode?.let { InfoRow("Classroom", it + (slot.classroom?.let { n -> " ($n)" } ?: "")) }
                InfoRow("Class", "${slot.program} · ${slot.semester} · ${slot.division}")
                if (others.isNotEmpty()) InfoRow("Also meets", others.joinToString { "${dayShort(it.day)} ${hhmm(it.start)}" })
                slot.publishedAt?.let {
                    Text("Timetable version ${slot.version}, published ${materialTime(it)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        })
}
