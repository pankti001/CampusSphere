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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.chat.chatTime
import com.campussphere.app.ui.brand.*

/** Who can see something: only my class, or my whole college. Never another college. */
@Composable
internal fun AudienceChoice(value: String, select: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Who can see it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SKILL_AUDIENCES.forEachIndexed { i, (key, label) ->
                SegmentedButton(value == key, { select(key) }, SegmentedButtonDefaults.itemShape(i, SKILL_AUDIENCES.size)) { Text(label) }
            }
        }
    }
}

private fun audienceLabel(audience: String) = if (audience == "CLASS") "My class" else "College"

private fun skillTone(status: String) = when (status) {
    "ACCEPTED", "SCHEDULED", "COMPLETED", "ACTIVE" -> Tone.Positive
    "REQUESTED", "PAUSED" -> Tone.Warning
    "REJECTED", "CANCELLED" -> Tone.Danger
    else -> Tone.Neutral
}

private fun wantStatusLabel(status: String) = when (status) { "OPEN" -> "Open"; "MATCHED" -> "Teacher found"; else -> "Closed" }
private fun wantTone(status: String) = when (status) { "OPEN" -> Tone.Accent; "MATCHED" -> Tone.Positive; else -> Tone.Neutral }

private fun facultyTone(status: String) = when (status) {
    "OPEN" -> Tone.Warning
    "ACCEPTED", "COMPLETED" -> Tone.Positive
    else -> Tone.Danger
}

private fun modeText(mode: String?, place: String?) =
    (if (mode == "ONLINE") "Online" else "In person") + (place?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

private fun plural(n: Long, one: String, many: String = one + "s") = "$n " + if (n == 1L) one else many

/** A small icon in front of one line of detail. */
@Composable
private fun SkillLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Like ConfirmDialog, but with its own wording for the dismiss button ("Keep it"). */
@Composable
private fun SkillDialog(title: String, body: String?, confirm: String, dismiss: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
                        danger: Boolean = false, confirmEnabled: Boolean = true, extra: @Composable () -> Unit = {}) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { body?.let { Text(it) }; extra() } },
        confirmButton = {
            TextButton(onConfirm, enabled = confirmEnabled, colors = if (danger) ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()) { Text(confirm) }
        },
        dismissButton = { TextButton(onDismiss) { Text(dismiss) } })
}

/** A date, start time, length, mode and place, used for group sessions. */
@Composable
internal fun TimeForm(title: String, form: SkillSessionForm, error: String?, busy: Boolean, button: String,
                      edit: (SkillSessionForm) -> Unit, send: () -> Unit) {
    InfoCard {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
        error?.let { MessageBanner(it, MessageKind.Error) }
        PrimaryButton(button, send, enabled = !busy)
    }
}

// ------------------------------------------------------------------ "I want to learn" posts

internal fun LazyListScope.wantsPage(ui: SkillUi, vm: SkillViewModel) {
    item(key = "new") { PrimaryButton("Post what I want to learn", vm::newWant, enabled = !ui.busy, icon = CampusIcons.Plus) }
    item(key = "tabs") { CampusTabs(listOf("Class & college", "My posts"), if (ui.wantsMine) 1 else 0) { vm.openWants(it == 1) } }
    if (ui.wants.isEmpty()) item(key = "empty") {
        EmptyState(if (ui.wantsMine) "No posts yet" else "Nothing asked yet",
            if (ui.wantsMine) "Post what you want to learn; classmates who know it can offer to teach you."
            else "When classmates post what they want to learn, it appears here. You can help or say \"Me too\".")
    }
    items(ui.wants, key = { "w" + it.id }) { w ->
        ListRow(w.title,
            subtitle = listOfNotNull((if (w.mine) "You" else w.student) + " · " + audienceLabel(w.audience) + " · " + chatTime(w.createdAt),
                w.details.takeIf { it.isNotBlank() }).joinToString("\n"),
            meta = "${plural(w.comments, "comment")} · ${w.supporters} me too · ${w.helpers} can teach",
            icon = CampusIcons.Megaphone,
            chip = { StatusChip(wantStatusLabel(w.status), wantTone(w.status)) },
            onClick = { if (!ui.busy) vm.openWant(w.id) })
    }
}

internal fun LazyListScope.wantPage(d: SkillWantDetail, ui: SkillUi, vm: SkillViewModel) {
    val w = d.want
    item(key = "want-summary") {
        InfoCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(skillLabel(SKILL_CATEGORIES, w.category), Tone.Accent)
                StatusChip(wantStatusLabel(w.status), wantTone(w.status))
            }
            InfoRow("Asked by", (if (w.mine) "You" else w.student) + (w.classLabel?.let { " · $it" } ?: ""))
            InfoRow("Who can see it", audienceLabel(w.audience))
            InfoRow("Posted", chatTime(w.createdAt))
            if (w.details.isNotBlank()) InfoRow("Details", w.details)
            if (d.supporterNames.isNotEmpty()) InfoRow("Also want to learn", d.supporterNames.joinToString(", "))
        }
    }
    if (w.status != "OPEN") item(key = "want-state") {
        MessageBanner(if (w.status == "MATCHED") "A teacher was chosen" else "This post is closed",
            if (w.status == "MATCHED") MessageKind.Success else MessageKind.Info)
    }
    item(key = "want-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!w.mine && w.status == "OPEN") {
                if (w.myHelp == "OFFERED") SecondaryButton("Withdraw my offer", vm::withdrawHelp, enabled = !ui.busy, danger = true,
                    icon = CampusIcons.Close)
                else if (w.myHelp == null || w.myHelp == "WITHDRAWN") PrimaryButton("I can teach this", vm::startHelp, enabled = !ui.busy,
                    icon = CampusIcons.Handshake)
                if (w.meToo) SecondaryButton("Undo me too", { vm.meToo(false) }, enabled = !ui.busy, icon = CampusIcons.Check)
                else SecondaryButton("Me too", { vm.meToo(true) }, enabled = !ui.busy, icon = CampusIcons.Plus)
            }
            if (!w.mine) SecondaryButton("Message ${w.student.substringBefore(' ')}", { vm.chatWith(w.studentId) }, enabled = !ui.busy,
                icon = CampusIcons.Chat)
            if (w.mine && w.status != "CLOSED") SecondaryButton("Close post", vm::closeWant, enabled = !ui.busy, danger = true,
                icon = CampusIcons.Archive)
        }
    }
    if (w.myHelp == "ACCEPTED") item(key = "want-chosen") {
        MessageBanner("You were chosen to teach this. See it under Requests.", MessageKind.Success)
    }
    if (w.mine && d.helpers.isNotEmpty()) {
        item(key = "helpers-header") { SectionHeader("Classmates who can teach you") }
        items(d.helpers, key = { "h" + it.id }) { h ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(h.teacher, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        h.classLabel?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(skillRatingText(h.rating, h.reviews), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (h.message.isNotBlank()) Text("“${h.message}”", style = MaterialTheme.typography.bodyMedium)
                if (h.status == "OFFERED" && w.status == "OPEN") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Choose", { vm.acceptHelper(h) }, Modifier.weight(1f), enabled = !ui.busy)
                        SecondaryButton("No thanks", { vm.declineHelper(h) }, Modifier.weight(1f), enabled = !ui.busy)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (h.status != "OFFERED") StatusChip(when (h.status) { "ACCEPTED" -> "Chosen"; "DECLINED" -> "Not chosen"; else -> "Withdrawn" },
                        if (h.status == "ACCEPTED") Tone.Positive else Tone.Neutral)
                    Spacer(Modifier.weight(1f))
                    TextButton({ vm.chatWith(h.teacherId) }, enabled = !ui.busy) {
                        Icon(CampusIcons.Chat, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Message")
                    }
                }
            }
        }
        if (d.supporterNames.isNotEmpty()) item(key = "helpers-note") {
            MessageBanner("Everyone who said \"Me too\" will learn with the classmate you choose, in one group with a group chat.",
                MessageKind.Info)
        }
    }
    item(key = "comments-header") { SectionHeader("Discussion (${d.comments.count { !it.deleted }})") }
    if (d.comments.isEmpty()) item(key = "comments-empty") {
        Text("No comments yet. Ask a question or share a tip.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(d.comments, key = { "c" + it.id }) { c ->
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (c.mine) "You" else c.author) + " · " + chatTime(c.createdAt), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (c.mine && !c.deleted) TextButton({ vm.deleteComment(c) }, enabled = !ui.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            }
            Text(if (c.deleted) "Comment removed" else c.body, style = MaterialTheme.typography.bodyMedium,
                color = if (c.deleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
    }
    if (w.status != "CLOSED") item(key = "comment-box") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(ui.commentDraft, vm::editComment, Modifier.weight(1f), label = { Text("Write a comment") }, maxLines = 4,
                shape = MaterialTheme.shapes.small)
            Button(vm::sendComment, enabled = !ui.busy && ui.commentDraft.isNotBlank(), shape = MaterialTheme.shapes.small) { Text("Send") }
        }
    }
}

@Composable
internal fun WantForm(form: SkillWantForm, error: String?, busy: Boolean, edit: (SkillWantForm) -> Unit, save: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoCard {
            OutlinedTextField(form.title, { edit(form.copy(title = it.take(80))) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("I want to learn") }, placeholder = { Text("e.g. Excel formulas") })
            Choice("Category", SKILL_CATEGORIES, form.category, allLabel = null) { it?.let { c -> edit(form.copy(category = c)) } }
            OutlinedTextField(form.details, { edit(form.copy(details = it.take(1000))) }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Details (optional)") }, placeholder = { Text("What exactly, your level, when you're free…") })
            AudienceChoice(form.audience) { edit(form.copy(audience = it)) }
        }
        error?.let { MessageBanner(it, MessageKind.Error) }
        PrimaryButton("Post", save, enabled = !busy)
    }
}

@Composable
internal fun HelpDialog(draft: SkillHelpDraft, busy: Boolean, edit: (SkillHelpDraft) -> Unit, send: () -> Unit, dismiss: () -> Unit) {
    ConfirmDialog(title = "Offer to teach",
        body = "\"${draft.want.title}\" for ${draft.want.student.substringBefore(' ')}" +
            if (draft.want.supporters > 0) " and ${draft.want.supporters} more" else "",
        confirm = "Send offer", onConfirm = send, onDismiss = dismiss, confirmEnabled = !busy,
        extra = {
            OutlinedTextField(draft.message, { edit(draft.copy(message = it)) }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("Message (optional)") }, placeholder = { Text("How you can help, when you're free…") })
        })
}

// ------------------------------------------------------------------ Groups

internal fun LazyListScope.groupsPage(ui: SkillUi, vm: SkillViewModel) {
    if (ui.groups.isEmpty()) item(key = "empty") {
        EmptyState("No learning groups", "When two or more classmates learn the same skill from one person, they form a group here, with its own group chat.")
    }
    items(ui.groups, key = { "g" + it.id }) { g ->
        ListRow(g.name,
            subtitle = (if (g.iTeach) "You teach" else "Taught by ${g.teacher}") + " · " + plural(g.members, "learner"),
            meta = g.nextSession?.let { "Next: " + skillSessionTime(it.startsAt, it.endsAt) },
            icon = CampusIcons.Users,
            chip = { StatusChip(if (g.status == "ACTIVE") "Active" else "Closed", if (g.status == "ACTIVE") Tone.Positive else Tone.Neutral) },
            onClick = { if (!ui.busy) vm.openGroup(g.id) })
    }
}

internal fun LazyListScope.groupPage(d: SkillGroupDetail, ui: SkillUi, vm: SkillViewModel) {
    val g = d.group
    item(key = "group-summary") {
        InfoCard {
            StatusChip(if (g.status == "ACTIVE") "Active" else "Closed", if (g.status == "ACTIVE") Tone.Positive else Tone.Neutral)
            InfoRow("Teacher", if (g.iTeach) "You teach this group" else g.teacher)
            InfoRow("Learners", d.members.size.toString())
        }
    }
    item(key = "group-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The teacher's main action is setting a session, so the chat is secondary for them.
            if (g.conversationId != null) {
                if (g.iTeach) SecondaryButton("Group chat", vm::openGroupChat, enabled = !ui.busy, icon = CampusIcons.Chat)
                else PrimaryButton("Group chat", vm::openGroupChat, enabled = !ui.busy, icon = CampusIcons.Chat)
            }
            if (!g.iTeach && g.status == "ACTIVE") SecondaryButton("Leave group", vm::leaveGroup, enabled = !ui.busy, danger = true,
                icon = CampusIcons.Logout)
        }
    }
    if (g.iTeach && g.status == "ACTIVE") item(key = "group-session-form") {
        val form = ui.groupSessionForm
        if (form == null) PrimaryButton("Set a group session", vm::startGroupSession, enabled = !ui.busy, icon = CampusIcons.Calendar)
        else TimeForm("Set a session for the whole group", form, ui.groupFormError, ui.busy, "Set session", vm::editGroupSession, vm::saveGroupSession)
    }
    item(key = "members-header") { SectionHeader("Learners (${d.members.size})") }
    items(d.members, key = { "m" + it.studentId }) { m -> ListRow(m.name, subtitle = m.classLabel) }
    item(key = "sessions-header") { SectionHeader("Group sessions") }
    if (d.sessions.isEmpty()) item(key = "sessions-empty") {
        Text(if (g.iTeach) "No sessions yet. Set a time that suits the group." else "No sessions yet. ${g.teacher.substringBefore(' ')} will set one.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(d.sessions, key = { "gs" + it.id }) { s ->
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(skillSessionTime(s.startsAt, s.endsAt), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                StatusChip(skillStatusLabel(s.status), skillTone(s.status))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SkillLine(if (s.mode == "ONLINE") CampusIcons.Chat else CampusIcons.Building, modeText(s.mode, s.place))
                s.cancelReason?.let {
                    if (s.status == "CANCELLED") Text("Reason: $it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (g.iTeach && s.status == "SCHEDULED") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.canComplete) PrimaryButton("Mark completed", { vm.completeGroupSession(s) }, Modifier.weight(1f), enabled = !ui.busy)
                SecondaryButton("Cancel", { vm.startGroupCancel(s) }, Modifier.weight(1f), enabled = !ui.busy, danger = true)
            }
        }
    }
}

@Composable
internal fun GroupCancelDialog(draft: GroupCancelDraft, busy: Boolean, edit: (GroupCancelDraft) -> Unit, done: () -> Unit, dismiss: () -> Unit) {
    SkillDialog(title = "Cancel this group session?", body = null, confirm = "Cancel session", dismiss = "Keep it",
        onConfirm = done, onDismiss = dismiss, danger = true, confirmEnabled = !busy) {
        OutlinedTextField(draft.reason, { edit(draft.copy(reason = it)) }, Modifier.fillMaxWidth(), minLines = 2,
            label = { Text("Reason (optional)") })
    }
}

// ------------------------------------------------------------------ Learning from faculty

internal fun LazyListScope.facultyPage(ui: SkillUi, vm: SkillViewModel) {
    item(key = "new") { PrimaryButton("Ask a faculty member to teach", vm::newFacultyAsk, enabled = !ui.busy, icon = CampusIcons.Plus) }
    item(key = "hint") {
        Text("Requests your class or college made. Tap \"Me too\" to join one; the faculty member sees how many students want it.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (ui.facultyRequests.isEmpty()) item(key = "empty") { EmptyState("No requests yet", "Ask a faculty member to teach a topic, and classmates can join.") }
    items(ui.facultyRequests, key = { "f" + it.id }) { r -> FacultyCard(r, !ui.busy) { vm.openFacultyRequest(r.id) } }
}

@Composable
internal fun FacultyCard(r: FacultyLearningRequest, enabled: Boolean, onClick: () -> Unit) {
    val time = if (r.startsAt != null && r.endsAt != null && r.status == "ACCEPTED")
        skillSessionTime(r.startsAt, r.endsAt) + (r.place?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") else null
    ListRow(r.topic,
        subtitle = "To ${r.faculty} · " + plural(r.students, "student") + (if (r.joined) " · you joined" else ""),
        meta = time,
        icon = CampusIcons.Book,
        chip = { StatusChip(facultyStatusLabel(r.status), facultyTone(r.status)) },
        onClick = { if (enabled) onClick() })
}

internal fun LazyListScope.facultyRequestPage(d: FacultyLearningDetail, ui: SkillUi, vm: SkillViewModel) {
    val r = d.request
    item(key = "faculty-summary") {
        InfoCard {
            StatusChip(facultyStatusLabel(r.status), facultyTone(r.status))
            InfoRow("Asked to", r.faculty)
            InfoRow("Asked by", (if (r.mine) "You" else r.createdBy) + " · " + chatTime(r.createdAt))
            InfoRow("Who can see it", audienceLabel(r.audience))
            if (r.details.isNotBlank()) InfoRow("Details", r.details)
            if (r.startsAt != null && r.endsAt != null && (r.status == "ACCEPTED" || r.status == "COMPLETED")) {
                InfoRow("When", skillSessionTime(r.startsAt, r.endsAt))
                InfoRow("Where", modeText(r.mode, r.place))
            }
            r.reason?.let { if (r.status == "DECLINED" || r.status == "CANCELLED") InfoRow("Reason", it) }
        }
    }
    item(key = "faculty-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val canJoin = !r.joined && (r.status == "OPEN" || r.status == "ACCEPTED")
            if (canJoin) PrimaryButton("Me too", { vm.facultyAction("join") }, enabled = !ui.busy, icon = CampusIcons.Plus)
            if (r.joined && !r.mine && (r.status == "OPEN" || r.status == "ACCEPTED"))
                SecondaryButton("Leave", { vm.facultyAction("leave") }, enabled = !ui.busy, danger = true, icon = CampusIcons.Logout)
            if (r.mine && r.status == "OPEN") SecondaryButton("Cancel request", { vm.facultyAction("cancel") }, enabled = !ui.busy,
                danger = true, icon = CampusIcons.Close)
        }
    }
    item(key = "students-header") { SectionHeader("Students who want this (${d.students.size})") }
    items(d.students, key = { "s" + it.id }) { s -> ListRow(s.name, subtitle = s.classLabel) }
}

@Composable
internal fun FacultyAskCard(form: FacultyAskForm, choices: List<SkillFacultyChoice>, error: String?, busy: Boolean,
                            edit: (FacultyAskForm) -> Unit, send: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (choices.isEmpty()) MessageBanner("No faculty members are listed for your college yet.", MessageKind.Error)
        InfoCard {
            if (choices.isNotEmpty()) {
                Choice("Faculty", choices.map { it.id to (it.name + if (it.teachesMyClass) " · your class" else "") }, form.facultyId,
                    allLabel = null) { it?.let { id -> edit(form.copy(facultyId = id)) } }
                choices.firstOrNull { it.id == form.facultyId }?.let { c ->
                    Text(if (c.teachesMyClass) "Teaches your class" + (c.subjects?.let { ": $it" } ?: "") else "Faculty of your college",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(form.topic, { edit(form.copy(topic = it.take(120))) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Topic") }, placeholder = { Text("e.g. Data structures: trees") })
            OutlinedTextField(form.details, { edit(form.copy(details = it.take(1000))) }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("Details (optional)") })
            AudienceChoice(form.audience) { edit(form.copy(audience = it)) }
        }
        MessageBanner("Classmates who can see it can join with \"Me too\". The faculty member gets a notification, and when they accept, everyone who joined is told the time.",
            MessageKind.Info)
        error?.let { MessageBanner(it, MessageKind.Error) }
        PrimaryButton("Send request", send, enabled = !busy && choices.isNotEmpty())
    }
}
