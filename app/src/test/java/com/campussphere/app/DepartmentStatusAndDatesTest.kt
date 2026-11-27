package com.campussphere.app

import com.campussphere.app.data.remote.api.AcademicPeriodApi
import com.campussphere.app.data.remote.api.AuthApi
import com.campussphere.app.data.remote.dto.AcademicCatalogInput
import com.campussphere.app.data.repository.AcademicCatalogFailure
import com.campussphere.app.data.repository.AcademicYearRepository
import com.campussphere.app.data.repository.DepartmentRepository
import com.campussphere.app.presentation.college.academicDatesProblem
import com.campussphere.app.presentation.college.isCurrent
import com.campussphere.app.presentation.college.periodLabel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDate

class DepartmentStatusAndDatesTest {
    private fun retrofit(answer: (okhttp3.Request) -> Pair<Int, String>): Retrofit {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = answer(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
    }

    private fun body(request: okhttp3.Request) = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()

    @Test fun datesAreCheckedBeforeSending() {
        assertNull(academicDatesProblem("", " "))
        assertNull(academicDatesProblem("2026-06-01", "2027-05-31"))
        assertEquals("Enter both dates, or leave both empty.", academicDatesProblem("2026-06-01", ""))
        assertEquals("Use YYYY-MM-DD for dates, for example 2026-06-01.", academicDatesProblem("01/06/2026", "2027-05-31"))
        assertEquals("The end date must be after the start date.", academicDatesProblem("2026-06-01", "2026-06-01"))
        assertEquals("A period can be at most about one year long.", academicDatesProblem("2026-06-01", "2027-08-01"))
    }

    @Test fun periodsShowTheirDatesAndWhetherTheyAreCurrent() {
        assertEquals("1 Jun 2026 – 31 May 2027", periodLabel("2026-06-01", "2027-05-31"))
        assertNull(periodLabel(null, null))
        assertTrue(isCurrent("2026-06-01", "2027-05-31", LocalDate.of(2026, 11, 27)))
        assertFalse(isCurrent("2026-06-01", "2027-05-31", LocalDate.of(2027, 6, 1)))
        assertFalse(isCurrent(null, null, LocalDate.of(2026, 11, 27)))
    }

    @Test fun yearDatesAreSentAndPlainItemsStayCodeAndNameOnly() = runBlocking {
        val sent = mutableListOf<String>()
        val years = AcademicYearRepository(retrofit { r ->
            sent += body(r)
            201 to """{"id":3,"code":"2026-2027","name":"Academic Year 2026-27","startDate":"2026-06-01","endDate":"2027-05-31"}"""
        }.create(AcademicPeriodApi::class.java))
        val saved = years.save("Bearer s", null, AcademicCatalogInput("2026-2027", "Academic Year 2026-27", "2026-06-01", "2027-05-31"))
        assertEquals("2026-06-01", saved.startDate)
        years.save("Bearer s", 3, AcademicCatalogInput("2026-2027", "Academic Year 2026-27", clearDates = true))
        years.save("Bearer s", null, AcademicCatalogInput("2027-2028", "Plain"))
        assertEquals("""{"code":"2026-2027","name":"Academic Year 2026-27","startDate":"2026-06-01","endDate":"2027-05-31"}""", sent[0])
        assertEquals("""{"code":"2026-2027","name":"Academic Year 2026-27","clearDates":true}""", sent[1])
        assertEquals("""{"code":"2027-2028","name":"Plain"}""", sent[2])
    }

    @Test fun departmentsCanBeDeactivatedAndInactiveRefusalsAreExplained() = runBlocking {
        val calls = mutableListOf<String>()
        val departments = DepartmentRepository(retrofit { r ->
            calls += "${r.method} ${r.url.encodedPath} ${body(r)}"
            if (r.method == "PUT") 200 to """{"id":2,"code":"CS","name":"Computer Science","active":false}"""
            else 409 to """{"code":"DEPARTMENT_INACTIVE","message":"server text"}"""
        }.create(AuthApi::class.java))
        val saved = departments.setActive("Bearer s", 2, false)
        assertEquals(false, saved.active)
        assertEquals("""PUT /api/college/departments/2/active {"active":false}""", calls[0])
        val refused = runCatching { departments.save("Bearer s", 2, AcademicCatalogInput("CS", "Computer Science")) }
            .exceptionOrNull() as AcademicCatalogFailure
        assertEquals(409, refused.status)
        assertTrue(refused.message!!.startsWith("This department is inactive."))
    }
}
