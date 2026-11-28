package com.campussphere.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import com.campussphere.app.data.remote.api.*
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** A failed Academic Resources request. [status] is the HTTP status (0 for network or local file problems). */
class ResourceFailure(val status: Int, message: String) : Exception(message)

const val RESOURCE_LIMIT_BYTES = 10 * 1024 * 1024
const val SYLLABUS = "syllabus"
const val PAPERS = "papers"

val EXAM_TYPES = listOf("MID1" to "Mid-term 1", "MID2" to "Mid-term 2", "FINAL" to "Final exam", "OTHER" to "Other")

/** "Final exam", or the faculty's own label for an "Other" exam. */
fun examLabel(type: String?, label: String?): String =
    if (type == "OTHER" && !label.isNullOrBlank()) label else EXAM_TYPES.firstOrNull { it.first == type }?.second ?: type.orEmpty()

/** Friendly message for a server error code. The server's own text is never shown. */
fun resourceErrorMessage(status: Int, code: String?): String = when (code) {
    "RESOURCE_NOT_FOUND" -> "This syllabus, question paper or subject is not available to you."
    "RESOURCE_INVALID" -> "Check the details and filters, then try again."
    "RESOURCE_FILE_INVALID" -> "Only a PDF file up to 10 MB can be used."
    "RESOURCE_DUPLICATE" -> "This file or version label was already uploaded for this subject."
    "RESOURCE_STATE_CONFLICT" -> "This changed or can no longer be edited. Refresh and try again."
    "VERIFICATION_REQUIRED" -> "Your account needs verification before you can open academic resources."
    else -> when (status) {
        401 -> "Your session expired. Sign in again."
        403 -> "You do not have permission for this."
        404 -> "This is no longer available."
        413 -> "The file is larger than 10 MB."
        in 500..599 -> "CampusSphere could not complete the request. Try again shortly."
        else -> "Request failed. Check your connection and try again."
    }
}

/** Filters sent to the server, which applies them inside what the account may see anyway. */
data class ResourceQuery(val subjectId: Long? = null, val semesterId: Long? = null, val examYear: Int? = null,
                         val examType: String? = null, val q: String = "", val page: Int = 0, val status: String? = null) {
    fun toMap(): Map<String, String> = buildMap {
        status?.let { put("status", it) }
        subjectId?.let { put("subjectId", it.toString()) }
        semesterId?.let { put("semesterId", it.toString()) }
        examYear?.let { put("examYear", it.toString()) }
        examType?.let { put("examType", it) }
        q.trim().takeIf { it.isNotEmpty() }?.let { put("q", it.take(80)) }
        if (page > 0) put("page", page.toString())
    }
}

/**
 * Copies at most [RESOURCE_LIMIT_BYTES] from [input], refusing anything larger or anything that is not a PDF.
 * Used for both downloads and previews so neither trusts the response blindly.
 */
fun copyPdf(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    var head = ByteArray(0)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (head.size < 5) head += buffer.copyOf(minOf(count, 5 - head.size))
        total += count
        if (total > RESOURCE_LIMIT_BYTES) throw ResourceFailure(0, "The file is larger than 10 MB.")
        output.write(buffer, 0, count)
    }
    if (!head.contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) throw ResourceFailure(0, "This file is not a valid PDF.")
    return total
}

class ResourceRepository(private val api: ResourceApi) {
    suspend fun list(auth: String, area: String, kind: String, query: ResourceQuery = ResourceQuery()) =
        call { api.list(auth, area, kind, query.toMap()) }
    suspend fun detail(auth: String, area: String, kind: String, id: Long) = call { api.detail(auth, area, kind, id) }
    suspend fun semesters(auth: String) = call { api.semesters(auth) }
    suspend fun subjects(auth: String, semesterId: Long? = null) = call { api.subjects(auth, semesterId) }

    // ---- Faculty ----
    suspend fun teachable(auth: String) = call { api.teachable(auth) }
    suspend fun editSyllabus(auth: String, id: Long, fields: SyllabusEdit) = call { api.editSyllabus(auth, id, fields) }
    suspend fun editPaper(auth: String, id: Long, fields: PaperEdit) = call { api.editPaper(auth, id, fields) }
    suspend fun saveUnits(auth: String, id: Long, units: List<ResourceUnit>) = call { api.saveUnits(auth, id, units) }
    suspend fun archive(auth: String, kind: String, id: Long, archive: Boolean) =
        call { api.archive(auth, kind, id, if (archive) "archive" else "unarchive") }

    /** Reads and checks a picked PDF on the device; the server checks the content again. Runs off the main thread. */
    suspend fun read(resolver: ContentResolver, uri: Uri): MaterialUpload = withContext(Dispatchers.IO) {
        var name: String? = null
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0)
        }
        val fileName = name?.takeIf { it.lowercase().endsWith(".pdf") } ?: throw ResourceFailure(0, "Choose a PDF file.")
        val bytes = resolver.openInputStream(uri)?.use { input ->
            java.io.ByteArrayOutputStream().also { copyPdf(input, it) }.toByteArray()
        } ?: throw ResourceFailure(0, "The selected file could not be opened.")
        if (bytes.isEmpty()) throw ResourceFailure(0, "The selected file is empty.")
        MaterialUpload(fileName, "application/pdf", bytes)
    }

    /** Uploads a syllabus version or question paper. Only the form fields and the file are sent, never any id but the subject. */
    suspend fun upload(auth: String, kind: String, subjectId: Long, fields: Map<String, String?>, file: MaterialUpload,
                       onProgress: (Float) -> Unit): ResourceItem {
        val text = "text/plain".toMediaType()
        val parts = buildMap {
            put("subjectId", subjectId.toString().toRequestBody(text))
            fields.forEach { (key, value) -> value?.takeIf { it.isNotBlank() }?.let { put(key, it.trim().toRequestBody(text)) } }
        }
        return call { api.upload(auth, kind, parts, filePart(file, onProgress)) }
    }

    suspend fun replaceFile(auth: String, kind: String, id: Long, file: MaterialUpload, onProgress: (Float) -> Unit) =
        call { api.replaceFile(auth, kind, id, filePart(file, onProgress)) }

    private fun filePart(file: MaterialUpload, onProgress: (Float) -> Unit) = MultipartBody.Part.createFormData("file",
        file.fileName, ProgressRequestBody(file.bytes, "application/pdf".toMediaType(), onProgress))

    // ---- College Admin ----
    suspend fun events(auth: String, kind: String, id: Long) = call { api.events(auth, kind, id) }
    suspend fun moderate(auth: String, kind: String, id: Long, action: String, note: String?) =
        call { api.moderate(auth, kind, id, ResourceModeration(action, note?.trim()?.ifEmpty { null })) }
    suspend fun coverage(auth: String, semesterId: Long? = null) = call { api.coverage(auth, semesterId) }
    suspend fun stats(auth: String) = call { api.stats(auth) }

    /** Saves the PDF into the document the user chose with the system "Save as" screen. */
    suspend fun download(auth: String, area: String, kind: String, id: Long, resolver: ContentResolver, target: Uri) {
        val body = call { api.file(auth, area, kind, id) }
        withContext(Dispatchers.IO) {
            body.use { source ->
                val out = resolver.openOutputStream(target, "wt") ?: throw ResourceFailure(0, "The download location could not be opened.")
                try {
                    out.use { copyPdf(source.byteStream(), it) }
                } catch (e: ResourceFailure) {
                    runCatching { resolver.delete(target, null, null) }
                    throw e
                }
            }
        }
    }

    /**
     * Fetches the PDF into a private file inside the app's own cache (other apps cannot read it) for the in-app
     * preview. The caller deletes it when the preview closes; [clearPreviews] removes any left behind.
     */
    suspend fun preview(auth: String, area: String, kind: String, id: Long, cacheDir: File): File {
        val body = call { api.file(auth, area, kind, id) }
        return withContext(Dispatchers.IO) {
            val folder = File(cacheDir, PREVIEW_FOLDER).apply { mkdirs() }
            val file = File(folder, UUID.randomUUID().toString() + ".pdf")
            try {
                body.use { source -> file.outputStream().use { copyPdf(source.byteStream(), it) } }
                file
            } catch (e: Exception) {
                file.delete()
                if (e is ResourceFailure) throw e
                throw ResourceFailure(0, resourceErrorMessage(0, null))
            }
        }
    }

    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResourceFailure) {
            throw e
        } catch (e: IOException) {
            throw ResourceFailure(0, resourceErrorMessage(0, null))
        }
        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            return (response.body() ?: Unit) as T
        }
        val code = runCatching {
            JsonParser.parseString(response.errorBody()?.string().orEmpty()).asJsonObject["code"]?.asString
        }.getOrNull()
        throw ResourceFailure(response.code(), resourceErrorMessage(response.code(), code))
    }

    companion object {
        const val PREVIEW_FOLDER = "resource-preview"

        /** Deletes every preview file still in the cache (for example after the app was closed mid-preview). */
        fun clearPreviews(cacheDir: File) {
            File(cacheDir, PREVIEW_FOLDER).listFiles()?.forEach { it.delete() }
        }
    }
}
