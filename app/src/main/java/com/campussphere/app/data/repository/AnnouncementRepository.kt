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
import retrofit2.Response
import java.io.IOException

/** A failed announcement request. [status] is the HTTP status (0 for network or local file problems). */
class AnnouncementFailure(val status: Int, message: String) : Exception(message)

/** Friendly message for a server error code; the server's own text is never shown. */
fun announcementErrorMessage(status: Int, code: String?): String = when (code) {
    "ANNOUNCEMENT_TARGET_INVALID" -> "Choose an audience you are allowed to address."
    "ANNOUNCEMENT_SCHEDULE_INVALID" -> "The expiry must be in the future and after the publication time."
    "ANNOUNCEMENT_STATE_CONFLICT" -> "This announcement changed or can no longer be changed this way. Refresh and try again."
    "ANNOUNCEMENT_NOT_FOUND" -> "This announcement is no longer available."
    "MATERIAL_FILE_INVALID" -> "Attach a PDF, Word, PowerPoint, Excel or text file up to 10 MB."
    "INVALID_REQUEST" -> "Check the title, message and audience, then try again."
    else -> when (status) {
        401 -> "Your session expired. Sign in again."
        403 -> "You do not have permission for this action."
        404 -> "This announcement is no longer available."
        in 500..599 -> "CampusSphere could not complete the request. Try again shortly."
        else -> "Request failed. Check your connection and try again."
    }
}

class AnnouncementRepository(private val api: AnnouncementApi, private val files: MaterialRepository) {
    suspend fun list(auth: String, area: String, category: String?, priority: String?, status: String?, q: String?,
                     unread: Boolean?, page: Int = 0) =
        call { api.list(auth, area, category, priority, status, q?.trim()?.ifEmpty { null }, unread, page) }
    suspend fun mine(auth: String, status: String?, page: Int = 0) = call { api.mine(auth, status, page) }
    suspend fun targets(auth: String, area: String) = call { api.targets(auth, area) }
    suspend fun overview(auth: String) = call { api.overview(auth) }
    suspend fun detail(auth: String, area: String, id: Long) = call { api.detail(auth, area, id) }
    suspend fun stats(auth: String, id: Long) = call { api.stats(auth, id) }
    suspend fun create(auth: String, area: String, input: AnnouncementInput) = call { api.create(auth, area, input) }
    suspend fun update(auth: String, area: String, id: Long, input: AnnouncementInput) = call { api.update(auth, area, id, input) }
    suspend fun publish(auth: String, area: String, id: Long, publishAt: String?) =
        call { api.publish(auth, area, id, AnnouncementPublish(publishAt)) }
    suspend fun archive(auth: String, area: String, id: Long) = call { api.archive(auth, area, id) }
    suspend fun delete(auth: String, area: String, id: Long) { call { api.delete(auth, area, id) } }
    suspend fun markRead(auth: String, area: String, id: Long) { call { api.read(auth, area, id) } }
    suspend fun detach(auth: String, area: String, id: Long) = call { api.detach(auth, area, id) }

    /** Reads and checks a picked file with the same rules as study materials. */
    suspend fun readFile(resolver: ContentResolver, uri: Uri): MaterialUpload = try {
        files.read(resolver, uri)
    } catch (e: MaterialFailure) {
        throw AnnouncementFailure(e.status, e.message.orEmpty())
    }

    suspend fun attach(auth: String, area: String, id: Long, file: MaterialUpload, onProgress: (Float) -> Unit): AnnouncementItem {
        val body = ProgressRequestBody(file.bytes, file.mimeType.toMediaType(), onProgress)
        return call { api.attach(auth, area, id, MultipartBody.Part.createFormData("file", file.fileName, body)) }
    }

    suspend fun download(auth: String, area: String, id: Long, resolver: ContentResolver, target: Uri) {
        val body = call { api.attachment(auth, area, id) }
        withContext(Dispatchers.IO) {
            body.use { source ->
                resolver.openOutputStream(target, "wt")?.use { source.byteStream().copyTo(it) }
                    ?: throw AnnouncementFailure(0, "The download location could not be opened.")
            }
        }
    }

    private suspend fun <T> call(block: suspend () -> Response<T>): T {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AnnouncementFailure(0, announcementErrorMessage(0, null))
        }
        if (response.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            return (response.body() ?: Unit) as T
        }
        val code = runCatching {
            JsonParser.parseString(response.errorBody()?.string().orEmpty()).asJsonObject["code"]?.asString
        }.getOrNull()
        throw AnnouncementFailure(response.code(), announcementErrorMessage(response.code(), code))
    }
}
