package com.campussphere.app.presentation.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.FacultyNotice
import com.campussphere.app.data.repository.FacultyNotificationRepository
import com.campussphere.app.data.repository.TimetableFailure
import com.campussphere.app.presentation.materials.materialTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Short label for a notice kind. */
fun noticeKind(kind: String) = when (kind) {
    "ASSIGNED" -> "New class"
    "CHANGED" -> "Changed"
    "REASSIGNED" -> "Reassigned"
    "CANCELLED" -> "Removed"
    else -> kind
}

/** Unread notices not yet shown in the phone's notification bar, oldest first, at most [limit]. */
fun noticesToPost(notices: List<FacultyNotice>, lastPostedId: Long, limit: Int = 5): List<FacultyNotice> =
    notices.filter { it.readAt == null && it.id > lastPostedId }.sortedBy { it.id }.takeLast(limit)

// ---- Inbox ----

data class NoticesUi(
    val notices: List<FacultyNotice> = emptyList(),
    val unreadOnly: Boolean = false,
    val expanded: Long? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val unread get() = notices.count { it.readAt == null }
}

class FacultyNoticesViewModel(
    private val repo: FacultyNotificationRepository,
    private val auth: () -> String,
    private val lost: (Int) -> Unit,
) : ViewModel() {
    private val state = MutableStateFlow(NoticesUi())
    val ui: StateFlow<NoticesUi> = state.asStateFlow()

    fun load() = run(loading = true) {
        val notices = repo.list(auth(), state.value.unreadOnly)
        state.update { it.copy(notices = notices, loaded = true) }
    }

    fun setUnreadOnly(value: Boolean) {
        state.update { it.copy(unreadOnly = value) }
        load()
    }

    /** Opening a notice shows its details and marks it as read. */
    fun open(notice: FacultyNotice) {
        state.update { it.copy(expanded = if (it.expanded == notice.id) null else notice.id) }
        if (notice.readAt != null) return
        run {
            repo.markRead(auth(), notice.id)
            state.update { s -> s.copy(notices = s.notices.map { if (it.id == notice.id) it.copy(readAt = "read") else it }) }
        }
    }

    fun markAllRead() = run {
        repo.markAllRead(auth())
        state.update { s -> s.copy(notices = s.notices.map { if (it.readAt == null) it.copy(readAt = "read") else it }) }
    }

    private fun run(loading: Boolean = false, block: suspend () -> Unit) {
        if (state.value.loading) return
        state.update { it.copy(loading = loading, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? TimetableFailure
                if (failure != null && (failure.status == 401 || failure.status == 403)) lost(failure.status)
                state.update { it.copy(error = failure?.message ?: "Request failed. Check your connection and try again.") }
            } finally {
                state.update { it.copy(loading = false) }
            }
        }
    }
}

@Composable
fun FacultyNotificationsScreen(repo: FacultyNotificationRepository, auth: () -> String, lost: (Int) -> Unit,
                               exit: () -> Unit, openTimetable: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<FacultyNoticesViewModel>(viewModelStoreOwner = owner) { FacultyNoticesViewModel(repo, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.load() }
    BackHandler(onBack = exit)

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(exit) { Text("Back") }
            Text("Notifications", style = MaterialTheme.typography.headlineMedium)
            Text("Your class assignments and timetable changes.", style = MaterialTheme.typography.bodyMedium)
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(!ui.unreadOnly, { vm.setUnreadOnly(false) }, { Text("All") })
                FilterChip(ui.unreadOnly, { vm.setUnreadOnly(true) }, { Text("Unread") })
                Spacer(Modifier.weight(1f))
                if (ui.unread > 0) TextButton(vm::markAllRead, enabled = !ui.loading) { Text("Mark all read") }
            }
            OutlinedButton(openTimetable, modifier = Modifier.fillMaxWidth()) { Text("Open my timetable") }
        }
        if (ui.loaded && ui.notices.isEmpty() && ui.error == null) item {
            Text(if (ui.unreadOnly) "No unread notifications." else "No notifications yet. You will be told when a timetable assigns or changes your classes.")
        }
        items(ui.notices, key = { it.id }) { n ->
            val unread = n.readAt == null
            OutlinedCard(onClick = { vm.open(n) }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.outlinedCardColors(containerColor = if (unread) MaterialTheme.colorScheme.secondaryContainer
                                                                         else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                            Text(noticeKind(n.kind), Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                        if (unread) Text("Unread", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(n.title, style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.Normal)
                    Text(n.body, style = MaterialTheme.typography.bodyMedium, maxLines = if (ui.expanded == n.id) 20 else 2)
                    if (ui.expanded == n.id) {
                        Text("${n.program} · ${n.semester} · Division ${n.division} · ${n.academicYear}", style = MaterialTheme.typography.bodySmall)
                        Text("Starts ${n.startsOn}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(materialTime(n.createdAt), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---- Phone notification bar ----

/**
 * Shows new timetable notices in the phone's notification bar while the app is running or in the background.
 * The lock screen only shows "You have a timetable update"; details appear after unlocking.
 */
object TimetableNotifier {
    private const val CHANNEL = "timetable_updates"
    private const val PREFS = "campussphere_notifications"
    private const val LAST = "last_posted_id"

    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL, "Timetable updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "New classes and changes to your teaching timetable"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Posts unread notices that were not shown before; each notice id is shown at most once on this phone. */
    fun post(context: Context, notices: List<FacultyNotice>) {
        if (!allowed(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fresh = noticesToPost(notices, prefs.getLong(LAST, 0))
        if (fresh.isEmpty()) return
        channel(context)
        val open = PendingIntent.getActivity(context, 0,
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val hidden = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("CampusSphere").setContentText("You have a timetable update").build()
        val manager = NotificationManagerCompat.from(context)
        for (notice in fresh) {
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(notice.title)
                .setContentText(notice.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(hidden)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                manager.notify("timetable", notice.id.toInt(), notification)
            } catch (e: SecurityException) {
                return
            }
        }
        prefs.edit().putLong(LAST, fresh.maxOf { it.id }).apply()
    }
}

/**
 * For the faculty dashboard: asks once for notification permission (Android 13+), then checks for new notices
 * every minute while the app is running and on every [refreshKey] change, updating the unread count and the
 * notification bar. Delivery while the app is fully closed needs push messaging (FCM), which is not set up.
 */
@Composable
fun FacultyNotificationEffects(repo: FacultyNotificationRepository, auth: () -> String, refreshKey: Int,
                               onCount: (Int) -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !TimetableNotifier.allowed(context))
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(refreshKey) {
        while (true) {
            try {
                val unread = repo.list(auth(), unreadOnly = true)
                onCount(repo.unread(auth()).toInt())
                TimetableNotifier.post(context.applicationContext, unread)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or signed out: the next check tries again; the screen shows its own errors.
            }
            delay(60_000)
        }
    }
}
