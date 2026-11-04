package com.haruchka.testvpnapp.core.model

/** Счётчики трафика, которые ведёт TUN ↔ GuardWire-мост. */
data class VpnTrafficStats(
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val packetsIn: Long = 0,
    val packetsOut: Long = 0,
)
