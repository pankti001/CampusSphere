package com.campussphere.app.data.remote.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/** The signed-in student's own profile and current class (null fields when not enrolled). */
data class StudentMe(val id: Long, val name: String, val email: String, val college: String, val collegeCode: String,
                     val program: String?, val academicYear: String?, val semester: String?, val division: String?)

/** kind is CLASS, GROUP or DIRECT. For DIRECT, name is the other student's name. */
data class ChatConversation(val id: Long, val kind: String, val name: String?, val description: String?,
                            val joinPolicy: String?, val archived: Boolean, val myRole: String, val members: Long,
                            val unread: Long, val otherStudentId: Long?, val lastMessage: String?, val lastSender: String?,
                            val lastMessageAt: String?, val blocked: Boolean, val canPost: Boolean)

data class ChatMessage(val id: Long, val conversationId: Long, val senderId: Long, val sender: String, val mine: Boolean,
                       val body: String, val attachmentName: String?, val attachmentFormat: String?,
                       val attachmentSize: Long?, val createdAt: String, val deleted: Boolean, val readBy: Long)

data class ChatMember(val studentId: Long, val name: String, val role: String, val me: Boolean)
data class ChatPerson(val studentId: Long, val name: String, val classLabel: String?)
data class ChatOpenGroup(val id: Long, val name: String, val description: String, val members: Long)
data class ChatGroupInput(val name: String, val description: String, val joinPolicy: String)
data class ChatMessageInput(val body: String, val clientKey: String)
data class ChatReportInput(val reason: String, val note: String?, val block: Boolean)
data class ChatStudentRef(val studentId: Long)
data class ChatReadInput(val messageId: Long)
data class ChatCount(val count: Long)

interface ChatApi {
    @GET("api/student/me")
    suspend fun me(@Header("Authorization") auth: String): Response<StudentMe>

    @GET("api/student/chats")
    suspend fun conversations(@Header("Authorization") auth: String): Response<List<ChatConversation>>

    @GET("api/student/chats/unread")
    suspend fun unread(@Header("Authorization") auth: String): Response<ChatCount>

    @GET("api/student/chats/{id}")
    suspend fun conversation(@Header("Authorization") auth: String, @Path("id") id: Long): Response<ChatConversation>

    @GET("api/student/chats/people")
    suspend fun people(@Header("Authorization") auth: String, @Query("q") q: String): Response<List<ChatPerson>>

    @POST("api/student/chats/direct")
    suspend fun direct(@Header("Authorization") auth: String, @Body ref: ChatStudentRef): Response<ChatConversation>

    @GET("api/student/chats/groups/open")
    suspend fun openGroups(@Header("Authorization") auth: String, @Query("q") q: String): Response<List<ChatOpenGroup>>

    @POST("api/student/chats/groups")
    suspend fun createGroup(@Header("Authorization") auth: String, @Body input: ChatGroupInput): Response<ChatConversation>

    @PUT("api/student/chats/{id}")
    suspend fun updateGroup(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: ChatGroupInput): Response<ChatConversation>

    @POST("api/student/chats/{id}/archive")
    suspend fun archive(@Header("Authorization") auth: String, @Path("id") id: Long): Response<ChatConversation>

    @POST("api/student/chats/{id}/join")
    suspend fun join(@Header("Authorization") auth: String, @Path("id") id: Long): Response<ChatConversation>

    @POST("api/student/chats/{id}/leave")
    suspend fun leave(@Header("Authorization") auth: String, @Path("id") id: Long): Response<Unit>

    @GET("api/student/chats/{id}/members")
    suspend fun members(@Header("Authorization") auth: String, @Path("id") id: Long): Response<List<ChatMember>>

    @POST("api/student/chats/{id}/members")
    suspend fun addMember(@Header("Authorization") auth: String, @Path("id") id: Long, @Body ref: ChatStudentRef): Response<List<ChatMember>>

    @DELETE("api/student/chats/{id}/members/{studentId}")
    suspend fun removeMember(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("studentId") studentId: Long): Response<List<ChatMember>>

    @GET("api/student/chats/{id}/messages")
    suspend fun messages(@Header("Authorization") auth: String, @Path("id") id: Long, @Query("before") before: Long?): Response<List<ChatMessage>>

    @POST("api/student/chats/{id}/messages")
    suspend fun send(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: ChatMessageInput): Response<ChatMessage>

    @Multipart
    @POST("api/student/chats/{id}/messages/file")
    suspend fun sendFile(@Header("Authorization") auth: String, @Path("id") id: Long, @Part file: MultipartBody.Part,
                         @Part("body") body: RequestBody?, @Part("clientKey") clientKey: RequestBody): Response<ChatMessage>

    @DELETE("api/student/chats/{id}/messages/{messageId}")
    suspend fun delete(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("messageId") messageId: Long): Response<ChatMessage>

    @POST("api/student/chats/{id}/read")
    suspend fun read(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: ChatReadInput): Response<Unit>

    @Streaming
    @GET("api/student/chats/{id}/messages/{messageId}/file")
    suspend fun file(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("messageId") messageId: Long): Response<ResponseBody>

    @POST("api/student/chats/{id}/messages/{messageId}/report")
    suspend fun report(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("messageId") messageId: Long,
                       @Body input: ChatReportInput): Response<Unit>

    @GET("api/student/chats/blocks")
    suspend fun blocked(@Header("Authorization") auth: String): Response<List<ChatPerson>>

    @POST("api/student/chats/blocks")
    suspend fun block(@Header("Authorization") auth: String, @Body ref: ChatStudentRef): Response<Unit>

    @DELETE("api/student/chats/blocks/{studentId}")
    suspend fun unblock(@Header("Authorization") auth: String, @Path("studentId") studentId: Long): Response<Unit>
}
