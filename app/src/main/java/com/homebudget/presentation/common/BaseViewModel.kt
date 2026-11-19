package com.homebudget.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homebudget.core.drive.DriveAccessDeniedException
import com.homebudget.core.drive.DriveAuthException
import com.homebudget.core.drive.DriveTransientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Shared busy/error handling. Error text never includes financial data or tokens. */
abstract class BaseViewModel : ViewModel() {
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    protected fun message(text: String) { _messages.trySend(text) }

    protected fun launchSafely(showBusy: Boolean = true, block: suspend () -> Unit): Job = viewModelScope.launch {
        if (showBusy) _busy.value = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message(friendly(e))
        } finally {
            if (showBusy) _busy.value = false
        }
    }

    companion object {
        fun friendly(e: Throwable): String = when (e) {
            is DriveTransientException -> "No connection to Google Drive. Your changes are saved and will sync later."
            is DriveAuthException -> "Google Drive access is needed. Open Sync center to authorize."
            is DriveAccessDeniedException -> "Access to the household folder was denied."
            is androidx.credentials.exceptions.GetCredentialCancellationException -> "Sign-in cancelled"
            is androidx.credentials.exceptions.NoCredentialException -> "No Google account available on this phone"
            else -> e.message?.takeIf { it.length < 160 } ?: "Something went wrong"
        }
    }
}
