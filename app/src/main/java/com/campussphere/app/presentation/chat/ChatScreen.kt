package com.campussphere.app.presentation.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.ChatMessage
import com.campussphere.app.data.repository.CHAT_REPORT_REASONS
import com.campussphere.app.data.repository.ChatRepository
import com.campussphere.app.data.repository.HOMEWORK_FORMATS
import com.campussphere.app.data.repository.HOMEWORK_LIMIT_BYTES
import com.campussphere.app.data.repository.HomeworkFailure
import com.campussphere.app.data.repository.HomeworkRepository
import com.campussphere.app.data.repository.homeworkPickerTypes
import com.campussphere.app.data.repository.homeworkSize
import com.campussphere.app.presentation.homework.homeworkTime
import com.campussphere.app.ui.brand.appear
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

/** Students' groups and chats. Faculty and College Admins have no way into this screen or its data. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(repo: ChatRepository, files: HomeworkRepository, auth: () -> String, lost: (Int) -> Unit,
               openId: Long? = null, exit: () -> Unit) {
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
    BackHandler { if (!ui.busy && !vm.back()) exit() }

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
        AlertDialog(onDismissRequest = { actionsFor = null }, title = { Text(if (m.mine) "Your message" else m.sender) },
            text = { Text(m.body.ifEmpty { m.attachmentName.orEmpty() }, maxLines = 4, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                if (m.mine) TextButton({ actionsFor = null; vm.delete(m) }) { Text("Delete for everyone") }
                else TextButton({ actionsFor = null; reporting = m }) { Text("Report") }
            },
            dismissButton = {
                Row {
                    if (!m.mine) TextButton({ actionsFor = null; vm.blockSender(m.senderId, m.sender) }) { Text("Block ${m.sender.substringBefore(' ')}") }
                    TextButton({ actionsFor = null }) { Text("Close") }
                }
            })
    }
    reporting?.let { m -> ReportDialog(m, onDismiss = { reporting = null }) { reason, note, block -> reporting = null; vm.report(m, reason, note, block) } }
    confirm?.let { action ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (action == "leave") "Leave this group?" else "Archive this group?") },
            text = { Text(if (action == "leave") "You will stop receiving its messages. You can rejoin an open group later."
                else "Messages stay readable, but nobody can post or join any more.") },
            confirmButton = { TextButton({ confirm = null; if (action == "leave") vm.leave() else vm.archive() }) { Text(if (action == "leave") "Leave" else "Archive") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }

    val current = ui.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ if (!vm.back()) exit() }, enabled = !ui.busy) { Text("Back") }
            Column(Modifier.weight(1f)) {
                Text(when (ui.page) {
                    ChatPage.LIST -> "Groups and chats"
                    ChatPage.CHAT, ChatPage.DETAILS -> current?.name ?: "Chat"
                    ChatPage.NEW_GROUP -> if (ui.form?.id == null) "New study group" else "Edit group"
                    ChatPage.PEOPLE -> if (ui.peopleMode == PeopleMode.ADD) "Add a member" else "Message a student"
                    ChatPage.DISCOVER -> "Open study groups"
                    ChatPage.BLOCKED -> "Blocked students"
                }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.page == ChatPage.CHAT && current != null) Text(when (current.kind) {
                    "CLASS" -> "Class group · ${current.members} students"
                    "GROUP" -> "Study group · ${current.members} members" + if (current.archived) " · archived" else ""
                    else -> if (current.blocked) "Blocked" else "Private chat"
                } + if (ui.live) " · live" else "", style = MaterialTheme.typography.bodySmall)
            }
            if (ui.page == ChatPage.CHAT && current != null) TextButton({ vm.details() }, enabled = !ui.busy) { Text("Details") }
        }
        if (ui.loading || ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        ui.progress?.let { p -> LinearProgressIndicator({ p }, Modifier.fillMaxWidth()) }
        Messages(ui.error, ui.notice, vm::clearMessages)
        com.campussphere.app.ui.brand.ScreenSwitch(ui.page, Modifier.weight(1f)) { page -> Column(Modifier.fillMaxSize()) { when (page) {
            ChatPage.LIST -> ConversationList(ui, vm)
            ChatPage.CHAT -> if (current != null) {
                val list = rememberLazyListState()
                LaunchedEffect(ui.messages.lastOrNull()?.id) { if (ui.messages.isNotEmpty()) list.animateScrollToItem(ui.messages.size) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        if (ui.hasOlder) TextButton(vm::older, enabled = !ui.busy) { Text("Load earlier messages") }
                        else if (ui.messages.isEmpty()) Text("No messages yet. Say hello!", style = MaterialTheme.typography.bodySmall)
                    }
                    items(ui.messages, key = { it.id }) { m ->
                        val image = images[m.id]
                        Bubble(m, current.kind, image,
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
                Composer(ui, current.canPost, current.blocked, pickError, onPick = { pick.launch(homeworkPickerTypes(HOMEWORK_FORMATS.map { it.first })) },
                    onClearFile = { vm.chooseFile(null) }, onDraft = vm::setDraft, onSend = vm::send)
            }
            ChatPage.DETAILS -> if (current != null) Details(ui, vm, onConfirm = { confirm = it })
            ChatPage.NEW_GROUP -> GroupFormPage(ui, vm)
            ChatPage.PEOPLE -> PeoplePage(ui, vm)
            ChatPage.DISCOVER -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(ui.query, vm::setQuery, label = { Text("Search groups") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedButton(vm::discover, enabled = !ui.busy) { Text("Search") }
                    }
                }
                if (ui.openGroups.isEmpty() && !ui.busy) item { Text("No open groups to join. You can create one.") }
                items(ui.openGroups, key = { it.id }) { g ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(g.name, fontWeight = FontWeight.SemiBold)
                            if (g.description.isNotBlank()) Text(g.description, style = MaterialTheme.typography.bodySmall)
                            Text("${g.members} members", style = MaterialTheme.typography.bodySmall)
                            Button({ vm.join(g) }, enabled = !ui.busy) { Text("Join") }
                        }
                    }
                }
            }
            ChatPage.BLOCKED -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Blocked students cannot start a private chat with you, and you do not see their messages in groups.",
                    style = MaterialTheme.typography.bodySmall) }
                if (ui.blocked.isEmpty() && !ui.busy) item { Text("You have not blocked anyone.") }
                items(ui.blocked, key = { it.studentId }) { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, modifier = Modifier.weight(1f))
                        OutlinedButton({ vm.unblock(p) }, enabled = !ui.busy) { Text("Unblock") }
                    }
                }
            }
        } } }
    }
}

@Composable
private fun Messages(error: String?, notice: String?, dismiss: () -> Unit) {
    (error ?: notice)?.let { text ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = if (error != null) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.secondaryContainer)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(dismiss) { Text("OK") }
            }
        }
    }
}

@Composable
private fun ColumnScope.ConversationList(ui: ChatUi, vm: ChatViewModel) {
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ vm.people(PeopleMode.DIRECT) }, enabled = !ui.busy) { Text("Message a student") }
                    OutlinedButton(vm::newGroup, enabled = !ui.busy) { Text("New group") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(vm::discover, enabled = !ui.busy) { Text("Join a group") }
                    TextButton(vm::blockedList, enabled = !ui.busy) { Text("Blocked") }
                }
                Text("Chats are only for students. Teachers and the college admin cannot see them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ui.loaded && ui.conversations.isEmpty()) item {
            Text("No chats yet. Your class group appears once you are enrolled in a class.")
        }
        items(ui.conversations, key = { it.id }) { c ->
            Card(onClick = { vm.open(c) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().appear(ui.conversations.indexOf(c)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((when (c.kind) { "CLASS" -> "🏫 "; "GROUP" -> "👥 "; else -> "👤 " }) + (c.name ?: "Chat"),
                            fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(c.lastSender?.takeIf { c.kind != "DIRECT" }?.substringBefore(' '), c.lastMessage)
                            .joinToString(": ").ifEmpty { if (c.blocked) "Blocked" else "No messages yet" },
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(chatTime(c.lastMessageAt), style = MaterialTheme.typography.labelSmall)
                        if (c.unread > 0) Badge { Text(if (c.unread > 99) "99+" else c.unread.toString()) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: ChatMessage, kind: String, image: ImageBitmap?, onLongPress: () -> Unit, onShowImage: () -> Unit, onSave: () -> Unit) {
    val mine = m.mine
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Surface(color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = if (mine) androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                else androidx.compose.foundation.shape.RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = onLongPress)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!mine && kind != "DIRECT") Text(m.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (m.deleted) Text("Message deleted", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                m.attachmentName?.let { name ->
                    val picture = m.attachmentFormat == "jpg" || m.attachmentFormat == "png"
                    if (image != null) Image(image, name, Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit)
                    Text("📎 $name" + (m.attachmentSize?.let { " (${homeworkSize(it)})" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (picture && image == null) TextButton(onShowImage) { Text("Show") }
                        TextButton(onSave) { Text("Save") }
                    }
                }
                if (m.body.isNotEmpty()) Text(m.body)
                Text(chatTime(m.createdAt) + (readLabel(m, kind)?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun Composer(ui: ChatUi, canPost: Boolean, blocked: Boolean, pickError: String?, onPick: () -> Unit,
                     onClearFile: () -> Unit, onDraft: (String) -> Unit, onSend: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (!canPost) {
                Text(if (blocked) "You cannot message this student (one of you has blocked the other)."
                    else "This group is archived. You can read it but not post.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp))
                return@Column
            }
            ui.file?.let { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📎 ${f.fileName} (${homeworkSize(f.bytes.size.toLong())})", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClearFile) { Text("Remove") }
                }
            }
            pickError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onPick, enabled = !ui.busy) { Text("📎") }
                OutlinedTextField(ui.draft, onDraft, placeholder = { Text("Message") }, maxLines = 4, modifier = Modifier.weight(1f))
                TextButton(onSend, enabled = !ui.busy && (ui.draft.isNotBlank() || ui.file != null)) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ColumnScope.Details(ui: ChatUi, vm: ChatViewModel, onConfirm: (String) -> Unit) {
    val c = ui.current ?: return
    val owner = c.myRole == "OWNER" && c.kind == "GROUP" && !c.archived
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (c.kind) {
                    "CLASS" -> Text("Everyone currently enrolled in your class is in this group automatically. You cannot leave it.")
                    "GROUP" -> {
                        if (!c.description.isNullOrBlank()) Text(c.description)
                        Text(if (c.joinPolicy == "OPEN") "Open: any student of your college can join." else "Invite only: the owner adds members.",
                            style = MaterialTheme.typography.bodySmall)
                        if (c.archived) Text("Archived.", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Text("Private chat between the two of you.")
                }
                if (owner) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(vm::editGroup, enabled = !ui.busy) { Text("Edit") }
                    OutlinedButton({ vm.people(PeopleMode.ADD) }, enabled = !ui.busy) { Text("Add member") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.kind == "GROUP" && !c.archived) TextButton({ onConfirm("leave") }, enabled = !ui.busy) { Text("Leave group") }
                    if (owner) TextButton({ onConfirm("archive") }, enabled = !ui.busy) { Text("Archive") }
                    if (c.kind == "DIRECT" && c.otherStudentId != null && !c.blocked) {
                        TextButton({ vm.blockSender(c.otherStudentId, c.name ?: "This student") }, enabled = !ui.busy) { Text("Block") }
                    }
                }
                Text("Members (${ui.members.size})", style = MaterialTheme.typography.titleSmall)
            }
        }
        items(ui.members, key = { it.studentId }) { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name + (if (m.me) " (you)" else "") + if (m.role == "OWNER") " · owner" else "", modifier = Modifier.weight(1f))
                if (owner && !m.me) TextButton({ vm.removeMember(m) }, enabled = !ui.busy) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun GroupFormPage(ui: ChatUi, vm: ChatViewModel) {
    val form = ui.form ?: return
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(form.name, { v -> vm.updateForm { it.copy(name = v) } }, label = { Text("Group name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(form.description, { v -> vm.updateForm { it.copy(description = v) } }, label = { Text("What is it for? (optional)") },
            minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(form.open, { on -> vm.updateForm { it.copy(open = on) } })
            Spacer(Modifier.width(8.dp))
            Text(if (form.open) "Open: any student of your college can find and join it" else "Invite only: you add the members")
        }
        ui.formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(vm::saveGroup, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text(if (form.id == null) "Create group" else "Save") }
    }
}

@Composable
private fun PeoplePage(ui: ChatUi, vm: ChatViewModel) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ui.query, vm::setQuery, label = { Text("Student name") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(vm::search, enabled = !ui.busy) { Text("Search") }
            }
            Text("Students of your college only.", style = MaterialTheme.typography.bodySmall)
        }
        if (ui.people.isEmpty() && !ui.busy) item { Text("Search by name, or leave it empty and search to see students.") }
        items(ui.people, key = { it.studentId }) { p ->
            Card(onClick = { vm.pick(p) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold)
                    p.classLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ReportDialog(message: ChatMessage, onDismiss: () -> Unit, onReport: (String, String, Boolean) -> Unit) {
    var reason by remember { mutableStateOf("SPAM") }
    var note by remember { mutableStateOf("") }
    var block by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Report message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("The message will be hidden for you. Reports are kept only as a record; teachers and the college admin cannot see chats.",
                    style = MaterialTheme.typography.bodySmall)
                CHAT_REPORT_REASONS.forEach { (key, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(reason == key, { reason = key })
                        Text(label)
                    }
                }
                OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(block, { block = it })
                    Text("Also block ${message.sender}")
                }
            }
        },
        confirmButton = { TextButton({ onReport(reason, note, block) }) { Text("Report") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
