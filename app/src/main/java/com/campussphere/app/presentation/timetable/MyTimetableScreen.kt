package com.campussphere.app.presentation.timetable

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Read-only timetable for faculty ("faculty") and students ("student"): today's schedule and the full week. */
@Composable
fun MyTimetableScreen(repo: TimetableRepository, area: String, auth: () -> String, lost: (Int) -> Unit, exit: () -> Unit) {
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

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(exit) { Text("Back") }
            Text(if (faculty) "My teaching timetable" else "My class timetable", style = MaterialTheme.typography.headlineMedium)
            week?.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            week?.publishedAt?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Last updated ${materialTime(it)}", style = MaterialTheme.typography.bodySmall)
                    if (updated) Badge("Updated this week", MaterialTheme.colorScheme.tertiaryContainer)
                }
            }
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        ui.error?.let { item { MessageCard(it, error = true, action = "Retry" to vm::load) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(ui.tab == MyTab.TODAY, { vm.setTab(MyTab.TODAY) }, { Text("Today") })
                FilterChip(ui.tab == MyTab.WEEK, { vm.setTab(MyTab.WEEK) }, { Text("Week") })
                Spacer(Modifier.weight(1f))
                TextButton(vm::load, enabled = !ui.loading) { Text("Refresh") }
            }
        }

        if (week != null) {
            val emptyReason = when {
                !week.enrolled -> "You are not enrolled in a class yet. Your college adds you to a division."
                week.slots.isEmpty() && faculty -> "No published classes are assigned to you yet."
                week.slots.isEmpty() -> "Your class timetable has not been published yet."
                else -> null
            }
            if (emptyReason != null) item { MessageCard(emptyReason, error = false) }
            else when (ui.tab) {
                MyTab.TODAY -> {
                    val today = ui.today
                    val date = today?.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    item {
                        Text(date?.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) ?: "Today",
                            style = MaterialTheme.typography.titleMedium)
                    }
                    val rows = today?.let { dayRows(it.schedule.slots, it.day) }.orEmpty()
                    if (rows.isEmpty()) item {
                        MessageCard(if (faculty) "You have no classes today." else "No classes today.", error = false)
                    }
                    items(rows, key = { "${it.kind}-${it.start}-${it.slot?.id}" }) { row ->
                        PeriodLine(row, faculty, isNow(row, now)) { vm.select(row.slot) }
                    }
                }
                MyTab.WEEK -> {
                    val days = shownDays(week.slots.map { it.day })
                    item { DayChips(days, ui.day) { vm.setDay(it) } }
                    val rows = dayRows(week.slots, ui.day)
                    if (rows.isEmpty()) item { MessageCard("Nothing is scheduled on ${dayName(ui.day)}.", error = false) }
                    items(rows, key = { "${it.kind}-${it.start}-${it.slot?.id}" }) { row ->
                        PeriodLine(row, faculty, false) { vm.select(row.slot) }
                    }
                }
            }
        }
    }

    ui.selected?.let { slot -> SlotDetails(slot, week?.slots.orEmpty(), faculty) { vm.select(null) } }
}

@Composable
private fun PeriodLine(row: DayRow, faculty: Boolean, now: Boolean, open: () -> Unit) {
    val slot = row.slot
    when {
        slot == null -> PeriodCard(row.start, row.end, RowKind.FREE, "Free period", emptyList())
        row.kind == RowKind.LESSON -> PeriodCard(row.start, row.end, row.kind, slotTitle(slot),
            listOf(
                if (faculty) "${slot.program} · ${slot.semester} · ${slot.division}" else slot.faculty.orEmpty(),
                slot.classroomCode?.let { "Room $it" + (slot.classroom?.let { name -> " · $name" } ?: "") }.orEmpty()),
            highlight = now,
            badge = if (recentlyUpdated(slot.publishedAt, slot.version)) "Updated" else null,
            onClick = open)
        else -> PeriodCard(row.start, row.end, row.kind, slotTitle(slot), emptyList(), highlight = now)
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
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${dayName(slot.day)} ${hhmm(slot.start)}–${hhmm(slot.end)}")
                if (!faculty) slot.faculty?.let { Text("Faculty: $it") }
                slot.classroomCode?.let { Text("Classroom: $it" + (slot.classroom?.let { n -> " ($n)" } ?: "")) }
                Text("Class: ${slot.program} · ${slot.semester} · ${slot.division}")
                if (others.isNotEmpty()) Text("Also meets: " + others.joinToString { "${dayShort(it.day)} ${hhmm(it.start)}" })
                slot.publishedAt?.let { Text("Timetable version ${slot.version}, published ${materialTime(it)}",
                    style = MaterialTheme.typography.bodySmall) }
            }
        })
}
