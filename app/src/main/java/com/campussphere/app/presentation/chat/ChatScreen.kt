package com.campussphere.app.presentation.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.ChatConversation
import com.campussphere.app.data.remote.api.ChatMessage
import com.campussphere.app.data.repository.CHAT_REPORT_REASONS
import com.campussphere.app.data.repository.ChatRepository
import com.campussphere.app.data.repository.HOMEWORK_FORMATS
import com.campussphere.app.data.repository.HOMEWORK_LIMIT_BYTES
import com.campussphere.app.data.repository.HomeworkFailure
import com.campussphere.app.data.repository.HomeworkRepository
import com.campussphere.app.data.repository.homeworkPickerTypes
import com.campussphere.app.data.repository.homeworkSize
import com.campussphere.app.ui.brand.*
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

/** "14:05" today, "3 Dec 14:05" on other days. */
fun chatTime(iso: String?, zone: ZoneId = ZoneId.systemDefault(), today: java.time.LocalDate = java.time.LocalDate.now(zone)): String {
    val time = iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() } ?: return ""
    return if (time.toLocalDate() == today) time.format(CLOCK) else "${time.format(DAY)} ${time.format(CLOCK)}"
}

/**
 * Students' groups and chats. Faculty and College Admins have no way into this screen or its data.
 *
 * [closeWithChat]: the chat was opened from somewhere else (Skill Exchange), so Back from that conversation closes
 * Chats and returns there, instead of showing the list of all chats first.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(repo: ChatRepository, files: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit,
               openId: Long? = null, closeWithChat: Boolean = false, exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<ChatViewModel>(viewModelStoreOwner = owner) { ChatViewModel(repo, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var actionsFor by remember { mutableStateOf<ChatMessage?>(null) }
    var reporting by remember { mutableStateOf<ChatMessage?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var pickError by remember { mutableStateOf<String?>(null) }
    val images = remember { mutableStateMapOf<Long, ImageBitmap>() }
    var saving by remember { mutableStateOf<ChatMessage?>(null) }

    LaunchedEffect(vm) { vm.start(openId) }
    val goBack = { if (closeWithChat && ui.page == ChatPage.CHAT) exit() else if (!vm.back()) exit() }
    BackHandler { if (!ui.busy) goBack() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            pickError = null
            try {
                vm.chooseFile(files.read(context.contentResolver, uri, HOMEWORK_FORMATS.map { it.first }, HOMEWORK_LIMIT_BYTES))
            } catch (e: HomeworkFailure) { pickError = e.message }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val m = saving
        if (uri != null && m != null) vm.download("Saved ${m.attachmentName}.") {
            repo.saveFile(auth(), m.conversationId, m.id, context.contentResolver, uri)
        }
        saving = null
    }

    actionsFor?.let { m ->
        val preview = m.body.ifEmpty { m.attachmentName.orEmpty() }.let { if (it.length > 240) it.take(240).trimEnd() + "…" else it }
        if (m.mine) ConfirmDialog("Your message", preview, "Delete for everyone",
            onConfirm = { actionsFor = null; vm.delete(m) }, onDismiss = { actionsFor = null }, danger = true)
        else ConfirmDialog(m.sender, preview, "Report",
            onConfirm = { actionsFor = null; reporting = m }, onDismiss = { actionsFor = null }, danger = true,
            extra = {
                SecondaryButton("Block ${m.sender.substringBefore(' ')}", { actionsFor = null; vm.blockSender(m.senderId, m.sender) },
                    danger = true)
            })
    }
    reporting?.let { m -> ReportDialog(m, onDismiss = { reporting = null }) { reason, note, block -> reporting = null; vm.report(m, reason, note, block) } }
    confirm?.let { action ->
        val c = ui.current
        val (title, body, button) = when (action) {
            "leave" -> Triple("Leave this group?", when {
                c?.learning == true -> "You will also leave the learning group. You keep the messages so far, but get no new messages or notifications."
                c?.kind == "CLASS" -> "You will get no class group messages or notifications. You keep the messages so far, and can rejoin your class group any time."
                else -> "You will get no new messages or notifications and cannot reply. You keep the messages so far until you delete the group."
            }, "Leave")
            "delete" -> Triple(if (c?.kind == "DIRECT") "Delete this chat?" else "Delete this group?",
                "Its messages are removed from your phone only; nobody else is affected. " +
                    if (c?.left == true || c?.deleted == true) "It will not come back." else "If a new message arrives, it comes back without the old ones.",
                "Delete")
            "deleteAll" -> Triple("Delete this group for everyone?",
                "Nobody can send messages any more. Members can still read the old messages until they delete the group.", "Delete for everyone")
            else -> Triple("Archive this group?", "Messages stay readable, but nobody can post or join any more.", "Archive")
        }
        ConfirmDialog(title, body, button,
            onConfirm = {
                confirm = null
                when (action) { "leave" -> vm.leave(); "delete" -> vm.deleteChat(); "deleteAll" -> vm.deleteForEveryone(); else -> vm.archive() }
            },
            onDismiss = { confirm = null }, danger = action != "archive")
    }

    val current = ui.current
    // Each page keeps its own saved state (scroll position) while another is open, so Back returns to the same place.
    ScreenSwitch(ui.page, Modifier.fillMaxSize()) { page ->
        when (page) {
            ChatPage.LIST -> ConversationListPage(ui, vm, goBack)
            ChatPage.CHAT -> Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                CampusTopBar(current?.name ?: "Chat", goBack, subtitle = current?.let { chatSubtitle(it, ui.live) },
                    backEnabled = !ui.busy) {
                    if (current != null) IconButton({ vm.details() }, enabled = !ui.busy) { Icon(CampusIcons.Info, "Details") }
                }
                ProgressLine(ui.loading || ui.busy, ui.progress)
                ChatBanner(ui, vm::clearMessages, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                if (current != null) {
                    val list = rememberLazyListState()
                    LaunchedEffect(ui.messages.lastOrNull()?.id) { if (ui.messages.isNotEmpty()) list.animateScrollToItem(ui.messages.size) }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item(key = "chat-head") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                if (ui.hasOlder) TextButton(vm::older, enabled = !ui.busy) { Text("Load earlier messages") }
                                else if (ui.messages.isEmpty()) Text("No messages yet. Say hello.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                            }
                        }
                        itemsIndexed(ui.messages, key = { _, m -> m.id }) { index, m ->
                            val previous = ui.messages.getOrNull(index - 1)
                            val image = images[m.id]
                            Bubble(m, current.kind, showSender = previous == null || previous.senderId != m.senderId || previous.mine != m.mine,
                                image = image,
                                onLongPress = { if (!m.deleted) actionsFor = m },
                                onShowImage = {
                                    scope.launch {
                                        runCatching { vm.fileBytes(m) }.onSuccess { bytes ->
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = if (bytes.size > 1_500_000) 2 else 1 })
                                                ?.let { images[m.id] = it.asImageBitmap() }
                                        }
                                    }
                                },
                                onSave = { saving = m; save.launch(m.attachmentName ?: "file") })
                        }
                    }
                    Composer(ui, current, pickError, onPick = { pick.launch(homeworkPickerTypes(HOMEWORK_FORMATS.map { it.first })) },
                        onClearFile = { vm.chooseFile(null) }, onDraft = vm::setDraft, onSend = vm::send,
                        onDelete = { confirm = "delete" }, onRejoin = vm::rejoinClass)
                }
            }
            ChatPage.DETAILS -> DetailsPage(ui, vm, goBack, onConfirm = { confirm = it })
            ChatPage.NEW_GROUP -> GroupFormPage(ui, vm, goBack)
            ChatPage.PEOPLE -> PeoplePage(ui, vm, goBack)
            ChatPage.DISCOVER -> DiscoverPage(ui, vm, goBack)
            ChatPage.BLOCKED -> BlockedPage(ui, vm, goBack)
        }
    }
}

/** "Study group · 5 members · live" under a conversation's name. */
private fun chatSubtitle(c: ChatConversation, live: Boolean): String = when (c.kind) {
    "CLASS" -> "Class group · ${c.members} student" + if (c.members == 1L) "" else "s"
    "GROUP" -> (if (c.learning) "Learning group" else "Study group") + " · ${c.members} member" + (if (c.members == 1L) "" else "s") +
        (if (c.deleted) " · deleted" else if (c.archived) " · archived" else "")
    else -> if (c.blocked) "Blocked" else "Private chat"
} + (if (c.left) " · you left" else if (live) " · live" else "")

/** The thin progress line under the top bar: the upload percentage while sending a file, otherwise a busy line. */
@Composable
private fun ProgressLine(busy: Boolean, progress: Float?) {
    Box(Modifier.fillMaxWidth().height(3.dp)) {
        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxSize())
        else if (busy) LinearProgressIndicator(Modifier.fillMaxSize())
    }
}

/** The error or confirmation of the last action, with OK to dismiss it. */
@Composable
private fun ChatBanner(ui: ChatUi, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    val text = ui.error ?: ui.notice ?: return
    MessageBanner(text, if (ui.error != null) MessageKind.Error else MessageKind.Success, modifier,
        actionLabel = "OK", onAction = dismiss)
}

/** A [CampusPage] wired to the chat state: busy line, Back, and the dismissable error/confirmation at the top. */
@Composable
private fun ChatPageFrame(title: String, ui: ChatUi, vm: ChatViewModel, onBack: () -> Unit, stateKey: String,
                          subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {},
                          content: LazyListScope.() -> Unit) {
    CampusPage(title, onBack, subtitle = subtitle, stateKey = stateKey, busy = ui.loading || ui.busy,
        backEnabled = !ui.busy, actions = actions) {
        if (ui.error != null || ui.notice != null) item(key = "chat-banner") { ChatBanner(ui, vm::clearMessages) }
        content()
    }
}

@Composable
private fun ConversationListPage(ui: ChatUi, vm: ChatViewModel, onBack: () -> Unit) {
    ChatPageFrame("Groups and chats", ui, vm, onBack, stateKey = "list",
        actions = { TextButton(vm::blockedList, enabled = !ui.busy) { Text("Blocked") } }) {
        item(key = "start") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Message a student", { vm.people(PeopleMode.DIRECT) }, enabled = !ui.busy, icon = CampusIcons.Chat)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("New group", vm::newGroup, Modifier.weight(1f), enabled = !ui.busy)
                    SecondaryButton("Join a group", vm::discover, Modifier.weight(1f), enabled = !ui.busy)
                }
                Text("Chats are only for students. Teachers and the college admin can't see them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ui.loaded && ui.conversations.isEmpty()) item(key = "empty") {
            EmptyState("No chats yet", "Your class group appears once you're enrolled in a class.")
        }
        itemsIndexed(ui.conversations, key = { _, c -> c.id }) { index, c ->
            ListRow(
                title = c.name ?: "Chat",
                modifier = Modifier.appear(index),
                subtitle = when {
                    c.deleted -> "Group deleted by its owner"
                    c.left -> "You left this group"
                    else -> listOfNotNull(c.lastSender?.takeIf { c.kind != "DIRECT" }?.substringBefore(' '), c.lastMessage)
                        .joinToString(": ").ifEmpty { if (c.blocked) "Blocked" else "No messages yet" }
                },
                meta = chatTime(c.lastMessageAt).takeIf { it.isNotEmpty() },
                icon = when (c.kind) { "CLASS" -> CampusIcons.Building; "GROUP" -> CampusIcons.Users; else -> CampusIcons.Chat },
                chip = if (c.unread > 0) ({ StatusChip(if (c.unread > 99) "99+" else c.unread.toString(), Tone.Accent) }) else null,
                onClick = { vm.open(c) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: ChatMessage, kind: String, showSender: Boolean, image: ImageBitmap?, onLongPress: () -> Unit,
                   onShowImage: () -> Unit, onSave: () -> Unit) {
    val mine = m.mine
    val scheme = MaterialTheme.colorScheme
    val container = if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh
    val onContainer = if (mine) scheme.onPrimaryContainer else scheme.onSurface
    Column(Modifier.fillMaxWidth().padding(top = if (showSender) 4.dp else 0.dp),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!mine && kind != "DIRECT" && showSender) {
            Text(m.sender, style = MaterialTheme.typography.labelMedium, color = scheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp))
        }
        Surface(color = container, contentColor = onContainer,
            shape = if (mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = onLongPress)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (m.deleted) Text("Message deleted", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium,
                    color = onContainer.copy(alpha = 0.7f))
                m.attachmentName?.let { name ->
                    val picture = m.attachmentFormat == "jpg" || m.attachmentFormat == "png"
                    if (image != null) Image(image, name, Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Fit)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(CampusIcons.File, null, Modifier.size(18.dp))
                        Text(name + (m.attachmentSize?.let { " (${homeworkSize(it)})" } ?: ""), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    Row {
                        val colors = ButtonDefaults.textButtonColors(contentColor = onContainer)
                        if (picture && image == null) TextButton(onShowImage, colors = colors) {
                            Icon(CampusIcons.Eye, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Show")
                        }
                        TextButton(onSave, colors = colors) {
                            Icon(CampusIcons.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save")
                        }
                    }
                }
                if (m.body.isNotEmpty()) Text(m.body, style = MaterialTheme.typography.bodyLarge)
                Text(chatTime(m.createdAt) + (readLabel(m, kind)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = onContainer.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End))
            }
        }
    }
}

@Composable
private fun Composer(ui: ChatUi, c: ChatConversation, pickError: String?, onPick: () -> Unit,
                     onClearFile: () -> Unit, onDraft: (String) -> Unit, onSend: () -> Unit, onDelete: () -> Unit, onRejoin: () -> Unit) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!c.canPost) {
                    Text(when {
                        c.left -> "You left this group. You can still read the messages until you left, but get nothing new."
                        c.deleted -> "This group was deleted by its owner. You can still read the old messages."
                        c.blocked -> "You can't message this student (one of you has blocked the other)."
                        else -> "This group is archived. You can read it but not post."
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
                    if (c.left || c.deleted || c.archived) {
                        if (c.left && c.kind == "CLASS") PrimaryButton("Rejoin class group", onRejoin, enabled = !ui.busy)
                        SecondaryButton("Delete group", onDelete, enabled = !ui.busy, icon = CampusIcons.Trash, danger = true)
                    }
                } else {
                    ui.file?.let { f ->
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(CampusIcons.File, null, Modifier.size(18.dp))
                                Text("${f.fileName} (${homeworkSize(f.bytes.size.toLong())})", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClearFile) { Icon(CampusIcons.Close, "Remove file") }
                            }
                        }
                    }
                    pickError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onPick, enabled = !ui.busy) { Icon(CampusIcons.Plus, "Attach a file") }
                        OutlinedTextField(value = ui.draft, onValueChange = onDraft, modifier = Modifier.weight(1f),
                            placeholder = { Text("Message") }, maxLines = 4, shape = MaterialTheme.shapes.large)
                        FilledIconButton(onSend, enabled = !ui.busy && (ui.draft.isNotBlank() || ui.file != null)) {
                            Icon(CampusIcons.ChevronRight, "Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsPage(ui: ChatUi, vm: ChatViewModel, onBack: () -> Unit, onConfirm: (String) -> Unit) {
    val c = ui.current
    ChatPageFrame(c?.name ?: "Chat", ui, vm, onBack, stateKey = "details-${c?.id}",
        subtitle = c?.let { chatSubtitle(it, live = false) }) {
        if (c != null) {
            val owner = c.myRole == "OWNER" && c.kind == "GROUP" && !c.archived && !c.left
            val teaches = c.learning && c.myRole == "OWNER"
            item(key = "about") {
                InfoCard {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(when (c.kind) {
                            "CLASS" -> "Class group"
                            "GROUP" -> if (c.learning) "Learning group" else "Study group"
                            else -> "Private chat"
                        }, Tone.Accent)
                        if (c.archived) StatusChip("Archived", Tone.Warning)
                        if (c.deleted) StatusChip("Deleted", Tone.Danger)
                        if (c.left) StatusChip("You left")
                        if (c.blocked) StatusChip("Blocked", Tone.Danger)
                    }
                    when (c.kind) {
                        "CLASS" -> Text(if (c.left) "You left your class group. Rejoin it any time from Join a group."
                            else "Everyone enrolled in your class is in this group. You can leave it and rejoin later.")
                        "GROUP" -> {
                            c.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            Text(if (c.joinPolicy == "OPEN") "Open: any student of your college can join." else "Invite only: the owner adds members.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> Text("Private chat between the two of you.")
                    }
                }
            }
            if (teaches) item(key = "teaches") {
                MessageBanner("You teach this learning group, so you stay in its chat. You can still delete the messages from your phone.")
            }
            if (owner) item(key = "owner") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Add member", { vm.people(PeopleMode.ADD) }, enabled = !ui.busy, icon = CampusIcons.Plus)
                    SecondaryButton("Edit group", vm::editGroup, enabled = !ui.busy, icon = CampusIcons.Edit)
                }
            }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (c.kind != "DIRECT" && !c.left && !teaches)
                        SecondaryButton("Leave group", { onConfirm("leave") }, enabled = !ui.busy, icon = CampusIcons.Logout, danger = true)
                    SecondaryButton(if (c.kind == "DIRECT") "Delete chat" else "Delete group", { onConfirm("delete") }, enabled = !ui.busy,
                        icon = CampusIcons.Trash, danger = true)
                    if (c.canDeleteForEveryone)
                        SecondaryButton("Delete for everyone", { onConfirm("deleteAll") }, enabled = !ui.busy, icon = CampusIcons.Trash, danger = true)
                    val other = c.otherStudentId
                    if (c.kind == "DIRECT" && other != null && !c.blocked)
                        SecondaryButton("Block", { vm.blockSender(other, c.name ?: "This student") }, enabled = !ui.busy, danger = true)
                }
            }
            item(key = "members-head") { SectionHeader("Members (${ui.members.size})") }
            items(ui.members, key = { it.studentId }) { m ->
                ListRow(m.name + if (m.me) " (you)" else "", subtitle = if (m.role == "OWNER") "Owner" else null,
                    chip = if (owner && !m.me) ({
                        TextButton({ vm.removeMember(m) }, enabled = !ui.busy,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
                    }) else null)
            }
        }
    }
}

@Composable
private fun GroupFormPage(ui: ChatUi, vm: ChatViewModel, onBack: () -> Unit) {
    val form = ui.form
    ChatPageFrame(if (form?.id == null) "New study group" else "Edit group", ui, vm, onBack, stateKey = "form-${form?.id}") {
        if (form != null) {
            item(key = "form") {
                InfoCard {
                    OutlinedTextField(form.name, { v -> vm.updateForm { it.copy(name = v) } }, label = { Text("Group name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(form.description, { v -> vm.updateForm { it.copy(description = v) } },
                        label = { Text("What is it for? (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (form.open) "Open: any student of your college can find and join it" else "Invite only: you add the members",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(form.open, { on -> vm.updateForm { it.copy(open = on) } })
                    }
                }
            }
            ui.formError?.let { problem -> item(key = "form-error") { MessageBanner(problem, MessageKind.Error) } }
            item(key = "save") {
                PrimaryButton(if (form.id == null) "Create group" else "Save", vm::saveGroup, enabled = !ui.busy)
            }
        }
    }
}

@Composable
private fun PeoplePage(ui: ChatUi, vm: ChatViewModel, onBack: () -> Unit) {
    val adding = ui.peopleMode == PeopleMode.ADD
    ChatPageFrame(if (adding) "Add a member" else "Message a student", ui, vm, onBack, stateKey = "people-${ui.peopleMode}",
        subtitle = "Students of your college only") {
        item(key = "search") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchField(ui.query, vm::setQuery, "Student name", onSearch = vm::search)
                SecondaryButton("Search", vm::search, enabled = !ui.busy, icon = CampusIcons.Search)
            }
        }
        if (ui.people.isEmpty() && !ui.busy) item(key = "empty") {
            EmptyState("Find a student", "Search by name, or leave it empty and search to see students.")
        }
        items(ui.people, key = { it.studentId }) { p ->
            ListRow(p.name, subtitle = p.classLabel, icon = if (adding) CampusIcons.Plus else CampusIcons.Chat, onClick = { vm.pick(p) })
        }
    }
}

@Composable
private fun DiscoverPage(ui: ChatUi, vm: ChatViewModel, onBack: () -> Unit) {
    ChatPageFrame("Open study groups", ui, vm, onBack, stateKey = "discover") {
        item(key = "search") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchField(ui.query, vm::setQuery, "Search groups", onSearch = vm::discover)
                SecondaryButton("Search", vm::discover, enabled = !ui.busy, icon = CampusIcons.Search)
                SecondaryButton("Rejoin my class group", vm::rejoinClass, enabled = !ui.busy, icon = CampusIcons.Building)
            }
        }
        if (ui.openGroups.isEmpty() && !ui.busy) item(key = "empty") {
            EmptyState("No open groups", "There are no open groups to join. You can create one.")
        }
        items(ui.openGroups, key = { it.id }) { g ->
            ListRow(g.name, subtitle = g.description.takeIf { it.isNotBlank() },
                meta = "${g.members} member" + if (g.members == 1L) "" else "s", icon = CampusIcons.Users,
                chip = { Button({ vm.join(g) }, enabled = !ui.busy, shape = MaterialTheme.shapes.small) { Text("Join") } })
        }
    }
}

@Composable
private fun BlockedPage(ui: ChatUi, vm: ChatViewModel, onBack: () -> Unit) {
    ChatPageFrame("Blocked students", ui, vm, onBack, stateKey = "blocked") {
        item(key = "about") {
            Text("Blocked students can't start a private chat with you, and you don't see their messages in groups.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ui.blocked.isEmpty() && !ui.busy) item(key = "empty") {
            EmptyState("No one blocked", "You haven't blocked anyone.")
        }
        items(ui.blocked, key = { it.studentId }) { p ->
            ListRow(p.name, subtitle = p.classLabel,
                chip = { TextButton({ vm.unblock(p) }, enabled = !ui.busy) { Text("Unblock") } })
        }
    }
}

@Composable
private fun ReportDialog(message: ChatMessage, onDismiss: () -> Unit, onReport: (String, String, Boolean) -> Unit) {
    var reason by remember { mutableStateOf("SPAM") }
    var note by remember { mutableStateOf("") }
    var block by remember { mutableStateOf(false) }
    ConfirmDialog("Report message",
        "The message will be hidden for you. Reports are kept only as a record; teachers and the college admin can't see chats.",
        "Report", onConfirm = { onReport(reason, note, block) }, onDismiss = onDismiss, danger = true,
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CHAT_REPORT_REASONS.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth().selectable(reason == key, onClick = { reason = key }, role = Role.RadioButton),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(reason == key, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().toggleable(block, onValueChange = { block = it }, role = Role.Checkbox),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(block, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Also block ${message.sender}")
                }
            }
        })
}
