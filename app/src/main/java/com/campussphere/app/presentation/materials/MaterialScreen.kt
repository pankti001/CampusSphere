package com.campussphere.app.presentation.materials

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.MaterialRepository

@Composable
private fun Choice(label:String,options:List<Pair<String,String>>,value:String,select:(String)->Unit){
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={expanded=true}){Text(label+" · "+(options.firstOrNull{it.first==value}?.second ?: "Select"))}
        DropdownMenu(expanded,{expanded=false}){options.forEach{(key,text)->
            DropdownMenuItem({Text(text)},{select(key);expanded=false})
        }}
    }
}

@Composable
fun MaterialScreen(repo:MaterialRepository,area:String,auth:()->String,lost:(Int)->Unit,exit:()->Unit){
    val owner=remember(area){object:ViewModelStoreOwner{override val viewModelStore=ViewModelStore()}}
    DisposableEffect(owner){onDispose{owner.viewModelStore.clear()}}
    val vm=viewModel<MaterialViewModel>(viewModelStoreOwner=owner){MaterialViewModel(repo,area,auth,lost)}
    val context=LocalContext.current
    var selected by remember { mutableStateOf<Long?>(null) }
    val item=vm.items.firstOrNull{it.id==selected}
    var form by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("NOTES") }
    var subject by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var assignment by remember { mutableStateOf("") }
    var source by remember { mutableStateOf<Uri?>(null) }
    var saved by remember { mutableStateOf<Uri?>(null) }
    var openError by remember { mutableStateOf(false) }
    val types=listOf("NOTES","READING","REFERENCE").map{it to it.lowercase().replaceFirstChar(Char::uppercase)}
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){if(it!=null)source=it}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(if(item?.format=="pdf")"application/pdf" else "text/plain")){ uri ->
        if(uri!=null && item!=null) vm.action("Document downloaded."){
            repo.download(auth(),area,item.id,context.contentResolver,uri);saved=uri
        }
    }
    val back:()->Unit={if(form)form=false else if(selected!=null){selected=null;saved=null;openError=false}else exit()}
    BackHandler{if(!vm.busy)back()}
    LaunchedEffect(vm){vm.load()}
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{
            TextButton(back,enabled=!vm.busy){Text("Back")}
            Text(if(form && selected==null)"Upload material" else if(form)"Edit material" else "Study materials",style=MaterialTheme.typography.headlineMedium)
            if(vm.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.error?.let{Text(it,color=MaterialTheme.colorScheme.error);TextButton(vm::load,enabled=!vm.busy){Text("Refresh")}}
            vm.notice?.let{Text(it)}
        }
        if(form)item{
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                if(selected==null){
                    Choice("Class",vm.classes.map{it.id.toString() to (it.subject+" · "+it.division+" · "+it.program+" · "+it.semester)},assignment){assignment=it}
                    OutlinedButton({pick.launch(arrayOf("application/pdf","text/plain"))}){Text(if(source==null)"Choose PDF or text file" else "Replace selected document")}
                    Text("PDF or UTF-8 text · up to 10 MiB")
                }
                OutlinedTextField(title,{title=it.take(160)},label={Text("Title")},singleLine=true)
                OutlinedTextField(desc,{desc=it.take(2000)},label={Text("Description")})
                Choice("Material type",types,kind){kind=it}
                Button(enabled=!vm.busy && title.isNotBlank() && (selected!=null || (source!=null && assignment.isNotBlank())),onClick={
                    val fields=MaterialFields(title.trim(),desc.trim(),kind)
                    vm.action("Material saved."){
                        if(selected==null)repo.upload(auth(),context.contentResolver,requireNotNull(source),assignment.toLong(),fields)
                        else repo.api.edit(auth(),requireNotNull(selected),fields)
                        form=false;vm.refresh()
                    }
                }){Text(if(selected==null)"Upload" else "Save changes")}
            }
        } else if(item!=null)item{
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(item.title,style=MaterialTheme.typography.titleLarge)
                Text(item.description);Text(item.subject+" · "+item.division+" · "+item.semester+" · "+item.year)
                Text(item.faculty+" · "+item.uploaded);Text(item.kind+" · "+item.format.uppercase())
                if(item.archived)Text("Archived") else {
                    Button({export.launch("material-"+item.id+"."+item.format)},enabled=!vm.busy){Text("Download document")}
                    saved?.let{uri->TextButton({
                        val view=Intent(Intent.ACTION_VIEW).setDataAndType(uri,if(item.format=="pdf")"application/pdf" else "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        openError=runCatching{context.startActivity(view)}.isFailure
                    }){Text("Open downloaded document")}}
                    if(openError)Text("No compatible viewer is installed. The downloaded file is available in Files.")
                    if(area=="faculty")OutlinedButton({title=item.title;desc=item.description;kind=item.kind;form=true}){Text("Edit details")}
                    if(area!="student")OutlinedButton({confirm=true},enabled=!vm.busy){Text("Archive material")}
                }
                if(area=="college"){
                    Text("Activity",style=MaterialTheme.typography.titleMedium)
                    TextButton({vm.history(item.id)},enabled=!vm.busy){Text("Refresh activity")}
                    vm.events.forEach{Text(it.action+" · "+it.actor+" · "+it.time)}
                }
            }
        } else {
            item{
                if(area=="faculty" && vm.classes.isNotEmpty())Button({title="";desc="";kind="NOTES";assignment="";source=null;form=true},enabled=!vm.busy){Text("Upload material")}
                if(area=="faculty" && vm.classes.isEmpty() && !vm.busy && vm.error==null)Text("No active teaching assignments.")
                Choice("Subject",listOf("" to "All subjects")+vm.items.map{it.subject}.distinct().sorted().map{it to it},subject){subject=it}
                Choice("Type",listOf("" to "All types")+types,filter){filter=it}
            }
            val visible=vm.items.filter{(subject.isEmpty() || it.subject==subject)&&(filter.isEmpty() || filter==it.kind)}
            if(visible.isEmpty() && !vm.busy && vm.error==null)item{Text("No materials available for this selection.")}
            items(visible,key={it.id}){m->OutlinedCard(onClick={selected=m.id;saved=null;openError=false;if(area=="college")vm.history(m.id)},modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp)){Text(m.title,style=MaterialTheme.typography.titleMedium);Text(m.subject+" · "+m.division);Text(if(m.archived)"Archived" else m.kind+" · "+m.faculty)}
            }}
        }
    }
    if(confirm && item!=null)AlertDialog(onDismissRequest={confirm=false},title={Text("Archive this material?")},
        text={Text("Students will lose access. The college retains the document and its activity history.")},
        confirmButton={TextButton({confirm=false;vm.action("Material archived."){repo.api.archive(auth(),area,item.id);selected=null;vm.refresh()}}){Text("Archive")}},
        dismissButton={TextButton({confirm=false}){Text("Cancel")}})
}
