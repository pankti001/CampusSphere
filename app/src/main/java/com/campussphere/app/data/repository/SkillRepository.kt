package com.campussphere.app.data.repository

import com.campussphere.app.data.remote.api.*
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A failed Skill Exchange request. [status] is the HTTP status (0 for network problems). */
class SkillFailure(val status: Int, val code: String?, message: String) : Exception(message)

val SKILL_CATEGORIES = listOf("PROGRAMMING" to "Programming", "DESIGN" to "Design", "LANGUAGES" to "Languages",
    "MATHEMATICS" to "Mathematics", "SCIENCE" to "Science", "BUSINESS" to "Business", "ARTS" to "Arts",
    "MUSIC" to "Music", "SPORTS" to "Sports", "ACADEMICS" to "Academics", "OTHER" to "Other")
val SKILL_LEVELS = listOf("BEGINNER" to "Beginner", "INTERMEDIATE" to "Intermediate", "ADVANCED" to "Advanced")
val SKILL_OFFER_MODES = listOf("ONLINE" to "Online", "IN_PERSON" to "In person", "BOTH" to "Online or in person")
val SKILL_SESSION_MODES = listOf("ONLINE" to "Online", "IN_PERSON" to "In person")
val SKILL_DAYS = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")

fun skillLabel(options: List<Pair<String, String>>, key: String?): String = options.firstOrNull { it.first == key }?.second ?: key.orEmpty()

fun skillCategoryIcon(category: String): String = when (category) {
    "PROGRAMMING" -> "💻"; "DESIGN" -> "🎨"; "LANGUAGES" -> "🗣"; "MATHEMATICS" -> "➗"; "SCIENCE" -> "🔬"
    "BUSINESS" -> "📈"; "ARTS" -> "🖌"; "MUSIC" -> "🎵"; "SPORTS" -> "🏅"; "ACADEMICS" -> "📘"; else -> "✨"
}

/** Friendly message for a server error code. The server's own text is never shown. */
fun skillErrorMessage(status: Int, code: String?): String = when (code) {
    "SKILL_NOT_FOUND" -> "This skill, request or session is no longer available."
    "SKILL_INVALID" -> "Check the details and try again."
    "SKILL_STATE_CONFLICT" -> "This cannot be done right now. Refresh and try again."
    "SKILL_DUPLICATE" -> "You already offer this skill, or already have an open request for it."
    "SKILL_REVIEW_EXISTS" -> "You have already reviewed this session."
    "NOTIFICATION_NOT_FOUND" -> "This notification is no longer available."
    "VERIFICATION_REQUIRED" -> "Your account needs verification before you can use Skill Exchange."
    "INVALID_REQUEST" -> "Check the details and try again."
    else -> when (status) {
        401 -> "Your session expired. Sign in again."
        403 -> "Skill Exchange is only for students of a college."
        404 -> "This is no longer available."
        in 500..599 -> "Skill Exchange is not available right now. Try again shortly."
        else -> "Could not reach CampusSphere. Check your connection and try again."
    }
}

/** What the student is typing for an offer. Slots are day + "HH:mm" text. */
data class SkillOfferForm(
    val id: Long? = null, val name: String = "", val category: String = "PROGRAMMING", val description: String = "",
    val level: String = "BEGINNER", val teachingMethod: String = "", val mode: String = "BOTH",
    val slots: List<SkillSlot> = emptyList(),
)

fun SkillOffer.toForm() = SkillOfferForm(id, name, category, description, level, teachingMethod, mode,
    slots.map { SkillSlot(it.day, it.start.take(5), it.end.take(5)) })

private fun clock(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim()) }.getOrNull()

/** Checks an offer form before it is sent; null when it is fine. */
fun skillOfferProblem(form: SkillOfferForm): String? {
    val name = form.name.trim()
    return when {
        name.length < 2 -> "Give the skill a name of at least 2 characters."
        name.length > 60 -> "Keep the skill name to 60 characters."
        form.description.trim().length > 1000 -> "Keep the description to 1000 characters."
        form.teachingMethod.trim().length > 300 -> "Keep the teaching method to 300 characters."
        form.slots.size > 14 -> "Add at most 14 available times."
        form.slots.any { s -> s.day !in 1..7 || clock(s.start) == null || clock(s.end) == null } -> "Write times as HH:mm, for example 16:30."
        form.slots.any { s -> !clock(s.end)!!.isAfter(clock(s.start)!!) } -> "Each available time must end after it starts."
        form.slots.groupBy { it.day to clock(it.start) }.any { it.value.size > 1 } -> "Two available times start at the same moment."
        else -> null
    }
}

fun SkillOfferForm.toInput() = SkillOfferInput(name.trim(), category, description.trim(), level, teachingMethod.trim(), mode,
    slots.map { SkillSlot(it.day, clock(it.start)!!.toString(), clock(it.end)!!.toString()) })

/** What the student is typing to propose a session: a date, a start time and a length. */
data class SkillSessionForm(val teach: Boolean = false, val date: String = "", val time: String = "", val minutes: Int = 60,
                            val mode: String = "ONLINE", val place: String = "")

/** Turns a session form into the times to send, in the phone's time zone; or a message saying what to fix. */
fun skillSessionTimes(form: SkillSessionForm, now: ZonedDateTime, zone: ZoneId = now.zone): Result<Pair<OffsetDateTime, OffsetDateTime>> {
    val date = runCatching { LocalDate.parse(form.date.trim()) }.getOrNull()
        ?: return Result.failure(IllegalArgumentException("Write the date as YYYY-MM-DD."))
    val time = clock(form.time) ?: return Result.failure(IllegalArgumentException("Write the start time as HH:mm."))
    val start = ZonedDateTime.of(date, time, zone)
    return when {
        form.minutes !in 15..240 -> Result.failure(IllegalArgumentException("A session lasts 15 minutes to 4 hours."))
        !start.isAfter(now) -> Result.failure(IllegalArgumentException("Choose a time in the future."))
        start.isAfter(now.plusDays(180)) -> Result.failure(IllegalArgumentException("Choose a time within the next 6 months."))
        form.place.trim().length > 300 -> Result.failure(IllegalArgumentException("Keep the place or link to 300 characters."))
        else -> Result.success(start.toOffsetDateTime() to start.plusMinutes(form.minutes.toLong()).toOffsetDateTime())
    }
}

private val SESSION_DAY = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK)
private val SESSION_END = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

/** "Wed 3 Dec, 16:00–17:00" in the phone's time zone. */
fun skillSessionTime(startIso: String, endIso: String, zone: ZoneId = ZoneId.systemDefault()): String = runCatching {
    OffsetDateTime.parse(startIso).atZoneSameInstant(zone).format(SESSION_DAY) + "–" +
        OffsetDateTime.parse(endIso).atZoneSameInstant(zone).format(SESSION_END)
}.getOrDefault(startIso)

/** "Mon 16:00–17:00". */
fun skillSlotText(slot: SkillSlot) = "${skillLabel(SKILL_DAYS.map { it.first.toString() to it.second }, slot.day.toString())} " +
    "${slot.start.take(5)}–${slot.end.take(5)}"

/** "★ 4.5 (12)" or "New" when there are no reviews yet. */
fun skillRatingText(rating: Double?, reviews: Long): String =
    if (rating == null || reviews == 0L) "New" else "★ ${String.format(Locale.UK, "%.1f", rating)} ($reviews)"

fun skillStatusLabel(status: String): String = when (status) {
    "REQUESTED" -> "Waiting"; "ACCEPTED" -> "Accepted"; "REJECTED" -> "Declined"; "CANCELLED" -> "Cancelled"
    "SCHEDULED" -> "Confirmed"; "COMPLETED" -> "Completed"; "ACTIVE" -> "Visible"; "PAUSED" -> "Paused"
    else -> status
}

class SkillRepository(private val api: SkillApi) {
    suspend fun summary(auth: String) = call { api.summary(auth) }
    suspend fun browse(auth: String, q: String, category: String?, level: String?, mode: String?, page: Int = 0) =
        call { api.browse(auth, q.trim().ifEmpty { null }, category, level, mode, page) }
    suspend fun offer(auth: String, id: Long) = call { api.offer(auth, id) }
    suspend fun mine(auth: String) = call { api.mine(auth) }
    suspend fun save(auth: String, form: SkillOfferForm): SkillOffer {
        skillOfferProblem(form)?.let { throw SkillFailure(0, "LOCAL", it) }
        val input = form.toInput()
        return call { form.id?.let { api.update(auth, it, input) } ?: api.create(auth, input) }
    }
    suspend fun pause(auth: String, id: Long, pause: Boolean) = call { api.setStatus(auth, id, if (pause) "pause" else "resume") }
    suspend fun archive(auth: String, id: Long) { call { api.archive(auth, id) } }
    suspend fun requests(auth: String, box: String) = call { api.requests(auth, box, null, 0) }
    suspend fun request(auth: String, id: Long) = call { api.request(auth, id) }
    suspend fun ask(auth: String, offerId: Long, exchangeOfferId: Long?, message: String) =
        call { api.ask(auth, SkillRequestInput(offerId, exchangeOfferId, message.trim())) }
    suspend fun decide(auth: String, id: Long, action: String) = call { api.decide(auth, id, action) }
    suspend fun propose(auth: String, requestId: Long, form: SkillSessionForm, now: ZonedDateTime = ZonedDateTime.now()): SkillSession {
        val (start, end) = skillSessionTimes(form, now).getOrElse { throw SkillFailure(0, "LOCAL", it.message ?: "Check the time.") }
        return call { api.propose(auth, requestId, SkillSessionInput(form.teach, start.toString(), end.toString(), form.mode, form.place.trim())) }
    }
    suspend fun sessions(auth: String, history: Boolean) = call { api.sessions(auth, if (history) "history" else "upcoming") }
    suspend fun confirm(auth: String, id: Long) = call { api.act(auth, id, "confirm") }
    suspend fun complete(auth: String, id: Long) = call { api.act(auth, id, "complete") }
    suspend fun cancel(auth: String, id: Long, reason: String) = call { api.cancel(auth, id, SkillCancelInput(reason.trim())) }
    suspend fun review(auth: String, sessionId: Long, rating: Int, comment: String) =
        call { api.review(auth, sessionId, SkillReviewInput(rating, comment.trim())) }
    suspend fun reviews(auth: String, studentId: Long) = call { api.reviews(auth, studentId) }
    suspend fun notices(auth: String) = call { api.notices(auth, 0) }
    suspend fun unread(auth: String) = call { api.unread(auth) }.count
    suspend fun read(auth: String, id: Long) { call { api.read(auth, id) } }

    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw SkillFailure(0, null, skillErrorMessage(0, null))
        }
        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            return (response.body() ?: Unit) as T
        }
        val code = runCatching {
            JsonParser.parseString(response.errorBody()?.string().orEmpty()).asJsonObject["code"]?.asString
        }.getOrNull()
        throw SkillFailure(response.code(), code, skillErrorMessage(response.code(), code))
    }
}
