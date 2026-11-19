package com.homebudget.core.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.homebudget.BuildConfig
import com.homebudget.core.common.AccountInfo
import com.homebudget.core.common.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Supplies short-lived Drive access tokens. Tokens are never written to app storage. */
interface AccessTokenProvider {
    /** @throws com.homebudget.core.drive.DriveAuthException when user interaction is needed. */
    suspend fun accessToken(): String
    suspend fun invalidate(token: String)
}

class AuthorizationRequiredException(val pendingIntent: PendingIntent?) : Exception("Drive authorization required")

/**
 * Authentication (who you are) uses Credential Manager + Sign in with Google.
 * Authorization (Drive access) uses Google Identity Services AuthorizationClient. They are separate:
 * signing in never implies Drive access.
 *
 * Scope decision (docs/DECISIONS.md D-03): only the non-sensitive `drive.file` scope is used. The app
 * can see files it created for this user, plus files/folders the user explicitly picks in the
 * Google Picker (used by members to open a household folder shared with them).
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
) : AccessTokenProvider {

    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    suspend fun signIn(activity: Activity): AccountInfo {
        check(isConfigured) { "Google sign-in is not configured (missing web client ID). See docs/GOOGLE-SETUP.md." }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = CredentialManager.create(activity).getCredential(activity, request)
        val credential = result.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Unexpected credential type"
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val subject = runCatching { jwtClaim(google.idToken, "sub") }.getOrNull()
        val email = runCatching { jwtClaim(google.idToken, "email") }.getOrNull() ?: google.id
        val info = AccountInfo(email = email, displayName = google.displayName ?: email, subject = subject)
        prefs.setAccount(info)
        return info
    }

    suspend fun signOut() {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        prefs.setAccount(null)
    }

    private fun request(email: String?): AuthorizationRequest = AuthorizationRequest.Builder()
        .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
        .apply { if (email != null) setAccount(Account(email, "com.google")) }
        .build()

    /**
     * Opens the Google Drive Picker (via Google Identity Services) so the user can choose the household
     * file (`HomeBudget - <name>.homebudget.json`). Always returns a PendingIntent to launch; the picked id is read with [pickedFileIds].
     */
    suspend fun pickerIntent(activity: Activity): PendingIntent? {
        val email = prefs.currentSession().account?.email
        val req = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .setOptOutIncludingGrantedScopes(true)
            .setPrompt(AuthorizationRequest.Prompt.CONSENT)
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES, "application/json")
            .apply { if (email != null) setAccount(Account(email, "com.google")) }
            .build()
        val result = Identity.getAuthorizationClient(activity).authorize(req).await()
        return result.pendingIntent
    }

    /** Comma-separated `picked_file_ids` returned by the Picker. */
    fun pickedFileIds(result: AuthorizationResult): List<String> =
        result.tokenResponseParams?.getString("picked_file_ids")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** Interactive authorization; returns a PendingIntent to launch if consent is needed, or null if granted. */
    suspend fun authorizeDrive(activity: Activity): PendingIntent? {
        val email = prefs.currentSession().account?.email
        val result = Identity.getAuthorizationClient(activity).authorize(request(email)).await()
        return if (result.hasResolution()) result.pendingIntent else null
    }

    fun parseAuthorizationResult(data: android.content.Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    override suspend fun accessToken(): String {
        val email = prefs.currentSession().account?.email
            ?: throw com.homebudget.core.drive.DriveAuthException("Not signed in")
        val result = try {
            Identity.getAuthorizationClient(context).authorize(request(email)).await()
        } catch (e: Exception) {
            throw com.homebudget.core.drive.DriveAuthException("Authorization failed: ${e.javaClass.simpleName}")
        }
        if (result.hasResolution()) throw com.homebudget.core.drive.DriveAuthException("Drive consent required")
        return result.accessToken ?: throw com.homebudget.core.drive.DriveAuthException("No access token")
    }

    override suspend fun invalidate(token: String) {
        withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, token) } }
    }

    private fun jwtClaim(jwt: String, claim: String): String? {
        val payload = jwt.split(".").getOrNull(1) ?: return null
        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        return JSONObject(decoded).optString(claim).takeIf { it.isNotBlank() }
    }

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
