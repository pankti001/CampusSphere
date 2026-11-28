package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.resources.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ResourceTest {
    @get:Rule val temp = TemporaryFolder()

    private val paper = """
        {"id":4,"kind":"PAPER","subjectId":7,"subjectCode":"CS101","subject":"Data Structures","program":"BSc CS",
        "semester":"Semester 1","academicYear":"2026-2027","faculty":"Synthetic Faculty","title":"End semester",
        "description":"","versionLabel":null,"effectiveFrom":null,"current":false,"examType":"FINAL","examTypeLabel":null,
        "examYear":2025,"examSession":"May","fileName":"ds-2025.pdf","sizeBytes":14,"status":"PUBLISHED",
        "moderationNote":null,"createdAt":"2026-11-28T10:00:00+05:30","updatedAt":"2026-11-28T10:00:00+05:30",
        "canEdit":false,"canModerate":false,"units":[]}
    """.trimIndent()

    private fun item(id: Long, kind: String = "PAPER", year: Int? = 2025, type: String? = "FINAL", label: String? = null,
                     title: String = "Paper", current: Boolean = false) = ResourceItem(id, kind, 7, "CS101", "DS", "BSc",
        "Sem 1", "2026", "F", title, "", null, null, current, type, label, year, "", "f.pdf", 1, "PUBLISHED", null,
        "2026-11-28T10:00:00Z", "2026-11-28T10:00:00Z", false, false, null)

    @Test fun studentReadsOnlyTheStudentAreaAndSendsNoIdentity() = runBlocking {
        val seen = mutableListOf<String>()
        val repository = repo { request ->
            seen += request.url.encodedPath + "?" + request.url.encodedQuery.orEmpty()
            assertEquals("Bearer synthetic", request.header("Authorization"))
            200 to "[$paper]"
        }
        val list = repository.list("Bearer synthetic", "student", PAPERS, ResourceQuery(subjectId = 7, examType = "FINAL", q = "  end  "))
        assertEquals(2025, list.single().examYear)
        assertEquals("/api/student/resources/papers?subjectId=7&examType=FINAL&q=end", seen.single())
        assertFalse(seen.single().contains("college"))
        assertFalse(seen.single().contains("studentId"))
    }

    @Test fun errorsUseFriendlyMessagesAndNeverShowTheServerText() = runBlocking {
        val missing = runCatching {
            repo { 404 to """{"code":"RESOURCE_NOT_FOUND","message":"private detail"}""" }.detail("Bearer s", "student", PAPERS, 9)
        }.exceptionOrNull() as ResourceFailure
        assertEquals(404, missing.status)
        assertTrue(missing.message.orEmpty().contains("not available"))
        assertFalse(missing.message.orEmpty().contains("private"))
        val expired = runCatching { repo { 401 to "" }.semesters("Bearer s") }.exceptionOrNull() as ResourceFailure
        assertEquals(401, expired.status)
    }

    @Test fun previewKeepsAPrivatePdfCopyAndRefusesAnythingElse() = runBlocking {
        val cache = temp.newFolder("cache")
        val pdf = "%PDF-1.4\n%%EOF".toByteArray()
        val file = repo { 200 to String(pdf) }.preview("Bearer s", "student", SYLLABUS, 4, cache)
        assertEquals(File(cache, ResourceRepository.PREVIEW_FOLDER), file.parentFile)
        assertArrayEquals(pdf, file.readBytes())

        val bad = runCatching { repo { 200 to "<html>not a pdf</html>" }.preview("Bearer s", "student", SYLLABUS, 4, cache) }
            .exceptionOrNull() as ResourceFailure
        assertTrue(bad.message.orEmpty().contains("not a valid PDF"))
        assertEquals("only the good copy is left", 1, File(cache, ResourceRepository.PREVIEW_FOLDER).listFiles()!!.size)

        ResourceRepository.clearPreviews(cache)
        assertEquals(0, File(cache, ResourceRepository.PREVIEW_FOLDER).listFiles()!!.size)
    }

    @Test fun pdfCopyStopsAtTenMegabytes() {
        val big = ByteArray(RESOURCE_LIMIT_BYTES + 1).also { "%PDF-".toByteArray().copyInto(it) }
        val failure = runCatching { copyPdf(ByteArrayInputStream(big), ByteArrayOutputStream()) }.exceptionOrNull()
        assertTrue(failure is ResourceFailure)
        assertEquals(5L, copyPdf(ByteArrayInputStream("%PDF-".toByteArray()), ByteArrayOutputStream()))
    }

    @Test fun paperFiltersCombineYearTypeAndSearch() {
        val items = listOf(item(1, year = 2025, type = "FINAL", title = "End semester"),
            item(2, year = 2024, type = "MID1", title = "Mid term"),
            item(3, year = 2025, type = "OTHER", label = "Class test", title = "Surprise"))
        assertEquals(listOf(1L, 3L), filterPapers(items, PaperFilter(year = 2025)).map { it.id })
        assertEquals(listOf(2L), filterPapers(items, PaperFilter(examType = "MID1")).map { it.id })
        assertEquals(listOf(3L), filterPapers(items, PaperFilter(query = "class TEST")).map { it.id })
        assertEquals(listOf(2025, 2024), paperYears(items))
    }

    @Test fun currentSyllabusComesFirstAndLabelsReadWell() {
        val ordered = syllabusOrder(listOf(item(1, "SYLLABUS"), item(3, "SYLLABUS"), item(2, "SYLLABUS", current = true)))
        assertEquals(listOf(2L, 3L, 1L), ordered.map { it.id })
        assertEquals("Final exam", examLabel("FINAL", null))
        assertEquals("Class test", examLabel("OTHER", "Class test"))
        assertEquals("Final exam 2025 · May", paperHeading(item(1).copy(examSession = "May")))
        assertEquals("1 Jun 2026", resourceDate("2026-06-01"))
        assertEquals("28 Nov 2026, 14:13", resourceTime("2026-11-28T08:43:00Z", java.time.ZoneId.of("Asia/Kolkata")))
    }

    @Test fun facultyUploadSendsSubjectFieldsAndPdfOnlyAndReportsProgress() = runBlocking {
        var progress = 0f
        val repository = repo { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/faculty/resources/papers", request.url.encodedPath)
            val body = okio.Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            listOf("subjectId", "examType", "examYear", "title").forEach { assertTrue("missing $it", body.contains("name=\"$it\"")) }
            assertFalse("blank optional fields are left out", body.contains("name=\"examTypeLabel\""))
            assertTrue(body.contains("filename=\"ds.pdf\""))
            assertTrue(body.contains("Content-Type: application/pdf"))
            assertFalse(body.contains("collegeId"))
            assertFalse(body.contains("facultyId"))
            201 to paper
        }
        val file = MaterialUpload("ds.pdf", "application/pdf", "%PDF-1.4\n%%EOF".toByteArray())
        val created = repository.upload("Bearer s", PAPERS, 7, mapOf("examType" to "FINAL", "examTypeLabel" to null,
            "examYear" to "2025", "examSession" to " ", "title" to " End semester "), file) { progress = it }
        assertEquals(4L, created.id)
        assertEquals(1f, progress)
    }

    @Test fun moderationAndArchiveUseTheirOwnRoutes() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = repo { request ->
            val body = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            calls += "${request.method} ${request.url.encodedPath} $body".trim()
            200 to paper
        }
        repository.moderate("Bearer s", PAPERS, 4, "HIDE", "  Wrong subject ")
        repository.archive("Bearer s", SYLLABUS, 5, archive = false)
        assertEquals(listOf("""POST /api/college/resources/papers/4/moderation {"action":"HIDE","note":"Wrong subject"}""",
            "POST /api/faculty/resources/syllabus/5/unarchive"), calls)
    }

    @Test fun formsAndModerationFollowTheServerRules() {
        assertNotNull(syllabusProblem(" ", "2026", ""))
        assertNotNull(syllabusProblem("Syllabus", "", ""))
        assertNotNull(syllabusProblem("Syllabus", "2026", "01/06/2026"))
        assertNull(syllabusProblem("Syllabus", "2026-27", "2026-06-01"))
        assertNotNull(paperProblem(null, "", "2025", "Paper"))
        assertNotNull(paperProblem("OTHER", " ", "2025", "Paper"))
        assertNotNull(paperProblem("FINAL", "", "1989", "Paper"))
        assertNull(paperProblem("OTHER", "Class test", "2025", "Paper"))
        assertEquals(listOf("ARCHIVE", "HIDE", "REMOVE"), moderationActions("PUBLISHED"))
        assertEquals(listOf("RESTORE", "REMOVE"), moderationActions("HIDDEN"))
        assertTrue(moderationActions("REMOVED").isEmpty())
        assertTrue(moderationNeedsNote("REMOVE"))
        assertFalse(moderationNeedsNote("RESTORE"))
    }

    private fun repo(answer: (okhttp3.Request) -> Pair<Int, String>): ResourceRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = answer(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        return ResourceRepository(retrofit.create(ResourceApi::class.java))
    }
}
