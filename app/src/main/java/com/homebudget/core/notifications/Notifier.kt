package com.homebudget.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.homebudget.MainActivity
import com.homebudget.R
import com.homebudget.core.common.Money
import com.homebudget.domain.model.MonthSummary
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifier @Inject constructor(@ApplicationContext private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_BUDGET, "Budget alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Warnings when household spending approaches or exceeds the monthly budget"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Returns true if posted. Amounts are shown only in the notification, never logged. */
    fun show(level: AlertLevel, householdName: String, summary: MonthSummary, currency: String): Boolean {
        if (!canPost()) return false
        ensureChannel()
        val util = (summary.utilizationBasisPoints ?: 0) / 100
        val (title, text) = when (level) {
            AlertLevel.WARN_80, AlertLevel.WARN_90 -> "$util% of budget used" to
                "$householdName has ${Money.format(summary.remainingBudgetMinor ?: 0, currency)} left this month."
            AlertLevel.OVER_BUDGET -> "Monthly budget exceeded" to
                "$householdName is over budget by ${Money.format(summary.overrunMinor, currency)}."
            AlertLevel.DAILY_OVER_BUDGET -> "Still over budget" to
                "$householdName is ${Money.format(summary.overrunMinor, currency)} over this month's budget."
        }
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_BUDGET)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(level.ordinal + 100, n)
        } catch (e: SecurityException) {
            return false
        }
        return true
    }

    companion object {
        const val CHANNEL_BUDGET = "budget_alerts"
    }
}
