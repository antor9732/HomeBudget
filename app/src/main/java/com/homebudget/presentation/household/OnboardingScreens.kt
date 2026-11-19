package com.homebudget.presentation.household

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homebudget.presentation.common.SectionCard
import com.homebudget.presentation.transactions.CollectMessages

/** Launches Google's Drive consent screen when the view model asks for it. */
@Composable
fun ConsentLauncher(vm: AccountViewModel) {
    val pending by vm.pendingConsent.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onConsentResult(r.data, r.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(pending) { pending?.let { launcher.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }
}

@Composable
fun PickerLauncher(vm: AccountViewModel) {
    val pending by vm.pendingPicker.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onPickerResult(r.data, r.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(pending) { pending?.let { launcher.launch(IntentSenderRequest.Builder(it.intentSender).build()) } }
}

@Composable
fun WelcomeScreen(onCreate: () -> Unit, onJoin: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val activity = LocalContext.current as Activity
    ConsentLauncher(vm)
    PickerLauncher(vm)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val choice by vm.startChoice.collectAsStateWithLifecycle()
    val passFor by vm.passphraseFor.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle(initialValue = null)
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    var pass by remember { mutableStateOf("") }
    Scaffold(snackbarHost = { SnackbarHost(host) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(Icons.Filled.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("HomeBudget", style = MaterialTheme.typography.displaySmall)
            Text("Household income, expenses and a monthly budget — shared with your family.", style = MaterialTheme.typography.titleMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                passFor != null -> {
                    Text("This household has a recovery passphrase. Ask the owner for it.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Recovery passphrase") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Button(onClick = { vm.join(activity, passFor!!, pass) }, enabled = pass.length >= 8 && !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Join household") }
                }
                choice -> {
                    session?.account?.let { Text("Signed in as ${it.email}", style = MaterialTheme.typography.titleSmall) }
                    Button(onClick = { vm.startNewHousehold(activity) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Start my household")
                    }
                    OutlinedButton(onClick = { vm.pickFolder(activity) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("I was invited — open the invitation")
                    }
                    Text("Invited? In the Google Drive window open 'Shared with me' and tap the file \"HomeBudget - …\". You join instantly.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Feature(Icons.Filled.PhoneAndroid, "Works offline", "Everything is saved on this phone first. No internet needed to add or view money.")
                    Feature(Icons.Filled.CloudSync, "Shared with family", "Invite family by Gmail. Everyone sees the same household through your own Google Drive. No HomeBudget server.")
                    Feature(Icons.Filled.Lock, "Private", "Only the Google accounts you invite can open your household.")
                    Spacer(Modifier.height(8.dp))
                    if (vm.googleConfigured) {
                        Button(onClick = { vm.continueWithGoogle(activity) }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text("Continue with Google")
                        }
                    }
                    TextButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Use without Google (this phone only)") }
                }
            }
        }
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateHouseholdScreen(onBack: () -> Unit, onDone: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val activity = LocalContext.current as Activity
    val session by vm.session.collectAsStateWithLifecycle(initialValue = null)
    val done by vm.done.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    LaunchedEffect(done) { if (done) onDone() }
    var name by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var budget by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Create household") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(name, { name = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Household name") }, singleLine = true)
            OutlinedTextField(owner, { owner = it.take(60) }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true,
                placeholder = { Text(session?.account?.displayName ?: "") })
            OutlinedTextField(budget, { budget = it }, Modifier.fillMaxWidth(), label = { Text("This month's budget (optional, ৳)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Currency: Bangladeshi Taka (৳ BDT)", style = MaterialTheme.typography.bodySmall)
            SectionCard(title = "Google account (optional)") {
                val acc = session?.account
                if (acc != null) Text("Signed in as ${acc.email}. You can connect Google Drive sharing from Settings after creating the household.")
                else {
                    Text("You can start offline and sign in later from Settings to share with family.", style = MaterialTheme.typography.bodySmall)
                    if (vm.googleConfigured) OutlinedButton(onClick = { vm.signIn(activity) }, Modifier.padding(top = 8.dp)) { Text("Sign in with Google") }
                }
            }
            Button(onClick = { vm.createLocal(name, owner, budget) }, enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Create household")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinHouseholdScreen(onBack: () -> Unit, onDone: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val activity = LocalContext.current as Activity
    ConsentLauncher(vm)
    PickerLauncher(vm)
    val session by vm.session.collectAsStateWithLifecycle(initialValue = null)
    val folders by vm.folders.collectAsStateWithLifecycle()
    val picked by vm.pickedFolder.collectAsStateWithLifecycle()
    val checks by vm.accessChecks.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    LaunchedEffect(done) { if (done) onDone() }
    var selected by remember { mutableStateOf<String?>(null) }
    var pass by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Join or restore") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!vm.googleConfigured) {
                Text("Google sign-in is not configured in this build. See docs/GOOGLE-SETUP.md.", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Text("1. Sign in with the Google account the owner invited (or the owner's account to restore on a new phone).")
            val acc = session?.account
            if (acc == null) Button(onClick = { vm.signIn(activity) }) { Text("Sign in with Google") }
            else Text("Signed in as ${acc.email}", style = MaterialTheme.typography.titleSmall)
            Text("2. Choose the household file. Members: tap 'Choose from Google Drive', open 'Shared with me' and tap the file \"HomeBudget - … .homebudget.json\". Owners restoring a phone: use 'My households'.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.pickFolder(activity) }, enabled = acc != null && !busy) { Text("Choose from Google Drive") }
                OutlinedButton(onClick = { vm.loadFolders(activity) }, enabled = acc != null && !busy) { Text("My households") }
            }
            picked?.let { f ->
                LaunchedEffect(f.folderId) { selected = f.folderId }
                SectionCard(Modifier.clickable { selected = f.folderId }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Folder, null, tint = if (selected == f.folderId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(f.name, style = MaterialTheme.typography.titleSmall)
                            f.ownerEmail?.let { Text("Owner: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            selected?.let { id ->
                OutlinedButton(onClick = { vm.runAccessCheck(activity, id) }, enabled = !busy) { Text("Check Drive access (test)") }
            }
            checks?.let { list ->
                SectionCard(title = "Drive access check") {
                    list.forEach { c ->
                        Text((if (c.ok) "✓ " else "✗ ") + c.step, style = MaterialTheme.typography.bodyMedium,
                            color = if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Send a screenshot of this card to the developer.", style = MaterialTheme.typography.labelSmall)
                }
            }
            folders?.let { list ->
                if (list.isEmpty()) Text("No households created by this account. Members should use 'Choose from Google Drive'.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEach { f ->
                    SectionCard(Modifier.clickable { selected = f.folderId }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = if (selected == f.folderId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(f.name, style = MaterialTheme.typography.titleSmall)
                                Text(if (f.ownedByMe) "Owned by you" else "Owner: ${f.ownerEmail ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Text("3. Only if the owner set a recovery passphrase: enter it. Otherwise leave it empty.")
            OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Recovery passphrase") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Button(onClick = { selected?.let { vm.join(activity, it, pass) } }, enabled = selected != null && (pass.isEmpty() || pass.length >= 8) && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Join household") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectDriveScreen(onBack: () -> Unit, vm: AccountViewModel = hiltViewModel()) {
    val activity = LocalContext.current as Activity
    ConsentLauncher(vm)
    val done by vm.done.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    CollectMessages(vm, host)
    LaunchedEffect(done) { if (done) onBack() }
    var pass by remember { mutableStateOf("") }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Connect Google Drive") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("HomeBudget will save this household as one private file \"HomeBudget - <household>.homebudget.json\" in your Google Drive. Only Google accounts you invite can open it, and the data inside is encrypted.")
            Button(onClick = { vm.connectDrive(activity) }, enabled = !busy && vm.googleConfigured, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Sign in & connect")
            }
            if (!vm.googleConfigured) Text("Google sign-in is not configured in this build. See docs/GOOGLE-SETUP.md.", color = MaterialTheme.colorScheme.error)
        }
    }
}
