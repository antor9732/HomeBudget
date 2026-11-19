package com.homebudget.presentation.sync

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.core.sync.SyncEngine
import com.homebudget.core.sync.SyncOutcome
import com.homebudget.domain.model.SyncHealth
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.SyncRepository
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.common.LabeledRow
import com.homebudget.presentation.common.SectionCard
import com.homebudget.presentation.dashboard.formatTimestamp
import com.homebudget.presentation.household.AccountViewModel
import com.homebudget.presentation.household.ConsentLauncher
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
class SyncCenterViewModel @Inject constructor(
    private val households: HouseholdRepository,
    private val sync: SyncRepository,
    private val engine: SyncEngine,
) : BaseViewModel() {
    val state = households.context.filterNotNull().flatMapLatest { ctx ->
        combine(sync.observeStatus(ctx.householdId), sync.observeConflicts(ctx.householdId)) { s, c -> Triple(ctx, s, c) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncNow() = launchSafely {
        val hh = households.currentContext()?.householdId ?: return@launchSafely
        message(when (val r = engine.sync(hh)) {
            is SyncOutcome.Success -> "Sync complete: ${r.report.uploaded} sent, ${r.report.applied} received" +
                (if (r.report.conflicts > 0) ", ${r.report.conflicts} conflicts" else "") +
                (if (r.report.rejected > 0) ", ${r.report.rejected} rejected" else "")
            SyncOutcome.LocalOnly -> "This household is not connected to Google Drive"
            is SyncOutcome.AuthRequired -> "Tap 'Authorize Google Drive'"
            is SyncOutcome.AccessRevoked -> "Access revoked: ${r.message}"
            is SyncOutcome.Failed -> "Sync failed (${r.message}). Pending changes are kept."
        })
    }

    fun keep(id: String) = launchSafely { sync.keepCurrent(id) }
    fun useOther(id: String) = launchSafely { sync.applyOtherVersion(id); message("Other version applied; it will sync to everyone") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncCenterScreen(onBack: () -> Unit, vm: SyncCenterViewModel = hiltViewModel(), account: AccountViewModel = hiltViewModel()) {
    val activity = LocalContext.current as Activity
    ConsentLauncher(account)
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    CollectMessages(account, host)
    Scaffold(
        topBar = { TopAppBar(title = { Text("Sync center") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        val (ctx, s, conflicts) = state ?: return@Scaffold
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                SectionCard(title = "Status") {
                    LabeledRow("State") { Text(when (s.health) {
                        SyncHealth.LOCAL_ONLY -> "Only on this phone"
                        SyncHealth.IDLE -> "Connected"
                        SyncHealth.SYNCING -> "Syncing"
                        SyncHealth.ERROR -> "Problem"
                        SyncHealth.AUTH_REQUIRED -> "Authorization needed"
                        SyncHealth.ACCESS_REVOKED -> "Access revoked"
                    }) }
                    LabeledRow("Last successful sync") { Text(formatTimestamp(s.lastSuccessfulSyncAt)) }
                    LabeledRow("Pending changes") { Text(s.pendingCount.toString()) }
                    LabeledRow("Open conflicts") { Text(s.openConflicts.toString()) }
                    s.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (s.health != SyncHealth.LOCAL_ONLY) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::syncNow, enabled = !busy) { Text("Sync now") }
                        if (s.health == SyncHealth.AUTH_REQUIRED || s.health == SyncHealth.ERROR)
                            OutlinedButton(onClick = { account.authorizeAndSync(activity) }) { Text("Authorize Google Drive") }
                    }
                }
            }
            item {
                Text("Every change is saved on this phone first and added to the shared household file in Google Drive as an encrypted change. Changes from other phones are merged without overwriting each other.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (conflicts.isNotEmpty()) item { Text("Conflicts", style = MaterialTheme.typography.titleMedium) }
            items(conflicts, key = { it.id }) { c ->
                SectionCard(title = c.entityType.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + if (c.open) "" else " (resolved)") {
                    Text("Edited on two phones at the same time. All phones now show:", style = MaterialTheme.typography.bodySmall)
                    Text(c.currentSummary, style = MaterialTheme.typography.bodyLarge)
                    Text("Other version:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Text(c.otherSummary, style = MaterialTheme.typography.bodyLarge)
                    Text("Detected ${formatTimestamp(c.detectedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.open && !ctx.readOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.keep(c.id) }) { Text("Keep shown version") }
                        TextButton(onClick = { vm.useOther(c.id) }) { Text("Use other version") }
                    }
                }
            }
        }
    }
}
