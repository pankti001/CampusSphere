package com.campussphere.app.presentation.skills

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.campussphere.app.ui.brand.*

internal val SkillTint = Color(0xFFE0F2FE)

private val StarColor = Color(0xFFF59E0B)

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

    // Each page (and each tab of a page) keeps its own scroll position, so going back to a list returns to the same place.
    CampusPage(
        title = title(ui),
        subtitle = subtitle(ui),
        onBack = { if (!vm.back()) exit() },
        backEnabled = !ui.busy,
        busy = ui.busy || ui.loading,
        stateKey = stateKey(ui),
        actions = {
            if (ui.page == SkillPage.MARKET) IconButton(vm::openNotices, enabled = !ui.busy) {
                BadgedBox(badge = { if (ui.unread > 0) Badge { Text("${ui.unread}") } }) {
                    Icon(CampusIcons.Bell, "Notifications")
                }
            }
        },
    ) {
        // Messages keep their dismiss action, as before.
        ui.error?.let { text ->
            item(key = "skill-error") { MessageBanner(text, MessageKind.Error, actionLabel = "Dismiss", onAction = vm::clearMessages) }
        }
        ui.notice?.let { text ->
            item(key = "skill-notice") { MessageBanner(text, MessageKind.Success, actionLabel = "Dismiss", onAction = vm::clearMessages) }
        }
        when (ui.page) {
            SkillPage.MARKET -> market(ui, vm)
            SkillPage.OFFER -> ui.offer?.let { offerPage(it, ui, vm) }
            SkillPage.MINE -> minePage(ui, vm)
            SkillPage.FORM -> ui.form?.let { item(key = "offer-form") { OfferForm(it, ui.formError, ui.busy, vm::editForm, vm::saveOffer) } }
            SkillPage.REQUESTS -> requestsPage(ui, vm)
            SkillPage.REQUEST -> ui.request?.let { requestPage(it, ui, vm) }
            SkillPage.SESSIONS -> sessionsPage(ui, vm)
            SkillPage.NOTICES -> noticesPage(ui, vm)
            SkillPage.REVIEWS -> reviewsPage(ui)
            SkillPage.WANTS -> wantsPage(ui, vm)
            SkillPage.WANT -> ui.want?.let { wantPage(it, ui, vm) }
            SkillPage.WANT_FORM -> ui.wantForm?.let {
                item(key = "want-form") { WantForm(it, ui.wantFormError, ui.busy, vm::editWantForm, vm::saveWant) }
            }
            SkillPage.GROUPS -> groupsPage(ui, vm)
            SkillPage.GROUP -> ui.group?.let { groupPage(it, ui, vm) }
            SkillPage.FACULTY -> facultyPage(ui, vm)
            SkillPage.FACULTY_FORM -> ui.facultyForm?.let {
                item(key = "faculty-form") {
                    FacultyAskCard(it, ui.facultyChoices, ui.facultyFormError, ui.busy, vm::editFacultyForm, vm::sendFacultyAsk)
                }
            }
            SkillPage.FACULTY_REQUEST -> ui.facultyRequest?.let { facultyRequestPage(it, ui, vm) }
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
    SkillPage.WANT_FORM -> "I want to learn"
    SkillPage.GROUPS -> "Learning groups"
    SkillPage.GROUP -> ui.group?.group?.name ?: "Group"
    SkillPage.FACULTY -> "Learn from faculty"
    SkillPage.FACULTY_FORM -> "Ask a faculty member"
    SkillPage.FACULTY_REQUEST -> ui.facultyRequest?.request?.topic ?: "Request"
}

private fun subtitle(ui: SkillUi): String? = when (ui.page) {
    SkillPage.MARKET -> "Learn from classmates in your college"
    SkillPage.OFFER -> ui.offer?.let { skillLabel(SKILL_CATEGORIES, it.category) }
    SkillPage.REQUEST -> ui.request?.let { (if (it.incoming) "From " else "To ") + it.partner }
    SkillPage.GROUP -> ui.group?.group?.let { if (it.iTeach) "You teach this group" else "Taught by ${it.teacher}" }
    SkillPage.FACULTY_REQUEST -> ui.facultyRequest?.request?.let { "To ${it.faculty}" }
    else -> null
}

/** One scroll position per page, per tab and per opened item. */
private fun stateKey(ui: SkillUi): String = when (ui.page) {
    SkillPage.MARKET -> "market"
    SkillPage.OFFER -> "offer-${ui.offer?.id}"
    SkillPage.MINE -> "mine"
    SkillPage.FORM -> "offer-form-${ui.form?.id ?: "new"}"
    SkillPage.REQUESTS -> "requests-${if (ui.incoming) "in" else "out"}"
    SkillPage.REQUEST -> "request-${ui.request?.id}"
    SkillPage.SESSIONS -> "sessions-${if (ui.history) "past" else "upcoming"}"
    SkillPage.NOTICES -> "notices"
    SkillPage.REVIEWS -> "reviews-${ui.reviewsOf}"
    SkillPage.WANTS -> "wants-${if (ui.wantsMine) "mine" else "all"}"
    SkillPage.WANT -> "want-${ui.want?.want?.id}"
    SkillPage.WANT_FORM -> "want-form"
    SkillPage.GROUPS -> "groups"
    SkillPage.GROUP -> "group-${ui.group?.group?.id}"
    SkillPage.FACULTY -> "faculty"
    SkillPage.FACULTY_FORM -> "faculty-form"
    SkillPage.FACULTY_REQUEST -> "faculty-request-${ui.facultyRequest?.request?.id}"
}

// ---- Shared bits (kept for the other Skill Exchange screens) ----

@Composable
internal fun Chip(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Surface(color = color, shape = MaterialTheme.shapes.extraLarge) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
internal fun statusColor(status: String) = when (status) {
    "ACCEPTED", "SCHEDULED", "COMPLETED", "ACTIVE" -> MaterialTheme.colorScheme.primaryContainer
    "REQUESTED", "PAUSED" -> MaterialTheme.colorScheme.tertiaryContainer
    "REJECTED", "CANCELLED" -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

/** A filter-style chip that opens a short list of choices, for any key type. */
@Composable
internal fun <K> Choice(label: String, options: List<Pair<K, String>>, value: K?, allLabel: String? = "All", select: (K?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = options.firstOrNull { it.first == value }?.second
    Box {
        FilterChip(selected = chosen != null, onClick = { open = true },
            label = { Text("$label: " + (chosen ?: allLabel ?: "Choose"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(CampusIcons.ChevronDown, null, Modifier.size(18.dp)) })
        DropdownMenu(open, { open = false }) {
            allLabel?.let { DropdownMenuItem({ Text(it) }, { select(null); open = false }) }
            options.forEach { (key, text) ->
                DropdownMenuItem({ Text(text, fontWeight = if (key == value) FontWeight.SemiBold else FontWeight.Normal) },
                    { select(key); open = false })
            }
        }
    }
}

@Composable
internal fun Section(heading: String, body: String) {
    InfoRow(heading, body)
}

private fun skillTone(status: String) = when (status) {
    "ACCEPTED", "SCHEDULED", "COMPLETED", "ACTIVE" -> Tone.Positive
    "REQUESTED", "PAUSED" -> Tone.Warning
    "REJECTED", "CANCELLED" -> Tone.Danger
    else -> Tone.Neutral
}

private fun skillIcon(category: String): ImageVector = when (category) {
    "ACADEMICS", "MATHEMATICS", "SCIENCE", "LANGUAGES" -> CampusIcons.Book
    "BUSINESS" -> CampusIcons.Chart
    "DESIGN", "ARTS" -> CampusIcons.Edit
    "PROGRAMMING" -> CampusIcons.File
    else -> CampusIcons.Handshake
}

private fun modeText(mode: String, place: String) =
    (if (mode == "ONLINE") "Online" else "In person") + if (place.isNotBlank()) " · $place" else ""

private fun stars(rating: Int) = "★".repeat(rating) + "☆".repeat(5 - rating)

/** A small icon in front of one line of detail. */
@Composable
private fun SkillLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Like ConfirmDialog, but with its own wording for the dismiss button ("Keep it", "Later"). */
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

// ---- Marketplace ----

private fun LazyListScope.hubRow(key: String, title: String, subtitle: String, icon: ImageVector, busy: Boolean, open: () -> Unit) {
    item(key = "hub-$key") { ListRow(title, subtitle = subtitle, icon = icon, onClick = { if (!busy) open() }) }
}

private fun LazyListScope.market(ui: SkillUi, vm: SkillViewModel) {
    item(key = "hub-header") { SectionHeader("Your exchange") }
    hubRow("mine", "My skills", "Skills you teach classmates", CampusIcons.Checklist, ui.busy) { vm.openMine() }
    hubRow("requests", "Requests", "Asks to learn, to you and from you", CampusIcons.Handshake, ui.busy) { vm.openRequests() }
    hubRow("sessions", "Sessions", "Upcoming and past sessions", CampusIcons.Calendar, ui.busy) { vm.openSessions(false) }
    hubRow("wants", "Want to learn", "Posts from your class and college", CampusIcons.Megaphone, ui.busy) { vm.openWants(false) }
    hubRow("groups", "Learning groups", "Learn together, with a group chat", CampusIcons.Users, ui.busy) { vm.openGroups() }
    hubRow("faculty", "Learn from faculty", "Ask a faculty member to teach a topic", CampusIcons.Book, ui.busy) { vm.openFaculty() }

    item(key = "find-header") { SectionHeader("Find a skill") }
    item(key = "search") { SearchField(ui.query, vm::setQuery, "Search skills, e.g. Java or guitar") }
    item(key = "filters") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Category", SKILL_CATEGORIES, ui.category, allLabel = "All categories") { vm.setFilters(category = it) }
            ChoiceChip("Level", SKILL_LEVELS, ui.level, allLabel = "All levels") { vm.setFilters(level = it) }
            ChoiceChip("Mode", SKILL_SESSION_MODES, ui.mode, allLabel = "Any mode") { vm.setFilters(mode = it) }
        }
    }
    if (ui.marketLoaded && ui.offers.isEmpty()) item(key = "empty") {
        EmptyState("No skills found", if (ui.query.isBlank() && ui.category == null && ui.level == null && ui.mode == null)
            "Nobody else in your college offers a skill yet. Offer something you're good at, and classmates can find it here."
            else "Try another search or filter.") {
            SecondaryButton("Offer a skill", vm::newOffer, icon = CampusIcons.Plus)
        }
    }
    items(ui.offers, key = { "o" + it.id }) { OfferRow(it, !ui.busy) { vm.openOffer(it.id) } }
}

@Composable
private fun OfferRow(offer: SkillOffer, enabled: Boolean, onClick: () -> Unit) {
    val request = offer.myRequest
    ListRow(offer.name,
        subtitle = (if (offer.mine) "You" else offer.provider) + (offer.classLabel?.let { " · $it" } ?: ""),
        meta = listOf(skillRatingText(offer.rating, offer.reviews), skillLabel(SKILL_LEVELS, offer.level),
            skillLabel(SKILL_OFFER_MODES, offer.mode)).joinToString(" · "),
        icon = skillIcon(offer.category),
        chip = {
            if (request != null) StatusChip(skillStatusLabel(request), skillTone(request))
            else if (offer.mine && offer.status != "ACTIVE") StatusChip(skillStatusLabel(offer.status), skillTone(offer.status))
        },
        onClick = { if (enabled) onClick() })
}

private fun LazyListScope.offerPage(offer: SkillOffer, ui: SkillUi, vm: SkillViewModel) {
    item(key = "offer-summary") {
        InfoCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(skillLabel(SKILL_LEVELS, offer.level), Tone.Accent)
                if (offer.mine && offer.status != "ACTIVE") StatusChip(skillStatusLabel(offer.status), skillTone(offer.status))
            }
            InfoRow("Taught by", (if (offer.mine) "You" else offer.provider) + (offer.classLabel?.let { " · $it" } ?: ""))
            InfoRow("Rating", skillRatingText(offer.rating, offer.reviews))
            InfoRow("Mode", skillLabel(SKILL_OFFER_MODES, offer.mode))
            if (offer.description.isNotBlank()) InfoRow("About", offer.description)
            if (offer.teachingMethod.isNotBlank()) InfoRow("How I teach", offer.teachingMethod)
            InfoRow("Usually available", if (offer.slots.isEmpty()) "Ask in your request or chat."
                else offer.slots.joinToString("\n") { skillSlotText(it) })
        }
    }
    if (!offer.mine) item(key = "offer-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (offer.myRequest) {
                null -> PrimaryButton("Ask to learn this", vm::startAsk, enabled = !ui.busy, icon = CampusIcons.Handshake)
                else -> MessageBanner("You already have a request for this skill (${skillStatusLabel(offer.myRequest).lowercase()}). " +
                    "See it under Requests.", MessageKind.Info)
            }
            SecondaryButton("Message", { vm.chatWith(offer.providerId) }, enabled = !ui.busy, icon = CampusIcons.Chat)
            SecondaryButton("Reviews (${ui.reviews.size})", vm::openReviews, enabled = !ui.busy, icon = CampusIcons.Checklist)
        }
    }
}

@Composable
private fun AskDialog(ask: SkillAsk, myOffers: List<SkillOffer>, busy: Boolean, edit: (SkillAsk) -> Unit, send: () -> Unit,
                      dismiss: () -> Unit) {
    ConfirmDialog(title = "Learn ${ask.offer.name}", body = "Send a request to ${ask.offer.provider.substringBefore(' ')}.",
        confirm = "Send request", onConfirm = send, onDismiss = dismiss, confirmEnabled = !busy,
        extra = {
            OutlinedTextField(ask.message, { edit(ask.copy(message = it)) }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("Message to ${ask.offer.provider.substringBefore(' ')} (optional)") })
            Text("Offer a skill back (mutual exchange)", style = MaterialTheme.typography.titleSmall)
            if (myOffers.isEmpty()) Text("Add one of your own skills under My skills to offer an exchange.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Choice("I teach", myOffers.map { it.id to it.name }, ask.exchangeOfferId, allLabel = "Nothing, just learn") {
                edit(ask.copy(exchangeOfferId = it))
            }
        })
}

// ---- My skills ----

private fun LazyListScope.minePage(ui: SkillUi, vm: SkillViewModel) {
    item(key = "new") { PrimaryButton("Offer a new skill", vm::newOffer, enabled = !ui.busy, icon = CampusIcons.Plus) }
    item(key = "hint") {
        Text("Only students of your own college can see your skills.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (ui.mine.isEmpty()) item(key = "empty") { EmptyState("No skills yet", "Share what you're good at, and classmates can ask to learn it.") }
    items(ui.mine, key = { "m" + it.id }) { offer ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OfferRow(offer, !ui.busy) { vm.openOffer(offer.id) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ vm.editOffer(offer) }, enabled = !ui.busy) {
                    Icon(CampusIcons.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit")
                }
                TextButton({ vm.pause(offer) }, enabled = !ui.busy) {
                    Icon(if (offer.status == "ACTIVE") CampusIcons.Hide else CampusIcons.Eye, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (offer.status == "ACTIVE") "Pause" else "Show again")
                }
                var confirm by remember { mutableStateOf(false) }
                TextButton({ confirm = true }, enabled = !ui.busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(CampusIcons.Trash, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove")
                }
                if (confirm) ConfirmDialog("Remove ${offer.name}?", "Classmates will no longer see it. Existing requests and sessions are kept.",
                    "Remove", onConfirm = { confirm = false; vm.remove(offer) }, onDismiss = { confirm = false }, danger = true)
            }
        }
    }
}

@Composable
private fun OfferForm(form: SkillOfferForm, error: String?, busy: Boolean, edit: (SkillOfferForm) -> Unit, save: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoCard {
            OutlinedTextField(form.name, { edit(form.copy(name = it.take(60))) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Skill name") }, placeholder = { Text("e.g. Java basics") })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice("Category", SKILL_CATEGORIES, form.category, allLabel = null) { it?.let { c -> edit(form.copy(category = c)) } }
                Choice("Level", SKILL_LEVELS, form.level, allLabel = null) { it?.let { l -> edit(form.copy(level = l)) } }
                Choice("Mode", SKILL_OFFER_MODES, form.mode, allLabel = null) { it?.let { m -> edit(form.copy(mode = m)) } }
            }
            OutlinedTextField(form.description, { edit(form.copy(description = it.take(1000))) }, Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("What you will teach") })
            OutlinedTextField(form.teachingMethod, { edit(form.copy(teachingMethod = it.take(300))) }, Modifier.fillMaxWidth(), minLines = 2,
                label = { Text("How you teach (optional)") }, placeholder = { Text("Hands-on practice, short exercises…") })
            AudienceChoice(form.audience) { edit(form.copy(audience = it)) }
        }
        SectionHeader("When you're usually free")
        if (form.slots.isNotEmpty()) InfoCard {
            form.slots.forEachIndexed { index, slot ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Choice("Day", SKILL_DAYS, slot.day, allLabel = null) { d ->
                            d?.let { edit(form.copy(slots = form.slots.toMutableList().also { l -> l[index] = slot.copy(day = it) })) }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton({ edit(form.copy(slots = form.slots.filterIndexed { i, _ -> i != index })) }) {
                            Icon(CampusIcons.Close, "Remove time")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(slot.start, { t -> edit(form.copy(slots = form.slots.toMutableList().also { l -> l[index] = slot.copy(start = t.take(5)) })) },
                            Modifier.weight(1f), singleLine = true, label = { Text("From") })
                        OutlinedTextField(slot.end, { t -> edit(form.copy(slots = form.slots.toMutableList().also { l -> l[index] = slot.copy(end = t.take(5)) })) },
                            Modifier.weight(1f), singleLine = true, label = { Text("To") })
                    }
                }
            }
        }
        if (form.slots.size < 14) SecondaryButton("Add a time", { edit(form.copy(slots = form.slots + SkillSlot(1, "16:00", "17:00"))) },
            icon = CampusIcons.Plus)
        error?.let { MessageBanner(it, MessageKind.Error) }
        PrimaryButton(if (form.id == null) "Publish skill" else "Save changes", save, enabled = !busy)
    }
}

// ---- Requests ----

private fun LazyListScope.requestsPage(ui: SkillUi, vm: SkillViewModel) {
    item(key = "tabs") { CampusTabs(listOf("To me", "Sent by me"), if (ui.incoming) 0 else 1) { vm.openRequests(it == 0) } }
    if (ui.requests.isEmpty()) item(key = "empty") {
        EmptyState("No requests", if (ui.incoming) "When a classmate asks to learn one of your skills, it appears here."
            else "Find a skill in the marketplace and ask to learn it.")
    }
    items(ui.requests, key = { "r" + it.id }) { r ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ListRow(r.offerName,
                subtitle = listOfNotNull((if (r.incoming) "From " else "To ") + r.partner + " · " + chatTime(r.createdAt),
                    if (!r.exchange) null else if (r.incoming) "Exchange: ${r.learner.substringBefore(' ')} teaches you ${r.exchangeOfferName}"
                        else "Exchange: you teach ${r.exchangeOfferName}").joinToString("\n"),
                meta = r.message.takeIf { it.isNotBlank() }?.let { "“$it”" },
                icon = CampusIcons.Handshake,
                chip = { StatusChip(skillStatusLabel(r.status), skillTone(r.status)) },
                onClick = { if (!ui.busy) vm.openRequest(r.id) })
            RequestButtons(r, ui.busy, vm)
        }
    }
}

@Composable
private fun RequestButtons(r: SkillRequest, busy: Boolean, vm: SkillViewModel) {
    val canDecide = r.incoming && r.status == "REQUESTED"
    val canCancel = (!r.incoming && r.status == "REQUESTED") || r.status == "ACCEPTED"
    if (!canDecide && !canCancel) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canDecide) {
            PrimaryButton("Accept", { vm.decide(r, "accept") }, Modifier.weight(1f), enabled = !busy)
            SecondaryButton("Decline", { vm.decide(r, "reject") }, Modifier.weight(1f), enabled = !busy, danger = true)
        }
        if (canCancel) SecondaryButton("Cancel request", { vm.decide(r, "cancel") }, Modifier.weight(1f), enabled = !busy, danger = true)
    }
}

private fun LazyListScope.requestPage(r: SkillRequest, ui: SkillUi, vm: SkillViewModel) {
    item(key = "request-summary") {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (r.incoming) "${r.learner} wants to learn from you" else "You asked ${r.provider}",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                StatusChip(skillStatusLabel(r.status), skillTone(r.status))
            }
            InfoRow("Skill", r.offerName)
            r.exchangeOfferName?.let {
                InfoRow("In exchange", (if (r.incoming) r.learner.substringBefore(' ') + " teaches you" else "You teach") + " $it")
            }
            if (r.message.isNotBlank()) InfoRow("Message", "“${r.message}”")
            InfoRow("Sent", chatTime(r.createdAt))
            InfoRow("Completed sessions", r.completedSessions.toString())
        }
    }
    item(key = "request-actions") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Message ${r.partner.substringBefore(' ')}", { vm.chatWith(r.partnerId) }, enabled = !ui.busy,
                icon = CampusIcons.Chat)
            r.groupId?.let { g -> SecondaryButton("Learning group", { vm.openGroup(g) }, enabled = !ui.busy, icon = CampusIcons.Users) }
            RequestButtons(r, ui.busy, vm)
        }
    }
    if (r.status == "ACCEPTED") item(key = "session-form") {
        val form = ui.sessionForm
        if (form == null) PrimaryButton("Propose a session", vm::startSessionForm, enabled = !ui.busy, icon = CampusIcons.Calendar)
        else SessionForm(r, form, ui.sessionFormError, ui.busy, vm::editSessionForm, vm::proposeSession)
    }
    item(key = "sessions-header") { SectionHeader("Sessions") }
    if (ui.requestSessions.isEmpty()) item(key = "sessions-empty") {
        Text(if (r.status == "ACCEPTED") "No sessions yet. Propose a time that suits you both." else "No sessions.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(ui.requestSessions, key = { "s" + it.id }) { SessionCard(it, ui.busy, vm) }
}

@Composable
private fun SessionForm(r: SkillRequest, form: SkillSessionForm, error: String?, busy: Boolean,
                        edit: (SkillSessionForm) -> Unit, send: () -> Unit) {
    InfoCard {
        Text("Propose a session", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (r.exchange) Choice("Who teaches", listOf(true to "I teach (${if (r.incoming) r.offerName else r.exchangeOfferName})",
            false to "I learn (${if (r.incoming) r.exchangeOfferName else r.offerName})"), form.teach, allLabel = null) {
            it?.let { t -> edit(form.copy(teach = t)) }
        } else Text(if (r.incoming) "You teach ${r.offerName}." else "${r.provider.substringBefore(' ')} teaches you ${r.offerName}.",
            style = MaterialTheme.typography.bodyMedium)
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
        PrimaryButton("Send proposal", send, enabled = !busy)
    }
}

// ---- Sessions ----

private fun LazyListScope.sessionsPage(ui: SkillUi, vm: SkillViewModel) {
    item(key = "tabs") { CampusTabs(listOf("Upcoming", "Past"), if (ui.history) 1 else 0) { vm.openSessions(it == 1) } }
    if (ui.sessions.isEmpty()) item(key = "empty") {
        EmptyState(if (ui.history) "No past sessions" else "No upcoming sessions",
            if (ui.history) "Completed and cancelled sessions appear here." else "Accept or send a request, then propose a time.")
    }
    items(ui.sessions, key = { "s" + it.id }) { SessionCard(it, ui.busy, vm) }
}

@Composable
private fun SessionCard(s: SkillSession, busy: Boolean, vm: SkillViewModel) {
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.skill, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            StatusChip(if (s.status == "REQUESTED") (if (s.needsMyConfirmation) "Confirm?" else "Awaiting reply") else skillStatusLabel(s.status),
                skillTone(s.status))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((if (s.iTeach) "You teach " else "You learn from ") + s.partner, style = MaterialTheme.typography.bodyMedium)
            SkillLine(CampusIcons.Clock, skillSessionTime(s.startsAt, s.endsAt))
            SkillLine(if (s.mode == "ONLINE") CampusIcons.Chat else CampusIcons.Building, modeText(s.mode, s.place))
            s.cancelReason?.let {
                if (s.status == "CANCELLED") Text("Reason: $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.myRating?.let { Text("Your rating: " + stars(it), style = MaterialTheme.typography.bodySmall) }
        }
        val canCancel = s.status == "REQUESTED" || s.status == "SCHEDULED"
        if (s.needsMyConfirmation || s.canComplete || s.canReview || canCancel) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.needsMyConfirmation) PrimaryButton("Confirm", { vm.confirm(s) }, Modifier.weight(1f), enabled = !busy)
                if (s.canComplete) PrimaryButton("Mark completed", { vm.complete(s) }, Modifier.weight(1f), enabled = !busy)
                if (s.canReview) PrimaryButton("Rate ${s.partner.substringBefore(' ')}", { vm.startReview(s) }, Modifier.weight(1f), enabled = !busy)
                if (canCancel) SecondaryButton("Cancel", { vm.startCancel(s) }, Modifier.weight(1f), enabled = !busy, danger = true)
            }
        }
    }
}

@Composable
private fun SessionActionDialog(a: SkillSessionAction, busy: Boolean, edit: (SkillSessionAction) -> Unit, done: () -> Unit,
                                dismiss: () -> Unit) {
    SkillDialog(
        title = if (a.review) "Rate your session with ${a.session.partner.substringBefore(' ')}" else "Cancel this session?",
        body = if (a.review) "Your first name, rating and comment are visible to students of your college." else null,
        confirm = if (a.review) "Submit review" else "Cancel session",
        dismiss = if (a.review) "Later" else "Keep it",
        onConfirm = done, onDismiss = dismiss, danger = !a.review, confirmEnabled = !busy,
    ) {
        if (a.review) Row {
            (1..5).forEach { star ->
                TextButton({ edit(a.copy(rating = star)) }) {
                    Text(if (star <= a.rating) "★" else "☆", style = MaterialTheme.typography.headlineSmall,
                        color = if (star <= a.rating) StarColor else MaterialTheme.colorScheme.outline)
                }
            }
        }
        OutlinedTextField(a.text, { edit(a.copy(text = it)) }, Modifier.fillMaxWidth(), minLines = 2,
            label = { Text(if (a.review) "What went well? (optional)" else "Reason (optional)") })
    }
}

// ---- Notifications and reviews ----

private fun noticeIcon(kind: String): ImageVector = when {
    kind.startsWith("REQUEST") -> CampusIcons.Handshake
    kind == "SESSION_REMINDER" -> CampusIcons.Clock
    kind.startsWith("SESSION") -> CampusIcons.Calendar
    kind.startsWith("WANT") -> CampusIcons.Megaphone
    kind.startsWith("GROUP") -> CampusIcons.Users
    kind.startsWith("FACULTY") -> CampusIcons.Book
    else -> CampusIcons.Check
}

private fun LazyListScope.noticesPage(ui: SkillUi, vm: SkillViewModel) {
    if (ui.notices.isEmpty()) item(key = "empty") { EmptyState("No notifications", "Requests, sessions, reminders and reviews will appear here.") }
    items(ui.notices, key = { "n" + it.id }) { n ->
        val unread = n.readAt == null
        ListRow(n.title,
            subtitle = n.body + (n.startsAt?.takeIf { n.kind == "SESSION_REMINDER" }?.let { " · " + chatTime(it) } ?: ""),
            meta = chatTime(n.createdAt),
            icon = noticeIcon(n.kind),
            chip = { if (unread) StatusChip("New", Tone.Accent) },
            onClick = { if (!ui.busy) vm.openNotice(n) })
    }
}

private fun LazyListScope.reviewsPage(ui: SkillUi) {
    if (ui.reviews.isEmpty()) item(key = "empty") { EmptyState("No reviews yet", "Reviews appear after completed sessions.") }
    items(ui.reviews, key = { "rv" + it.id }) { r ->
        InfoCard {
            Text(stars(r.rating), color = StarColor, style = MaterialTheme.typography.titleMedium)
            Text("${r.reviewer} · ${r.skill} · ${chatTime(r.createdAt)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.comment.isNotBlank()) Text(r.comment, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
