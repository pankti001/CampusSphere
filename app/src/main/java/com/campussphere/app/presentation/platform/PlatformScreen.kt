package com.campussphere.app.presentation.platform

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.campussphere.app.data.remote.api.*
import com.campussphere.app.data.repository.PlatformFailure
import com.campussphere.app.data.repository.PlatformRepository
import com.campussphere.app.ui.brand.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Page { OVERVIEW, REQUESTS, REVIEW, COLLEGES, SELECT_COLLEGE, DETAILS, ADD, EDIT, INVITATIONS }

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
    // College search and paging live here, so Back from a college returns to the same search and page of the list.
    var collegeQuery by remember { mutableStateOf("") }
    var collegeSearch by remember { mutableStateOf("") }
    var collegeCursors by remember { mutableStateOf(listOf(0L)) }
    // As before, invitation paging starts again whenever the page, the college or the data revision changes.
    var invitationCursors by remember(page, selected?.id, revision, blocked) { mutableStateOf(listOf(Long.MAX_VALUE)) }
    var revoking by remember { mutableStateOf<CollegeInvitation?>(null) }
    val scope = rememberCoroutineScope()

    fun navigate(target: Page) {
        error = null
        notice = null
        revoking = null
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
    fun resetCollegeList() {
        collegeQuery = ""
        collegeSearch = ""
        collegeCursors = listOf(0L)
    }

    // Each read runs only while its page is shown and starts again when the page, its paging or the revision changes.
    val listCache = remember { HashMap<String, Any>() }
    val open = !blocked
    val pendingSummary = if (open && page == Page.OVERVIEW)
        rememberRead(revision, denied = ::onFailure) { registrationCall { registration.pending(authorization(), 0) } } else null
    val overviewStats = if (open && page == Page.OVERVIEW)
        rememberRead(revision, denied = ::onFailure) { repository.overview(authorization()) } else null
    val requestRead = if (open && page == Page.REQUESTS)
        rememberRead(cursor, revision, denied = ::onFailure, cache = listCache, cacheKey = "requests-$cursor") {
            registrationCall { registration.pending(authorization(), cursor) }
        } else null
    val applicationRead = if (open && page == Page.REVIEW)
        rememberRead(applicationId, revision, denied = ::onFailure) { registrationCall { registration.detail(authorization(), applicationId) } } else null
    val collegesRead = if (open && (page == Page.COLLEGES || page == Page.SELECT_COLLEGE))
        rememberRead(page, collegeSearch, collegeCursors.last(), revision, denied = ::onFailure, cache = listCache,
            cacheKey = "colleges-${page.name}-$collegeSearch-${collegeCursors.last()}") {
            repository.colleges(authorization(), collegeSearch, collegeCursors.last())
        } else null
    val collegeRead = if (open && (page == Page.DETAILS || page == Page.INVITATIONS)) selected?.let { college ->
        rememberRead(page, college.id, revision, denied = ::onFailure) { repository.college(authorization(), college.id) }
    } else null
    val liveCollege = collegeRead?.value
    val historyRead = if (page == Page.INVITATIONS && liveCollege != null)
        rememberRead(liveCollege.id, invitationCursors.last(), revision, denied = ::onFailure, cache = listCache,
            cacheKey = "history-${liveCollege.id}-${invitationCursors.last()}") {
            repository.invitations(authorization(), liveCollege.id, invitationCursors.last())
        } else null

    BackHandler(page != Page.OVERVIEW && !busy) { back() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (page == Page.OVERVIEW && !blocked) {
            Overview(pendingSummary, overviewStats, error, notice, busy,
                openRequests = { cursor = 0; navigate(Page.REQUESTS) },
                openColleges = { resetCollegeList(); navigate(Page.COLLEGES) },
                openInvitations = { resetCollegeList(); navigate(Page.SELECT_COLLEGE) },
                onLogout = onLogout)
        } else {
            CampusPage(
                title = if (blocked) "Platform access" else when (page) {
                    Page.OVERVIEW -> "Platform administration"
                    Page.REQUESTS -> "College requests"
                    Page.REVIEW -> "Review request"
                    Page.COLLEGES -> "Colleges"
                    Page.SELECT_COLLEGE -> "Choose a college"
                    Page.DETAILS -> "College details"
                    Page.ADD -> "Add college"
                    Page.EDIT -> "Edit college"
                    Page.INVITATIONS -> "College Admin invitations"
                },
                onBack = { back() }.takeUnless { blocked },
                subtitle = if (blocked) null else when (page) {
                    Page.REQUESTS -> "Applications waiting for review"
                    Page.REVIEW -> applicationRead?.value?.let { "${it.name} · ${it.code}" }
                    Page.COLLEGES -> "Search and manage colleges"
                    Page.SELECT_COLLEGE -> "For College Admin invitations"
                    Page.DETAILS, Page.INVITATIONS -> (liveCollege ?: selected)?.name
                    Page.ADD -> "New colleges start inactive"
                    Page.EDIT -> selected?.name
                    Page.OVERVIEW -> null
                },
                stateKey = if (blocked) "blocked" else when (page) {
                    Page.REQUESTS -> "requests-$cursor"
                    Page.REVIEW -> "review-$applicationId"
                    Page.COLLEGES, Page.SELECT_COLLEGE -> "colleges-${page.name}-$collegeSearch-${collegeCursors.last()}"
                    Page.DETAILS -> "college-${selected?.id}"
                    Page.EDIT -> "edit-${selected?.id}"
                    Page.INVITATIONS -> "invitations-${selected?.id}-${invitationCursors.last()}"
                    else -> page.name
                },
                busy = busy,
                backEnabled = !busy,
                error = if (blocked) null else error,
                notice = if (blocked) null else notice,
                actions = {
                    if (!blocked) IconButton(onLogout, enabled = !busy) { Icon(CampusIcons.Logout, "Sign out") }
                },
            ) {
                if (blocked) {
                    item(key = "blocked") {
                        EmptyState("Platform access unavailable", error ?: "Refresh your access and try again.") {
                            PrimaryButton("Retry", {
                                blocked = false
                                navigate(Page.OVERVIEW)
                                revision++
                            }, icon = CampusIcons.Refresh)
                        }
                    }
                    item(key = "blocked-sign-out") {
                        SecondaryButton("Sign out", onLogout, enabled = !busy, icon = CampusIcons.Logout)
                    }
                } else when (page) {
                    Page.OVERVIEW -> Unit

                    Page.REQUESTS -> loaded("requests", requestRead) { result ->
                        item(key = "requests-count") {
                            SectionHeader(if (result.count == 1L) "1 application waiting" else "${result.count} applications waiting")
                        }
                        if (result.items.isEmpty()) item(key = "requests-empty") {
                            EmptyState("No pending requests",
                                if (cursor == 0L) "New college applications appear here." else "Nothing more on this page.")
                        }
                        items(result.items, key = { "request-${it.id}" }) { row ->
                            ListRow(row.name, subtitle = "${row.code} · ${row.fullName}",
                                meta = "Expires ${localTime(row.expiresAt) ?: row.expiresAt}", icon = CampusIcons.Building,
                                chip = { StatusChip(statusLabel(row.status), statusTone(row.status)) },
                                onClick = { applicationId = row.id; navigate(Page.REVIEW) })
                        }
                        pager("requests-pager", "First page",
                            onPrevious = if (cursor != 0L) ({ cursor = 0 }) else null,
                            onNext = if (result.items.size == 25) ({ cursor = result.items.last().id }) else null)
                    }

                    Page.REVIEW -> loaded("review", applicationRead) { row ->
                        if (error != null) item(key = "review-refresh") {
                            SecondaryButton("Refresh application", {
                                error = null
                                revision++
                            }, enabled = !busy, icon = CampusIcons.Refresh)
                        }
                        // The revision is part of the key, so refreshing starts the checklist again (as before).
                        item(key = "review-${row.id}-$revision") {
                            RegistrationReview(row, busy || error != null, approve = { evidence -> mutate { auth ->
                                registrationCall { registration.approve(auth, row.id, evidence) }
                                cursor = 0; navigate(Page.REQUESTS); notice = "Application approved."
                            } }, reject = { reason -> mutate { auth ->
                                registrationCall { registration.reject(auth, row.id, Rejection(reason)) }
                                cursor = 0; navigate(Page.REQUESTS); notice = "Application rejected."
                            } })
                        }
                    }

                    Page.COLLEGES, Page.SELECT_COLLEGE -> {
                        val selecting = page == Page.SELECT_COLLEGE
                        if (!selecting) item(key = "colleges-add") {
                            PrimaryButton("Add college", { navigate(Page.ADD) }, icon = CampusIcons.Plus)
                        }
                        item(key = "colleges-search") {
                            SearchField(collegeQuery, { collegeQuery = it.take(150) }, "Search by name or code", onSearch = {
                                collegeSearch = collegeQuery.trim()
                                collegeCursors = listOf(0L)
                            })
                        }
                        loaded("colleges", collegesRead) { colleges ->
                            if (colleges.isEmpty()) item(key = "colleges-empty") {
                                if (collegeSearch.isEmpty()) EmptyState("No colleges yet", "Colleges you add appear here.")
                                else EmptyState("No matches", "No colleges match your search. Try another name or code.")
                            }
                            items(colleges, key = { "college-${it.id}" }) { college ->
                                ListRow(college.name, subtitle = college.code, icon = CampusIcons.Building,
                                    chip = { CollegeChip(college.active) },
                                    onClick = {
                                        selected = college
                                        navigate(if (page == Page.SELECT_COLLEGE) Page.INVITATIONS else Page.DETAILS)
                                    })
                            }
                            pager("colleges-pager", "Previous page",
                                onPrevious = if (collegeCursors.size > 1) ({ collegeCursors = collegeCursors.dropLast(1) }) else null,
                                onNext = if (colleges.size == PlatformRepository.PAGE_SIZE) ({ collegeCursors = collegeCursors + colleges.last().id }) else null)
                        }
                    }

                    Page.DETAILS -> loaded("details", collegeRead) { live ->
                        item(key = "details-${live.id}") {
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
                        item(key = "college-form-${existing?.id ?: "new"}-$revision") {
                            CollegeForm(existing, busy) { input ->
                                mutate { auth ->
                                    selected = repository.save(auth, existing?.id, input)
                                    if (existing == null) resetCollegeList()
                                    navigate(if (existing == null) Page.COLLEGES else Page.DETAILS)
                                    notice = if (existing == null) "College added." else "College updated."
                                }
                            }
                        }
                    }

                    Page.INVITATIONS -> loaded("invite", collegeRead) { live ->
                        // The revision is part of the key, so the email field clears after an invitation is issued (as before).
                        item(key = "invite-form-${live.id}-$revision") {
                            InvitationForm(live.active, busy) { email ->
                                mutate { auth ->
                                    token = repository.invite(auth, live.id, email).token
                                    notice = "Invitation issued."
                                    revision++
                                }
                            }
                        }
                        item(key = "history-title") { SectionHeader("Invitation history") }
                        loaded("history", historyRead) { rows ->
                            if (rows.isEmpty()) item(key = "history-empty") {
                                EmptyState("No invitations",
                                    if (invitationCursors.size > 1) "Nothing more on this page." else "Invitations you issue appear here.")
                            }
                            items(rows, key = { "invitation-${it.id}" }) { invitation ->
                                InvitationRow(invitation, busy) { revoking = invitation }
                            }
                            pager("history-pager", "Previous page",
                                onPrevious = if (invitationCursors.size > 1) ({ invitationCursors = invitationCursors.dropLast(1) }) else null,
                                onNext = if (rows.size == PlatformRepository.PAGE_SIZE) ({ invitationCursors = invitationCursors + rows.last().id }) else null)
                        }
                    }
                }
            }
        }
    }

    val target = revoking
    val owner = liveCollege
    if (target != null && owner != null && page == Page.INVITATIONS && !blocked) {
        ConfirmDialog("Revoke this invitation?", "The recipient can no longer use the invitation code.", "Revoke",
            onConfirm = {
                revoking = null
                mutate { auth ->
                    repository.revoke(auth, owner.id, target.id)
                    notice = "Invitation revoked."
                    revision++
                }
            },
            onDismiss = { revoking = null }, danger = true, confirmEnabled = !busy)
    }

    token?.let { secret ->
        AlertDialog(
            onDismissRequest = { token = null },
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text("Invitation code") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Share this code privately with the verified recipient. It expires in 24 hours and can't be shown again.")
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                        Text(secret, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { token = null }) { Text("Done") } },
        )
    }
}

/** The Super Admin home: brand header, key numbers, shortcuts and sign out. */
@Composable
private fun Overview(pending: ReadState<RequestPage>?, stats: ReadState<PlatformOverview>?, error: String?, notice: String?,
                     busy: Boolean, openRequests: () -> Unit, openColleges: () -> Unit, openInvitations: () -> Unit,
                     onLogout: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "hello") {
            BrandHero(Modifier.appear(0)) {
                CampusBrandRow(logoSize = 30.dp, fontSize = 15.sp, onDark = true)
                Spacer(Modifier.height(8.dp))
                Text("Platform administration", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Review college requests and manage colleges.", style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f))
            }
        }
        if (error != null) item(key = "error") { MessageBanner(error, MessageKind.Error) }
        if (notice != null) item(key = "notice") { MessageBanner(notice, MessageKind.Success) }

        item(key = "numbers") {
            val requests = pending?.value
            val requestsFailed = pending?.error != null
            val figures = stats?.value
            val figuresFailed = stats?.error != null
            Column(Modifier.appear(1), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberTile("College requests", figure(requests != null, requestsFailed, requests?.count),
                        caption(requests != null, requestsFailed, requests?.count, "waiting for review"),
                        tone = if ((requests?.count ?: 0L) > 0L) Tone.Warning else Tone.Accent,
                        modifier = Modifier.weight(1f), onClick = openRequests)
                    NumberTile("Invitations", figure(figures != null, figuresFailed, figures?.pendingInvitations),
                        caption(figures != null, figuresFailed, figures?.pendingInvitations, "waiting to be accepted"),
                        tone = Tone.Accent, modifier = Modifier.weight(1f), onClick = openInvitations)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberTile("Active colleges", figure(figures != null, figuresFailed, figures?.activeColleges),
                        caption(figures != null, figuresFailed, figures?.activeColleges,
                            figures?.totalColleges?.let { "of $it colleges" } ?: "colleges with access"),
                        tone = Tone.Positive, modifier = Modifier.weight(1f), onClick = openColleges)
                    NumberTile("Inactive colleges", figure(figures != null, figuresFailed, figures?.inactiveColleges),
                        caption(figures != null, figuresFailed, figures?.inactiveColleges, "without access"),
                        tone = if ((figures?.inactiveColleges ?: 0L) > 0L) Tone.Danger else Tone.Accent,
                        modifier = Modifier.weight(1f), onClick = openColleges)
                }
            }
        }
        val pendingError = pending?.error
        if (pending != null && pendingError != null) item(key = "pending-error") {
            MessageBanner(pendingError, MessageKind.Error, actionLabel = "Retry", onAction = pending::retry)
        }
        val statsError = stats?.error
        if (stats != null && statsError != null) item(key = "stats-error") {
            MessageBanner(statsError, MessageKind.Error, actionLabel = "Retry", onAction = stats::retry)
        }
        if (stats?.value?.totalColleges == 0L) item(key = "no-colleges") {
            MessageBanner("No colleges have been added yet.", MessageKind.Info)
        }

        // Navigation stays available even when the numbers fail to load.
        item(key = "manage") {
            Column(Modifier.appear(2), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader("Manage")
                ShortcutGrid(listOf(
                    Shortcut("College requests", CampusIcons.Checklist, pending?.value?.count ?: 0L, openRequests),
                    Shortcut("Colleges", CampusIcons.Building, 0L, openColleges),
                    Shortcut("Admin invitations", CampusIcons.Users, 0L, openInvitations),
                ))
            }
        }

        item(key = "footer") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onLogout, enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(CampusIcons.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sign out")
                }
            }
        }
    }
}

private fun figure(loaded: Boolean, failed: Boolean, value: Long?): String = when {
    loaded -> value?.toString() ?: "–"
    failed -> "–"
    else -> "…"
}

private fun caption(loaded: Boolean, failed: Boolean, value: Long?, text: String): String = when {
    loaded && value != null -> text
    loaded || failed -> "Not available"
    else -> "Loading"
}

/** One read for a page: the value once loaded, or the error to show with a retry. */
private class ReadState<T : Any> {
    var value by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var attempt by mutableIntStateOf(0)
        private set

    fun retry() {
        attempt++
    }
}

/**
 * The same loading rules as before: a new read for each set of [keys], 401/403/503 go to [denied] (which blocks the
 * screen), any other failure is shown with a retry.
 */
@Composable
private fun <T : Any> rememberRead(vararg keys: Any?, denied: (Exception) -> Unit, cache: MutableMap<String, Any>? = null,
                                   cacheKey: String = "", load: suspend () -> T): ReadState<T> {
    // With a [cache], a list shows its last rows while it reloads, so coming Back keeps its scroll position.
    val read = remember(*keys) {
        @Suppress("UNCHECKED_CAST") val seed = cache?.get(cacheKey) as T?
        ReadState<T>().apply { value = seed }
    }
    LaunchedEffect(read, read.attempt) {
        read.error = null
        try {
            val fresh = load()
            read.value = fresh
            cache?.put(cacheKey, fresh)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            read.value = null
            val platformFailure = failure as? PlatformFailure
            if (platformFailure?.status in setOf(401, 403, 503)) {
                denied(failure)
            } else {
                read.error = platformFailure?.message ?: "Could not load CampusSphere. Try again."
            }
        }
    }
    return read
}

/** Shows a read in a page: a progress line while loading, the error with a retry, or the [content]. */
private fun <T : Any> LazyListScope.loaded(key: String, read: ReadState<T>?, content: LazyListScope.(T) -> Unit) {
    if (read == null) return
    val failure = read.error
    val value = read.value
    when {
        failure != null -> item(key = "$key-error") {
            MessageBanner(failure, MessageKind.Error, actionLabel = "Retry", onAction = read::retry)
        }
        value == null -> item(key = "$key-loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else -> content(value)
    }
}

private fun LazyListScope.pager(key: String, previousLabel: String, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?) {
    if (onPrevious == null && onNext == null) return
    item(key = key) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onPrevious != null) SecondaryButton(previousLabel, onPrevious, Modifier.weight(1f), icon = CampusIcons.Back)
            if (onNext != null) SecondaryButton("Next page", onNext, Modifier.weight(1f), icon = CampusIcons.ChevronRight)
        }
    }
}

@Composable
private fun CollegeChip(active: Boolean) {
    if (active) StatusChip("Active", Tone.Positive) else StatusChip("Inactive", Tone.Danger)
}

private fun statusLabel(status: String) = status.lowercase().replaceFirstChar { it.uppercase() }

private fun statusTone(status: String) = when (status) {
    "PENDING" -> Tone.Warning
    "ACTIVE", "APPROVED", "ACCEPTED", "USED", "REDEEMED" -> Tone.Positive
    "INACTIVE", "REJECTED", "REVOKED" -> Tone.Danger
    else -> Tone.Neutral
}

private fun localTime(raw: String): String? = runCatching {
    java.time.OffsetDateTime.parse(raw).atZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM))
}.getOrNull()

@Composable
private fun CollegeDetails(college: College, busy: Boolean, onEdit: () -> Unit, onInvite: () -> Unit,
                           onStatus: (Boolean) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(college.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                CollegeChip(college.active)
            }
            InfoRow("College code", college.code)
        }
        PrimaryButton("Edit college", onEdit, enabled = !busy, icon = CampusIcons.Edit)
        SecondaryButton("College Admin invitations", onInvite, enabled = !busy, icon = CampusIcons.Users)
        SecondaryButton(if (college.active) "Deactivate college" else "Activate college",
            { if (college.active) confirm = true else onStatus(true) }, enabled = !busy,
            icon = if (college.active) CampusIcons.Close else CampusIcons.Check, danger = college.active)
    }
    if (confirm) ConfirmDialog("Deactivate this college?", "College users lose access and pending invitations are revoked.",
        "Deactivate", onConfirm = { confirm = false; onStatus(false) }, onDismiss = { confirm = false }, danger = true)
}

@Composable
private fun CollegeForm(college: College?, busy: Boolean, onSave: (CollegeInput) -> Unit) {
    var name by rememberSaveable { mutableStateOf(college?.name.orEmpty()) }
    var code by rememberSaveable { mutableStateOf(college?.code.orEmpty()) }
    val validCode = code.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,29}"))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoCard {
            OutlinedTextField(name, { name = it.take(150) }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("College name") }, singleLine = true)
            OutlinedTextField(code, { code = it.take(30) }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("College code") }, singleLine = true, isError = code.isNotEmpty() && !validCode,
                supportingText = { Text("Letters, digits, _ or -, starting with a letter or digit.") })
        }
        PrimaryButton(if (busy) "Saving…" else "Save college", { onSave(CollegeInput(name.trim(), code)) },
            enabled = !busy && name.isNotBlank() && validCode, icon = CampusIcons.Check)
    }
}

@Composable
private fun InvitationForm(active: Boolean, busy: Boolean, onInvite: (String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    if (!active) {
        MessageBanner("Activate this college before issuing invitations.", MessageKind.Info)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard {
                OutlinedTextField(email, { email = it.take(150) }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("Verified institutional email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            }
            PrimaryButton(if (busy) "Sending…" else "Issue invitation", { onInvite(email) },
                enabled = !busy && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches(), icon = CampusIcons.Plus)
        }
    }
}

@Composable
private fun InvitationRow(invitation: CollegeInvitation, busy: Boolean, onRevoke: () -> Unit) {
    val expiration = localTime(invitation.expiresAt)
    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(invitation.email, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (expiration != null) Text("Expires $expiration", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(statusLabel(invitation.status), statusTone(invitation.status))
        }
        if (invitation.status == "PENDING") {
            TextButton(onRevoke, enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(CampusIcons.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Revoke invitation")
            }
        }
    }
}
