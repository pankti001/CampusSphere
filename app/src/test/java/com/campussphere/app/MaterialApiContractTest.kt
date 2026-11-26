package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
import com.campussphere.app.presentation.materials.MaterialFilter
import com.campussphere.app.presentation.materials.applyMaterialFilter
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

class MaterialApiContractTest {
    private val item = """
        {"id":4,"title":"Unit 1 notes","description":"Cells","topic":"Unit 1","type":"NOTES","subjectId":7,
        "subject":"Biology","divisionId":5,"division":"A","semester":"First","program":"BSc","academicYear":"2026-2027",
        "facultyId":null,"faculty":"Synthetic Faculty","assignmentId":3,"fileName":"notes.pdf","format":"pdf",
        "mimeType":"application/pdf","sizeBytes":14,"status":"PUBLISHED","moderationNote":null,
        "uploadedAt":"2026-11-26T10:00:00+05:30","updatedAt":"2026-11-26T10:00:00+05:30","canEdit":true,"canModerate":false}
    """.trimIndent()

    @Test fun uploadSendsFieldsAndFileButNoCollegeOrFacultyIdAndReportsProgress() = runBlocking {
        var progress = 0f
        val repository = repo { request ->
            assertEquals("POST", request.method)
            assertEquals("/api/faculty/materials", request.url.encodedPath)
            assertEquals("Bearer synthetic", request.header("Authorization"))
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            listOf("assignmentId", "title", "description", "type", "topic").forEach {
                assertTrue("missing $it", body.contains("name=\"$it\""))
            }
            assertTrue(body.contains("filename=\"notes.pdf\""))
            assertTrue(body.contains("Content-Type: application/pdf"))
            assertFalse(body.contains("collegeId"))
            assertFalse(body.contains("facultyId"))
            201 to item
        }
        val file = MaterialUpload("notes.pdf", "application/pdf", "%PDF-1.4\n%%EOF".toByteArray())
        val created = repository.upload("Bearer synthetic", 3, MaterialFields("Unit 1 notes", "Cells", "Unit 1", "NOTES"), file) {
            progress = it
        }
        assertEquals(4L, created.id)
        assertEquals("PUBLISHED", created.status)
        assertEquals(1f, progress)
    }

    @Test fun eachRoleReadsItsOwnArea() = runBlocking {
        val paths = mutableListOf<String>()
        val repository = repo { request -> paths += request.url.encodedPath; 200 to "[$item]" }
        assertEquals(1, repository.list("Bearer s", "student").size)
        repository.list("Bearer s", "college")
        assertEquals(listOf("/api/student/materials", "/api/college/materials"), paths)
    }

    @Test fun moderationSendsActionAndTrimmedNoteOnly() = runBlocking {
        val repository = repo { request ->
            assertEquals("/api/college/materials/4/moderation", request.url.encodedPath)
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            assertEquals("""{"action":"HIDE","note":"Wrong class"}""", body)
            200 to item.replace("\"PUBLISHED\"", "\"HIDDEN\"")
        }
        assertEquals("HIDDEN", repository.moderate("Bearer s", 4, "HIDE", "  Wrong class ").status)
    }

    @Test fun errorsUseFriendlyMessagesAndNeverShowTheServerBody() = runBlocking {
        val repository = repo { 409 to """{"code":"MATERIAL_DUPLICATE","message":"private server detail"}""" }
        val failure = runCatching { repository.list("Bearer s", "faculty") }.exceptionOrNull() as MaterialFailure
        assertEquals(409, failure.status)
        assertTrue(failure.message.orEmpty().contains("already shared"))
        assertFalse(failure.message.orEmpty().contains("private"))
        val expired = runCatching { repo { 401 to "" }.list("Bearer s", "student") }.exceptionOrNull() as MaterialFailure
        assertEquals(401, expired.status)
    }

    @Test fun localFileCheckMatchesTheServerRules() {
        assertNull(materialFileProblem("notes.PDF", 100))
        assertNull(materialFileProblem("lab.docx", 100))
        assertNotNull(materialFileProblem("virus.exe", 100))
        assertNotNull(materialFileProblem("noextension", 100))
        assertNotNull(materialFileProblem("empty.pdf", 0))
        assertNotNull(materialFileProblem("big.pdf", MATERIAL_LIMIT_BYTES + 1L))
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", materialMimeType("s.pptx"))
    }

    @Test fun filtersCombineSearchSubjectTypeAndStatus() {
        fun m(id: Long, title: String, subject: Long, type: String, status: String) = MaterialItem(id, title, "", null, type,
            subject, "S$subject", 1, "A", "First", "BSc", "2026", 9, "F", 1, "f.pdf", "pdf", "application/pdf", 1,
            status, null, "2026-11-26T10:00:00Z", "2026-11-26T10:00:00Z", false, false)
        val items = listOf(m(1, "Cell biology", 7, "NOTES", "PUBLISHED"), m(2, "Genetics slides", 7, "SLIDES", "HIDDEN"),
            m(3, "Cell lab", 8, "LAB_MANUAL", "PUBLISHED"))
        assertEquals(listOf(1L, 3L), applyMaterialFilter(items, MaterialFilter(query = "cell")).map { it.id })
        assertEquals(listOf(1L), applyMaterialFilter(items, MaterialFilter(query = "CELL", subjectId = 7)).map { it.id })
        assertEquals(listOf(2L), applyMaterialFilter(items, MaterialFilter(status = "HIDDEN")).map { it.id })
        assertEquals(listOf(3L), applyMaterialFilter(items, MaterialFilter(type = "LAB_MANUAL")).map { it.id })
    }

    private fun repo(answer: (okhttp3.Request) -> Pair<Int, String>): MaterialRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = answer(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        val retrofit = Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
        return MaterialRepository(retrofit.create(MaterialApi::class.java))
    }
}
