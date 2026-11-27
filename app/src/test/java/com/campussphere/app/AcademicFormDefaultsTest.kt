package com.campussphere.app

import com.campussphere.app.data.remote.dto.AcademicCatalogItem
import com.campussphere.app.presentation.college.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AcademicFormDefaultsTest {
    @Test fun newAcademicYearIsSuggested() {
        assertEquals("2026-2027", currentAcademicYear(LocalDate.of(2026, 11, 27)))
        assertEquals("2025-2026", currentAcademicYear(LocalDate.of(2026, 3, 1)))
        val empty = suggestedAcademicYear(emptyList(), LocalDate.of(2026, 11, 27))
        assertEquals("2026-2027", empty.code)
        assertEquals("Academic Year 2026-27", empty.name)
        val next = suggestedAcademicYear(listOf(AcademicCatalogItem(1, "2026-2027", "x")), LocalDate.of(2026, 11, 27))
        assertEquals("2027-2028", next.code)
    }

    @Test fun nextSemesterNumberIsSuggested() {
        assertEquals("1", suggestedSemester(emptyList()).code)
        val next = suggestedSemester(listOf(AcademicCatalogItem(1, "1", "Semester 1"), AcademicCatalogItem(2, "2", "Semester 2")))
        assertEquals("3", next.code)
        assertEquals("Semester 3", next.name)
    }

    @Test fun codesAreSuggestedFromNamesAndCleanedUp() {
        assertEquals("CS", suggestedCode("Computer Science"))
        assertEquals("BCS", suggestedCode("BSc Computer Science"))
        assertEquals("JP1", suggestedCode("Java Programming 1"))
        assertEquals("PHYSIC", suggestedCode("Physics"))
        assertEquals("", suggestedCode("  "))
        assertEquals("DIV-A", normalizedCode(" div a "))
        assertEquals("CS", validatedDepartment("cs", "Computer Science")?.code)
    }
}
