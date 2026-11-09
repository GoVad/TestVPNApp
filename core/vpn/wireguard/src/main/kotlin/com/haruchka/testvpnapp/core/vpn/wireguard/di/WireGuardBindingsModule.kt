package com.haruchka.testvpnapp.core.vpn.wireguard.di

import com.haruchka.testvpnapp.core.domain.WireGuardEngine
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser
import com.haruchka.testvpnapp.core.vpn.wireguard.WireGuardConfigParser
import com.haruchka.testvpnapp.core.vpn.wireguard.WireGuardController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Реализации реального WireGuard-движка. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WireGuardBindingsModule {

    @Binds
    @Singleton
    abstract fun bindWireGuardEngine(impl: WireGuardController): WireGuardEngine

    @Binds
    @Singleton
    abstract fun bindWireGuardParser(impl: WireGuardConfigParser): WireGuardProfileParser
}
