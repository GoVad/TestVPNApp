package com.haruchka.testvpnapp.core.vpn.di

import com.haruchka.testvpnapp.core.domain.VpnSession
import com.haruchka.testvpnapp.core.domain.WireGuardEngine
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser
import com.haruchka.testvpnapp.core.vpn.VpnController
import com.haruchka.testvpnapp.core.vpn.WireGuardConfigParser
import com.haruchka.testvpnapp.core.vpn.WireGuardController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Платформенные реализации VPN. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VpnBindingsModule {

    @Binds
    @Singleton
    abstract fun bindVpnSession(impl: VpnController): VpnSession

    @Binds
    @Singleton
    abstract fun bindWireGuardEngine(impl: WireGuardController): WireGuardEngine

    @Binds
    @Singleton
    abstract fun bindWireGuardParser(impl: WireGuardConfigParser): WireGuardProfileParser
}
