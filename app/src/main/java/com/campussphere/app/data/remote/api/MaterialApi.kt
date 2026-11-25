package com.campussphere.app.data.remote.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

data class MaterialFields(val title:String,val description:String,val kind:String)
data class MaterialItem(val id:Long,val title:String,val description:String,val kind:String,val format:String,
    val subject:String,val division:String,val faculty:String,val semester:String,val year:String,val uploaded:String,val archived:Boolean)
data class MaterialEvent(val action:String,val actor:String,val time:String)

interface MaterialApi {
    @GET("api/{area}/materials") suspend fun list(@Header("Authorization") auth:String,@Path("area") area:String):List<MaterialItem>
    @Multipart @POST("api/faculty/materials") suspend fun upload(@Header("Authorization") auth:String,
        @PartMap fields:Map<String, @JvmSuppressWildcards RequestBody>,@Part file:MultipartBody.Part):Long
    @PATCH("api/faculty/materials/{id}") suspend fun edit(@Header("Authorization") auth:String,@Path("id") id:Long,@Body fields:MaterialFields)
    @DELETE("api/{area}/materials/{id}") suspend fun archive(@Header("Authorization") auth:String,@Path("area") area:String,@Path("id") id:Long)
    @GET("api/{area}/materials/{id}/file") suspend fun file(@Header("Authorization") auth:String,@Path("area") area:String,@Path("id") id:Long):ResponseBody
    @GET("api/college/materials/{id}/events") suspend fun events(@Header("Authorization") auth:String,@Path("id") id:Long):List<MaterialEvent>
}
