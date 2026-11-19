package com.homebudget.core.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.homebudget.core.common.AppPreferences
import com.homebudget.core.sync.ChangeListener
import com.homebudget.core.sync.SyncEngine
import com.homebudget.core.sync.SyncOutcome
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Central WorkManager scheduling. Work survives reboot and respects battery/network constraints. */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChangeListener {
    private val wm get() = WorkManager.getInstance(context)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun onLocalChange(householdId: String) {
        requestSync()
        requestBudgetCheck()
    }

    override fun onDataChanged(householdId: String) = requestBudgetCheck()

    fun requestSync() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(WORK_SYNC_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun schedulePeriodicSync() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun requestBudgetCheck() {
        wm.enqueueUniqueWork(WORK_BUDGET_CHECK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<BudgetCheckWorker>().build())
    }

    fun scheduleDailyReminder(minuteOfDay: Int) {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        var next = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val req = PeriodicWorkRequestBuilder<DailyReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_DAILY_REMINDER, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancelDailyReminder() {
        wm.cancelUniqueWork(WORK_DAILY_REMINDER)
    }

    companion object {
        const val WORK_SYNC_NOW = "sync-now"
        const val WORK_SYNC_PERIODIC = "sync-periodic"
        const val WORK_BUDGET_CHECK = "budget-check"
        const val WORK_DAILY_REMINDER = "daily-over-budget-reminder"
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
    private val prefs: AppPreferences,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val hh = prefs.currentSession().householdId ?: return Result.success()
        return when (val outcome = engine.sync(hh)) {
            is SyncOutcome.Failed -> if (outcome.retryable && runAttemptCount < 8) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }
}

@HiltWorker
class BudgetCheckWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val checker: BudgetAlertChecker,
    private val scheduler: WorkScheduler,
    private val prefs: AppPreferences,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val keepDaily = checker.checkAfterChange()
        // Budget no longer exceeded / reminders disabled -> cancel future reminders.
        if (keepDaily) scheduler.scheduleDailyReminder(prefs.notificationSettings.first().reminderMinuteOfDay)
        else scheduler.cancelDailyReminder()
        return Result.success()
    }
}

@HiltWorker
class DailyReminderWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val checker: BudgetAlertChecker,
    private val scheduler: WorkScheduler,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!checker.runDaily()) scheduler.cancelDailyReminder()
        return Result.success()
    }
}
