package com.haruchka.testvpnapp.core.data.di

import com.haruchka.testvpnapp.core.data.AuthRepository
import com.haruchka.testvpnapp.core.data.VpnLogStore
import com.haruchka.testvpnapp.core.data.VpnSettingsRepository
import com.haruchka.testvpnapp.core.data.VpnStateStore
import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.VpnLogRepository
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.VpnStateRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Реализации data-слоя. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {

    @Binds
    @Singleton
    abstract fun bindStateRepository(impl: VpnStateStore): VpnStateRepository

    @Binds
    @Singleton
    abstract fun bindLogRepository(impl: VpnLogStore): VpnLogRepository

    @Binds
    @Singleton
    abstract fun bindSettingsStore(impl: VpnSettingsRepository): VpnSettingsStore

    @Binds
    @Singleton
    abstract fun bindAuthService(impl: AuthRepository): AuthService
}
