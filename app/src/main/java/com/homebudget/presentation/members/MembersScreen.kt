package com.homebudget.presentation.members

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.core.sync.HouseholdCloudService
import com.homebudget.domain.model.Member
import com.homebudget.domain.model.MemberStatus
import com.homebudget.domain.model.Role
import com.homebudget.domain.model.SyncHealth
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.SyncRepository
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.common.Pill
import com.homebudget.presentation.common.SectionCard
import com.homebudget.presentation.transactions.CollectMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MembersViewModel @Inject constructor(
    households: HouseholdRepository,
    sync: SyncRepository,
    private val cloud: HouseholdCloudService,
) : BaseViewModel() {
    val state = households.context.filterNotNull().flatMapLatest { ctx ->
        combine(households.observeMembers(ctx.householdId), sync.observeStatus(ctx.householdId)) { m, s -> Triple(ctx, m, s) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun invite(email: String, role: Role) = launchSafely { cloud.invite(email, role); message("Invitation sent to $email") }
    fun changeRole(m: Member, role: Role) = launchSafely { cloud.changeRole(m.id, role); message("${m.displayName} is now ${role.name.lowercase()}") }
    fun remove(m: Member) = launchSafely { cloud.remove(m.id); message("${m.displayName} removed and Drive access revoked") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(onBack: () -> Unit, onConnectDrive: () -> Unit, vm: MembersViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    var inviting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Member?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Members") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (state?.first?.canManageMembers == true && state?.third?.health != SyncHealth.LOCAL_ONLY) IconButton(onClick = { inviting = true }) { Icon(Icons.Filled.PersonAdd, "Invite member") } })
        },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        val (ctx, members, sync) = state ?: return@Scaffold
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (sync.health == SyncHealth.LOCAL_ONLY) item {
                SectionCard(title = "Share with family") {
                    Text("This household is only on this phone. Connect Google Drive to invite members by Google account email.")
                    if (ctx.role == Role.OWNER) TextButton(onClick = onConnectDrive) { Text("Connect Google Drive") }
                }
            }
            items(members, key = { it.id }) { m -> MemberRow(m, isMe = m.id == ctx.member.id, canManage = ctx.canManageMembers && m.role != Role.OWNER && m.id != ctx.member.id,
                onRole = { vm.changeRole(m, it) }, onRemove = { removing = m }) }
            item {
                Text("Removing a member revokes their access to the household file and app membership. Data already downloaded to a phone that stays offline cannot be erased remotely.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
    if (inviting) InviteDialog(onDismiss = { inviting = false }) { email, role -> inviting = false; vm.invite(email, role) }
    removing?.let { m ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove ${m.displayName}?") },
            text = { Text("They will lose access to the shared household file and can no longer sync.") },
            confirmButton = { TextButton(onClick = { removing = null; vm.remove(m) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun MemberRow(m: Member, isMe: Boolean, canManage: Boolean, onRole: (Role) -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.displayName + if (isMe) " (you)" else "", style = MaterialTheme.typography.titleSmall)
                Text(m.email ?: "Not linked to a Google account", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(m.role.name.lowercase().replaceFirstChar { it.uppercase() }, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    val (bg, fg) = when (m.status) {
                        MemberStatus.ACTIVE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                        MemberStatus.INVITED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                        MemberStatus.REMOVED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                    }
                    Pill(m.status.name.lowercase().replaceFirstChar { it.uppercase() }, bg, fg)
                }
            }
            if (canManage && m.status != MemberStatus.REMOVED) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Manage ${m.displayName}") }
                DropdownMenu(menu, { menu = false }) {
                    listOf(Role.ADMIN, Role.MEMBER, Role.VIEWER).filter { it != m.role }.forEach { r ->
                        DropdownMenuItem(text = { Text("Make ${r.name.lowercase()}") }, onClick = { menu = false; onRole(r) })
                    }
                    DropdownMenuItem(text = { Text("Remove from household") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun InviteDialog(onDismiss: () -> Unit, onInvite: (String, Role) -> Unit) {
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(Role.MEMBER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite member") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(email, { email = it.trim() }, label = { Text("Google account email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Role.ADMIN, Role.MEMBER, Role.VIEWER).forEach { r -> FilterChip(role == r, { role = r }, { Text(r.name.lowercase()) }) }
                }
                Text(when (role) {
                    Role.ADMIN -> "Manage transactions, categories and budgets"
                    Role.MEMBER -> "Add transactions and edit their own; view everything"
                    Role.VIEWER -> "Read-only dashboard, reports and history"
                    Role.OWNER -> ""
                }, style = MaterialTheme.typography.bodySmall)
                Text("Google will email them a Drive share. Tell them the recovery passphrase in person.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onInvite(email, role) }, enabled = email.contains("@")) { Text("Invite") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
