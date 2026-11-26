package com.campussphere.app

import com.campussphere.app.presentation.announcements.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class AnnouncementFormTest {
    private val india = ZoneId.of("Asia/Kolkata")

    @Test fun scheduleTextBecomesAnInstantInTheDeviceZone() {
        assertNull(scheduleInstant("  ", india).getOrThrow())
        assertEquals("2026-12-01T09:30+05:30", scheduleInstant("2026-12-01 09:30", india).getOrThrow())
        assertTrue(scheduleInstant("01/12/2026 9:30", india).isFailure)
        assertTrue(scheduleInstant("2026-13-01 09:30", india).isFailure)
    }

    @Test fun facultyAlwaysAddressesStudentsOfOneDivision() {
        val form = AnnouncementForm(title = " Test moved ", body = " Room 4 ", audience = "EVERYONE", scope = "COLLEGE", targetId = 9)
        val input = announcementInput(form, faculty = true, zone = india).getOrThrow()
        assertEquals("STUDENTS", input.audience)
        assertEquals("DIVISION", input.scope)
        assertEquals(9L, input.targetId)
        assertEquals("Test moved", input.title)
        assertEquals("Room 4", input.body)
    }

    @Test fun collegeWideHasNoTargetAndOtherScopesNeedOne() {
        val wide = announcementInput(AnnouncementForm(title = "Holiday", body = "Closed", targetId = 5), false, india).getOrThrow()
        assertNull(wide.targetId)
        assertEquals("COLLEGE", wide.scope)
        val missing = announcementInput(AnnouncementForm(title = "Fees", body = "Due", scope = "DEPARTMENT"), false, india)
        assertEquals("Choose who should receive it.", missing.exceptionOrNull()?.message)
        val noClass = announcementInput(AnnouncementForm(title = "Quiz", body = "Friday"), true, india)
        assertEquals("Choose who should receive it.", noClass.exceptionOrNull()?.message)
    }

    @Test fun blankOrTooLongTextAndBadExpiryAreRejectedBeforeSending() {
        assertEquals("Enter a title.", announcementInput(AnnouncementForm(body = "x"), false, india).exceptionOrNull()?.message)
        assertEquals("Enter the announcement text.", announcementInput(AnnouncementForm(title = "x"), false, india).exceptionOrNull()?.message)
        assertTrue(announcementInput(AnnouncementForm(title = "x".repeat(201), body = "x"), false, india).isFailure)
        assertTrue(announcementInput(AnnouncementForm(title = "x", body = "x", expires = "tomorrow"), false, india).isFailure)
        val ok = announcementInput(AnnouncementForm(title = "x", body = "x", expires = "2026-12-31 18:00"), false, india).getOrThrow()
        assertEquals("2026-12-31T18:00+05:30", ok.expiresAt)
    }

    @Test fun labelsFallBackToTheRawKey() {
        assertEquals("Important notice", label(ANNOUNCEMENT_CATEGORIES, "IMPORTANT_NOTICE"))
        assertEquals("UNKNOWN", label(ANNOUNCEMENT_CATEGORIES, "UNKNOWN"))
        assertEquals(8, ANNOUNCEMENT_CATEGORIES.size)
        assertEquals(listOf("LOW", "NORMAL", "HIGH", "URGENT"), ANNOUNCEMENT_PRIORITIES.map { it.first })
    }
}
