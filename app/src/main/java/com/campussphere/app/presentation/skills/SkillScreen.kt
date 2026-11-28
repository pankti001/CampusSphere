package com.campussphere.app.presentation.skills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.chat.chatTime
import com.campussphere.app.ui.brand.EmptyState
import com.campussphere.app.ui.brand.IconBadge
import com.campussphere.app.ui.brand.ScreenSwitch
import com.campussphere.app.ui.brand.appear

internal val SkillTint = Color(0xFFE0F2FE)

/**
 * Skill Exchange: learn from and teach classmates of the student's own college. The server decides the college
 * from the signed-in account; nothing here can reach students of another college.
 */
@Composable
fun SkillScreen(repo: SkillRepository, chats: ChatRepository, auth: () -> String, lost: (Int) -> Unit,
                openChat: (Long) -> Unit, exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<SkillViewModel>(viewModelStoreOwner = owner) { SkillViewModel(repo, chats, auth, lost, openChat) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.start() }
    BackHandler { if (!ui.busy && !vm.back()) exit() }

    ui.ask?.let { AskDialog(it, ui.mine.filter { o -> o.status == "ACTIVE" }, ui.busy, vm::editAsk, vm::sendAsk) { vm.back() } }
    ui.action?.let { SessionActionDialog(it, ui.busy, vm::editAction, vm::finishAction) { vm.back() } }
    ui.help?.let { HelpDialog(it, ui.busy, vm::editHelp, vm::sendHelp) { vm.back() } }
    ui.groupCancel?.let { GroupCancelDialog(it, ui.busy, vm::editGroupCancel, vm::finishGroupCancel) { vm.back() } }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (!vm.back()) exit() }, enabled = !ui.busy) { Text("Back") }
            Text(title(ui), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (ui.page == SkillPage.MARKET) BadgedBox(badge = { if (ui.unread > 0) Badge { Text("${ui.unread}") } }) {
                TextButton(vm::openNotices, enabled = !ui.busy) { Text("🔔") }
            }
        }
        if (ui.busy || ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        Messages(ui.error, ui.notice, vm::clearMessages)
        ScreenSwitch(ui.page, Modifier.weight(1f)) { page ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    SkillPage.MARKET -> market(ui, vm)
                    SkillPage.OFFER -> ui.offer?.let { offerPage(it, ui, vm) }
                    SkillPage.MINE -> minePage(ui, vm)
                    SkillPage.FORM -> ui.form?.let { item { OfferForm(it, ui.formError, ui.busy, vm::editForm, vm::saveOffer) } }
                    SkillPage.REQUESTS -> requestsPage(ui, vm)
                    SkillPage.REQUEST -> ui.request?.let { requestPage(it, ui, vm) }
                    SkillPage.SESSIONS -> sessionsPage(ui, vm)
                    SkillPage.NOTICES -> noticesPage(ui, vm)
                    SkillPage.REVIEWS -> reviewsPage(ui)
                    SkillPage.WANTS -> wantsPage(ui, vm)
                    SkillPage.WANT -> ui.want?.let { wantPage(it, ui, vm) }
                    SkillPage.WANT_FORM -> ui.wantForm?.let { item { WantForm(it, ui.wantFormError, ui.busy, vm::editWantForm, vm::saveWant) } }
                    SkillPage.GROUPS -> groupsPage(ui, vm)
                    SkillPage.GROUP -> ui.group?.let { groupPage(it, ui, vm) }
                    SkillPage.FACULTY -> facultyPage(ui, vm)
                    SkillPage.FACULTY_FORM -> ui.facultyForm?.let { item { FacultyAskCard(it, ui.facultyChoices, ui.facultyFormError, ui.busy,
                        vm::editFacultyForm, vm::sendFacultyAsk) } }
                    SkillPage.FACULTY_REQUEST -> ui.facultyRequest?.let { facultyRequestPage(it, ui, vm) }
                }
            }
        }
    }
}

private fun title(ui: SkillUi) = when (ui.page) {
    SkillPage.MARKET -> "Skill Exchange"
    SkillPage.OFFER -> ui.offer?.name ?: "Skill"
    SkillPage.MINE -> "My skills"
    SkillPage.FORM -> if (ui.form?.id == null) "Offer a skill" else "Edit skill"
    SkillPage.REQUESTS -> "Requests"
    SkillPage.REQUEST -> ui.request?.offerName ?: "Request"
    SkillPage.SESSIONS -> "Sessions"
    SkillPage.NOTICES -> "Notifications"
    SkillPage.REVIEWS -> "Reviews of ${ui.reviewsOf.substringBefore(' ')}"
    SkillPage.WANTS -> "Want to learn"
    SkillPage.WANT -> ui.want?.want?.title ?: "Post"
    SkillPage.WANT_FORM -> "I want to learn…"
    SkillPage.GROUPS -> "Learning groups"
    SkillPage.GROUP -> ui.group?.group?.name ?: "Group"
    SkillPage.FACULTY -> "Learn from faculty"
    SkillPage.FACULTY_FORM -> "Ask a faculty member"
    SkillPage.FACULTY_REQUEST -> ui.facultyRequest?.request?.topic ?: "Request"
}

@Composable
private fun Messages(error: String?, notice: String?, dismiss: () -> Unit) {
    listOfNotNull(error?.let { it to true }, notice?.let { it to false }).forEach { (text, bad) ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = if (bad) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f))
                TextButton(dismiss) { Text("OK") }
            }
        }
    }
}

@Composable
internal fun Chip(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun statusColor(status: String) = when (status) {
    "ACCEPTED", "SCHEDULED", "COMPLETED", "ACTIVE" -> MaterialTheme.colorScheme.primaryContainer
    "REQUESTED", "PAUSED" -> MaterialTheme.colorScheme.tertiaryContainer
    "REJECTED", "CANCELLED" -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
internal fun <K> Choice(label: String, options: List<Pair<K, String>>, value: K?, allLabel: String? = "All", select: (K?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) {
            Text("$label: " + (options.firstOrNull { it.first == value }?.second ?: allLabel ?: "Choose"), maxLines = 1)
        }
        DropdownMenu(open, { open = false }) {
            allLabel?.let { DropdownMenuItem({ Text(it) }, { select(null); open = false }) }
            options.forEach { (key, text) -> DropdownMenuItem({ Text(text) }, { select(key); open = false }) }
        }
    }
}

// ---- Marketplace ----

private fun LazyListScope.market(ui: SkillUi, vm: SkillViewModel) {
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()).appear(0), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(vm::openMine, enabled = !ui.busy) { Text("🎓 My skills") }
            FilledTonalButton({ vm.openRequests() }, enabled = !ui.busy) { Text("📨 Requests") }
            FilledTonalButton({ vm.openSessions(false) }, enabled = !ui.busy) { Text("📅 Sessions") }
            FilledTonalButton({ vm.openWants(false) }, enabled = !ui.busy) { Text("🙋 Want to learn") }
            FilledTonalButton(vm::openGroups, enabled = !ui.busy) { Text("👥 Groups") }
            FilledTonalButton(vm::openFaculty, enabled = !ui.busy) { Text("🧑‍🏫 Faculty") }
        }
    }
    item {
        OutlinedTextField(ui.query, vm::setQuery, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Search skills in your college") }, placeholder = { Text("Java, guitar, public speaking…") })
    }
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Category", SKILL_CATEGORIES, ui.category) { vm.setFilters(category = it) }
            Choice("Level", SKILL_LEVELS, ui.level) { vm.setFilters(level = it) }
            Choice("Mode", SKILL_SESSION_MODES, ui.mode) { vm.setFilters(mode = it) }
        }
    }
    if (ui.marketLoaded && ui.offers.isEmpty()) item {
        EmptyState("No skills found", if (ui.query.isBlank() && ui.category == null && ui.level == null && ui.mode == null)
            "Nobody else in your college offers a skill yet. Offer something you are good at, and classmates can find it here."
            else "Try another search or filter.") { FilledTonalButton(vm::newOffer) { Text("Offer a skill") } }
    }
    items(ui.offers, key = { it.id }) { OfferCard(it, !ui.busy) { vm.openOffer(it.id) } }
}

@Composable
private fun OfferCard(offer: SkillOffer, enabled: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().appear(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(skillCategoryIcon(offer.category), SkillTint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(offer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(skillRatingText(offer.rating, offer.reviews), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(if (offer.mine) "You" else offer.provider + (offer.classLabel?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(skillLabel(SKILL_LEVELS, offer.level))
                    Chip(skillLabel(SKILL_OFFER_MODES, offer.mode))
                    offer.myRequest?.let { Chip("Requested: " + skillStatusLabel(it), statusColor(it)) }
                    if (offer.mine && offer.status != "ACTIVE") Chip(skillStatusLabel(offer.status), statusColor(offer.status))
                }
            }
        }
    }
}

private fun LazyListScope.offerPage(offer: SkillOffer, ui: SkillUi, vm: SkillViewModel) {
    item {
        Card(Modifier.fillMaxWidth().appear(0), colors = CardDefaults.cardColors(containerColor = SkillTint)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(skillCategoryIcon(offer.category), Color.White)
                    Column(Modifier.weight(1f)) {
                        Text(offer.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                        Text(skillLabel(SKILL_CATEGORIES, offer.category), color = Color(0xFF334155))
                    }
                }
                Text("Taught by " + (if (offer.mine) "you" else offer.provider) + (offer.classLabel?.let { " · $it" } ?: ""), color = Color(0xFF0F172A))
                Text(skillRatingText(offer.rating, offer.reviews) + " · " + skillLabel(SKILL_LEVELS, offer.level) + " · " +
                    skillLabel(SKILL_OFFER_MODES, offer.mode), color = Color(0xFF334155))
            }
        }
    }
    if (offer.description.isNotBlank()) item { Section("About", offer.description) }
    if (offer.teachingMethod.isNotBlank()) item { Section("How I teach", offer.teachingMethod) }
    item {
        Section("Usually available", if (offer.slots.isEmpty()) "Ask in your request or chat." else offer.slots.joinToString("\n") { skillSlotText(it) })
    }
    if (!offer.mine) item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (offer.myRequest) {
                null -> Button(vm::startAsk, Modifier.fillMaxWidth(), enabled = !ui.busy) { Text("Ask to learn this") }
                else -> Text("You already have a request for this skill (${skillStatusLabel(offer.myRequest).lowercase()}). " +
                    "See it under Requests.", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.chatWith(offer.providerId) }, enabled = !ui.busy) { Text("💬 Message") }
                OutlinedButton(vm::openReviews, enabled = !ui.busy) { Text("Reviews (${ui.reviews.size})") }
            }
        }
    }
}

@Composable
internal fun Section(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AskDialog(ask: SkillAsk, myOffers: List<SkillOffer>, busy: Boolean, edit: (SkillAsk) -> Unit, send: () -> Unit,
                      dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Learn ${ask.offer.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(ask.message, { edit(ask.copy(message = it)) }, Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text("Message to ${ask.offer.provider.substringBefore(' ')} (optional)") })
                Text("Offer a skill back (mutual exchange)", style = MaterialTheme.typography.titleSmall)
                if (myOffers.isEmpty()) Text("Add one of your own skills under My skills to offer an exchange.",
                    style = MaterialTheme.typography.bodySmall)
                else Choice("I teach", myOffers.map { it.id to it.name }, ask.exchangeOfferId, allLabel = "Nothing, just learn") {
                    edit(ask.copy(exchangeOfferId = it))
                }
            }
        },
        confirmButton = { TextButton(send, enabled = !busy) { Text("Send request") } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

// ---- My skills ----

private fun LazyListScope.minePage(ui: SkillUi, vm: SkillViewModel) {
    item { Button(vm::newOffer, Modifier.fillMaxWidth(), enabled = !ui.busy) { Text("➕ Offer a new skill") } }
    item {
        Text("Only students of your own college can see your skills.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (ui.mine.isEmpty()) item { EmptyState("No skills yet", "Share what you are good at, and classmates can ask to learn it.") }
    items(ui.mine, key = { it.id }) { offer ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OfferCard(offer, !ui.busy) { vm.openOffer(offer.id) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ vm.editOffer(offer) }, enabled = !ui.busy) { Text("Edit") }
                TextButton({ vm.pause(offer) }, enabled = !ui.busy) { Text(if (offer.status == "ACTIVE") "Pause" else "Show again") }
                var confirm by remember { mutableStateOf(false) }
                TextButton({ confirm = true }, enabled = !ui.busy) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Remove ${offer.name}?") },
                    text = { Text("Classmates will no longer see it. Existing requests and sessions are kept.") },
                    confirmButton = { TextButton({ confirm = false; vm.remove(offer) }) { Text("Remove") } },
                    dismissButton = { TextButton({ confirm = false }) { Text("Keep") } })
            }
        }
    }
}

@Composable
private fun OfferForm(form: SkillOfferForm, error: String?, busy: Boolean, edit: (SkillOfferForm) -> Unit, save: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(form.name, { edit(form.copy(name = it.take(60))) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Skill name") }, placeholder = { Text("e.g. Java basics") })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("Category", SKILL_CATEGORIES, form.category, allLabel = null) { it?.let { c -> edit(form.copy(category = c)) } }
            Choice("Level", SKILL_LEVELS, form.level, allLabel = null) { it?.let { l -> edit(form.copy(level = l)) } }
            Choice("Mode", SKILL_OFFER_MODES, form.mode, allLabel = null) { it?.let { m -> edit(form.copy(mode = m)) } }
        }
        AudienceChoice(form.audience) { edit(form.copy(audience = it)) }
        OutlinedTextField(form.description, { edit(form.copy(description = it.take(1000))) }, Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("What you will teach") })
        OutlinedTextField(form.teachingMethod, { edit(form.copy(teachingMethod = it.take(300))) }, Modifier.fillMaxWidth(), minLines = 2,
            label = { Text("How you teach (optional)") }, placeholder = { Text("Hands-on practice, short exercises…") })
        Text("When you are usually free", style = MaterialTheme.typography.titleSmall)
        form.slots.forEachIndexed { index, slot ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Choice("Day", SKILL_DAYS, slot.day, allLabel = null) { d ->
                    d?.let { edit(form.copy(slots = form.slots.toMutableList().also { l -> l[index] = slot.copy(day = it) })) }
                }
                OutlinedTextField(slot.start, { t -> edit(form.copy(slots = form.slots.toMutableList().also { l -> l[index] = slot.copy(start = t.take(5)) })) },
                    Modifier.weight(1f), singleLine = true, label = { Text("From") })
                OutlinedTextField(slot.end, { t -> edit(form.copy(slots = form.slots.toMutableList().also { l -> l[index] = slot.copy(end = t.take(5)) })) },
                    Modifier.weight(1f), singleLine = true, label = { Text("To") })
                TextButton({ edit(form.copy(slots = form.slots.filterIndexed { i, _ -> i != index })) }) { Text("✕") }
            }
        }
        if (form.slots.size < 14) TextButton({ edit(form.copy(slots = form.slots + SkillSlot(1, "16:00", "17:00"))) }) { Text("➕ Add a time") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(save, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (form.id == null) "Publish to my college" else "Save changes") }
    }
}

// ---- Requests ----

private fun LazyListScope.requestsPage(ui: SkillUi, vm: SkillViewModel) {
    item {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(ui.incoming, { vm.openRequests(true) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("To me") }
            SegmentedButton(!ui.incoming, { vm.openRequests(false) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Sent by me") }
        }
    }
    if (ui.requests.isEmpty()) item {
        EmptyState("No requests", if (ui.incoming) "When a classmate asks to learn one of your skills, it appears here."
            else "Find a skill in the marketplace and ask to learn it.")
    }
    items(ui.requests, key = { it.id }) { r ->
        Card(onClick = { vm.openRequest(r.id) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().appear(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.offerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Chip(skillStatusLabel(r.status), statusColor(r.status))
                }
                Text((if (r.incoming) "From " else "To ") + r.partner + " · " + chatTime(r.createdAt), style = MaterialTheme.typography.bodyMedium)
                if (r.exchange) Text(if (r.incoming) "🔁 Exchange: ${r.learner.substringBefore(' ')} teaches you ${r.exchangeOfferName}"
                    else "🔁 Exchange: you teach ${r.exchangeOfferName}", style = MaterialTheme.typography.bodySmall)
                if (r.message.isNotBlank()) Text("“${r.message}”", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                RequestButtons(r, ui.busy, vm)
            }
        }
    }
}

@Composable
private fun RequestButtons(r: SkillRequest, busy: Boolean, vm: SkillViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r.incoming && r.status == "REQUESTED") {
            Button({ vm.decide(r, "accept") }, enabled = !busy) { Text("Accept") }
            OutlinedButton({ vm.decide(r, "reject") }, enabled = !busy) { Text("Decline") }
        }
        if ((!r.incoming && r.status == "REQUESTED") || r.status == "ACCEPTED") {
            TextButton({ vm.decide(r, "cancel") }, enabled = !busy) { Text("Cancel request", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun LazyListScope.requestPage(r: SkillRequest, ui: SkillUi, vm: SkillViewModel) {
    item {
        Column(Modifier.appear(0), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (r.incoming) "${r.learner} wants to learn from you" else "You asked ${r.provider}",
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Chip(skillStatusLabel(r.status), statusColor(r.status))
            }
            Text("Skill: ${r.offerName}")
            r.exchangeOfferName?.let { Text("In exchange: ${if (r.incoming) r.learner.substringBefore(' ') + " teaches you" else "you teach"} $it") }
            if (r.message.isNotBlank()) Text("“${r.message}”", style = MaterialTheme.typography.bodyMedium)
            Text("Sent ${chatTime(r.createdAt)} · ${r.completedSessions} completed session" + if (r.completedSessions == 1L) "" else "s",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.chatWith(r.partnerId) }, enabled = !ui.busy) { Text("💬 Message ${r.partner.substringBefore(' ')}") }
                r.groupId?.let { g -> FilledTonalButton({ vm.openGroup(g) }, enabled = !ui.busy) { Text("👥 Learning group") } }
            }
            RequestButtons(r, ui.busy, vm)
        }
    }
    if (r.status == "ACCEPTED") item {
        val form = ui.sessionForm
        if (form == null) Button(vm::startSessionForm, Modifier.fillMaxWidth(), enabled = !ui.busy) { Text("📅 Propose a session") }
        else SessionForm(r, form, ui.sessionFormError, ui.busy, vm::editSessionForm, vm::proposeSession)
    }
    item { Text("Sessions", style = MaterialTheme.typography.titleMedium) }
    if (ui.requestSessions.isEmpty()) item {
        Text(if (r.status == "ACCEPTED") "No sessions yet. Propose a time that suits you both." else "No sessions.",
            style = MaterialTheme.typography.bodySmall)
    }
    items(ui.requestSessions, key = { "s" + it.id }) { SessionCard(it, ui.busy, vm) }
}

@Composable
private fun SessionForm(r: SkillRequest, form: SkillSessionForm, error: String?, busy: Boolean,
                        edit: (SkillSessionForm) -> Unit, send: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Propose a session", style = MaterialTheme.typography.titleSmall)
            if (r.exchange) Choice("Who teaches", listOf(true to "I teach (${if (r.incoming) r.offerName else r.exchangeOfferName})",
                false to "I learn (${if (r.incoming) r.exchangeOfferName else r.offerName})"), form.teach, allLabel = null) {
                it?.let { t -> edit(form.copy(teach = t)) }
            } else Text(if (r.incoming) "You teach ${r.offerName}." else "${r.provider.substringBefore(' ')} teaches you ${r.offerName}.")
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
            Button(send, Modifier.fillMaxWidth(), enabled = !busy) { Text("Send proposal") }
        }
    }
}

// ---- Sessions ----

private fun LazyListScope.sessionsPage(ui: SkillUi, vm: SkillViewModel) {
    item {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(!ui.history, { vm.openSessions(false) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Upcoming") }
            SegmentedButton(ui.history, { vm.openSessions(true) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Past") }
        }
    }
    if (ui.sessions.isEmpty()) item {
        EmptyState(if (ui.history) "No past sessions" else "No upcoming sessions",
            if (ui.history) "Completed and cancelled sessions appear here." else "Accept or send a request, then propose a time.")
    }
    items(ui.sessions, key = { it.id }) { SessionCard(it, ui.busy, vm) }
}

@Composable
private fun SessionCard(s: SkillSession, busy: Boolean, vm: SkillViewModel) {
    Card(Modifier.fillMaxWidth().appear(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.skill, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip(if (s.status == "REQUESTED") (if (s.needsMyConfirmation) "Confirm?" else "Awaiting reply") else skillStatusLabel(s.status),
                    statusColor(s.status))
            }
            Text((if (s.iTeach) "You teach " else "You learn from ") + s.partner)
            Text("🕒 " + skillSessionTime(s.startsAt, s.endsAt))
            Text((if (s.mode == "ONLINE") "💻 Online" else "📍 In person") + if (s.place.isNotBlank()) " · ${s.place}" else "",
                style = MaterialTheme.typography.bodySmall)
            s.cancelReason?.let { if (s.status == "CANCELLED") Text("Reason: $it", style = MaterialTheme.typography.bodySmall) }
            s.myRating?.let { Text("Your rating: " + "★".repeat(it) + "☆".repeat(5 - it), style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.needsMyConfirmation) Button({ vm.confirm(s) }, enabled = !busy) { Text("Confirm") }
                if (s.canComplete) Button({ vm.complete(s) }, enabled = !busy) { Text("Mark completed") }
                if (s.canReview) Button({ vm.startReview(s) }, enabled = !busy) { Text("Rate ${s.partner.substringBefore(' ')}") }
                if (s.status == "REQUESTED" || s.status == "SCHEDULED") TextButton({ vm.startCancel(s) }, enabled = !busy) {
                    Text("Cancel", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SessionActionDialog(a: SkillSessionAction, busy: Boolean, edit: (SkillSessionAction) -> Unit, done: () -> Unit,
                                dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss,
        title = { Text(if (a.review) "Rate your session with ${a.session.partner.substringBefore(' ')}" else "Cancel this session?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (a.review) Row {
                    (1..5).forEach { star ->
                        TextButton({ edit(a.copy(rating = star)) }) {
                            Text(if (star <= a.rating) "★" else "☆", style = MaterialTheme.typography.headlineSmall,
                                color = if (star <= a.rating) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                OutlinedTextField(a.text, { edit(a.copy(text = it)) }, Modifier.fillMaxWidth(), minLines = 2,
                    label = { Text(if (a.review) "What went well? (optional)" else "Reason (optional)") })
                if (a.review) Text("Your first name, rating and comment are visible to students of your college.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(done, enabled = !busy) { Text(if (a.review) "Submit review" else "Cancel session") } },
        dismissButton = { TextButton(dismiss) { Text(if (a.review) "Later" else "Keep it") } })
}

// ---- Notifications and reviews ----

private fun LazyListScope.noticesPage(ui: SkillUi, vm: SkillViewModel) {
    if (ui.notices.isEmpty()) item { EmptyState("No notifications", "Requests, sessions, reminders and reviews will appear here.") }
    items(ui.notices, key = { it.id }) { n ->
        val unread = n.readAt == null
        Card(onClick = { vm.openNotice(n) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().appear(),
            colors = CardDefaults.cardColors(containerColor = if (unread) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(when {
                    n.kind.startsWith("REQUEST") -> "📨"; n.kind == "SESSION_REMINDER" -> "⏰"
                    n.kind.startsWith("SESSION") -> "📅"; n.kind.startsWith("WANT") -> "🙋"
                    n.kind.startsWith("GROUP") -> "👥"; n.kind.startsWith("FACULTY") -> "🧑‍🏫"; else -> "⭐"
                }, SkillTint)
                Column(Modifier.weight(1f)) {
                    Text(n.title, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal)
                    Text(n.body + (n.startsAt?.takeIf { n.kind == "SESSION_REMINDER" }?.let { " · " + chatTime(it) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(chatTime(n.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun LazyListScope.reviewsPage(ui: SkillUi) {
    if (ui.reviews.isEmpty()) item { EmptyState("No reviews yet", "Reviews appear after completed sessions.") }
    items(ui.reviews, key = { it.id }) { r ->
        Card(Modifier.fillMaxWidth().appear()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("★".repeat(r.rating) + "☆".repeat(5 - r.rating), color = Color(0xFFF59E0B), style = MaterialTheme.typography.titleMedium)
                Text("${r.reviewer} · ${r.skill} · ${chatTime(r.createdAt)}", style = MaterialTheme.typography.bodySmall)
                if (r.comment.isNotBlank()) Text(r.comment)
            }
        }
    }
}
