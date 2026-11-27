package com.campussphere.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import com.campussphere.app.data.remote.api.*
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.IOException
import java.util.UUID

/** A failed chat request. [status] is the HTTP status (0 for network or local file problems). */
class ChatFailure(val status: Int, val code: String?, message: String) : Exception(message)

val CHAT_REPORT_REASONS = listOf("SPAM" to "Spam", "ABUSE" to "Abuse", "HARASSMENT" to "Harassment",
    "INAPPROPRIATE" to "Inappropriate content", "OTHER" to "Something else")

/** Friendly message for a server error code. The server's own text is never shown. */
fun chatErrorMessage(status: Int, code: String?): String = when (code) {
    "CHAT_NOT_FOUND" -> "This chat or message is no longer available."
    "CHAT_INVALID" -> "Check the message or group details and try again."
    "CHAT_NOT_ALLOWED" -> "You cannot do that in this chat."
    "CHAT_STATE_CONFLICT" -> "This group is archived; no new messages or changes."
    "CHAT_BLOCKED" -> "You cannot message this student."
    "CHAT_LIMIT" -> "The group is full, or you already own the maximum of 20 groups."
    "CHAT_FILE_INVALID", "MATERIAL_FILE_INVALID" -> "Share a PDF, Word, PowerPoint, Excel, text, JPG or PNG file up to 10 MB."
    "VERIFICATION_REQUIRED" -> "Your account needs verification before you can use chats."
    "INVALID_REQUEST" -> "Check the details and try again."
    else -> when (status) {
        401 -> "Your session expired. Sign in again."
        403 -> "Chats are only for students of a college."
        404 -> "This chat is no longer available."
        413 -> "The file is larger than 10 MB."
        in 500..599 -> "Chats are not available right now. Try again shortly."
        else -> "Could not reach CampusSphere. Check your connection and try again."
    }
}

/** One live update: which conversation changed and how ("message", "read" or "members"). */
sealed interface ChatEvent {
    data object Connected : ChatEvent
    data class Changed(val type: String, val conversationId: Long) : ChatEvent
    /** The stream was refused (401/403); the app should refresh the session instead of retrying. */
    data class Refused(val status: Int) : ChatEvent
}

/** Reads Server-Sent Events line by line; returns an event when a blank line ends one. */
class SseParser {
    private var name = "message"
    private val data = StringBuilder()

    fun feed(line: String): ChatEvent? {
        if (line.isEmpty()) {
            val type = name
            val text = data.toString()
            name = "message"
            data.clear()
            if (type == "ready") return ChatEvent.Connected
            val id = Regex("\"conversationId\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            return ChatEvent.Changed(type, id)
        }
        when {
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> name = line.removePrefix("event:").trim()
            line.startsWith("data:") -> { if (data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").trim()) }
        }
        return null
    }
}

class ChatRepository(private val api: ChatApi, private val streamClient: OkHttpClient? = null,
                     private val baseUrl: String = "") {
    suspend fun me(auth: String) = call { api.me(auth) }
    suspend fun conversations(auth: String) = call { api.conversations(auth) }
    suspend fun unread(auth: String) = call { api.unread(auth) }.count
    suspend fun conversation(auth: String, id: Long) = call { api.conversation(auth, id) }
    suspend fun people(auth: String, q: String) = call { api.people(auth, q.trim()) }
    suspend fun direct(auth: String, studentId: Long) = call { api.direct(auth, ChatStudentRef(studentId)) }
    suspend fun openGroups(auth: String, q: String) = call { api.openGroups(auth, q.trim()) }
    suspend fun createGroup(auth: String, input: ChatGroupInput) = call { api.createGroup(auth, input) }
    suspend fun updateGroup(auth: String, id: Long, input: ChatGroupInput) = call { api.updateGroup(auth, id, input) }
    suspend fun archive(auth: String, id: Long) = call { api.archive(auth, id) }
    suspend fun join(auth: String, id: Long) = call { api.join(auth, id) }
    suspend fun leave(auth: String, id: Long) { call { api.leave(auth, id) } }
    suspend fun members(auth: String, id: Long) = call { api.members(auth, id) }
    suspend fun addMember(auth: String, id: Long, studentId: Long) = call { api.addMember(auth, id, ChatStudentRef(studentId)) }
    suspend fun removeMember(auth: String, id: Long, studentId: Long) = call { api.removeMember(auth, id, studentId) }
    suspend fun messages(auth: String, id: Long, before: Long? = null) = call { api.messages(auth, id, before) }
    suspend fun send(auth: String, id: Long, body: String, clientKey: String = UUID.randomUUID().toString()) =
        call { api.send(auth, id, ChatMessageInput(body.trim(), clientKey)) }
    suspend fun sendFile(auth: String, id: Long, file: MaterialUpload, caption: String, onProgress: (Float) -> Unit,
                         clientKey: String = UUID.randomUUID().toString()): ChatMessage {
        val text = "text/plain".toMediaType()
        val part = MultipartBody.Part.createFormData("file", file.fileName,
            ProgressRequestBody(file.bytes, file.mimeType.toMediaType(), onProgress))
        return call { api.sendFile(auth, id, part, caption.trim().ifEmpty { null }?.toRequestBody(text), clientKey.toRequestBody(text)) }
    }
    suspend fun delete(auth: String, id: Long, messageId: Long) = call { api.delete(auth, id, messageId) }
    suspend fun read(auth: String, id: Long, messageId: Long) { call { api.read(auth, id, ChatReadInput(messageId)) } }
    suspend fun report(auth: String, id: Long, messageId: Long, reason: String, note: String?, block: Boolean) {
        call { api.report(auth, id, messageId, ChatReportInput(reason, note?.trim()?.ifEmpty { null }, block)) }
    }
    suspend fun blocked(auth: String) = call { api.blocked(auth) }
    suspend fun block(auth: String, studentId: Long) { call { api.block(auth, ChatStudentRef(studentId)) } }
    suspend fun unblock(auth: String, studentId: Long) { call { api.unblock(auth, studentId) } }

    /** Downloads a shared file into memory (at most 10 MB), for showing images in the chat. */
    suspend fun fileBytes(auth: String, id: Long, messageId: Long): ByteArray {
        val body = call { api.file(auth, id, messageId) }
        return withContext(Dispatchers.IO) { body.use { it.bytes() } }
    }

    /** Streams a shared file into the document the student chose. */
    suspend fun saveFile(auth: String, id: Long, messageId: Long, resolver: ContentResolver, target: Uri) {
        val body = call { api.file(auth, id, messageId) }
        withContext(Dispatchers.IO) {
            body.use { stream ->
                resolver.openOutputStream(target, "wt")?.use { stream.byteStream().copyTo(it) }
                    ?: throw ChatFailure(0, null, "The download location could not be opened.")
            }
        }
    }

    /**
     * Live updates while collected: connects to the chat stream, reconnects after a drop (waiting a little longer
     * each time, up to 30 s) and stops for good when the server refuses the session.
     */
    fun events(auth: () -> String): Flow<ChatEvent> = flow {
        val client = streamClient ?: return@flow
        var wait = 1_000L
        while (currentCoroutineContext().isActive) {
            val call = client.newCall(Request.Builder().url(baseUrl + "api/student/chats/stream")
                .header("Authorization", auth()).header("Accept", "text/event-stream").build())
            val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        emit(ChatEvent.Refused(response.code))
                        return@flow
                    }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    wait = 1_000L
                    val source = response.body?.source() ?: throw IOException("No stream")
                    val parser = SseParser()
                    while (!source.exhausted()) parser.feed(source.readUtf8LineStrict())?.let { emit(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Dropped or not reachable: try again shortly.
            } finally {
                cancel?.dispose()
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000L)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChatFailure) {
            throw e
        } catch (e: IOException) {
            throw ChatFailure(0, null, chatErrorMessage(0, null))
        }
        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            return (response.body() ?: Unit) as T
        }
        val code = runCatching {
            JsonParser.parseString(response.errorBody()?.string().orEmpty()).asJsonObject["code"]?.asString
        }.getOrNull()
        throw ChatFailure(response.code(), code, chatErrorMessage(response.code(), code))
    }
}
