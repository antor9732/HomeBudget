package com.homebudget.core.common

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "homebudget_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class NotificationSettings(
    val enabled: Boolean = true,
    val warn80: Boolean = true,
    val warn90: Boolean = true,
    val dailyOverBudgetReminder: Boolean = true,
    /** Minutes after local midnight. Default 20:00. */
    val reminderMinuteOfDay: Int = 20 * 60,
)

/** Google identity of the signed-in user (no tokens are ever stored here). */
data class AccountInfo(val email: String, val displayName: String, val subject: String?)

data class Session(
    val householdId: String?,
    val memberId: String?,
    val account: AccountInfo?,
)

/**
 * Device-scoped settings (theme, notifications, selected household/member, device id).
 * Access tokens are NOT stored: they are obtained on demand from Google Identity Services.
 */
/** Minimal identity contract used by repositories and sync (fakeable in tests). */
interface LocalIdentity {
    val session: Flow<Session>
    suspend fun deviceId(): String
    suspend fun currentSession(): Session
    suspend fun setHousehold(householdId: String?, memberId: String?)
}

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) : LocalIdentity {
    private val ds get() = context.dataStore

    private object K {
        val deviceId = stringPreferencesKey("device_id")
        val householdId = stringPreferencesKey("household_id")
        val memberId = stringPreferencesKey("member_id")
        val email = stringPreferencesKey("account_email")
        val name = stringPreferencesKey("account_name")
        val sub = stringPreferencesKey("account_sub")
        val theme = stringPreferencesKey("theme")
        val nEnabled = booleanPreferencesKey("notif_enabled")
        val n80 = booleanPreferencesKey("notif_80")
        val n90 = booleanPreferencesKey("notif_90")
        val nDaily = booleanPreferencesKey("notif_daily")
        val nMinute = intPreferencesKey("notif_minute")
        val notified = stringSetPreferencesKey("notified_keys")
        val onboarded = booleanPreferencesKey("privacy_ack")
    }

    override suspend fun deviceId(): String {
        ds.data.first()[K.deviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        ds.edit { if (it[K.deviceId] == null) it[K.deviceId] = id }
        return ds.data.first()[K.deviceId]!!
    }

    override val session: Flow<Session> = ds.data.map { p ->
        Session(
            householdId = p[K.householdId],
            memberId = p[K.memberId],
            account = p[K.email]?.let { AccountInfo(it, p[K.name] ?: it, p[K.sub]) },
        )
    }

    override suspend fun currentSession(): Session = session.first()

    override suspend fun setHousehold(householdId: String?, memberId: String?) {
        ds.edit {
        if (householdId == null) it.remove(K.householdId) else it[K.householdId] = householdId
        if (memberId == null) it.remove(K.memberId) else it[K.memberId] = memberId
        }
    }

    suspend fun setAccount(account: AccountInfo?) = ds.edit {
        if (account == null) {
            it.remove(K.email); it.remove(K.name); it.remove(K.sub)
        } else {
            it[K.email] = account.email
            it[K.name] = account.displayName
            if (account.subject != null) it[K.sub] = account.subject else it.remove(K.sub)
        }
    }

    val theme: Flow<ThemeMode> = ds.data.map { p -> p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM }
    suspend fun setTheme(mode: ThemeMode) = ds.edit { it[K.theme] = mode.name }

    val notificationSettings: Flow<NotificationSettings> = ds.data.map { p ->
        val d = NotificationSettings()
        NotificationSettings(
            enabled = p[K.nEnabled] ?: d.enabled,
            warn80 = p[K.n80] ?: d.warn80,
            warn90 = p[K.n90] ?: d.warn90,
            dailyOverBudgetReminder = p[K.nDaily] ?: d.dailyOverBudgetReminder,
            reminderMinuteOfDay = p[K.nMinute] ?: d.reminderMinuteOfDay,
        )
    }

    suspend fun setNotificationSettings(s: NotificationSettings) = ds.edit {
        it[K.nEnabled] = s.enabled; it[K.n80] = s.warn80; it[K.n90] = s.warn90
        it[K.nDaily] = s.dailyOverBudgetReminder; it[K.nMinute] = s.reminderMinuteOfDay
    }

    /** Keys of alerts already shown, e.g. "hh:202611:WARN_80" or "hh:202611:DAILY:2026-11-16". */
    suspend fun notifiedKeys(): Set<String> = ds.data.first()[K.notified] ?: emptySet()
    suspend fun addNotifiedKey(key: String) = ds.edit { p ->
        val current = p[K.notified] ?: emptySet()
        // Keep the set bounded.
        p[K.notified] = (current + key).toList().takeLast(200).toSet()
    }

    val privacyAcknowledged: Flow<Boolean> = ds.data.map { it[K.onboarded] ?: false }
    suspend fun setPrivacyAcknowledged() = ds.edit { it[K.onboarded] = true }
}
