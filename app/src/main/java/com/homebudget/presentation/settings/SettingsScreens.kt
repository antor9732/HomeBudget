package com.homebudget.presentation.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.homebudget.BuildConfig
import com.homebudget.core.common.AppPreferences
import com.homebudget.core.common.NotificationSettings
import com.homebudget.core.common.ThemeMode
import com.homebudget.core.notifications.WorkScheduler
import com.homebudget.core.sync.BackupService
import com.homebudget.core.sync.BackupVerification
import com.homebudget.core.sync.HouseholdCloudService
import com.homebudget.domain.model.Role
import com.homebudget.domain.model.SyncHealth
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.SyncRepository
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.presentation.common.LabeledRow
import com.homebudget.presentation.common.SectionCard
import com.homebudget.presentation.common.SettingsRow
import com.homebudget.presentation.household.AccountViewModel
import com.homebudget.presentation.transactions.CollectMessages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val households: HouseholdRepository,
    sync: SyncRepository,
    private val cloud: HouseholdCloudService,
    private val scheduler: WorkScheduler,
) : BaseViewModel() {
    val theme = prefs.theme.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val notifications = prefs.notificationSettings.stateIn(viewModelScope, SharingStarted.Eagerly, NotificationSettings())
    val state = households.context.filterNotNull().flatMapLatest { ctx ->
        combine(sync.observeStatus(ctx.householdId), prefs.session) { s, session -> Triple(ctx, s, session) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTheme(m: ThemeMode) = viewModelScope.launch { prefs.setTheme(m) }

    fun setNotifications(s: NotificationSettings) = viewModelScope.launch {
        prefs.setNotificationSettings(s)
        if (!s.enabled || !s.dailyOverBudgetReminder) scheduler.cancelDailyReminder()
        scheduler.requestBudgetCheck() // re-evaluate (reschedules daily reminder at the new time if still over budget)
    }

    fun rename(name: String) = launchSafely { households.renameHousehold(name); message("Household renamed") }
    fun leave() = launchSafely { cloud.leaveOnThisDevice() }
}

@Composable
fun SettingsScreen(
    onMembers: () -> Unit, onNotifications: () -> Unit, onSync: () -> Unit, onBackup: () -> Unit,
    onPrivacy: () -> Unit, onBudget: () -> Unit, onConnectDrive: () -> Unit, onCategories: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(), account: AccountViewModel = hiltViewModel(),
) {
    val activity = LocalContext.current as android.app.Activity
    val state by vm.state.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host); CollectMessages(account, host)
    var renaming by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val (ctx, sync, session) = state ?: return
    Column(Modifier.fillMaxSize()) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp, 8.dp))
        LazyColumn(Modifier.testTag("settings_list"), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard(title = "Household") {
                    LabeledRow("Name") { Text(ctx.household.name) }
                    LabeledRow("Your role") { Text(ctx.role.name.lowercase()) }
                    LabeledRow("Currency") { Text("৳ ${ctx.household.currencyCode}") }
                    if (ctx.role == Role.OWNER && !ctx.readOnly) SettingsRow(Icons.Filled.Edit, "Rename household") { renaming = true }
                    SettingsRow(Icons.Filled.Group, "Members", "Invite family, roles and access") { onMembers() }
                    SettingsRow(Icons.Filled.AccountBalanceWallet, "Monthly budget & opening balance") { onBudget() }
                    SettingsRow(Icons.Filled.Category, "Categories", "Expense and income categories") { onCategories() }
                }
            }
            item {
                SectionCard(title = "Account & sync") {
                    val acc = session.account
                    LabeledRow("Google account") { Text(acc?.email ?: "Not signed in") }
                    if (sync.health == SyncHealth.LOCAL_ONLY && ctx.role == Role.OWNER) {
                        SettingsRow(Icons.Filled.CloudSync, "Connect Google Drive", "Share this household with family") { onConnectDrive() }
                    }
                    SettingsRow(Icons.Filled.CloudSync, "Sync center", "Status, pending changes, conflicts") { onSync() }
                    SettingsRow(Icons.Filled.Backup, "Backup & restore", "Verify cloud backup, recovery passphrase") { onBackup() }
                    if (acc == null) {
                        if (account.googleConfigured) SettingsRow(Icons.Filled.Login, "Sign in with Google") { account.signIn(activity) }
                    } else SettingsRow(Icons.AutoMirrored.Filled.Logout, "Sign out", "Data on this phone is kept") { account.signOut() }
                }
            }
            item {
                SectionCard(title = "Preferences") {
                    SettingsRow(Icons.Filled.Notifications, "Budget notifications") { onNotifications() }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Filled.Palette, null, tint = MaterialTheme.colorScheme.primary)
                        Row(Modifier.padding(start = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ThemeMode.entries.forEach { m -> FilterChip(theme == m, { vm.setTheme(m) }, { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                        }
                    }
                    Text("Dates: day-month-year · Month boundary: calendar month in this phone's time zone", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item {
                SectionCard(title = "About") {
                    SettingsRow(Icons.Filled.PrivacyTip, "Privacy & data storage") { onPrivacy() }
                    LabeledRow("Version") { Text(BuildConfig.VERSION_NAME) }
                    TextButton(onClick = { leaving = true }) { Text("Remove household from this phone", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        androidx.compose.material3.SnackbarHost(host)
    }
    if (renaming) {
        var name by remember { mutableStateOf(ctx.household.name) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text("Rename household") },
            text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = false; vm.rename(name) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } })
    }
    if (leaving) {
        AlertDialog(onDismissRequest = { leaving = false }, title = { Text("Remove from this phone?") },
            text = { Text(if (sync.health == SyncHealth.LOCAL_ONLY) "This household is NOT backed up to Google Drive. All its data will be permanently deleted from this phone."
                else "Local data is deleted from this phone. Synced data stays in Google Drive; unsynced changes (${sync.pendingCount}) are lost.") },
            confirmButton = { TextButton(onClick = { leaving = false; vm.leave() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.notifications.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var pickTime by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Budget notifications") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fun set(n: NotificationSettings) {
                if (n.enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.setNotifications(n)
            }
            ToggleRow("Budget notifications", "Local alerts on this phone only", s.enabled) { set(s.copy(enabled = it)) }
            HorizontalDivider()
            ToggleRow("Warn at 80% of budget", null, s.warn80, s.enabled) { set(s.copy(warn80 = it)) }
            ToggleRow("Warn at 90% of budget", null, s.warn90, s.enabled) { set(s.copy(warn90 = it)) }
            ToggleRow("Daily reminder while over budget", "Stops automatically when spending is back under budget", s.dailyOverBudgetReminder, s.enabled) { set(s.copy(dailyOverBudgetReminder = it)) }
            LabeledRow("Reminder time") {
                TextButton(onClick = { pickTime = true }, enabled = s.enabled && s.dailyOverBudgetReminder) {
                    Text("%02d:%02d".format(s.reminderMinuteOfDay / 60, s.reminderMinuteOfDay % 60))
                }
            }
            Text("The reminder runs through Android's battery-friendly scheduler, so it may arrive a little after the chosen time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (pickTime) {
        val st = rememberTimePickerState(s.reminderMinuteOfDay / 60, s.reminderMinuteOfDay % 60)
        AlertDialog(onDismissRequest = { pickTime = false }, text = { TimePicker(st) },
            confirmButton = { TextButton(onClick = { pickTime = false; vm.setNotifications(s.copy(reminderMinuteOfDay = st.hour * 60 + st.minute)) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } })
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val households: HouseholdRepository,
    private val backup: BackupService,
    private val cloud: HouseholdCloudService,
) : BaseViewModel() {
    val verification = MutableStateFlow<BackupVerification?>(null)
    val context = households.context.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun hh() = households.currentContext()?.householdId ?: throw IllegalStateException("No household")
    fun verify() = launchSafely { verification.value = backup.verify(hh()) }
    fun rebuild() = launchSafely { val r = backup.rebuildFromDrive(hh()); message("Rebuilt from ${r.applied} verified operations") }
    fun snapshot() = launchSafely { backup.createSnapshot(hh()); message("Encrypted snapshot saved and verified") }
    fun changePassphrase(p: String, c: String) = launchSafely {
        if (p != c) return@launchSafely message("Passphrases do not match")
        cloud.changePassphrase(p.toCharArray()); needsPassphrase.value = true
        message("Recovery passphrase set. Tell members the passphrase for future joins.")
    }
    val needsPassphrase = MutableStateFlow<Boolean?>(null)
    fun loadPassphraseState() = launchSafely(showBusy = false) { needsPassphrase.value = cloud.currentNeedsPassphrase() }
    fun removePassphrase() = launchSafely {
        cloud.removePassphrase(); needsPassphrase.value = false
        message("Passphrase turned off. Invited members can join with one tap.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit, vm: BackupViewModel = hiltViewModel()) {
    val v by vm.verification.collectAsStateWithLifecycle()
    val ctx by vm.context.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    var confirmRebuild by remember { mutableStateOf(false) }
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Backup & restore") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            SectionCard(title = "Cloud backup") {
                Text("Your household file in Google Drive holds the complete encrypted change history. On a new phone, just sign in with the same Google account and it is restored automatically.",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::verify, enabled = !busy) { Text("Verify backup") }
                    OutlinedButton(onClick = vm::snapshot, enabled = !busy) { Text("Save snapshot") }
                }
                v?.let {
                    LabeledRow("Operations in Drive") { Text("${it.remoteOperations}") }
                    LabeledRow("Readable & authentic") { Text("${it.readable}") }
                    LabeledRow("Corrupted / invalid") { Text("${it.corrupted + it.invalid}") }
                    LabeledRow("Not yet uploaded") { Text("${it.localPending}") }
                    LabeledRow("Missing from Drive") { Text("${it.missingFromDrive}") }
                    Text(if (it.healthy) "Backup verified." else "Backup has problems. Local data will not be replaced.",
                        color = if (it.healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
            SectionCard(title = "Rebuild this phone from Drive") {
                Text("Replaces local data with a fresh copy built from the verified Drive history. Refused if the backup has problems or this phone has unsynced changes.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirmRebuild = true }, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Rebuild local data") }
            }
            val needsPass by vm.needsPassphrase.collectAsStateWithLifecycle()
            LaunchedEffect(ctx?.householdId) { vm.loadPassphraseState() }
            if (ctx?.role == Role.OWNER && needsPass == true) SectionCard(title = "Easy join (no passphrase)") {
                Text("Members currently need the recovery passphrase to join. Turn it off so invited Google accounts join with one tap. The household file stays private to the accounts you invite.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::removePassphrase, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Turn off passphrase") }
            }
            if (ctx?.role == Role.OWNER) SectionCard(title = if (needsPass == false) "Add a recovery passphrase (optional)" else "Change recovery passphrase") {
                OutlinedTextField(p1, { p1 = it }, Modifier.fillMaxWidth(), label = { Text("New passphrase") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(p2, { p2 = it }, Modifier.fillMaxWidth(), label = { Text("Confirm") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Button(onClick = { vm.changePassphrase(p1, p2); p1 = ""; p2 = "" }, enabled = p1.length >= 8 && !busy, modifier = Modifier.padding(top = 8.dp)) { Text("Change passphrase") }
            }
        }
    }
    if (confirmRebuild) AlertDialog(onDismissRequest = { confirmRebuild = false }, title = { Text("Rebuild local data?") },
        text = { Text("The app verifies the Drive copy first and keeps your current data if anything is wrong.") },
        confirmButton = { TextButton(onClick = { confirmRebuild = false; vm.rebuild() }) { Text("Rebuild") } },
        dismissButton = { TextButton(onClick = { confirmRebuild = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Privacy & data") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "On this phone" to "All household data (transactions, categories, budgets, members) is stored in the app's private database. The household encryption key is protected by the Android Keystore. Android cloud backup of app data is disabled.",
                "In your Google Drive" to "When you sign in with Google, the app creates one file \"HomeBudget - <household>.homebudget.json\" in the owner's Drive. It contains the household name, owner email, currency, the household key and the encrypted changes. Amounts, titles, notes and member details are encrypted with AES-256-GCM.",
                "Who can see it" to "Only Google accounts the owner invites get Drive access to the file, and only accounts recorded as household members can sync. By default no passphrase is needed; the owner can add an optional recovery passphrase in Backup & restore, then members need it to join.",
                "No HomeBudget server" to "The app talks only to Google's sign-in and Drive services. There are no ads, analytics or third-party trackers. Notifications are generated on the phone.",
                "Google permissions" to "Sign-in identifies your Google account. Drive access is limited to files this app creates and the household file you choose in Google's picker (drive.file). The app cannot see the rest of your Drive.",
                "Removing members" to "Removing a member revokes Drive sharing and app membership for future syncs. Data already on that member's phone cannot be erased remotely if the phone stays offline.",
                "Recovery passphrase" to "Optional. If you set one, it never leaves your phone; if every phone is lost and the passphrase is forgotten, encrypted Drive data cannot be recovered.",
                "Deleting data" to "Use Settings → Remove household from this phone to delete local data. Delete the household file in Google Drive to remove cloud data.",
            ).forEach { (t, b) -> SectionCard(title = t) { Text(b, style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}

