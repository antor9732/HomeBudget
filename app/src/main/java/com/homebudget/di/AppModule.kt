package com.homebudget.di

import android.content.Context
import com.homebudget.core.auth.AccessTokenProvider
import com.homebudget.core.auth.GoogleAuthManager
import com.homebudget.core.common.AppPreferences
import com.homebudget.core.common.LocalIdentity
import com.homebudget.core.common.SystemTimeProvider
import com.homebudget.core.common.TimeProvider
import com.homebudget.core.database.AppDatabase
import com.homebudget.core.database.SyncDao
import com.homebudget.core.drive.DriveRemote
import com.homebudget.core.drive.HttpDriveRemote
import com.homebudget.core.notifications.WorkScheduler
import com.homebudget.core.security.AndroidKeystoreKeyWrapper
import com.homebudget.core.security.DeviceKeyWrapper
import com.homebudget.core.sync.ChangeListener
import com.homebudget.core.sync.SyncRepositoryImpl
import com.homebudget.data.repository.FinanceRepositoryImpl
import com.homebudget.data.repository.HouseholdRepositoryImpl
import com.homebudget.domain.repository.FinanceRepository
import com.homebudget.domain.repository.HouseholdRepository
import com.homebudget.domain.repository.SyncRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProvidesModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = AppDatabase.build(context)

    @Provides fun syncDao(db: AppDatabase): SyncDao = db.sync()

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // No body logging: payloads and tokens must never be logged.
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindsModule {
    @Binds abstract fun time(impl: SystemTimeProvider): TimeProvider
    @Binds abstract fun identity(impl: AppPreferences): LocalIdentity
    @Binds abstract fun keyWrapper(impl: AndroidKeystoreKeyWrapper): DeviceKeyWrapper
    @Binds abstract fun drive(impl: HttpDriveRemote): DriveRemote
    @Binds abstract fun tokens(impl: GoogleAuthManager): AccessTokenProvider
    @Binds abstract fun changeListener(impl: WorkScheduler): ChangeListener
    @Binds abstract fun households(impl: HouseholdRepositoryImpl): HouseholdRepository
    @Binds abstract fun finance(impl: FinanceRepositoryImpl): FinanceRepository
    @Binds abstract fun sync(impl: SyncRepositoryImpl): SyncRepository
}
