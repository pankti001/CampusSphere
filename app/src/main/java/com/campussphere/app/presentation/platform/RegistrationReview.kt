package com.campussphere.app.presentation.platform

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.ui.brand.*

/** The details of one college application and, while it is pending, the verification checklist and decision. */
@Composable
internal fun RegistrationReview(request: RegistrationDetails, busy: Boolean,
    approve: (Verification)->Unit, reject: (String)->Unit) {
    var institution by rememberSaveable { mutableStateOf(false) }
    var representative by rememberSaveable { mutableStateOf(false) }
    var mailbox by rememberSaveable { mutableStateOf(false) }
    var method by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var decision by rememberSaveable { mutableStateOf("") }
    val pending = request.status == "PENDING"

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(request.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                StatusChip(requestStatusLabel(request.status), requestStatusTone(request.status))
            }
            InfoRow("College code", request.code)
            request.website?.let { InfoRow("Website", it) }
            InfoRow("Applicant", request.fullName + " · " + request.position)
            InfoRow("Email", request.email)
            InfoRow("Expires", readableTime(request.expiresAt))
        }
        if (pending) {
            SectionHeader("Institutional verification")
            MessageBanner("Use an official contact you found yourself, and confirm the applicant personally submitted this application.",
                MessageKind.Info)
            InfoCard {
                Text("How you verified", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup()) {
                    listOf("Official callback" to "OFFICIAL_CALLBACK", "Official email" to "OFFICIAL_EMAIL").forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(method == value, role = Role.RadioButton, onClick = { method = value }),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(method == value, null)
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column {
                    VerificationCheck("Institution is genuine", institution) { institution = it }
                    VerificationCheck("Applicant submitted this request and is authorized", representative) { representative = it }
                    VerificationCheck("Applicant controls the institutional email", mailbox) { mailbox = it }
                }
            }
            PrimaryButton("Approve application", { decision = "Approve" },
                enabled = !busy && method.isNotEmpty() && institution && representative && mailbox, icon = CampusIcons.Check)

            SectionHeader("Reject instead")
            OutlinedTextField(reason, { if (it.length <= 300) reason = it }, Modifier.fillMaxWidth(),
                label = { Text("Reason for rejection") }, minLines = 2,
                supportingText = { Text("${reason.length}/300") })
            SecondaryButton("Reject application", { decision = "Reject" }, enabled = !busy && reason.isNotBlank(),
                icon = CampusIcons.Close, danger = true)
        }
    }

    if (pending && decision.isNotEmpty()) {
        val approving = decision == "Approve"
        ConfirmDialog(
            title = if (approving) "Approve application?" else "Reject application?",
            body = if (approving) "This creates an active college and gives its first administrator access."
                else "Reason: " + reason.trim(),
            confirm = if (approving) "Approve" else "Reject",
            onConfirm = {
                decision = ""
                if (approving) approve(Verification(method, institution, representative, mailbox)) else reject(reason.trim())
            },
            onDismiss = { decision = "" },
            danger = !approving,
        )
    }
}

@Composable
private fun VerificationCheck(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(checked, null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun requestStatusLabel(status: String) = when (status) {
    "PENDING" -> "Pending"
    "APPROVED" -> "Approved"
    "REJECTED" -> "Rejected"
    "EXPIRED" -> "Expired"
    else -> status.lowercase().replaceFirstChar { it.uppercase() }
}

private fun requestStatusTone(status: String) = when (status) {
    "PENDING" -> Tone.Warning
    "APPROVED" -> Tone.Positive
    "REJECTED" -> Tone.Danger
    else -> Tone.Neutral
}

/** The expiry in the phone's own time and format; the raw value when it can't be read. */
private fun readableTime(raw: String): String = runCatching {
    java.time.OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(raw)
