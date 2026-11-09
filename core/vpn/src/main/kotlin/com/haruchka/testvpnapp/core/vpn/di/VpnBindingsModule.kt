package com.haruchka.testvpnapp.core.vpn.di

import com.haruchka.testvpnapp.core.domain.VpnSession
import com.haruchka.testvpnapp.core.vpn.VpnController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Фасад VPN-сессии, объединяющий оба движка. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VpnBindingsModule {

    @Binds
    @Singleton
    abstract fun bindVpnSession(impl: VpnController): VpnSession
}
