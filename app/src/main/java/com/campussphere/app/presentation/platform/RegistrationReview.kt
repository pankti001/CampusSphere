package com.campussphere.app.presentation.platform

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.api.*

@Composable
internal fun RegistrationReview(request: RegistrationDetails, busy: Boolean,
    approve: (Verification)->Unit, reject: (String)->Unit) {
    var institution by remember { mutableStateOf(false) }
    var representative by remember { mutableStateOf(false) }
    var mailbox by remember { mutableStateOf(false) }
    var method by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var decision by remember { mutableStateOf("") }
    Text(request.name,style=MaterialTheme.typography.headlineMedium)
    Text(request.code+" • "+request.status)
    request.website?.let { Text(it) }
    Text(request.fullName+" • "+request.position)
    Text(request.email)
    Text("Expires "+request.expiresAt)
    if (request.status!="PENDING") return
    Text("Institutional verification",style=MaterialTheme.typography.titleLarge)
    Text("Use an independently obtained official contact. Confirm the applicant personally submitted the application.")
    listOf("Official callback" to "OFFICIAL_CALLBACK","Official email" to "OFFICIAL_EMAIL").forEach { (label,value) ->
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(method==value,role=Role.RadioButton,onClick={method=value}),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            RadioButton(method==value,null); Text(label)
        }
    }
    Check("Institution is genuine",institution) { institution=it }
    Check("Applicant submitted this request and is authorized",representative) { representative=it }
    Check("Applicant controls the institutional email",mailbox) { mailbox=it }
    Button(enabled=!busy && method.isNotEmpty() && institution && representative && mailbox,
        onClick={decision="Approve"}) { Text("Approve application") }
    OutlinedTextField(reason,{ if(it.length<=300) reason=it },label={Text("Reason for rejection")})
    OutlinedButton(enabled=!busy && reason.isNotBlank(),onClick={decision="Reject"}) { Text("Reject application") }
    if (decision.isNotEmpty()) AlertDialog(onDismissRequest={decision=""},title={Text(decision+" application?")},
        text={Text(if (decision=="Approve") "Create an active college and grant its first administrator access?" else reason)},
        dismissButton={TextButton(onClick={decision=""}) {Text("Cancel")}},
        confirmButton={Button(onClick={
            val approving=decision=="Approve"; decision=""
            if (approving) approve(Verification(method,institution,representative,mailbox)) else reject(reason.trim())
        }) {Text("Confirm "+decision.lowercase())}})
}

@Composable
private fun Check(label:String,checked:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(checked,role=Role.Checkbox,onValueChange=change), verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Checkbox(checked,null); Text(label)
    }
}