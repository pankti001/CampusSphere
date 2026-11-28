package com.campussphere.app.data.remote.api

import retrofit2.Response
import retrofit2.http.*

/** A time the provider is usually free: day 1 = Monday … 7 = Sunday; times are "HH:mm" or "HH:mm:ss". */
data class SkillSlot(val day: Int, val start: String, val end: String)

/** A skill a classmate (or the student) offers to teach. Only offers of the student's own college ever arrive. */
data class SkillOffer(val id: Long, val providerId: Long, val provider: String, val classLabel: String?, val name: String,
                      val category: String, val description: String, val level: String, val teachingMethod: String,
                      val mode: String, val status: String, val rating: Double?, val reviews: Long, val mine: Boolean,
                      val myRequest: String?, val slots: List<SkillSlot>, val audience: String? = "COLLEGE")

/** audience is "CLASS" (only my class) or "COLLEGE" (my whole college). */
data class SkillOfferInput(val name: String, val category: String, val description: String, val level: String,
                           val teachingMethod: String, val mode: String, val slots: List<SkillSlot>, val audience: String)

/** A learning request. [incoming] is true when the student is the provider; [partner] is the other student. */
data class SkillRequest(val id: Long, val offerId: Long?, val offerName: String, val providerId: Long, val provider: String,
                        val learnerId: Long, val learner: String, val exchangeOfferId: Long?, val exchangeOfferName: String?,
                        val message: String, val status: String, val createdAt: String, val decidedAt: String?,
                        val incoming: Boolean, val partnerId: Long, val partner: String, val exchange: Boolean,
                        val completedSessions: Long, val wantId: Long? = null, val groupId: Long? = null)

data class SkillRequestInput(val offerId: Long, val exchangeOfferId: Long?, val message: String)

data class SkillSession(val id: Long, val requestId: Long, val skill: String, val teacherId: Long, val teacher: String,
                        val pupilId: Long, val pupil: String, val startsAt: String, val endsAt: String, val mode: String,
                        val place: String, val status: String, val iTeach: Boolean, val partnerId: Long, val partner: String,
                        val needsMyConfirmation: Boolean, val canComplete: Boolean, val canReview: Boolean,
                        val myRating: Int?, val cancelReason: String?)

data class SkillSessionInput(val teach: Boolean, val startsAt: String, val endsAt: String, val mode: String, val place: String)
data class SkillCancelInput(val reason: String)
data class SkillReviewInput(val rating: Int, val comment: String)
data class SkillReview(val id: Long, val rating: Int, val comment: String, val reviewer: String, val skill: String, val createdAt: String)
data class SkillNotice(val id: Long, val kind: String, val title: String, val body: String, val requestId: Long?,
                       val sessionId: Long?, val startsAt: String?, val createdAt: String, val readAt: String?,
                       val wantId: Long? = null, val groupId: Long? = null, val groupSessionId: Long? = null,
                       val facultyRequestId: Long? = null)
data class SkillSummary(val myOffers: Long, val incomingRequests: Long, val outgoingRequests: Long, val upcomingSessions: Long,
                        val nextSession: SkillSession?, val unreadNotices: Long, val openWants: Long = 0,
                        val groups: Long = 0, val facultyRequests: Long = 0)

// ---- "I want to learn" posts ----
data class SkillWant(val id: Long, val studentId: Long, val student: String, val classLabel: String?, val title: String,
                     val details: String, val category: String, val audience: String, val status: String, val createdAt: String,
                     val comments: Long, val supporters: Long, val helpers: Long, val mine: Boolean, val meToo: Boolean,
                     val myHelp: String?)
data class SkillWantInput(val title: String, val details: String, val category: String, val audience: String)
data class SkillTextInput(val text: String)
data class SkillWantComment(val id: Long, val authorId: Long, val author: String, val body: String, val createdAt: String,
                            val mine: Boolean, val deleted: Boolean)
data class SkillWantHelper(val id: Long, val teacherId: Long, val teacher: String, val classLabel: String?, val message: String,
                           val status: String, val createdAt: String, val rating: Double?, val reviews: Long)
data class SkillWantDetail(val want: SkillWant, val comments: List<SkillWantComment>, val helpers: List<SkillWantHelper>,
                           val supporterNames: List<String>)

// ---- Learning groups ----
data class SkillGroupSession(val id: Long, val startsAt: String, val endsAt: String, val mode: String, val place: String,
                             val status: String, val cancelReason: String?, val canComplete: Boolean)
data class SkillGroup(val id: Long, val name: String, val teacherId: Long, val teacher: String, val iTeach: Boolean,
                      val status: String, val conversationId: Long?, val members: Long, val nextSession: SkillGroupSession?,
                      val myRequestId: Long?)
data class SkillGroupMember(val studentId: Long, val name: String, val classLabel: String?, val requestId: Long)
data class SkillGroupDetail(val group: SkillGroup, val members: List<SkillGroupMember>, val sessions: List<SkillGroupSession>)
data class SkillGroupSessionInput(val startsAt: String, val endsAt: String, val mode: String, val place: String)

// ---- Learning from faculty ----
data class SkillFacultyChoice(val id: Long, val name: String, val teachesMyClass: Boolean, val subjects: String?)
data class FacultyLearningRequest(val id: Long, val facultyId: Long, val faculty: String, val createdById: Long,
                                  val createdBy: String, val topic: String, val details: String, val audience: String,
                                  val status: String, val startsAt: String?, val endsAt: String?, val mode: String?,
                                  val place: String?, val reason: String?, val createdAt: String, val students: Long,
                                  val joined: Boolean, val mine: Boolean, val canComplete: Boolean)
data class FacultyLearningStudent(val id: Long, val name: String, val classLabel: String?)
data class FacultyLearningDetail(val request: FacultyLearningRequest, val students: List<FacultyLearningStudent>)
data class FacultyAskInput(val facultyId: Long, val topic: String, val details: String, val audience: String)
data class FacultyAcceptInput(val startsAt: String, val endsAt: String, val mode: String, val place: String)
data class FacultyReasonInput(val reason: String)
data class FacultyLearningNotice(val id: Long, val kind: String, val title: String, val body: String, val requestId: Long,
                                 val createdAt: String, val readAt: String?)
data class SkillCount(val count: Long)

/** Skill Exchange. No call sends a college: the server always uses the signed-in student's own college. */
interface SkillApi {
    @GET("api/student/skills/summary")
    suspend fun summary(@Header("Authorization") auth: String): Response<SkillSummary>

    @GET("api/student/skills/offers")
    suspend fun browse(@Header("Authorization") auth: String, @Query("q") q: String?, @Query("category") category: String?,
                       @Query("level") level: String?, @Query("mode") mode: String?, @Query("page") page: Int): Response<List<SkillOffer>>

    @GET("api/student/skills/offers/{id}")
    suspend fun offer(@Header("Authorization") auth: String, @Path("id") id: Long): Response<SkillOffer>

    @GET("api/student/skills/mine")
    suspend fun mine(@Header("Authorization") auth: String): Response<List<SkillOffer>>

    @POST("api/student/skills/mine")
    suspend fun create(@Header("Authorization") auth: String, @Body input: SkillOfferInput): Response<SkillOffer>

    @PUT("api/student/skills/mine/{id}")
    suspend fun update(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: SkillOfferInput): Response<SkillOffer>

    /** action is "pause" or "resume". */
    @POST("api/student/skills/mine/{id}/{action}")
    suspend fun setStatus(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("action") action: String): Response<SkillOffer>

    @DELETE("api/student/skills/mine/{id}")
    suspend fun archive(@Header("Authorization") auth: String, @Path("id") id: Long): Response<Unit>

    /** box is "incoming", "outgoing" or "all". */
    @GET("api/student/skills/requests")
    suspend fun requests(@Header("Authorization") auth: String, @Query("box") box: String, @Query("status") status: String?,
                         @Query("page") page: Int): Response<List<SkillRequest>>

    @GET("api/student/skills/requests/{id}")
    suspend fun request(@Header("Authorization") auth: String, @Path("id") id: Long): Response<SkillRequest>

    @POST("api/student/skills/requests")
    suspend fun ask(@Header("Authorization") auth: String, @Body input: SkillRequestInput): Response<SkillRequest>

    /** action is "accept", "reject" or "cancel". */
    @POST("api/student/skills/requests/{id}/{action}")
    suspend fun decide(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("action") action: String): Response<SkillRequest>

    @POST("api/student/skills/requests/{id}/sessions")
    suspend fun propose(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: SkillSessionInput): Response<SkillSession>

    /** when is "upcoming" or "history". */
    @GET("api/student/skills/sessions")
    suspend fun sessions(@Header("Authorization") auth: String, @Query("when") whenFilter: String): Response<List<SkillSession>>

    /** action is "confirm" or "complete". */
    @POST("api/student/skills/sessions/{id}/{action}")
    suspend fun act(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("action") action: String): Response<SkillSession>

    @POST("api/student/skills/sessions/{id}/cancel")
    suspend fun cancel(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: SkillCancelInput): Response<SkillSession>

    @POST("api/student/skills/sessions/{id}/review")
    suspend fun review(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: SkillReviewInput): Response<SkillReview>

    @GET("api/student/skills/students/{id}/reviews")
    suspend fun reviews(@Header("Authorization") auth: String, @Path("id") studentId: Long): Response<List<SkillReview>>

    @GET("api/student/skills/notifications")
    suspend fun notices(@Header("Authorization") auth: String, @Query("page") page: Int): Response<List<SkillNotice>>

    @GET("api/student/skills/notifications/unread")
    suspend fun unread(@Header("Authorization") auth: String): Response<SkillCount>

    @POST("api/student/skills/notifications/{id}/read")
    suspend fun read(@Header("Authorization") auth: String, @Path("id") id: Long): Response<Unit>

    // ---- "I want to learn" posts ----
    @GET("api/student/skills/wants")
    suspend fun wants(@Header("Authorization") auth: String, @Query("mine") mine: Boolean, @Query("q") q: String?,
                      @Query("category") category: String?, @Query("page") page: Int): Response<List<SkillWant>>

    @POST("api/student/skills/wants")
    suspend fun postWant(@Header("Authorization") auth: String, @Body input: SkillWantInput): Response<SkillWant>

    @GET("api/student/skills/wants/{id}")
    suspend fun want(@Header("Authorization") auth: String, @Path("id") id: Long): Response<SkillWantDetail>

    @POST("api/student/skills/wants/{id}/close")
    suspend fun closeWant(@Header("Authorization") auth: String, @Path("id") id: Long): Response<SkillWant>

    @POST("api/student/skills/wants/{id}/comments")
    suspend fun comment(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: SkillTextInput): Response<SkillWantComment>

    @DELETE("api/student/skills/wants/{id}/comments/{commentId}")
    suspend fun deleteComment(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("commentId") commentId: Long): Response<Unit>

    @POST("api/student/skills/wants/{id}/me-too")
    suspend fun meToo(@Header("Authorization") auth: String, @Path("id") id: Long): Response<SkillWant>

    @DELETE("api/student/skills/wants/{id}/me-too")
    suspend fun notMeToo(@Header("Authorization") auth: String, @Path("id") id: Long): Response<SkillWant>

    @POST("api/student/skills/wants/{id}/help")
    suspend fun help(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: SkillTextInput): Response<SkillWant>

    @DELETE("api/student/skills/wants/{id}/help")
    suspend fun withdrawHelp(@Header("Authorization") auth: String, @Path("id") id: Long): Response<SkillWant>

    @POST("api/student/skills/wants/{id}/helpers/{helperId}/accept")
    suspend fun acceptHelper(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("helperId") helperId: Long): Response<SkillRequest>

    @POST("api/student/skills/wants/{id}/helpers/{helperId}/decline")
    suspend fun declineHelper(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("helperId") helperId: Long): Response<SkillWantDetail>

    // ---- Learning groups ----
    @GET("api/student/skills/groups")
    suspend fun groups(@Header("Authorization") auth: String): Response<List<SkillGroup>>

    @GET("api/student/skills/groups/{id}")
    suspend fun group(@Header("Authorization") auth: String, @Path("id") id: Long): Response<SkillGroupDetail>

    @POST("api/student/skills/groups/{id}/sessions")
    suspend fun groupSession(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: SkillGroupSessionInput): Response<SkillGroupDetail>

    @POST("api/student/skills/groups/{id}/sessions/{sessionId}/complete")
    suspend fun completeGroupSession(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("sessionId") sessionId: Long): Response<SkillGroupDetail>

    @POST("api/student/skills/groups/{id}/sessions/{sessionId}/cancel")
    suspend fun cancelGroupSession(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("sessionId") sessionId: Long,
                                   @Body input: SkillCancelInput): Response<SkillGroupDetail>

    @POST("api/student/skills/groups/{id}/leave")
    suspend fun leaveGroup(@Header("Authorization") auth: String, @Path("id") id: Long): Response<Unit>

    // ---- Learning from faculty ----
    @GET("api/student/skills/faculty")
    suspend fun facultyChoices(@Header("Authorization") auth: String): Response<List<SkillFacultyChoice>>

    @GET("api/student/skills/faculty-requests")
    suspend fun facultyRequests(@Header("Authorization") auth: String): Response<List<FacultyLearningRequest>>

    @POST("api/student/skills/faculty-requests")
    suspend fun askFaculty(@Header("Authorization") auth: String, @Body input: FacultyAskInput): Response<FacultyLearningRequest>

    @GET("api/student/skills/faculty-requests/{id}")
    suspend fun facultyRequest(@Header("Authorization") auth: String, @Path("id") id: Long): Response<FacultyLearningDetail>

    /** action is "join", "leave" or "cancel". */
    @POST("api/student/skills/faculty-requests/{id}/{action}")
    suspend fun facultyRequestAction(@Header("Authorization") auth: String, @Path("id") id: Long, @Path("action") action: String): Response<FacultyLearningRequest>
}

/** The faculty side: students asking the signed-in faculty member to teach a topic. */
interface FacultyLearningApi {
    @GET("api/faculty/learning-requests")
    suspend fun list(@Header("Authorization") auth: String): Response<List<FacultyLearningRequest>>

    @GET("api/faculty/learning-requests/{id}")
    suspend fun one(@Header("Authorization") auth: String, @Path("id") id: Long): Response<FacultyLearningDetail>

    @POST("api/faculty/learning-requests/{id}/accept")
    suspend fun accept(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: FacultyAcceptInput): Response<FacultyLearningDetail>

    @POST("api/faculty/learning-requests/{id}/decline")
    suspend fun decline(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: FacultyReasonInput): Response<FacultyLearningDetail>

    @POST("api/faculty/learning-requests/{id}/cancel")
    suspend fun cancel(@Header("Authorization") auth: String, @Path("id") id: Long, @Body input: FacultyReasonInput): Response<FacultyLearningDetail>

    @POST("api/faculty/learning-requests/{id}/complete")
    suspend fun complete(@Header("Authorization") auth: String, @Path("id") id: Long): Response<FacultyLearningDetail>

    @GET("api/faculty/learning-requests/notices")
    suspend fun notices(@Header("Authorization") auth: String): Response<List<FacultyLearningNotice>>

    @GET("api/faculty/learning-requests/notices/unread")
    suspend fun unread(@Header("Authorization") auth: String): Response<SkillCount>

    @POST("api/faculty/learning-requests/notices/{id}/read")
    suspend fun read(@Header("Authorization") auth: String, @Path("id") id: Long): Response<Unit>
}
