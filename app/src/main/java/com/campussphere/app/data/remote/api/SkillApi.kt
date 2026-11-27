package com.campussphere.app.data.remote.api

import retrofit2.Response
import retrofit2.http.*

/** A time the provider is usually free: day 1 = Monday … 7 = Sunday; times are "HH:mm" or "HH:mm:ss". */
data class SkillSlot(val day: Int, val start: String, val end: String)

/** A skill a classmate (or the student) offers to teach. Only offers of the student's own college ever arrive. */
data class SkillOffer(val id: Long, val providerId: Long, val provider: String, val classLabel: String?, val name: String,
                      val category: String, val description: String, val level: String, val teachingMethod: String,
                      val mode: String, val status: String, val rating: Double?, val reviews: Long, val mine: Boolean,
                      val myRequest: String?, val slots: List<SkillSlot>)

data class SkillOfferInput(val name: String, val category: String, val description: String, val level: String,
                           val teachingMethod: String, val mode: String, val slots: List<SkillSlot>)

/** A learning request. [incoming] is true when the student is the provider; [partner] is the other student. */
data class SkillRequest(val id: Long, val offerId: Long, val offerName: String, val providerId: Long, val provider: String,
                        val learnerId: Long, val learner: String, val exchangeOfferId: Long?, val exchangeOfferName: String?,
                        val message: String, val status: String, val createdAt: String, val decidedAt: String?,
                        val incoming: Boolean, val partnerId: Long, val partner: String, val exchange: Boolean,
                        val completedSessions: Long)

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
                       val sessionId: Long?, val startsAt: String?, val createdAt: String, val readAt: String?)
data class SkillSummary(val myOffers: Long, val incomingRequests: Long, val outgoingRequests: Long, val upcomingSessions: Long,
                        val nextSession: SkillSession?, val unreadNotices: Long)
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
}
