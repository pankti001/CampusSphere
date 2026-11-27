package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Query
import java.time.ZoneId
import java.time.ZonedDateTime

class SkillTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = ZonedDateTime.of(2026, 12, 1, 10, 0, 0, 0, zone)

    @Test fun offerFormsAreCheckedBeforeSending() {
        val good = SkillOfferForm(name = " Java basics ", slots = listOf(SkillSlot(3, "16:00", "17:30")))
        assertNull(skillOfferProblem(good))
        assertEquals("Java basics", good.toInput().name)
        assertEquals(listOf(SkillSlot(3, "16:00", "17:30")), good.toInput().slots)
        assertNotNull(skillOfferProblem(good.copy(name = "J")))
        assertNotNull(skillOfferProblem(good.copy(name = "x".repeat(61))))
        assertNotNull(skillOfferProblem(good.copy(description = "d".repeat(1001))))
        assertNotNull(skillOfferProblem(good.copy(slots = listOf(SkillSlot(3, "4pm", "17:00")))))
        assertNotNull(skillOfferProblem(good.copy(slots = listOf(SkillSlot(3, "17:00", "16:00")))))
        assertNotNull(skillOfferProblem(good.copy(slots = listOf(SkillSlot(3, "16:00", "17:00"), SkillSlot(3, "16:00", "18:00")))))
        assertNotNull(skillOfferProblem(good.copy(slots = List(15) { SkillSlot(it % 7 + 1, "%02d:00".format(it), "%02d:30".format(it)) })))
    }

    @Test fun sessionTimesUseThePhonesZoneAndMustBeInTheFuture() {
        val form = SkillSessionForm(date = "2026-12-03", time = "16:00", minutes = 90)
        val (start, end) = skillSessionTimes(form, now).getOrThrow()
        assertEquals("2026-12-03T16:00+05:30", start.toString())
        assertEquals("2026-12-03T17:30+05:30", end.toString())
        assertTrue(skillSessionTimes(form.copy(date = "3/12/2026"), now).isFailure)
        assertTrue(skillSessionTimes(form.copy(time = "4pm"), now).isFailure)
        assertTrue(skillSessionTimes(form.copy(date = "2026-12-01", time = "09:00"), now).isFailure)
        assertTrue(skillSessionTimes(form.copy(minutes = 300), now).isFailure)
        assertTrue(skillSessionTimes(form.copy(date = "2027-07-01"), now).isFailure)
        assertEquals("Thu 3 Dec, 16:00–17:30", skillSessionTime("2026-12-03T10:30:00Z", "2026-12-03T12:00:00Z", zone))
    }

    @Test fun labelsAndMessagesAreFriendly() {
        assertEquals("New", skillRatingText(null, 0))
        assertEquals("★ 4.5 (12)", skillRatingText(4.5, 12))
        assertEquals("Wed 16:00–17:00", skillSlotText(SkillSlot(3, "16:00:00", "17:00:00")))
        assertEquals("Waiting", skillStatusLabel("REQUESTED"))
        assertEquals("Confirmed", skillStatusLabel("SCHEDULED"))
        assertEquals("Online or in person", skillLabel(SKILL_OFFER_MODES, "BOTH"))
        assertEquals("This skill, request or session is no longer available.", skillErrorMessage(404, "SKILL_NOT_FOUND"))
        assertEquals("You have already reviewed this session.", skillErrorMessage(409, "SKILL_REVIEW_EXISTS"))
        assertEquals("Skill Exchange is only for students of a college.", skillErrorMessage(403, null))
        assertEquals("Your session expired. Sign in again.", skillErrorMessage(401, null))
    }

    @Test fun noCallCanCarryACollege() {
        // The app has no way to choose a college: no Skill Exchange call or body has a college field.
        for (method in SkillApi::class.java.declaredMethods) {
            method.parameterAnnotations.flatten().filterIsInstance<Query>().forEach { assertFalse(it.value.contains("college", true)) }
        }
        for (type in listOf(SkillOfferInput::class.java, SkillRequestInput::class.java, SkillSessionInput::class.java,
                SkillCancelInput::class.java, SkillReviewInput::class.java)) {
            type.declaredFields.forEach { assertFalse("${type.simpleName}.${it.name}", it.name.contains("college", true)) }
        }
    }

    @Test fun failuresBecomeFriendlyAndNothingInvalidIsSent() = runBlocking {
        val api = Retrofit.Builder().baseUrl("http://localhost/").addConverterFactory(GsonConverterFactory.create()).build()
            .create(SkillApi::class.java)
        val calls = mutableListOf<String>()
        val repo = SkillRepository(object : SkillApi by api {
            override suspend fun offer(auth: String, id: Long): Response<SkillOffer> {
                calls += "offer $id"
                return Response.error(404, """{"code":"SKILL_NOT_FOUND","message":"x"}""".toResponseBody("application/json".toMediaType()))
            }
            override suspend fun create(auth: String, input: SkillOfferInput): Response<SkillOffer> {
                calls += "create"; error("must not be sent")
            }
            override suspend fun propose(auth: String, id: Long, input: SkillSessionInput): Response<SkillSession> {
                calls += "propose"; error("must not be sent")
            }
        })
        val notFound = runCatching { repo.offer("Bearer t", 500) }.exceptionOrNull() as SkillFailure
        assertEquals(404, notFound.status)
        assertEquals("This skill, request or session is no longer available.", notFound.message)
        val bad = runCatching { repo.save("Bearer t", SkillOfferForm(name = "J")) }.exceptionOrNull() as SkillFailure
        assertEquals("LOCAL", bad.code)
        val past = runCatching { repo.propose("Bearer t", 1, SkillSessionForm(date = "2020-01-01", time = "10:00"), now) }
            .exceptionOrNull() as SkillFailure
        assertEquals("Choose a time in the future.", past.message)
        assertEquals(listOf("offer 500"), calls)
    }
}
