package com.campussphere.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.campussphere.app.data.remote.api.*
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.IOException

/** A failed material request. [status] is the HTTP status (0 for network or local file problems). */
class MaterialFailure(val status: Int, message: String) : Exception(message)

/** A file chosen for upload, already read and checked on the device. */
class MaterialUpload(val fileName: String, val mimeType: String, val bytes: ByteArray)

const val MATERIAL_LIMIT_BYTES = 10 * 1024 * 1024
private val MIME_BY_EXTENSION = mapOf(
    "pdf" to "application/pdf",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "txt" to "text/plain")
val MATERIAL_PICKER_TYPES = MIME_BY_EXTENSION.values.toTypedArray()

/** Local check before uploading; the server repeats every check on the actual content. */
fun materialFileProblem(fileName: String?, size: Long): String? {
    val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return when {
        fileName.isNullOrBlank() || ext.isEmpty() -> "Choose a named document."
        ext !in MIME_BY_EXTENSION -> "Choose a PDF, Word (.docx), PowerPoint (.pptx), Excel (.xlsx) or text file."
        size <= 0 -> "The selected file is empty."
        size > MATERIAL_LIMIT_BYTES -> "The file is larger than 10 MB."
        else -> null
    }
}

fun materialMimeType(fileName: String): String =
    MIME_BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

/** Friendly message for a server error code. The server's own text is never shown. */
fun materialErrorMessage(status: Int, code: String?): String = when (code) {
    "MATERIAL_FILE_INVALID" -> "This file type or content is not allowed. Use a PDF, Word, PowerPoint, Excel or text file up to 10 MB."
    "MATERIAL_DUPLICATE" -> "This file is already shared with this class."
    "MATERIAL_STATE_CONFLICT" -> "This material changed or can no longer be edited. Refresh and try again."
    "MATERIAL_NOT_FOUND" -> "This material or class is no longer available."
    "INVALID_REQUEST" -> "Check the title, type and other fields, then try again."
    "VERIFICATION_REQUIRED" -> "Your account needs verification before you can open materials."
    else -> when (status) {
        401 -> "Your session expired. Sign in again."
        403 -> "You do not have permission for this action."
        404 -> "This material is no longer available."
        413 -> "The file is larger than 10 MB."
        in 500..599 -> "CampusSphere could not complete the request. Try again shortly."
        else -> "Request failed. Check your connection and try again."
    }
}

/** Request body that reports how much of the file has been written to the network. */
class ProgressRequestBody(private val bytes: ByteArray, private val type: MediaType,
                          private val onProgress: (Float) -> Unit) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = bytes.size.toLong()
    override fun writeTo(sink: BufferedSink) {
        var written = 0
        while (written < bytes.size) {
            val count = minOf(16 * 1024, bytes.size - written)
            sink.write(bytes, written, count)
            written += count
            onProgress(written.toFloat() / bytes.size)
        }
    }
}

class MaterialRepository(private val api: MaterialApi) {
    suspend fun assignments(auth: String) = call { api.assignments(auth) }
    suspend fun list(auth: String, area: String) = call { api.list(auth, area) }
    suspend fun detail(auth: String, area: String, id: Long) = call { api.detail(auth, area, id) }
    suspend fun update(auth: String, id: Long, fields: MaterialFields) = call { api.update(auth, id, fields) }
    suspend fun delete(auth: String, id: Long) { call { api.delete(auth, id) } }
    suspend fun events(auth: String, id: Long) = call { api.events(auth, id) }
    suspend fun moderate(auth: String, id: Long, action: String, note: String?) =
        call { api.moderate(auth, id, MaterialModeration(action, note?.trim()?.ifEmpty { null })) }

    /** Reads and checks a picked document. Runs off the main thread. */
    suspend fun read(resolver: ContentResolver, uri: Uri): MaterialUpload = withContext(Dispatchers.IO) {
        var name: String? = null
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                name = it.getString(0)
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        materialFileProblem(name, if (size < 0) 1 else size)?.let { throw MaterialFailure(0, it) }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > MATERIAL_LIMIT_BYTES) throw MaterialFailure(0, "The file is larger than 10 MB.")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: throw MaterialFailure(0, "The selected file could not be opened.")
        materialFileProblem(name, bytes.size.toLong())?.let { throw MaterialFailure(0, it) }
        MaterialUpload(name!!, materialMimeType(name!!), bytes)
    }

    suspend fun upload(auth: String, assignmentId: Long, fields: MaterialFields, file: MaterialUpload,
                       onProgress: (Float) -> Unit): MaterialItem {
        val text = "text/plain".toMediaType()
        val parts = buildMap {
            put("assignmentId", assignmentId.toString().toRequestBody(text))
            put("title", fields.title.toRequestBody(text))
            put("description", fields.description.toRequestBody(text))
            put("type", fields.type.toRequestBody(text))
            fields.topic?.takeIf { it.isNotBlank() }?.let { put("topic", it.toRequestBody(text)) }
        }
        val body = ProgressRequestBody(file.bytes, file.mimeType.toMediaType(), onProgress)
        return call { api.upload(auth, parts, MultipartBody.Part.createFormData("file", file.fileName, body)) }
    }

    /** Streams the file straight into the document the user chose. */
    suspend fun download(auth: String, area: String, id: Long, resolver: ContentResolver, target: Uri) {
        val body = call { api.file(auth, area, id) }
        withContext(Dispatchers.IO) {
            body.use { source ->
                resolver.openOutputStream(target, "wt")?.use { source.byteStream().copyTo(it) }
                    ?: throw MaterialFailure(0, "The download location could not be opened.")
            }
        }
    }

    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MaterialFailure) {
            throw e
        } catch (e: IOException) {
            throw MaterialFailure(0, materialErrorMessage(0, null))
        }
        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            return (response.body() ?: Unit) as T
        }
        val code = runCatching {
            JsonParser.parseString(response.errorBody()?.string().orEmpty()).asJsonObject["code"]?.asString
        }.getOrNull()
        throw MaterialFailure(response.code(), materialErrorMessage(response.code(), code))
    }
}
