package com.campussphere.app.presentation.skills

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.chat.chatTime
import com.campussphere.app.ui.brand.EmptyState
import com.campussphere.app.ui.brand.IconBadge
import com.campussphere.app.ui.brand.appear

/** Who can see something: only my class, or my whole college. Never another college. */
@Composable
internal fun AudienceChoice(value: String, select: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Who can see it", style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SKILL_AUDIENCES.forEachIndexed { i, (key, label) ->
                SegmentedButton(value == key, { select(key) }, SegmentedButtonDefaults.itemShape(i, SKILL_AUDIENCES.size)) { Text(label) }
            }
        }
    }
}

@Composable
private fun audienceLabel(audience: String) = if (audience == "CLASS") "🏫 My class" else "🎓 College"

@Composable
private fun InfoCard(onClick: (() -> Unit)?, enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val modifier = Modifier.fillMaxWidth().appear()
    if (onClick != null) Card(onClick = onClick, enabled = enabled, modifier = modifier, colors = colors,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content) }
    else Card(modifier, colors = colors, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** A date, start time, length, mode and place, used for group sessions. */
@Composable
internal fun TimeForm(title: String, form: SkillSessionForm, error: String?, busy: Boolean, button: String,
                      edit: (SkillSessionForm) -> Unit, send: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.date, { edit(form.copy(date = it.take(10))) }, Modifier.weight(1.3f), singleLine = true,
                    label = { Text("Date") }, placeholder = { Text("YYYY-MM-DD") })
                OutlinedTextField(form.time, { edit(form.copy(time = it.take(5))) }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Start") }, placeholder = { Text("HH:mm") })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Length", listOf(30 to "30 min", 45 to "45 min", 60 to "1 hour", 90 to "1½ hours", 120 to "2 hours",
                    180 to "3 hours", 240 to "4 hours"), form.minutes, allLabel = null) { it?.let { m -> edit(form.copy(minutes = m)) } }
                Choice("Mode", SKILL_SESSION_MODES, form.mode, allLabel = null) { it?.let { m -> edit(form.copy(mode = m)) } }
            }
            OutlinedTextField(form.place, { edit(form.copy(place = it.take(300))) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (form.mode == "ONLINE") "Meeting link (optional)" else "Place (optional)") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(send, Modifier.fillMaxWidth(), enabled = !busy) { Text(button) }
        }
    }
}

// ------------------------------------------------------------------ "I want to learn" posts

internal fun LazyListScope.wantsPage(ui: SkillUi, vm: SkillViewModel) {
    item { Button(vm::newWant, Modifier.fillMaxWidth(), enabled = !ui.busy) { Text("➕ Post what I want to learn") } }
    item {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!ui.wantsMine, { vm.openWants(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Class & college") }
            SegmentedButton(ui.wantsMine, { vm.openWants(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("My posts") }
        }
    }
    if (ui.wants.isEmpty()) item {
        EmptyState(if (ui.wantsMine) "No posts yet" else "Nothing asked yet",
            if (ui.wantsMine) "Post what you want to learn; classmates who know it can offer to teach you."
            else "When classmates post what they want to learn, it appears here. You can help or say \"Me too\".")
    }
    items(ui.wants, key = { "w" + it.id }) { w ->
        InfoCard({ vm.openWant(w.id) }, !ui.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Chip(when (w.status) { "OPEN" -> "Open"; "MATCHED" -> "Teacher found"; else -> "Closed" }, statusColor(
                    when (w.status) { "OPEN" -> "REQUESTED"; "MATCHED" -> "ACCEPTED"; else -> "CANCELLED" }))
            }
            Text((if (w.mine) "You" else w.student) + " · " + audienceLabel(w.audience) + " · " + chatTime(w.createdAt),
                style = MaterialTheme.typography.bodySmall)
            if (w.details.isNotBlank()) Text(w.details, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("💬 ${w.comments}   🙋 ${w.supporters} me too   🧑‍🏫 ${w.helpers} can teach", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

internal fun LazyListScope.wantPage(d: SkillWantDetail, ui: SkillUi, vm: SkillViewModel) {
    val w = d.want
    item {
        Card(Modifier.fillMaxWidth().appear(0), colors = CardDefaults.cardColors(containerColor = SkillTint)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconBadge(skillCategoryIcon(w.category), Color.White)
                    Text(w.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                }
                Text("Asked by " + (if (w.mine) "you" else w.student) + (w.classLabel?.let { " · $it" } ?: ""), color = Color(0xFF0F172A))
                Text(audienceLabel(w.audience) + " · " + skillLabel(SKILL_CATEGORIES, w.category) + " · " + chatTime(w.createdAt),
                    color = Color(0xFF334155))
                if (w.details.isNotBlank()) Text(w.details, color = Color(0xFF0F172A))
                if (d.supporterNames.isNotEmpty()) Text("🙋 Also want to learn: " + d.supporterNames.joinToString(", "), color = Color(0xFF334155))
                if (w.status != "OPEN") Text(if (w.status == "MATCHED") "✅ A teacher was chosen" else "This post is closed",
                    fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
            }
        }
    }
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!w.mine && w.status == "OPEN") {
                if (w.myHelp == "OFFERED") OutlinedButton(vm::withdrawHelp, enabled = !ui.busy) { Text("Withdraw my offer") }
                else if (w.myHelp == null || w.myHelp == "WITHDRAWN") Button(vm::startHelp, enabled = !ui.busy) { Text("🧑‍🏫 I can teach this") }
                if (w.meToo) OutlinedButton({ vm.meToo(false) }, enabled = !ui.busy) { Text("✓ Me too") }
                else FilledTonalButton({ vm.meToo(true) }, enabled = !ui.busy) { Text("🙋 Me too") }
            }
            if (!w.mine) OutlinedButton({ vm.chatWith(w.studentId) }, enabled = !ui.busy) { Text("💬 Message ${w.student.substringBefore(' ')}") }
            if (w.mine && w.status != "CLOSED") OutlinedButton(vm::closeWant, enabled = !ui.busy) { Text("Close post") }
        }
    }
    if (w.myHelp == "ACCEPTED") item { Text("✅ You were chosen to teach this. See it under Requests.", fontWeight = FontWeight.SemiBold) }
    if (w.mine && d.helpers.isNotEmpty()) {
        item { Text("Classmates who can teach you", style = MaterialTheme.typography.titleMedium) }
        items(d.helpers, key = { "h" + it.id }) { h ->
            InfoCard(null, true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(h.teacher, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(skillRatingText(h.rating, h.reviews), color = MaterialTheme.colorScheme.primary)
                }
                h.classLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (h.message.isNotBlank()) Text("“${h.message}”")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (h.status) {
                        "OFFERED" -> if (w.status == "OPEN") {
                            Button({ vm.acceptHelper(h) }, enabled = !ui.busy) { Text("Choose") }
                            OutlinedButton({ vm.declineHelper(h) }, enabled = !ui.busy) { Text("No thanks") }
                        }
                        else -> Chip(when (h.status) { "ACCEPTED" -> "Chosen"; "DECLINED" -> "Not chosen"; else -> "Withdrawn" },
                            statusColor(if (h.status == "ACCEPTED") "ACCEPTED" else "CANCELLED"))
                    }
                    TextButton({ vm.chatWith(h.teacherId) }, enabled = !ui.busy) { Text("💬 Message") }
                }
            }
        }
        if (d.supporterNames.isNotEmpty()) item {
            Text("Everyone who said \"Me too\" will learn with the classmate you choose, in one group with a group chat.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    item { Text("Discussion (${d.comments.count { !it.deleted }})", style = MaterialTheme.typography.titleMedium) }
    if (d.comments.isEmpty()) item { Text("No comments yet. Ask a question or share a tip.", style = MaterialTheme.typography.bodySmall) }
    items(d.comments, key = { "c" + it.id }) { c ->
        Column(Modifier.fillMaxWidth().appear()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (c.mine) "You" else c.author) + " · " + chatTime(c.createdAt), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (c.mine && !c.deleted) TextButton({ vm.deleteComment(c) }, enabled = !ui.busy) { Text("Delete") }
            }
            Text(if (c.deleted) "Comment removed" else c.body, style = MaterialTheme.typography.bodyMedium,
                color = if (c.deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            HorizontalDivider(Modifier.padding(top = 6.dp))
        }
    }
    if (w.status != "CLOSED") item {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ui.commentDraft, vm::editComment, Modifier.weight(1f), label = { Text("Write a comment") }, maxLines = 4)
            Button(vm::sendComment, enabled = !ui.busy && ui.commentDraft.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
internal fun WantForm(form: SkillWantForm, error: String?, busy: Boolean, edit: (SkillWantForm) -> Unit, save: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(form.title, { edit(form.copy(title = it.take(80))) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("I want to learn…") }, placeholder = { Text("e.g. Excel formulas") })
        Choice("Category", SKILL_CATEGORIES, form.category, allLabel = null) { it?.let { c -> edit(form.copy(category = c)) } }
        OutlinedTextField(form.details, { edit(form.copy(details = it.take(1000))) }, Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("Details (optional)") }, placeholder = { Text("What exactly, your level, when you are free…") })
        AudienceChoice(form.audience) { edit(form.copy(audience = it)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(save, Modifier.fillMaxWidth(), enabled = !busy) { Text("Post") }
    }
}

@Composable
internal fun HelpDialog(draft: SkillHelpDraft, busy: Boolean, edit: (SkillHelpDraft) -> Unit, send: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Offer to teach") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\"${draft.want.title}\" for ${draft.want.student.substringBefore(' ')}" +
                    if (draft.want.supporters > 0) " and ${draft.want.supporters} more" else "")
                OutlinedTextField(draft.message, { edit(draft.copy(message = it)) }, Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text("Message (optional)") }, placeholder = { Text("How you can help, when you are free…") })
            }
        },
        confirmButton = { TextButton(send, enabled = !busy) { Text("Send offer") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

// ------------------------------------------------------------------ Groups

internal fun LazyListScope.groupsPage(ui: SkillUi, vm: SkillViewModel) {
    if (ui.groups.isEmpty()) item {
        EmptyState("No learning groups", "When two or more classmates learn the same skill from one person, they form a group here, with its own group chat.")
    }
    items(ui.groups, key = { "g" + it.id }) { g ->
        InfoCard({ vm.openGroup(g.id) }, !ui.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip(if (g.status == "ACTIVE") "Active" else "Closed", statusColor(if (g.status == "ACTIVE") "ACCEPTED" else "CANCELLED"))
            }
            Text((if (g.iTeach) "You teach" else "Taught by ${g.teacher}") + " · ${g.members} learner" + if (g.members == 1L) "" else "s",
                style = MaterialTheme.typography.bodyMedium)
            g.nextSession?.let { Text("Next: " + skillSessionTime(it.startsAt, it.endsAt), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal fun LazyListScope.groupPage(d: SkillGroupDetail, ui: SkillUi, vm: SkillViewModel) {
    val g = d.group
    item {
        Column(Modifier.appear(0), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (g.iTeach) "You teach this group" else "Taught by ${g.teacher}", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (g.conversationId != null) Button(vm::openGroupChat, enabled = !ui.busy) { Text("💬 Group chat") }
                if (!g.iTeach && g.status == "ACTIVE") OutlinedButton(vm::leaveGroup, enabled = !ui.busy) {
                    Text("Leave group", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    item { Text("Learners (${d.members.size})", style = MaterialTheme.typography.titleMedium) }
    items(d.members, key = { "m" + it.studentId }) { m ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name)
                m.classLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (g.iTeach && g.status == "ACTIVE") item {
        val form = ui.groupSessionForm
        if (form == null) Button(vm::startGroupSession, Modifier.fillMaxWidth(), enabled = !ui.busy) { Text("📅 Set a group session") }
        else TimeForm("Set a session for the whole group", form, ui.groupFormError, ui.busy, "Set session", vm::editGroupSession, vm::saveGroupSession)
    }
    item { Text("Group sessions", style = MaterialTheme.typography.titleMedium) }
    if (d.sessions.isEmpty()) item {
        Text(if (g.iTeach) "No sessions yet. Set a time that suits the group." else "No sessions yet. ${g.teacher.substringBefore(' ')} will set one.",
            style = MaterialTheme.typography.bodySmall)
    }
    items(d.sessions, key = { "gs" + it.id }) { s ->
        InfoCard(null, true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🕒 " + skillSessionTime(s.startsAt, s.endsAt), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Chip(skillStatusLabel(s.status), statusColor(s.status))
            }
            Text((if (s.mode == "ONLINE") "💻 Online" else "📍 In person") + if (s.place.isNotBlank()) " · ${s.place}" else "",
                style = MaterialTheme.typography.bodySmall)
            s.cancelReason?.let { if (s.status == "CANCELLED") Text("Reason: $it", style = MaterialTheme.typography.bodySmall) }
            if (g.iTeach && s.status == "SCHEDULED") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.canComplete) Button({ vm.completeGroupSession(s) }, enabled = !ui.busy) { Text("Mark completed") }
                TextButton({ vm.startGroupCancel(s) }, enabled = !ui.busy) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
internal fun GroupCancelDialog(draft: GroupCancelDraft, busy: Boolean, edit: (GroupCancelDraft) -> Unit, done: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Cancel this group session?") },
        text = {
            OutlinedTextField(draft.reason, { edit(draft.copy(reason = it)) }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("Reason (optional)") })
        },
        confirmButton = { TextButton(done, enabled = !busy) { Text("Cancel session") } },
        dismissButton = { TextButton(dismiss) { Text("Keep it") } })
}

// ------------------------------------------------------------------ Learning from faculty

internal fun LazyListScope.facultyPage(ui: SkillUi, vm: SkillViewModel) {
    item { Button(vm::newFacultyAsk, Modifier.fillMaxWidth(), enabled = !ui.busy) { Text("➕ Ask a faculty member to teach") } }
    item {
        Text("Requests your class or college made. Tap \"Me too\" to join one; the faculty member sees how many students want it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (ui.facultyRequests.isEmpty()) item { EmptyState("No requests yet", "Ask a faculty member to teach a topic, and classmates can join.") }
    items(ui.facultyRequests, key = { "f" + it.id }) { r -> FacultyCard(r, !ui.busy) { vm.openFacultyRequest(r.id) } }
}

@Composable
internal fun FacultyCard(r: FacultyLearningRequest, enabled: Boolean, onClick: () -> Unit) {
    InfoCard(onClick, enabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.topic, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Chip(facultyStatusLabel(r.status), statusColor(when (r.status) { "OPEN" -> "REQUESTED"; "ACCEPTED", "COMPLETED" -> "ACCEPTED"; else -> "CANCELLED" }))
        }
        Text("To ${r.faculty} · ${r.students} student" + (if (r.students == 1L) "" else "s") + (if (r.joined) " · you joined" else ""),
            style = MaterialTheme.typography.bodyMedium)
        if (r.startsAt != null && r.endsAt != null && r.status == "ACCEPTED")
            Text("🕒 " + skillSessionTime(r.startsAt, r.endsAt) + (r.place?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

internal fun LazyListScope.facultyRequestPage(d: FacultyLearningDetail, ui: SkillUi, vm: SkillViewModel) {
    val r = d.request
    item {
        Card(Modifier.fillMaxWidth().appear(0), colors = CardDefaults.cardColors(containerColor = SkillTint)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.topic, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                Text("Asked to ${r.faculty} by " + (if (r.mine) "you" else r.createdBy) + " · " + chatTime(r.createdAt), color = Color(0xFF334155))
                Text(facultyStatusLabel(r.status) + " · " + audienceLabel(r.audience), fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                if (r.details.isNotBlank()) Text(r.details, color = Color(0xFF0F172A))
                if (r.startsAt != null && r.endsAt != null && (r.status == "ACCEPTED" || r.status == "COMPLETED")) {
                    Text("🕒 " + skillSessionTime(r.startsAt, r.endsAt), color = Color(0xFF0F172A), fontWeight = FontWeight.SemiBold)
                    Text((if (r.mode == "ONLINE") "💻 Online" else "📍 In person") + (r.place?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        color = Color(0xFF0F172A))
                }
                r.reason?.let { if (r.status == "DECLINED" || r.status == "CANCELLED") Text("Reason: $it", color = Color(0xFF334155)) }
            }
        }
    }
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canJoin = !r.joined && (r.status == "OPEN" || r.status == "ACCEPTED")
            if (canJoin) Button({ vm.facultyAction("join") }, enabled = !ui.busy) { Text("🙋 Me too") }
            if (r.joined && !r.mine && (r.status == "OPEN" || r.status == "ACCEPTED"))
                OutlinedButton({ vm.facultyAction("leave") }, enabled = !ui.busy) { Text("Leave") }
            if (r.mine && r.status == "OPEN") OutlinedButton({ vm.facultyAction("cancel") }, enabled = !ui.busy) {
                Text("Cancel request", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    item { Text("Students who want this (${d.students.size})", style = MaterialTheme.typography.titleMedium) }
    items(d.students, key = { "s" + it.id }) { s ->
        Column {
            Text(s.name)
            s.classLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun FacultyAskCard(form: FacultyAskForm, choices: List<SkillFacultyChoice>, error: String?, busy: Boolean,
                            edit: (FacultyAskForm) -> Unit, send: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (choices.isEmpty()) Text("No faculty members are listed for your college yet.", color = MaterialTheme.colorScheme.error)
        else Choice("Faculty", choices.map { it.id to (it.name + if (it.teachesMyClass) " ★" else "") }, form.facultyId, allLabel = null) {
            it?.let { id -> edit(form.copy(facultyId = id)) }
        }
        choices.firstOrNull { it.id == form.facultyId }?.let { c ->
            Text(if (c.teachesMyClass) "Teaches your class" + (c.subjects?.let { ": $it" } ?: "") else "Faculty of your college",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(form.topic, { edit(form.copy(topic = it.take(120))) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Topic") }, placeholder = { Text("e.g. Data structures: trees") })
        OutlinedTextField(form.details, { edit(form.copy(details = it.take(1000))) }, Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("Details (optional)") })
        AudienceChoice(form.audience) { edit(form.copy(audience = it)) }
        Text("Classmates who can see it can join with \"Me too\". The faculty member gets a notification, and when they accept, everyone who joined is told the time.",
            style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(send, Modifier.fillMaxWidth(), enabled = !busy && choices.isNotEmpty()) { Text("Send request") }
    }
}
