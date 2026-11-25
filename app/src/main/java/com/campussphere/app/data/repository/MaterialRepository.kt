package com.campussphere.app.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.campussphere.app.data.remote.api.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class MaterialRepository(val api:MaterialApi,private val attendance:AttendanceApi){
    suspend fun assignments(auth:String)=attendance.assignments(auth).let {
        if(!it.isSuccessful) throw retrofit2.HttpException(it)
        it.body() ?: throw IOException()
    }
    suspend fun upload(auth:String,resolver:ContentResolver,uri:Uri,id:Long,fields:MaterialFields)=withContext(Dispatchers.IO){
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) it.getString(0) else null
        } ?: throw IllegalArgumentException("Select a named PDF or text document.")
        val ext=name.substringAfterLast('.',"").lowercase()
        require(ext in setOf("pdf","txt")){"Select a PDF or UTF-8 text document."}
        val bytes=resolver.openInputStream(uri)?.use { input ->
            val output=java.io.ByteArrayOutputStream()
            val buffer=ByteArray(8192)
            while(true){
                val count=input.read(buffer)
                if(count<0) break
                require(output.size()+count<=10*1024*1024){"Maximum file size is 10 MiB."}
                output.write(buffer,0,count)
            }
            output.toByteArray()
        } ?: throw IOException()
        require(bytes.isNotEmpty()){"The file is empty."}
        val parts=mapOf("assignmentId" to id.toString(),"title" to fields.title,"description" to fields.description,"kind" to fields.kind)
        api.upload(auth,parts.mapValues { it.value.toRequestBody("text/plain".toMediaType()) },
            MultipartBody.Part.createFormData("file","document."+ext,bytes.toRequestBody("application/octet-stream".toMediaType())))
    }
    suspend fun download(auth:String,area:String,id:Long,resolver:ContentResolver,uri:Uri)=withContext(Dispatchers.IO){
        api.file(auth,area,id).use { body -> resolver.openOutputStream(uri,"wt")?.use { body.byteStream().copyTo(it) } ?: throw IOException() }
    }
}
