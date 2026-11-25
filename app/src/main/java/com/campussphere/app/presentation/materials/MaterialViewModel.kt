package com.campussphere.app.presentation.materials

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.MaterialRepository

class MaterialViewModel(val repo:MaterialRepository,val area:String,val auth:()->String,val lost:(Int)->Unit):ViewModel(){
    var items by mutableStateOf(listOf<MaterialItem>()); private set
    var classes by mutableStateOf(listOf<AttendanceAssignment>()); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var events by mutableStateOf(listOf<MaterialEvent>()); private set
    fun action(success:String?=null,block:suspend ()->Unit){
        if(busy)return
        busy=true;error=null;notice=null
        viewModelScope.launch{
            try{block();notice=success}
            catch(e:CancellationException){throw e}
            catch(e:Exception){
                val status=(e as? retrofit2.HttpException)?.code()
                if(status==401 || status==403){items=emptyList();classes=emptyList();events=emptyList();lost(status)}
                error=when(status){
                    400,413->"Check the document format, size and fields."
                    404->"The material or assignment is no longer available."
                    else->if(e is IllegalArgumentException)e.message else "Request failed. Check your connection and refresh before trying again."
                }
            }finally{busy=false}
        }
    }
    suspend fun refresh(){
        val list=repo.api.list(auth(),area)
        classes=if(area=="faculty")repo.assignments(auth()) else emptyList()
        items=list
    }
    fun load()=action{refresh()}
    fun history(id:Long){events=emptyList();action{events=repo.api.events(auth(),id)}}
}
