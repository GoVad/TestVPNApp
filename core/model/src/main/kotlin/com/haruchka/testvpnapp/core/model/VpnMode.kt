package com.haruchka.testvpnapp.core.model

/** Режим VPN-движка. */
enum class VpnMode {
    /** Учебный SelfMadeGuard/2 поверх TCP. */
    SIMPLIFIED,

    /** WireGuard из официальной Android-библиотеки. */
    REAL,
}
