package com.homebudget

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.homebudget.core.notifications.Notifier
import com.homebudget.core.notifications.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HomeBudgetApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var scheduler: WorkScheduler
    @Inject lateinit var notifier: Notifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        notifier.ensureChannel()
        scheduler.schedulePeriodicSync()
        scheduler.requestBudgetCheck()
    }
}
