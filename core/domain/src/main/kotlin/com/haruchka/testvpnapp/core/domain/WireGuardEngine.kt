package com.haruchka.testvpnapp.core.domain

/** Контракт реального WireGuard-движка. */
interface WireGuardEngine {
    fun connect(configText: String)

    fun disconnect()
}
