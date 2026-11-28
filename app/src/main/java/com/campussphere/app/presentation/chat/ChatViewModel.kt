package com.campussphere.app.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.ChatEvent
import com.campussphere.app.data.repository.ChatFailure
import com.campussphere.app.data.repository.ChatRepository
import com.campussphere.app.data.repository.MaterialUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class ChatPage { LIST, CHAT, DETAILS, NEW_GROUP, PEOPLE, DISCOVER, BLOCKED }

/** What the people search is for: opening a private chat, or adding someone to the current group. */
enum class PeopleMode { DIRECT, ADD }

data class GroupForm(val id: Long? = null, val name: String = "", val description: String = "", val open: Boolean = false)

data class ChatUi(
    val page: ChatPage = ChatPage.LIST,
    val loading: Boolean = false, val busy: Boolean = false, val progress: Float? = null,
    val error: String? = null, val notice: String? = null, val live: Boolean = false,
    val conversations: List<ChatConversation> = emptyList(), val loaded: Boolean = false,
    val current: ChatConversation? = null,
    /** Oldest first, as shown on screen. */
    val messages: List<ChatMessage> = emptyList(), val hasOlder: Boolean = false,
    val draft: String = "", val file: MaterialUpload? = null,
    val members: List<ChatMember> = emptyList(),
    val query: String = "", val people: List<ChatPerson> = emptyList(), val peopleMode: PeopleMode = PeopleMode.DIRECT,
    val openGroups: List<ChatOpenGroup> = emptyList(), val blocked: List<ChatPerson> = emptyList(),
    val form: GroupForm? = null, val formError: String? = null,
)

const val CHAT_PAGE = 50

/** Merges a fresh page into what is shown: newer copies replace older ones (deletions, read counts), oldest first. */
fun mergeMessages(shown: List<ChatMessage>, fresh: List<ChatMessage>): List<ChatMessage> =
    (shown.associateBy { it.id } + fresh.associateBy { it.id }).values.sortedBy { it.id }

/** "Seen" for a private chat, "Read by N" in a group, nothing until someone has read it. */
fun readLabel(message: ChatMessage, kind: String): String? = when {
    !message.mine || message.deleted || message.readBy == 0L -> null
    kind == "DIRECT" -> "Seen"
    else -> "Read by ${message.readBy}"
}

/** Checks a group form before it is sent. */
fun groupFormProblem(form: GroupForm): String? {
    val name = form.name.trim()
    return when {
        name.length < 3 -> "Give the group a name of at least 3 characters."
        name.length > 80 -> "Keep the name to 80 characters."
        form.description.trim().length > 500 -> "Keep the description to 500 characters."
        else -> null
    }
}

class ChatViewModel(private val repo: ChatRepository, private val auth: () -> String,
                    private val lost: (Int) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = state.asStateFlow()
    private var stream: Job? = null
    private var listRefresh: Job? = null
    /** Kept until a send succeeds, so retrying a failed send cannot post the message twice. */
    private var pendingKey: String? = null

    private fun message(e: Exception): String {
        val failure = e as? ChatFailure
        if (failure != null && (failure.status == 401 || failure.status == 403)) lost(failure.status)
        return failure?.message ?: "Something went wrong. Try again."
    }

    private fun fail(text: String) = state.update { it.copy(error = text, notice = null) }

    private fun work(block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(message(e)) }
            finally { state.update { it.copy(busy = false, progress = null) } }
        }
    }

    fun clearMessages() = state.update { it.copy(error = null, notice = null) }

    /** Loads the list and starts live updates. [openId] opens one conversation straight away. */
    fun start(openId: Long? = null) {
        if (!state.value.loaded) {
            state.update { it.copy(loading = true) }
            viewModelScope.launch {
                try {
                    val list = repo.conversations(auth())
                    state.update { it.copy(conversations = list, loaded = true) }
                    openId?.let { id -> list.firstOrNull { it.id == id }?.let(::open) }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(message(e)) }
                finally { state.update { it.copy(loading = false) } }
            }
        }
        if (stream == null) stream = viewModelScope.launch {
            repo.events(auth).collect { event ->
                when (event) {
                    ChatEvent.Connected -> state.update { it.copy(live = true) }
                    is ChatEvent.Refused -> { state.update { it.copy(live = false) }; lost(event.status) }
                    is ChatEvent.Changed -> changed(event)
                }
            }
        }
    }

    private fun changed(event: ChatEvent.Changed) {
        val current = state.value.current
        if (current != null && current.id == event.conversationId && state.value.page == ChatPage.CHAT) {
            viewModelScope.launch { runCatching { newest(current.id) } }
        }
        if (event.type == "members" && current?.id == event.conversationId && state.value.page == ChatPage.DETAILS) {
            viewModelScope.launch { runCatching { reloadMembers(current.id) } }
        }
        // Several events in a burst refresh the list once.
        listRefresh?.cancel()
        listRefresh = viewModelScope.launch {
            delay(400)
            runCatching { repo.conversations(auth()) }.onSuccess { list -> state.update { it.copy(conversations = list) } }
        }
    }

    fun refreshList() = work { val list = repo.conversations(auth()); state.update { it.copy(conversations = list) } }

    // ---- One conversation ----

    fun open(conversation: ChatConversation) = work {
        val page = repo.messages(auth(), conversation.id)
        state.update {
            it.copy(page = ChatPage.CHAT, current = conversation, messages = page.reversed(), hasOlder = page.size >= CHAT_PAGE,
                draft = "", file = null, members = emptyList())
        }
        pendingKey = null
        markRead()
    }

    fun older() {
        val current = state.value.current ?: return
        val first = state.value.messages.firstOrNull()?.id ?: return
        work {
            val page = repo.messages(auth(), current.id, first)
            state.update { it.copy(messages = mergeMessages(it.messages, page), hasOlder = page.size >= CHAT_PAGE) }
        }
    }

    private suspend fun newest(id: Long) {
        val page = repo.messages(auth(), id)
        state.update { s -> if (s.current?.id == id) s.copy(messages = mergeMessages(s.messages, page)) else s }
        markRead()
    }

    private suspend fun markRead() {
        val s = state.value
        val current = s.current ?: return
        val last = s.messages.lastOrNull { !it.mine }?.id ?: return
        runCatching { repo.read(auth(), current.id, last) }
        state.update { st -> st.copy(conversations = st.conversations.map { if (it.id == current.id) it.copy(unread = 0) else it }) }
    }

    fun setDraft(text: String) = state.update { it.copy(draft = text.take(4000)) }
    fun chooseFile(file: MaterialUpload?) = state.update { it.copy(file = file, error = null) }

    fun send() {
        val s = state.value
        val current = s.current ?: return
        val text = s.draft.trim()
        val file = s.file
        if (text.isEmpty() && file == null) return
        val key = pendingKey ?: UUID.randomUUID().toString().also { pendingKey = it }
        work {
            val sent = if (file != null) repo.sendFile(auth(), current.id, file, text, { p -> state.update { it.copy(progress = p) } }, key)
                else repo.send(auth(), current.id, text, key)
            pendingKey = null
            state.update { it.copy(messages = mergeMessages(it.messages, listOf(sent)), draft = "", file = null) }
        }
    }

    fun delete(message: ChatMessage) {
        val current = state.value.current ?: return
        work {
            val gone = repo.delete(auth(), current.id, message.id)
            state.update { it.copy(messages = mergeMessages(it.messages, listOf(gone)), notice = "Message deleted for everyone.") }
        }
    }

    fun report(message: ChatMessage, reason: String, note: String, block: Boolean) {
        val current = state.value.current ?: return
        work {
            repo.report(auth(), current.id, message.id, reason, note, block)
            state.update {
                it.copy(messages = it.messages.filterNot { m -> m.id == message.id || (block && m.senderId == message.senderId) },
                    notice = if (block) "Reported and blocked. You will not see their messages." else "Reported. The message is hidden for you.")
            }
        }
    }

    fun blockSender(studentId: Long, name: String) = work {
        repo.block(auth(), studentId)
        state.update {
            it.copy(messages = it.messages.filterNot { m -> m.senderId == studentId },
                notice = "$name is blocked. You will not see their messages, and neither of you can start a private chat.")
        }
        state.value.current?.let { c -> val fresh = repo.conversation(auth(), c.id); state.update { it.copy(current = fresh) } }
    }

    suspend fun fileBytes(message: ChatMessage): ByteArray = repo.fileBytes(auth(), message.conversationId, message.id)

    fun download(notice: String, block: suspend () -> Unit) = work { block(); state.update { it.copy(notice = notice) } }

    // ---- Details, members and group settings ----

    fun details() {
        val current = state.value.current ?: return
        work {
            val fresh = repo.conversation(auth(), current.id)
            val members = repo.members(auth(), current.id)
            state.update { it.copy(page = ChatPage.DETAILS, current = fresh, members = members) }
        }
    }

    private suspend fun reloadMembers(id: Long) {
        val members = repo.members(auth(), id)
        state.update { it.copy(members = members) }
    }

    /** Leaves the group; it stays on screen, read-only, until the student deletes it. */
    fun leave() {
        val current = state.value.current ?: return
        work {
            repo.leave(auth(), current.id)
            val list = repo.conversations(auth())
            val fresh = list.firstOrNull { it.id == current.id }
            state.update { it.copy(page = if (fresh != null) ChatPage.CHAT else ChatPage.LIST, current = fresh,
                messages = if (fresh != null) it.messages else emptyList(), conversations = list,
                notice = "You left ${current.name}. You can still read the messages so far, or delete the group.") }
        }
    }

    /** Deletes the chat from this phone only: its messages go, and it leaves the list. */
    fun deleteChat() {
        val current = state.value.current ?: return
        work {
            repo.deleteForMe(auth(), current.id)
            val list = repo.conversations(auth())
            state.update { it.copy(page = ChatPage.LIST, current = null, messages = emptyList(), conversations = list,
                notice = "Deleted ${current.name ?: "the chat"} from your phone.") }
        }
    }

    fun deleteForEveryone() {
        val current = state.value.current ?: return
        work {
            val gone = repo.deleteForEveryone(auth(), current.id)
            state.update { it.copy(page = ChatPage.CHAT, current = gone,
                notice = "Group deleted for everyone. Members can still read old messages until they delete it.") }
        }
    }

    fun rejoinClass() = work {
        val c = repo.rejoinClass(auth())
        val list = repo.conversations(auth())
        state.update { it.copy(conversations = list) }
        val page = repo.messages(auth(), c.id)
        state.update { it.copy(page = ChatPage.CHAT, current = c, messages = page.reversed(), hasOlder = page.size >= CHAT_PAGE,
            draft = "", file = null, members = emptyList(), notice = "You are back in your class group.") }
    }

    fun archive() {
        val current = state.value.current ?: return
        work {
            val archived = repo.archive(auth(), current.id)
            state.update { it.copy(current = archived, notice = "Group archived. Messages stay readable; nobody can post.") }
        }
    }

    fun removeMember(member: ChatMember) {
        val current = state.value.current ?: return
        work { val members = repo.removeMember(auth(), current.id, member.studentId); state.update { it.copy(members = members, notice = "${member.name} removed.") } }
    }

    fun newGroup() = state.update { it.copy(page = ChatPage.NEW_GROUP, form = GroupForm(), formError = null) }

    fun editGroup() {
        val c = state.value.current ?: return
        state.update { it.copy(page = ChatPage.NEW_GROUP, form = GroupForm(c.id, c.name.orEmpty(), c.description.orEmpty(), c.joinPolicy == "OPEN"), formError = null) }
    }

    fun updateForm(change: (GroupForm) -> GroupForm) = state.update { s -> s.copy(form = s.form?.let(change), formError = null) }

    fun saveGroup() {
        val form = state.value.form ?: return
        groupFormProblem(form)?.let { problem -> state.update { it.copy(formError = problem) }; return }
        work {
            val input = ChatGroupInput(form.name.trim(), form.description.trim(), if (form.open) "OPEN" else "INVITE")
            val saved = if (form.id == null) repo.createGroup(auth(), input) else repo.updateGroup(auth(), form.id, input)
            val list = repo.conversations(auth())
            val members = repo.members(auth(), saved.id)
            // Straight to the group's details in the same step (a second step would be ignored while this one
            // is busy, which used to leave an empty screen): back where an edit started, or ready to add members.
            state.update {
                it.copy(page = ChatPage.DETAILS, current = saved, members = members, form = null, formError = null,
                    conversations = list, messages = if (form.id == null) emptyList() else it.messages,
                    notice = if (form.id == null) "Group created. Tap Add member to invite classmates." else "Group updated.")
            }
        }
    }

    // ---- People, open groups and blocks ----

    fun people(mode: PeopleMode) = state.update { it.copy(page = ChatPage.PEOPLE, peopleMode = mode, query = "", people = emptyList()) }
    fun setQuery(text: String) = state.update { it.copy(query = text.take(60)) }

    fun search() = work {
        val found = repo.people(auth(), state.value.query); state.update { it.copy(people = found) }
    }

    fun pick(person: ChatPerson) {
        val s = state.value
        if (s.peopleMode == PeopleMode.ADD) {
            val current = s.current ?: return
            work {
                val members = repo.addMember(auth(), current.id, person.studentId)
                state.update { it.copy(page = ChatPage.DETAILS, members = members, notice = "${person.name} added.") }
            }
        } else work {
            val chat = repo.direct(auth(), person.studentId)
            val list = repo.conversations(auth())
            state.update { it.copy(conversations = list) }
            val page = repo.messages(auth(), chat.id)
            state.update { it.copy(page = ChatPage.CHAT, current = chat, messages = page.reversed(), hasOlder = page.size >= CHAT_PAGE, draft = "", file = null) }
        }
    }

    fun discover() = work {
        val groups = repo.openGroups(auth(), state.value.query); state.update { it.copy(page = ChatPage.DISCOVER, openGroups = groups) }
    }

    fun join(group: ChatOpenGroup) = work {
        val joined = repo.join(auth(), group.id)
        val list = repo.conversations(auth())
        state.update { it.copy(conversations = list, notice = "You joined ${group.name}.") }
        val page = repo.messages(auth(), joined.id)
        state.update { it.copy(page = ChatPage.CHAT, current = joined, messages = page.reversed(), hasOlder = page.size >= CHAT_PAGE) }
    }

    fun blockedList() = work { val list = repo.blocked(auth()); state.update { it.copy(page = ChatPage.BLOCKED, blocked = list) } }

    fun unblock(person: ChatPerson) = work {
        repo.unblock(auth(), person.studentId)
        val list = repo.blocked(auth())
        state.update { it.copy(blocked = list, notice = "${person.name} is unblocked.") }
    }

    /** Back one step; returns false when the chats screen itself should close. */
    fun back(): Boolean {
        val s = state.value
        val next = when (s.page) {
            ChatPage.LIST -> return false
            ChatPage.CHAT, ChatPage.DISCOVER, ChatPage.BLOCKED -> ChatPage.LIST
            ChatPage.DETAILS -> ChatPage.CHAT
            ChatPage.NEW_GROUP -> if (s.form?.id != null) ChatPage.DETAILS else ChatPage.LIST
            ChatPage.PEOPLE -> if (s.peopleMode == PeopleMode.ADD) ChatPage.DETAILS else ChatPage.LIST
        }
        state.update {
            it.copy(page = next, error = null, notice = null, form = null,
                current = if (next == ChatPage.LIST) null else it.current,
                messages = if (next == ChatPage.LIST) emptyList() else it.messages)
        }
        if (next == ChatPage.CHAT) s.current?.let { c -> viewModelScope.launch { runCatching { newest(c.id) } } }
        if (next == ChatPage.LIST) viewModelScope.launch {
            runCatching { repo.conversations(auth()) }.onSuccess { list -> state.update { it.copy(conversations = list) } }
        }
        return true
    }
}
