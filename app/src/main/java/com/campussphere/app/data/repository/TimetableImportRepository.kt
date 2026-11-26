package com.campussphere.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.campussphere.app.data.remote.api.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
const val MAX_TIMETABLE_FILE_BYTES = 2 * 1024 * 1024

/** An Excel file the admin picked; checked for name and size on the phone, and fully on the server. */
class PickedSheet(val fileName: String, val bytes: ByteArray)

/** Problem with a picked file, or null when it can be sent. */
fun sheetProblem(fileName: String, size: Long): String? = when {
    !fileName.lowercase().endsWith(".xlsx") -> "Choose an Excel workbook (.xlsx). Save .xls or .csv files as .xlsx first."
    size <= 0 -> "The file is empty."
    size > MAX_TIMETABLE_FILE_BYTES -> "The file is larger than 2 MB."
    else -> null
}

class TimetableImportRepository(private val api: TimetableImportApi) {
    private val gson = Gson()

    suspend fun preview(auth: String, semesterId: Long, file: PickedSheet) = timetableRequest(gson) {
        api.preview(auth, semesterId.toString().toRequestBody(TEXT), part(file))
    }

    suspend fun importFile(auth: String, semesterId: Long, file: PickedSheet, sha256: String) = timetableRequest(gson) {
        api.importFile(auth, semesterId.toString().toRequestBody(TEXT), sha256.toRequestBody(TEXT), part(file))
    }

    suspend fun copy(auth: String, sourceId: Long, semesterId: Long, divisionId: Long) = timetableRequest(gson) {
        api.copy(auth, TimetableCopyRequest(sourceId, semesterId, divisionId))
    }

    /** Saves the template (with the semester's reference data when given) to the place the admin chose. */
    suspend fun downloadTemplate(auth: String, semesterId: Long?, resolver: ContentResolver, target: Uri) {
        val body = timetableRequest(gson) { api.template(auth, semesterId) }
        withContext(Dispatchers.IO) {
            body.use { source ->
                resolver.openOutputStream(target, "wt")?.use { source.byteStream().copyTo(it) }
                    ?: throw TimetableFailure(0, null, "The download location could not be opened.")
            }
        }
    }

    suspend fun readSheet(resolver: ContentResolver, uri: Uri): PickedSheet = withContext(Dispatchers.IO) {
        var name = "timetable.xlsx"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                if (!it.isNull(0)) name = it.getString(0)
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        sheetProblem(name, if (size < 0) 1 else size)?.let { throw TimetableFailure(0, null, it) }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_TIMETABLE_FILE_BYTES) throw TimetableFailure(0, null, "The file is larger than 2 MB.")
            }
            out.toByteArray()
        } ?: throw TimetableFailure(0, null, "The file could not be opened.")
        sheetProblem(name, bytes.size.toLong())?.let { throw TimetableFailure(0, null, it) }
        PickedSheet(name, bytes)
    }

    private fun part(file: PickedSheet) =
        MultipartBody.Part.createFormData("file", file.fileName, file.bytes.toRequestBody(XLSX_MIME.toMediaType()))

    private companion object {
        val TEXT = "text/plain".toMediaType()
    }
}

class FacultyNotificationRepository(private val api: FacultyNotificationApi) {
    private val gson = Gson()

    suspend fun list(auth: String, unreadOnly: Boolean = false, page: Int = 0) = timetableRequest(gson) { api.list(auth, unreadOnly, page) }
    suspend fun unread(auth: String) = timetableRequest(gson) { api.unread(auth) }.count
    suspend fun markRead(auth: String, id: Long) { timetableRequest(gson) { api.read(auth, id) } }
    suspend fun markAllRead(auth: String) { timetableRequest(gson) { api.readAll(auth) } }
}
