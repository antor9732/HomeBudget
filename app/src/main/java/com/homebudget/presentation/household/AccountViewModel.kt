package com.homebudget.presentation.household

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.homebudget.core.auth.GoogleAuthManager
import com.homebudget.core.common.AppPreferences
import com.homebudget.core.common.Money
import com.homebudget.core.sync.HouseholdCloudService
import com.homebudget.core.sync.JoinableFolder
import com.homebudget.core.sync.SyncEngine
import com.homebudget.core.sync.SyncOutcome
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.presentation.common.BaseViewModel
import com.homebudget.core.common.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

/** Google sign-in, Drive authorization and household create/join/connect flows. */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: GoogleAuthManager,
    private val prefs: AppPreferences,
    private val cloud: HouseholdCloudService,
    private val households: HouseholdRepository,
    private val finance: FinanceRepository,
    private val engine: SyncEngine,
    private val time: TimeProvider,
) : BaseViewModel() {
    val session = prefs.session
    val googleConfigured get() = auth.isConfigured
    val folders = MutableStateFlow<List<JoinableFolder>?>(null)
    /** Set when Google needs to show a Drive consent screen; the UI launches it. */
    val pendingConsent = MutableStateFlow<PendingIntent?>(null)
    val done = MutableStateFlow(false)
    /** Set when the Google Drive Picker should be shown. */
    val pendingPicker = MutableStateFlow<PendingIntent?>(null)
    val pickedFolder = MutableStateFlow<JoinableFolder?>(null)
    val accessChecks = MutableStateFlow<List<com.homebudget.core.sync.AccessCheck>?>(null)
    private var afterConsent: (suspend () -> Unit)? = null
    /** Signed in, Drive ready, and no household of this account was found: ask start vs invited. */
    val startChoice = MutableStateFlow(false)
    /** Household file that needs the owner's optional recovery passphrase. */
    val passphraseFor = MutableStateFlow<String?>(null)

    /**
     * One-tap start: sign in, get Drive access, then restore this account's household automatically
     * if it already exists (new phone / reinstall); otherwise offer "start" or "I was invited".
     */
    fun continueWithGoogle(activity: Activity) = withDrive(activity) {
        val mine = cloud.findMyHouseholds()
        if (mine.isNotEmpty()) joinAuto(mine.first().folderId) else startChoice.value = true
    }

    /** Creates a household named after the user and connects it to Google Drive, no questions asked. */
    fun startNewHousehold(activity: Activity) = withDrive(activity) {
        val acc = prefs.currentSession().account
        val first = acc?.displayName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }
        households.createLocalHousehold(if (first != null) "$first's home" else "My home", acc?.displayName ?: "Owner", acc?.email, "BDT")
        report(cloud.connectCurrentHousehold(null), "Your household is ready and saved to Google Drive")
        done.value = true
    }

    private suspend fun joinAuto(fileId: String) {
        try {
            report(cloud.join(fileId, null), "Household ready")
            done.value = true
        } catch (e: com.homebudget.core.sync.PassphraseRequiredException) {
            passphraseFor.value = fileId
            message(e.message ?: "Passphrase required")
        }
    }

    fun signIn(activity: Activity, then: (() -> Unit)? = null) = launchSafely {
        val info = auth.signIn(activity)
        message("Signed in as ${info.email}")
        then?.invoke()
    }

    fun signOut() = launchSafely { auth.signOut(); message("Signed out. Data on this phone is kept.") }

    /** Ensures Drive authorization, then runs [action]. */
    private fun withDrive(activity: Activity, action: suspend () -> Unit) = launchSafely {
        if (prefs.currentSession().account == null) { auth.signIn(activity) }
        val consent = auth.authorizeDrive(activity)
        if (consent != null) {
            afterConsent = action
            pendingConsent.value = consent
        } else action()
    }

    fun onConsentResult(data: Intent?, resultOk: Boolean) {
        pendingConsent.value = null
        val action = afterConsent ?: return
        afterConsent = null
        if (!resultOk) { message("Google Drive access was not granted"); return }
        launchSafely {
            val r = auth.parseAuthorizationResult(data)
            if (r.accessToken == null) message("Google Drive access was not granted") else action()
        }
    }

    /** Opens Google's Drive Picker so a member can choose the folder the owner shared. */
    fun pickFolder(activity: Activity) = launchSafely {
        if (prefs.currentSession().account == null) auth.signIn(activity)
        pendingPicker.value = auth.pickerIntent(activity) ?: return@launchSafely message("Google Drive Picker is not available on this phone")
    }

    fun onPickerResult(data: Intent?, resultOk: Boolean) {
        pendingPicker.value = null
        if (!resultOk) { message("No file chosen"); return }
        launchSafely {
            val r = auth.parseAuthorizationResult(data)
            val id = auth.pickedFileIds(r).firstOrNull() ?: return@launchSafely message("No file chosen")
            accessChecks.value = null
            pickedFolder.value = JoinableFolder(id, "Checking the chosen file…", null, false)
            val resolved = try {
                cloud.resolvePickedFolder(id)
            } catch (e: Exception) {
                pickedFolder.value = null
                throw e
            }
            pickedFolder.value = resolved
            if (!cloud.needsPassphrase(resolved.folderId)) {
                joinAuto(resolved.folderId) // invited member: one tap, nothing to type
                return@launchSafely
            }
            passphraseFor.value = resolved.folderId
            accessChecks.value = cloud.diagnoseFolder(resolved.folderId)
        }
    }

    fun runAccessCheck(activity: Activity, folderId: String) = withDrive(activity) {
        accessChecks.value = cloud.diagnoseFolder(folderId)
    }

    fun createLocal(name: String, ownerName: String, budgetText: String) = launchSafely {
        val account = prefs.currentSession().account
        households.createLocalHousehold(name, ownerName.ifBlank { account?.displayName ?: "Owner" }, account?.email, "BDT")
        Money.parseToMinor(budgetText)?.let { finance.setBudget(time.currentMonth(), it) }
        done.value = true
    }

    fun connectDrive(activity: Activity) {
        withDrive(activity) {
            report(cloud.connectCurrentHousehold(null), "Household connected to Google Drive")
            done.value = true
        }
    }

    fun loadFolders(activity: Activity) = withDrive(activity) { folders.value = cloud.listJoinableFolders() }

    fun join(activity: Activity, folderId: String, passphrase: String) = withDrive(activity) {
        report(cloud.join(folderId, passphrase.takeIf { it.isNotEmpty() }?.toCharArray()), "Joined household")
        done.value = true
    }

    fun authorizeAndSync(activity: Activity) = withDrive(activity) {
        val hh = prefs.currentSession().householdId ?: return@withDrive
        report(engine.sync(hh), "Synced")
    }

    private fun report(outcome: SyncOutcome, okText: String) {
        when (outcome) {
            is SyncOutcome.Success -> message(okText)
            SyncOutcome.LocalOnly -> message(okText)
            is SyncOutcome.AuthRequired -> message("Google Drive authorization is required")
            is SyncOutcome.AccessRevoked -> message("Access to this household was revoked")
            is SyncOutcome.Failed -> message("Saved. Sync will retry: ${outcome.message}")
        }
    }
}
