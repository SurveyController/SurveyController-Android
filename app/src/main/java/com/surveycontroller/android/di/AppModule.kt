package com.surveycontroller.android.di

import android.content.Context
import com.surveycontroller.android.core.backend.BackendClient
import com.surveycontroller.android.core.backend.DeviceIdentity
import com.surveycontroller.android.core.backend.RandomIpSessionStore
import com.surveycontroller.android.core.engine.RunEngine
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.update.UpdateChecker
import com.surveycontroller.android.data.RecentSurveyStore
import com.surveycontroller.android.data.SettingsStore
import com.surveycontroller.android.provider.ProviderRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient()

    @Provides
    @Singleton
    fun provideDeviceIdentity(@ApplicationContext context: Context): DeviceIdentity = DeviceIdentity(context)

    @Provides
    @Singleton
    fun provideRandomIpSessionStore(@ApplicationContext context: Context): RandomIpSessionStore =
        RandomIpSessionStore(context)

    @Provides
    @Singleton
    fun provideBackendClient(
        http: HttpClient,
        deviceIdentity: DeviceIdentity,
        sessionStore: RandomIpSessionStore,
    ): BackendClient = BackendClient(http, deviceIdentity, sessionStore)

    @Provides
    @Singleton
    fun provideProviderRegistry(http: HttpClient): ProviderRegistry =
        ProviderRegistry(http)

    @Provides
    @Singleton
    fun provideRunEngine(registry: ProviderRegistry, http: HttpClient, backend: BackendClient): RunEngine =
        RunEngine(registry, http, backend)

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore = SettingsStore(context)

    @Provides
    @Singleton
    fun provideRecentSurveyStore(@ApplicationContext context: Context): RecentSurveyStore = RecentSurveyStore(context)

    @Provides
    @Singleton
    fun provideUpdateChecker(http: HttpClient): UpdateChecker = UpdateChecker(http)
}
