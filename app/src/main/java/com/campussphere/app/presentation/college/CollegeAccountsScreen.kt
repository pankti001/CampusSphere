package com.campussphere.app.presentation.college

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campussphere.app.data.remote.api.CollegePerson
import com.campussphere.app.data.repository.CollegeAdminFailure
import com.campussphere.app.data.repository.CollegeAdminRepository
import com.campussphere.app.ui.brand.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Problem with an edited name or phone, or null when it can be saved. */
fun personDetailsProblem(name: String, phone: String): String? = when {
    name.isBlank() -> "Enter the person's name."
    name.trim().length > 100 -> "The name can be at most 100 characters."
    phone.isNotBlank() && !Regex("^[0-9]{10}$").matches(phone.trim()) -> "Enter a 10-digit phone number, or leave it empty."
    else -> null
}

data class AccountsUi(
    val kind: String = "faculty",
    val active: Boolean? = null,
    val query: String = "",
    val people: List<CollegePerson> = emptyList(),
    val editing: CollegePerson? = null,
    val name: String = "",
    val phone: String = "",
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class CollegeAccountsViewModel(private val repo: CollegeAdminRepository, private val auth: () -> String,
                               private val lost: (Int) -> Unit) : ViewModel() {
    private val state = MutableStateFlow(AccountsUi())
    val ui = state.asStateFlow()

    fun load() = run(loading = true) {
        val s = state.value
        val people = repo.people(auth(), s.kind, s.active, s.query)
        state.update { it.copy(people = people, loaded = true) }
    }

    fun setKind(kind: String) { state.update { it.copy(kind = kind, editing = null, notice = null) }; load() }
    fun setActiveFilter(active: Boolean?) { state.update { it.copy(active = active) }; load() }
    fun setQuery(q: String) {
        state.update { it.copy(query = q.take(100)) }
        if (q.isBlank()) load()
    }

    fun edit(person: CollegePerson?) = state.update {
        it.copy(editing = person, name = person?.name.orEmpty(), phone = person?.phone.orEmpty(), error = null, notice = null)
    }
    fun changeName(v: String) = state.update { it.copy(name = v.take(100), error = null) }
    fun changePhone(v: String) = state.update { it.copy(phone = v.filter(Char::isDigit).take(10), error = null) }

    fun save() {
        val s = state.value
        val person = s.editing ?: return
        personDetailsProblem(s.name, s.phone)?.let { p -> state.update { it.copy(error = p) }; return }
        run {
            val saved = repo.update(auth(), s.kind, person.id, s.name, s.phone)
            replace(saved, "Saved ${saved.name}.")
        }
    }

    fun setActive(active: Boolean) {
        val s = state.value
        val person = s.editing ?: return
        run {
            val saved = repo.setActive(auth(), s.kind, person.id, active)
            replace(saved, if (active) "${saved.name} can sign in again." else "${saved.name} is deactivated and can no longer sign in.")
        }
    }

    private fun replace(saved: CollegePerson, notice: String) = state.update { s ->
        val visible = s.active == null || s.active == saved.active
        s.copy(people = if (visible) s.people.map { if (it.id == saved.id) saved else it } else s.people.filterNot { it.id == saved.id },
            editing = null, notice = notice)
    }

    private fun run(loading: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy || state.value.loading) return
        state.update { it.copy(busy = !loading, loading = loading, error = null, notice = if (loading) it.notice else null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? CollegeAdminFailure
                if (failure != null && (failure.status == 401 || failure.status == 403) && failure.code != "ACCESS_DENIED") lost(failure.status)
                state.update { it.copy(error = failure?.message ?: "Request failed. Check your connection and try again.") }
            } finally {
                state.update { it.copy(busy = false, loading = false) }
            }
        }
    }
}

private val ACCOUNT_KINDS = listOf("faculty", "students")
private val STATUS_FILTERS = listOf("active" to "Active", "deactivated" to "Deactivated")

@Composable
fun CollegeAccountsScreen(repo: CollegeAdminRepository, auth: () -> String, lost: (Int) -> Unit, exit: () -> Unit) {
    val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm = viewModel<CollegeAccountsViewModel>(viewModelStoreOwner = owner) { CollegeAccountsViewModel(repo, auth, lost) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(vm) { if (!vm.ui.value.loaded) vm.load() }
    BackHandler { if (ui.editing != null) vm.edit(null) else exit() }
    val person = ui.editing

    CampusPage(
        title = person?.name ?: "Accounts",
        subtitle = if (person == null) "Faculty and students, including deactivated accounts" else person.email,
        onBack = { if (person != null) vm.edit(null) else exit() },
        backEnabled = !ui.busy,
        busy = ui.loading || ui.busy,
        // The list (per tab and status filter) and each person keep their own scroll position.
        stateKey = if (person != null) "edit-${person.id}" else "list-${ui.kind}-${ui.active}",
        error = ui.error,
        notice = ui.notice,
    ) {
        if (person != null) {
            item(key = "status") {
                InfoCard {
                    StatusChip(if (person.active) "Active" else "Deactivated", if (person.active) Tone.Positive else Tone.Danger)
                    InfoRow("Email", person.email)
                    person.department?.let { InfoRow("Department", it) }
                    Text(if (person.active) "Can sign in." else "Can't sign in. Their history is kept.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "form") {
                InfoCard {
                    OutlinedTextField(ui.name, vm::changeName, label = { Text("Full name") }, singleLine = true,
                        enabled = !ui.busy, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ui.phone, vm::changePhone, label = { Text("Phone (10 digits, optional)") }, singleLine = true,
                        enabled = !ui.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        supportingText = { Text("The email can't be changed here because it's used to sign in.") },
                        shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Cancel", { vm.edit(null) }, Modifier.weight(1f), enabled = !ui.busy)
                        PrimaryButton("Save", vm::save, Modifier.weight(1f), enabled = !ui.busy, icon = CampusIcons.Check)
                    }
                }
            }
            item(key = "access") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Sign-in access")
                    if (person.active) SecondaryButton("Deactivate account", { confirm = false }, enabled = !ui.busy,
                        icon = CampusIcons.Hide, danger = true)
                    else SecondaryButton("Reactivate account", { confirm = true }, enabled = !ui.busy, icon = CampusIcons.Refresh)
                }
            }
        } else {
            item(key = "kind") {
                CampusTabs(listOf("Faculty", "Students"), ACCOUNT_KINDS.indexOf(ui.kind).coerceAtLeast(0)) {
                    vm.setKind(ACCOUNT_KINDS[it])
                }
            }
            item(key = "filters") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchField(ui.query, vm::setQuery, "Search by name or email", onSearch = vm::load)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChoiceChip("Status", STATUS_FILTERS, when (ui.active) {
                            true -> "active"
                            false -> "deactivated"
                            null -> null
                        }) { vm.setActiveFilter(it?.let { key -> key == "active" }) }
                        Spacer(Modifier.weight(1f))
                        if (ui.query.isNotBlank()) TextButton(vm::load, enabled = !ui.loading) { Text("Search") }
                    }
                }
            }
            if (ui.loaded && ui.people.isEmpty() && ui.error == null) item(key = "empty") {
                when {
                    ui.query.isNotBlank() -> EmptyState("No matches", "Nobody matches your search.")
                    ui.active == false -> EmptyState("No deactivated accounts", "Everyone here can sign in.")
                    ui.kind == "faculty" -> EmptyState("No faculty accounts yet",
                        "Faculty register in the app with your college code, then you approve them under Faculty.")
                    else -> EmptyState("No student accounts yet",
                        "Students register in the app and ask to join your college. Approve them under Students.")
                }
            }
            items(ui.people, key = { "person-${it.id}" }) { p ->
                ListRow(p.name, subtitle = p.email,
                    meta = listOfNotNull(p.department, p.phone).joinToString(" · ").ifEmpty { "No department yet" },
                    icon = CampusIcons.Users,
                    chip = { StatusChip(if (p.active) "Active" else "Deactivated", if (p.active) Tone.Positive else Tone.Danger) },
                    // Rows can't be opened while a change is saving, as before.
                    onClick = { if (!ui.busy) vm.edit(p) })
            }
        }
    }

    confirm?.let { activate ->
        ConfirmDialog(
            title = if (activate) "Reactivate this account?" else "Deactivate this account?",
            body = if (activate) "They can sign in again with their existing password."
                   else "They're signed out and can't sign in. Their attendance, materials, assignments and enrollments are kept. You can reactivate them later.",
            confirm = if (activate) "Reactivate" else "Deactivate",
            onConfirm = { confirm = null; vm.setActive(activate) },
            onDismiss = { confirm = null },
            danger = !activate,
        )
    }
}
