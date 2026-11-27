package com.campussphere.app.presentation.platform

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.PlatformFailure
import com.campussphere.app.data.repository.PlatformRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Page { OVERVIEW, REQUESTS, REVIEW, COLLEGES, SELECT_COLLEGE, DETAILS, ADD, EDIT, INVITATIONS }
private val Navy = Color(0xFF152C40)
private val Teal = Color(0xFF00695F)

@Composable
fun PlatformScreen(repository: PlatformRepository, authorization: () -> String, onExpired: () -> Unit,
                   onLogout: () -> Unit, registration: CollegeRegistrationApi = remember { RetrofitClient.createService(CollegeRegistrationApi::class.java) }) {
    var page by remember { mutableStateOf(Page.OVERVIEW) }

    var applicationId by remember { mutableLongStateOf(0L) }
    var cursor by remember { mutableLongStateOf(0L) }
    suspend fun <T> registrationCall(call: suspend () -> T): T = try { call() }
        catch (e: retrofit2.HttpException) {
            val message = when(e.code()) {
                409 -> {
                    val code = runCatching { e.response()?.errorBody()?.use {
                        com.google.gson.JsonParser.parseString(it.string()).asJsonObject["code"]?.asString
                    } }.getOrNull()
                    when(code) {
                        "REGISTRATION_EXPIRED" -> "This application expired. Return to the pending queue."
                        "COLLEGE_CODE_CONFLICT" -> "This college code is already in use. Review the conflict before proceeding."
                        else -> "Application already decided or account conflict. Return to the queue and review."
                    }
                }
                403 -> "Platform access denied."
                503 -> "Database temporarily unavailable."
                else -> "The application request could not be completed."
            }
            throw PlatformFailure(e.code(), message)
        }
    var selected by remember { mutableStateOf<College?>(null) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun navigate(target: Page) {
        error = null
        notice = null
        page = target
    }
    fun back() = navigate(when (page) {
        Page.REVIEW -> Page.REQUESTS
        Page.EDIT -> Page.DETAILS
        Page.DETAILS, Page.ADD -> Page.COLLEGES
        Page.INVITATIONS -> Page.SELECT_COLLEGE
        else -> Page.OVERVIEW
    })
    fun onFailure(failure: Exception) {
        if (failure is CancellationException) throw failure
        val status = (failure as? PlatformFailure)?.status
        if (status in setOf(401, 403, 503)) {
            blocked = true
            selected = null
            token = null
        }
        error = (failure as? PlatformFailure)?.message ?: "Could not load CampusSphere. Try again."
        if (status == 401) onExpired()
    }
    fun mutate(action: suspend (String) -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                action(authorization())
            } catch (failure: Exception) {
                onFailure(failure)
            } finally {
                busy = false
            }
        }
    }

    BackHandler(page != Page.OVERVIEW && !busy) { back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier.widthIn(max = 760.dp).fillMaxWidth().background(Navy).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (page != Page.OVERVIEW && !blocked) {
                    TextButton(onClick = { back() }, enabled = !busy) { Text("Back", color = Color.White) }
                }
                Text("CampusSphere", Modifier.weight(1f), color = Color.White,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onLogout, enabled = !busy) { Text("Sign out", color = Color.White) }
            }
            key(page, selected?.id, revision, blocked) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (blocked) {
                        Heading("Platform access unavailable", error ?: "Refresh your access and try again.")
                        Button(onClick = {
                            blocked = false
                            navigate(Page.OVERVIEW)
                            revision++
                        }) { Text("Retry") }
                    } else {
                        notice?.let { Text(it, color = Teal) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        when (page) {
                            Page.OVERVIEW -> {
                                Heading("Super Admin dashboard", "Platform administration")
                                PrimaryAction("Pending College Requests") { cursor=0; navigate(Page.REQUESTS) }
                                ReadPanel(load = { registrationCall { registration.pending(authorization(),0) } }, denied = ::onFailure) {
                                    Metric("Pending applications", it.count)
                                }
                                ReadPanel(load = { repository.overview(authorization()) }, denied = ::onFailure) { stats ->
                                    Metric("Total colleges", stats.totalColleges)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Metric("Active", stats.activeColleges, Modifier.weight(1f))
                                        Metric("Inactive", stats.inactiveColleges, Modifier.weight(1f))
                                    }
                                    Metric("Pending invitations", stats.pendingInvitations)
                                    if (stats.totalColleges == 0L) Text("No colleges have been added yet.")
                                }
                                // Navigation stays available even when the statistics fail to load.
                                PrimaryAction("College management") { navigate(Page.COLLEGES) }
                                OutlinedButton(onClick = { navigate(Page.SELECT_COLLEGE) }) { Text("College Admin invitations") }
                            }
                            Page.REQUESTS -> key(cursor) {
                                Heading("Pending College Requests","Review applications from college representatives.")
                                ReadPanel(load={ registrationCall { registration.pending(authorization(),cursor) } },denied=::onFailure) { result ->
                                    Text("Pending applications: "+result.count)
                                    if(result.items.isEmpty()) Text("No pending applications on this page.")
                                    result.items.forEach { row ->
                                        OutlinedButton(onClick={ applicationId=row.id; navigate(Page.REVIEW) }) { Text(row.name+" • "+row.code) }
                                    }
                                    if(result.items.size==25) TextButton(onClick={cursor=result.items.last().id}) {Text("Next page")}
                                    if(cursor!=0L) TextButton(onClick={cursor=0}) {Text("First page")}
                                }
                            }
                            Page.REVIEW -> ReadPanel(load={ registrationCall { registration.detail(authorization(),applicationId) } },denied=::onFailure) { row ->
                                if (error != null) {
                                    OutlinedButton(enabled = !busy, onClick = {
                                        error = null
                                        revision++
                                    }) { Text("Refresh application") }
                                }
                                RegistrationReview(row, busy || error != null, approve={ evidence -> mutate { auth ->
                                    registrationCall { registration.approve(auth,row.id,evidence) }
                                    cursor=0; navigate(Page.REQUESTS); notice="Application approved."
                                } },reject={ reason -> mutate { auth ->
                                    registrationCall { registration.reject(auth,row.id,Rejection(reason)) }
                                    cursor=0; navigate(Page.REQUESTS); notice="Application rejected."
                                } })
                            }
                            Page.COLLEGES, Page.SELECT_COLLEGE -> CollegeList(
                                selecting = page == Page.SELECT_COLLEGE,
                                load = { search, after -> repository.colleges(authorization(), search, after) },
                                denied = ::onFailure,
                                onAdd = { navigate(Page.ADD) },
                                onSelect = {
                                    selected = it
                                    navigate(if (page == Page.SELECT_COLLEGE) Page.INVITATIONS else Page.DETAILS)
                                }
                            )
                            Page.DETAILS -> selected?.let { college ->
                                Heading("College details", "Manage college information and access.")
                                ReadPanel(load = { repository.college(authorization(), college.id) }, denied = ::onFailure) { live ->
                                    CollegeDetails(live, busy, onEdit = { selected = live; navigate(Page.EDIT) },
                                        onInvite = { selected = live; navigate(Page.INVITATIONS) },
                                        onStatus = { active ->
                                            mutate { auth ->
                                                repository.status(auth, live.id, active)
                                                notice = if (active) "College activated." else "College deactivated."
                                                revision++
                                            }
                                        })
                                }
                            }
                            Page.ADD, Page.EDIT -> {
                                val existing = selected.takeIf { page == Page.EDIT }
                                Heading(if (existing == null) "Add college" else "Edit college",
                                    if (existing == null) "New colleges start inactive." else "Update the college name and code.")
                                CollegeForm(existing, busy) { input ->
                                    mutate { auth ->
                                        selected = repository.save(auth, existing?.id, input)
                                        navigate(if (existing == null) Page.COLLEGES else Page.DETAILS)
                                        notice = if (existing == null) "College added." else "College updated."
                                    }
                                }
                            }
                            Page.INVITATIONS -> selected?.let { college ->
                                Heading("College Admin invitations", college.name)
                                ReadPanel(load = { repository.college(authorization(), college.id) }, denied = ::onFailure) { live ->
                                    InvitationForm(live.active, busy) { email ->
                                        mutate { auth ->
                                            token = repository.invite(auth, live.id, email).token
                                            notice = "Invitation issued."
                                            revision++
                                        }
                                    }
                                    InvitationHistory(load = { before -> repository.invitations(authorization(), live.id, before) },
                                        denied = ::onFailure, busy = busy, onRevoke = { invitation ->
                                            mutate { auth ->
                                                repository.revoke(auth, live.id, invitation.id)
                                                notice = "Invitation revoked."
                                                revision++
                                            }
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    token?.let { secret ->
        Dialog(onDismissRequest = { token = null }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
            Card {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Invitation code", style = MaterialTheme.typography.titleLarge)
                    Text("Share this code privately with the verified recipient. It expires in 24 hours and cannot be shown again.")
                    Text(secret, color = Teal, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { token = null }) { Text("Done") }
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Metric(label: String, value: Long?, modifier: Modifier = Modifier.fillMaxWidth()) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value?.toString() ?: "Unavailable", color = Teal, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun PrimaryAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Color.White)) { Text(label) }
}

@Composable
private fun <T : Any> ReadPanel(load: suspend () -> T, denied: (Exception) -> Unit, content: @Composable (T) -> Unit) {
    var value by remember { mutableStateOf<T?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        loadError = null
        try {
            value = load()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            value = null
            val platformFailure = failure as? PlatformFailure
            if (platformFailure?.status in setOf(401, 403, 503)) {
                denied(failure)
            } else {
                loadError = platformFailure?.message ?: "Could not load CampusSphere. Try again."
            }
        }
    }
    when {
        loadError != null -> {
            Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { retry++ }) { Text("Retry") }
        }
        value == null -> CircularProgressIndicator(Modifier.size(28.dp), color = Teal)
        else -> value?.let { content(it) }
    }
}

@Composable
private fun CollegeList(selecting: Boolean, load: suspend (String, Long) -> List<College>,
                        denied: (Exception) -> Unit, onAdd: () -> Unit, onSelect: (College) -> Unit) {
    var query by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var cursors by remember { mutableStateOf(listOf(0L)) }
    Heading(if (selecting) "Select a college" else "College management",
        if (selecting) "Choose a college to manage its invitations." else "Search, review, and manage colleges.")
    if (!selecting) PrimaryAction("Add college", onClick = onAdd)
    Input(query, { query = it.take(150) }, "Search by name or code")
    OutlinedButton(onClick = { search = query.trim(); cursors = listOf(0L) }) { Text("Search") }
    key(search, cursors.last()) {
        ReadPanel(load = { load(search, cursors.last()) }, denied = denied) { colleges ->
            if (colleges.isEmpty()) Text(if (search.isEmpty()) "No colleges yet." else "No colleges match your search.")
            colleges.forEach { college ->
                OutlinedCard(onClick = { onSelect(college) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(college.name, fontWeight = FontWeight.Bold)
                        Text(college.code + " • " + if (college.active) "Active" else "Inactive")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text("Previous") }
                if (colleges.size == PlatformRepository.PAGE_SIZE) {
                    TextButton(onClick = { cursors = cursors + colleges.last().id }) { Text("Next") }
                }
            }
        }
    }
}

@Composable
private fun CollegeDetails(college: College, busy: Boolean, onEdit: () -> Unit, onInvite: () -> Unit,
                           onStatus: (Boolean) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(college.name, style = MaterialTheme.typography.titleLarge)
            Text(college.code)
            Text(if (college.active) "Active" else "Inactive", color = Teal)
        }
    }
    PrimaryAction("Edit college", !busy, onEdit)
    OutlinedButton(onClick = onInvite, enabled = !busy) { Text("College Admin invitations") }
    OutlinedButton(onClick = { if (college.active) confirm = true else onStatus(true) }, enabled = !busy) {
        Text(if (college.active) "Deactivate college" else "Activate college")
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Deactivate this college?") },
        text = { Text("College users will lose access and pending invitations will be revoked.") },
        confirmButton = { TextButton(onClick = { confirm = false; onStatus(false) }) { Text("Deactivate") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}

@Composable
private fun CollegeForm(college: College?, busy: Boolean, onSave: (CollegeInput) -> Unit) {
    var name by remember { mutableStateOf(college?.name.orEmpty()) }
    var code by remember { mutableStateOf(college?.code.orEmpty()) }
    val validCode = code.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,29}"))
    Input(name, { name = it.take(150) }, "College name", enabled = !busy)
    Input(code, { code = it.take(30) }, "College code", enabled = !busy)
    Text("Use letters, digits, underscores or hyphens, beginning with a letter or digit.",
        style = MaterialTheme.typography.bodySmall)
    PrimaryAction(if (busy) "Saving…" else "Save college", !busy && name.isNotBlank() && validCode) {
        onSave(CollegeInput(name.trim(), code))
    }
}

@Composable
private fun InvitationForm(active: Boolean, busy: Boolean, onInvite: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    if (!active) {
        Text("Activate this college before issuing invitations.")
        return
    }
    Input(email, { email = it.take(150) }, "Verified institutional email", KeyboardType.Email, !busy)
    PrimaryAction(if (busy) "Sending…" else "Issue invitation",
        !busy && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { onInvite(email) }
}

@Composable
private fun InvitationHistory(load: suspend (Long) -> List<CollegeInvitation>, denied: (Exception) -> Unit,
                              busy: Boolean, onRevoke: (CollegeInvitation) -> Unit) {
    var confirm by remember { mutableStateOf<CollegeInvitation?>(null) }
    var cursors by remember { mutableStateOf(listOf(Long.MAX_VALUE)) }
    Text("Invitation history", style = MaterialTheme.typography.titleLarge)
    key(cursors.last()) {
        ReadPanel(load = { load(cursors.last()) }, denied = denied) { rows ->
            if (rows.isEmpty()) Text("No invitations on this page.")
            rows.forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(item.email, fontWeight = FontWeight.Bold)
                        Text(item.status.lowercase().replaceFirstChar { it.uppercase() }, color = Teal)
                        val expiration = runCatching {
                            java.time.OffsetDateTime.parse(item.expiresAt).atZoneSameInstant(java.time.ZoneId.systemDefault())
                                .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM))
                        }.getOrNull()
                        if (expiration != null) Text("Expires $expiration", style = MaterialTheme.typography.bodySmall)
                        if (item.status == "PENDING") {
                            TextButton(onClick = { confirm = item }, enabled = !busy) { Text("Revoke invitation") }
                        }
                    }
                }
            }
            Row {
                if (cursors.size > 1) TextButton(onClick = { cursors = cursors.dropLast(1) }) { Text("Previous") }
                if (rows.size == PlatformRepository.PAGE_SIZE) {
                    TextButton(onClick = { cursors = cursors + rows.last().id }) { Text("Next") }
                }
            }
        }
    }
    confirm?.let { invitation ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Revoke this invitation?") },
            text = { Text("The recipient will no longer be able to use the invitation code.") },
            confirmButton = {
                TextButton(onClick = { confirm = null; onRevoke(invitation) }, enabled = !busy) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun Input(value: String, change: (String) -> Unit, label: String,
                  keyboardType: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboardType), enabled = enabled)
}
