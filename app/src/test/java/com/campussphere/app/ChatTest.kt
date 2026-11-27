package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.chat.*
import com.campussphere.app.presentation.student.Activity
import com.campussphere.app.presentation.student.upcomingActivities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private fun msg(id: Long, mine: Boolean = false, readBy: Long = 0, deleted: Boolean = false, sender: Long = if (mine) 7 else 8) =
        ChatMessage(id, 9, sender, if (mine) "Me" else "Ravi Test", mine, if (deleted) "" else "m$id", null, null, null,
            "2026-11-27T10:00:00+05:30", deleted, readBy)

    private val group = ChatConversation(9, "GROUP", "Java revision", "", "OPEN", false, "MEMBER", 3, 2, null, "Hi",
        "Ravi Test", "2026-11-27T10:00:00+05:30", false, true)

    private open class FakeApi : ChatApi {
        val calls = mutableListOf<String>()
        override suspend fun me(auth: String): Response<StudentMe> = fail("me")
        override suspend fun conversations(auth: String): Response<List<ChatConversation>> = fail("conversations")
        override suspend fun unread(auth: String): Response<ChatCount> = fail("unread")
        override suspend fun conversation(auth: String, id: Long): Response<ChatConversation> = fail("conversation")
        override suspend fun people(auth: String, q: String): Response<List<ChatPerson>> = fail("people")
        override suspend fun direct(auth: String, ref: ChatStudentRef): Response<ChatConversation> = fail("direct")
        override suspend fun openGroups(auth: String, q: String): Response<List<ChatOpenGroup>> = fail("openGroups")
        override suspend fun createGroup(auth: String, input: ChatGroupInput): Response<ChatConversation> = fail("createGroup")
        override suspend fun updateGroup(auth: String, id: Long, input: ChatGroupInput): Response<ChatConversation> = fail("updateGroup")
        override suspend fun archive(auth: String, id: Long): Response<ChatConversation> = fail("archive")
        override suspend fun join(auth: String, id: Long): Response<ChatConversation> = fail("join")
        override suspend fun leave(auth: String, id: Long): Response<Unit> = fail("leave")
        override suspend fun members(auth: String, id: Long): Response<List<ChatMember>> = fail("members")
        override suspend fun addMember(auth: String, id: Long, ref: ChatStudentRef): Response<List<ChatMember>> = fail("addMember")
        override suspend fun removeMember(auth: String, id: Long, studentId: Long): Response<List<ChatMember>> = fail("removeMember")
        override suspend fun messages(auth: String, id: Long, before: Long?): Response<List<ChatMessage>> = fail("messages")
        override suspend fun send(auth: String, id: Long, input: ChatMessageInput): Response<ChatMessage> = fail("send")
        override suspend fun sendFile(auth: String, id: Long, file: MultipartBody.Part, body: RequestBody?, clientKey: RequestBody): Response<ChatMessage> = fail("sendFile")
        override suspend fun delete(auth: String, id: Long, messageId: Long): Response<ChatMessage> = fail("delete")
        override suspend fun read(auth: String, id: Long, input: ChatReadInput): Response<Unit> = fail("read")
        override suspend fun file(auth: String, id: Long, messageId: Long): Response<ResponseBody> = fail("file")
        override suspend fun report(auth: String, id: Long, messageId: Long, input: ChatReportInput): Response<Unit> = fail("report")
        override suspend fun blocked(auth: String): Response<List<ChatPerson>> = fail("blocked")
        override suspend fun block(auth: String, ref: ChatStudentRef): Response<Unit> = fail("block")
        override suspend fun unblock(auth: String, studentId: Long): Response<Unit> = fail("unblock")
        private fun fail(name: String): Nothing = throw AssertionError("Unexpected call: $name")
    }

    @Test fun streamEventsAreParsedFromServerSentEvents() {
        val parser = SseParser()
        val events = listOf("event:ready", "data:{}", "", ": ping", "", "event:message", "data:{\"conversationId\":12}", "",
            "event:read", "data: {\"conversationId\": 3}", "", "data:{\"other\":1}", "")
            .mapNotNull(parser::feed)
        assertEquals(listOf(ChatEvent.Connected, ChatEvent.Changed("message", 12), ChatEvent.Changed("read", 3)), events)
    }

    @Test fun messagesMergeWithoutDuplicatesAndReadLabelsAreHonest() {
        val merged = mergeMessages(listOf(msg(1), msg(2), msg(3)), listOf(msg(3, deleted = true), msg(4), msg(2)))
        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.id })
        assertTrue(merged.first { it.id == 3L }.deleted)
        assertNull(readLabel(msg(5, mine = true), "DIRECT"))
        assertEquals("Seen", readLabel(msg(5, mine = true, readBy = 1), "DIRECT"))
        assertEquals("Read by 4", readLabel(msg(5, mine = true, readBy = 4), "GROUP"))
        assertNull(readLabel(msg(5, mine = false, readBy = 4), "GROUP"))
        assertNull(readLabel(msg(5, mine = true, readBy = 4, deleted = true), "GROUP"))
    }

    @Test fun groupFormsAndTimesAreChecked() {
        assertNull(groupFormProblem(GroupForm(name = "Java revision")))
        assertEquals("Give the group a name of at least 3 characters.", groupFormProblem(GroupForm(name = " ab ")))
        assertEquals("Keep the name to 80 characters.", groupFormProblem(GroupForm(name = "x".repeat(81))))
        assertEquals("Keep the description to 500 characters.", groupFormProblem(GroupForm(name = "Java", description = "d".repeat(501))))
        val zone = ZoneId.of("Asia/Kolkata")
        assertEquals("10:00", chatTime("2026-11-27T10:00:00+05:30", zone, LocalDate.of(2026, 11, 27)))
        assertEquals("26 Nov 10:00", chatTime("2026-11-26T10:00:00+05:30", zone, LocalDate.of(2026, 11, 27)))
        assertEquals("", chatTime(null, zone))
        assertEquals("You cannot message this student.", chatErrorMessage(409, "CHAT_BLOCKED"))
        assertEquals("Chats are only for students of a college.", chatErrorMessage(403, null))
    }

    @Test fun aFailedSendCanBeRetriedWithoutPostingTwice() {
        val api = object : FakeApi() {
            val keys = mutableListOf<String>()
            var failFirst = true
            override suspend fun conversations(auth: String) = Response.success(listOf(group))
            override suspend fun messages(auth: String, id: Long, before: Long?) = Response.success(listOf(msg(2), msg(1)))
            override suspend fun read(auth: String, id: Long, input: ChatReadInput): Response<Unit> { calls += "read ${input.messageId}"; return Response.success(Unit) }
            override suspend fun send(auth: String, id: Long, input: ChatMessageInput): Response<ChatMessage> {
                keys += input.clientKey
                if (failFirst) { failFirst = false; throw java.io.IOException("offline") }
                return Response.success(msg(3, mine = true).copy(body = input.body))
            }
        }
        val vm = ChatViewModel(ChatRepository(api), { "Bearer s" }, {})
        vm.start()
        vm.open(group)
        assertEquals(listOf(1L, 2L), vm.ui.value.messages.map { it.id })
        assertTrue("marks the newest message read", api.calls.contains("read 2"))
        vm.setDraft(" Hello ")
        vm.send()
        assertEquals("Could not reach CampusSphere. Check your connection and try again.", vm.ui.value.error)
        assertEquals(" Hello ", vm.ui.value.draft)
        vm.send()
        assertEquals(2, api.keys.size)
        assertEquals("the retry reuses the first key", api.keys[0], api.keys[1])
        assertEquals("Hello", vm.ui.value.messages.last().body)
        assertEquals("", vm.ui.value.draft)
    }

    @Test fun reportingHidesTheMessageAndBlockingHidesTheSender() {
        val api = object : FakeApi() {
            var report: ChatReportInput? = null
            override suspend fun conversations(auth: String) = Response.success(listOf(group))
            override suspend fun messages(auth: String, id: Long, before: Long?) =
                Response.success(listOf(msg(3, sender = 8), msg(2, sender = 6), msg(1, sender = 8)))
            override suspend fun read(auth: String, id: Long, input: ChatReadInput) = Response.success(Unit)
            override suspend fun report(auth: String, id: Long, messageId: Long, input: ChatReportInput): Response<Unit> {
                report = input; return Response.success(Unit)
            }
        }
        val vm = ChatViewModel(ChatRepository(api), { "Bearer s" }, {})
        vm.start()
        vm.open(group)
        vm.report(vm.ui.value.messages.last(), "ABUSE", " insults ", block = true)
        assertEquals(ChatReportInput("ABUSE", "insults", true), api.report)
        assertEquals("only the other sender's message is left", listOf(2L), vm.ui.value.messages.map { it.id })
    }

    @Test fun refusedSessionsAreReported() {
        val api = object : FakeApi() {
            override suspend fun conversations(auth: String): Response<List<ChatConversation>> =
                Response.error(401, """{"code":"INVALID_AUTHENTICATION"}""".toResponseBody("application/json".toMediaType()))
        }
        var lost = 0
        ChatViewModel(ChatRepository(api), { "Bearer s" }, { lost = it }).start()
        assertEquals(401, lost)
    }

    @Test fun requestsUseStudentRoutesAndFileMessagesSendTheirKey() = runBlocking {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            seen += "${request.method} ${request.url.encodedPath}?${request.url.encodedQuery.orEmpty()} " +
                if (body.contains("form-data")) Regex("name=\"(\\w+)\"").findAll(body).joinToString(",") { it.groupValues[1] } else body
            val json = if (request.url.encodedPath.endsWith("/messages") || request.url.encodedPath.endsWith("/file"))
                """{"id":5,"conversationId":9,"senderId":7,"sender":"Me","mine":true,"body":"","attachmentName":"a.pdf","attachmentFormat":"pdf","attachmentSize":10,"createdAt":"2026-11-27T10:00:00+05:30","deleted":false,"readBy":0}"""
                else "[]"
            okhttp3.Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo = ChatRepository(Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(ChatApi::class.java))
        repo.people("Bearer s", " ash ")
        repo.send("Bearer s", 9, " hi ", "k-1")
        repo.sendFile("Bearer s", 9, MaterialUpload("a.pdf", "application/pdf", "%PDF-1.4\n%%EOF".toByteArray()), "", {}, "k-2")
        assertEquals("GET /api/student/chats/people?q=ash ", seen[0])
        assertEquals("""POST /api/student/chats/9/messages? {"body":"hi","clientKey":"k-1"}""", seen[1])
        assertEquals("POST /api/student/chats/9/messages/file? file,clientKey", seen[2])
    }

    @Test fun anUnavailableStreamEndsQuietlyWithoutAClient() = runBlocking {
        assertEquals(emptyList<ChatEvent>(), ChatRepository(FakeApi()).events { "Bearer s" }.toList())
    }

    @Test fun upcomingMixesLessonsDeadlinesAndEventsSoonestFirst() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 11, 27, 11, 0, 0, 0, zone)
        val slot = { start: String, subject: String -> TimetableSlot(1, "LESSON", 5, start, "12:30", "JAVA101", subject, "Krishna",
            "101", "Room 101", null, "Class A", "BSc", "Semester 1", 1, null) }
        val day = TimetableDay("2026-11-27", 5, TimetableSchedule(true, "A", 1, null, listOf(slot("09:00", "Java"), slot("12:00", "Python"))))
        val upcoming = upcomingActivities(day, null, emptyList(), now)
        assertEquals(listOf(Activity("Lesson", "JAVA101 · Python", "Today 12:00–12:30 · room 101", "2026-11-27T12:00")), upcoming)
        assertTrue(upcomingActivities(null, null, null, now).isEmpty())
    }
}
