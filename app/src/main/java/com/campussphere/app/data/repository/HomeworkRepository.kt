package com.campussphere.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.campussphere.app.data.remote.api.*
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale

/** A failed assignment request. [status] is the HTTP status (0 for network or local file problems). */
class HomeworkFailure(val status: Int, val code: String?, message: String) : Exception(message)

/** Every file type an assignment may allow, with the label shown to people. Old .doc/.ppt/.xls are not accepted. */
val HOMEWORK_FORMATS = listOf("pdf" to "PDF", "docx" to "Word (.docx)", "pptx" to "PowerPoint (.pptx)",
    "xlsx" to "Excel (.xlsx)", "txt" to "Text", "jpg" to "JPG image", "png" to "PNG image")
private val HOMEWORK_MIME = mapOf(
    "pdf" to "application/pdf",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "txt" to "text/plain", "jpg" to "image/jpeg", "png" to "image/png")
const val HOMEWORK_LIMIT_BYTES = 10 * 1024 * 1024

fun homeworkExtension(fileName: String?): String =
    fileName?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.let { if (it == "jpeg") "jpg" else it }.orEmpty()

/** MIME types for the system file picker, limited to the allowed formats. */
fun homeworkPickerTypes(allowed: List<String>): Array<String> =
    allowed.mapNotNull { HOMEWORK_MIME[it] }.distinct().toTypedArray().ifEmpty { arrayOf("*/*") }

/** Local check before uploading; the server repeats every check on the actual file content. */
fun homeworkFileProblem(fileName: String?, size: Long, allowed: List<String>, maxBytes: Int): String? {
    val ext = homeworkExtension(fileName)
    val labels = HOMEWORK_FORMATS.filter { it.first in allowed }.joinToString(", ") { it.second }
    return when {
        fileName.isNullOrBlank() || ext.isEmpty() -> "Choose a named file."
        ext !in allowed -> "This assignment accepts: $labels."
        size <= 0 -> "The selected file is empty."
        size > maxBytes -> "The file is larger than ${homeworkSize(maxBytes.toLong())}."
        else -> null
    }
}

fun homeworkSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.UK, "%.1f MB", bytes / (1024.0 * 1024.0)).replace(".0 MB", " MB")
    bytes >= 1024 -> String.format(Locale.UK, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** Friendly message for a server error code. The server's own text is never shown. */
fun homeworkErrorMessage(status: Int, code: String?): String = when (code) {
    "ASSIGNMENT_NOT_FOUND" -> "This assignment is no longer available to you."
    "ASSIGNMENT_INVALID" -> "Check the class, title, marks, file types and size, then try again."
    "ASSIGNMENT_DEADLINE_INVALID" -> "Check the dates: the deadline must be in the future and after the issue date. A published deadline can only move later."
    "ASSIGNMENT_STATE_CONFLICT" -> "This assignment cannot be changed in its current state. Refresh and try again."
    "ASSIGNMENT_MARKS_LOCKED" -> "Maximum marks cannot change after work has been marked."
    "ASSIGNMENT_FILE_INVALID" -> "Attach a PDF, Word, PowerPoint, Excel, text, JPG or PNG file up to 10 MB."
    "MATERIAL_FILE_INVALID" -> "The file is too large or not an allowed type."
    "SUBMISSION_NOT_FOUND" -> "This submission is no longer available."
    "SUBMISSION_FILE_INVALID" -> "This file type or content is not allowed for this assignment, or it is too large."
    "SUBMISSION_CLOSED" -> "This assignment is not accepting submissions now."
    "SUBMISSION_CONFLICT" -> "You have already submitted, and this assignment does not allow another submission."
    "EVALUATION_INVALID" -> "Marks must be between 0 and the maximum (up to two decimals). Feedback is limited to 4000 characters."
    "EVALUATION_STATE_CONFLICT" -> "This submission cannot be marked or returned now. Refresh and try again."
    "NOTIFICATION_NOT_FOUND" -> "This notification is no longer available."
    "VERIFICATION_REQUIRED" -> "Your account needs verification before you can open assignments."
    "INVALID_REQUEST" -> "Check the details and try again."
    else -> when (status) {
        401 -> "Your session expired. Sign in again."
        403 -> "You do not have permission for this action."
        404 -> "This assignment is no longer available."
        413 -> "The file is too large."
        in 500..599 -> "Assignments could not be loaded right now. Try again shortly."
        else -> "Request failed. Check your connection and try again."
    }
}

class HomeworkRepository(private val api: HomeworkApi) {
    // Faculty
    suspend fun classes(auth: String) = call { api.classes(auth) }
    suspend fun facultySummary(auth: String) = call { api.facultySummary(auth) }
    suspend fun facultyList(auth: String, subjectId: Long?, divisionId: Long?, status: String?, q: String?, page: Int) =
        call { api.facultyList(auth, subjectId, divisionId, status, q?.trim()?.ifEmpty { null }, page) }
    suspend fun detail(auth: String, area: String, id: Long) = call { api.detail(auth, area, id) }
    suspend fun save(auth: String, id: Long?, input: HomeworkInput) =
        call { if (id == null) api.create(auth, input) else api.update(auth, id, input) }
    suspend fun lifecycle(auth: String, id: Long, action: String) = call { api.lifecycle(auth, id, action) }
    suspend fun attach(auth: String, id: Long, file: MaterialUpload, onProgress: (Float) -> Unit = {}) =
        call { api.attach(auth, id, part(file, onProgress)) }
    suspend fun detach(auth: String, id: Long) = call { api.detach(auth, id) }
    suspend fun history(auth: String, area: String, id: Long) = call { api.history(auth, area, id) }
    suspend fun roster(auth: String, id: Long, filter: String, page: Int) = call { api.roster(auth, id, filter, page) }
    suspend fun review(auth: String, id: Long, submissionId: Long) = call { api.review(auth, id, submissionId) }
    suspend fun evaluate(auth: String, id: Long, submissionId: Long, marks: Double, feedback: String?) =
        call { api.evaluate(auth, id, submissionId, HomeworkEvaluation(marks, feedback?.trim()?.ifEmpty { null })) }
    suspend fun returnWork(auth: String, id: Long, submissionId: Long, feedback: String) =
        call { api.returnWork(auth, id, submissionId, HomeworkReturn(feedback.trim())) }

    // Students
    suspend fun studentSummary(auth: String) = call { api.studentSummary(auth) }
    suspend fun studentList(auth: String, tab: String, page: Int) = call { api.studentList(auth, tab, page) }
    suspend fun studentDetail(auth: String, id: Long) = call { api.studentDetail(auth, id) }
    suspend fun submit(auth: String, id: Long, file: MaterialUpload, comment: String, onProgress: (Float) -> Unit) =
        call {
            api.submit(auth, id, part(file, onProgress),
                comment.trim().ifEmpty { null }?.toRequestBody("text/plain".toMediaType()))
        }
    suspend fun notices(auth: String, unreadOnly: Boolean, page: Int) = call { api.notices(auth, unreadOnly, page) }
    suspend fun markRead(auth: String, id: Long) { call { api.markRead(auth, id) } }

    // College Admin
    suspend fun adminFilters(auth: String) = call { api.adminFilters(auth) }
    suspend fun adminStats(auth: String, filters: Map<String, String>) = call { api.adminStats(auth, filters) }
    suspend fun adminList(auth: String, filters: Map<String, String>) = call { api.adminList(auth, filters) }

    /** Reads and checks a picked file against the assignment's allowed types and size. Runs off the main thread. */
    suspend fun read(resolver: ContentResolver, uri: Uri, allowed: List<String>, maxBytes: Int): MaterialUpload =
        withContext(Dispatchers.IO) {
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    name = it.getString(0)
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
            homeworkFileProblem(name, if (size < 0) 1 else size, allowed, maxBytes)?.let { throw HomeworkFailure(0, null, it) }
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > maxBytes) {
                        throw HomeworkFailure(0, null, "The file is larger than ${homeworkSize(maxBytes.toLong())}.")
                    }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            } ?: throw HomeworkFailure(0, null, "The selected file could not be opened.")
            homeworkFileProblem(name, bytes.size.toLong(), allowed, maxBytes)?.let { throw HomeworkFailure(0, null, it) }
            val fileName = name!!
            MaterialUpload(fileName, HOMEWORK_MIME[homeworkExtension(fileName)] ?: "application/octet-stream", bytes)
        }

    /** Streams a download straight into the document the user chose. */
    suspend fun download(source: suspend () -> Response<ResponseBody>, resolver: ContentResolver, target: Uri) {
        val body = call { source() }
        withContext(Dispatchers.IO) {
            body.use { stream ->
                resolver.openOutputStream(target, "wt")?.use { stream.byteStream().copyTo(it) }
                    ?: throw HomeworkFailure(0, null, "The download location could not be opened.")
            }
        }
    }

    suspend fun saveAttachment(auth: String, area: String, id: Long, resolver: ContentResolver, target: Uri) =
        download({ api.attachment(auth, area, id) }, resolver, target)

    suspend fun saveSubmission(auth: String, area: String, id: Long, submissionId: Long, resolver: ContentResolver, target: Uri) =
        download({ api.submissionFile(auth, area, id, submissionId) }, resolver, target)

    private fun part(file: MaterialUpload, onProgress: (Float) -> Unit) = MultipartBody.Part.createFormData("file",
        file.fileName, ProgressRequestBody(file.bytes, file.mimeType.toMediaType(), onProgress))

    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeworkFailure) {
            throw e
        } catch (e: IOException) {
            throw HomeworkFailure(0, null, homeworkErrorMessage(0, null))
        }
        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            return (response.body() ?: Unit) as T
        }
        val code = runCatching {
            JsonParser.parseString(response.errorBody()?.string().orEmpty()).asJsonObject["code"]?.asString
        }.getOrNull()
        throw HomeworkFailure(response.code(), code, homeworkErrorMessage(response.code(), code))
    }
}
