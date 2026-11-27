package com.campussphere.app.presentation.college

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton({ if (person != null) vm.edit(null) else exit() }, enabled = !ui.busy) { Text("Back") }
            Text(if (person == null) "Accounts" else person.name, style = MaterialTheme.typography.headlineMedium)
            if (person == null) Text("Faculty and student accounts of your college, including deactivated ones.",
                style = MaterialTheme.typography.bodyMedium)
            if (ui.loading || ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            ui.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
        }
        if (person != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${person.email}${person.department?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodyMedium)
                Text(if (person.active) "Active: can sign in." else "Deactivated: cannot sign in. Their history is kept.",
                    color = if (person.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                OutlinedTextField(ui.name, vm::changeName, label = { Text("Full name") }, singleLine = true,
                    enabled = !ui.busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ui.phone, vm::changePhone, label = { Text("Phone (10 digits, optional)") }, singleLine = true,
                    enabled = !ui.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    supportingText = { Text("The email cannot be changed here because it is used to sign in.") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(vm::save, enabled = !ui.busy) { Text("Save") }
                    TextButton({ vm.edit(null) }, enabled = !ui.busy) { Text("Cancel") }
                }
                HorizontalDivider()
                if (person.active) OutlinedButton({ confirm = false }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Deactivate account")
                } else Button({ confirm = true }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text("Reactivate account") }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(ui.kind == "faculty", { vm.setKind("faculty") }, { Text("Faculty") })
                        FilterChip(ui.kind == "students", { vm.setKind("students") }, { Text("Students") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(ui.active == null, { vm.setActiveFilter(null) }, { Text("All") })
                        FilterChip(ui.active == true, { vm.setActiveFilter(true) }, { Text("Active") })
                        FilterChip(ui.active == false, { vm.setActiveFilter(false) }, { Text("Deactivated") })
                    }
                    OutlinedTextField(ui.query, vm::setQuery, label = { Text("Search by name or email") }, singleLine = true,
                        trailingIcon = { TextButton(vm::load, enabled = !ui.loading) { Text("Search") } },
                        modifier = Modifier.fillMaxWidth())
                }
            }
            if (ui.loaded && ui.people.isEmpty() && ui.error == null) item {
                Text(when {
                    ui.query.isNotBlank() -> "Nobody matches your search."
                    ui.active == false -> "No deactivated accounts."
                    ui.kind == "faculty" -> "No faculty accounts yet. Faculty register in the app with your college code, then you approve them under Faculty."
                    else -> "No student accounts yet. Students register in the app and request to join your college; approve them under Students."
                })
            }
            items(ui.people, key = { it.id }) { p ->
                OutlinedCard(onClick = { vm.edit(p) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(p.email, style = MaterialTheme.typography.bodySmall)
                            Text(listOfNotNull(p.department, p.phone).joinToString(" · ").ifEmpty { "No department yet" },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(color = if (p.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small) {
                            Text(if (p.active) "Active" else "Deactivated", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    confirm?.let { activate ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (activate) "Reactivate this account?" else "Deactivate this account?") },
            text = { Text(if (activate) "They can sign in again with their existing password."
                          else "They are signed out and cannot sign in. Their attendance, materials, assignments and enrollments are kept. You can reactivate them later.") },
            confirmButton = { TextButton({ confirm = null; vm.setActive(activate) }) { Text(if (activate) "Reactivate" else "Deactivate") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}
