package com.campussphere.app

import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.*
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

class TimetableImportApiContractTest {
    private val preview = """{"fileName":"week.xlsx","sha256":"abc","rows":2,"validRows":2,"errorRows":0,"fileErrors":[],
        "divisions":[{"divisionId":31,"division":"A","periods":2,"lessons":2,"newVersion":1,"publishedVersion":null}],
        "details":[],"canImport":true}"""

    private fun retrofit(answer: (okhttp3.Request) -> Pair<Int, String>): Retrofit {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = answer(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                .message("Synthetic").body(body.toResponseBody()).build()
        }.build()
        return Retrofit.Builder().baseUrl("https://example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
    }

    private fun body(request: okhttp3.Request) = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()

    @Test fun previewUploadsTheFileWithTheSemesterOnly() = runBlocking {
        var sent = ""
        val repo = TimetableImportRepository(retrofit { request ->
            assertEquals("/api/college/timetables/import/preview", request.url.encodedPath)
            sent = body(request)
            200 to preview
        }.create(TimetableImportApi::class.java))
        val result = repo.preview("Bearer s", 21, PickedSheet("week.xlsx", "PK-data".toByteArray()))
        assertTrue(result.canImport)
        assertEquals(1, result.divisions.single().newVersion)
        assertTrue(sent.contains("name=\"semesterId\""))
        assertTrue(sent.contains("filename=\"week.xlsx\""))
        assertTrue(sent.contains("Content-Type: $XLSX_MIME"))
        assertFalse(sent.contains("collegeId"))
    }

    @Test fun importSendsThePreviewHashAndExplainsAChangedFile() = runBlocking {
        var sent = ""
        val repo = TimetableImportRepository(retrofit { request ->
            sent = body(request)
            409 to """{"code":"TIMETABLE_IMPORT_CHANGED","message":"server text"}"""
        }.create(TimetableImportApi::class.java))
        val failure = runCatching { repo.importFile("Bearer s", 21, PickedSheet("week.xlsx", byteArrayOf(1)), "abc") }
            .exceptionOrNull() as TimetableFailure
        assertTrue(sent.contains("name=\"sha256\""))
        assertTrue(sent.contains("abc"))
        assertEquals("The file changed since the preview. Preview it again before importing.", failure.message)
    }

    @Test fun copySendsSourceAndTargetClass() = runBlocking {
        var sent = ""
        val repo = TimetableImportRepository(retrofit { request ->
            assertEquals("/api/college/timetables/copy", request.url.encodedPath)
            sent = body(request)
            404 to """{"code":"TIMETABLE_NOT_FOUND"}"""
        }.create(TimetableImportApi::class.java))
        runCatching { repo.copy("Bearer s", 4, 21, 31) }
        assertEquals("""{"sourceTimetableId":4,"semesterId":21,"divisionId":31}""", sent)
    }

    @Test fun facultyInboxUsesOnlyTheTokenForIdentity() = runBlocking {
        val seen = mutableListOf<String>()
        val repo = FacultyNotificationRepository(retrofit { request ->
            seen += request.method + " " + request.url.toString().removePrefix("https://example.invalid")
            when {
                request.url.encodedPath.endsWith("unread-count") -> 200 to """{"count":3}"""
                request.url.encodedPath.endsWith("/read") -> 204 to ""
                request.url.encodedPath.endsWith("read-all") -> 200 to """{"updated":3}"""
                else -> 200 to "[]"
            }
        }.create(FacultyNotificationApi::class.java))
        assertEquals(3L, repo.unread("Bearer s"))
        assertTrue(repo.list("Bearer s", unreadOnly = true).isEmpty())
        repo.markRead("Bearer s", 7)
        repo.markAllRead("Bearer s")
        assertEquals(listOf("GET /api/faculty/notifications/unread-count", "GET /api/faculty/notifications?unread=true&page=0",
            "POST /api/faculty/notifications/7/read", "POST /api/faculty/notifications/read-all"), seen)
    }

    @Test fun pickedFilesAreCheckedOnThePhoneFirst() {
        assertNull(sheetProblem("Week 1.XLSX", 2048))
        assertEquals("Choose an Excel workbook (.xlsx). Save .xls or .csv files as .xlsx first.", sheetProblem("week.csv", 10))
        assertEquals("The file is larger than 2 MB.", sheetProblem("week.xlsx", MAX_TIMETABLE_FILE_BYTES + 1L))
        assertEquals("The file is empty.", sheetProblem("week.xlsx", 0))
    }
}
