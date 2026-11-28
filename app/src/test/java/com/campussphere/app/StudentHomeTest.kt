package com.campussphere.app

import com.campussphere.app.data.remote.api.StudentHomeworkSummary
import com.campussphere.app.presentation.student.Section
import com.campussphere.app.presentation.student.StudentHomeUi
import com.campussphere.app.presentation.student.studentBadges
import org.junit.Assert.assertEquals
import org.junit.Test

class StudentHomeTest {
    @Test fun badgesAreZeroUntilSectionsLoad() {
        val badges = studentBadges(StudentHomeUi())
        assertEquals(0L, badges.assignments)
        assertEquals(0L, badges.chats)
        assertEquals(0L, badges.skills)
        assertEquals(0L, badges.notices)
    }

    @Test fun assignmentBadgeCountsPendingWorkAndNewNotices() {
        val ui = StudentHomeUi(homework = Section(StudentHomeworkSummary(pending = 2, dueThisWeek = 1, submitted = 3, evaluated = 1,
            unreadNotices = 4, upcoming = emptyList())))
        assertEquals(6L, studentBadges(ui).assignments)
    }
}
