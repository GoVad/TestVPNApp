package com.haruchka.testvpnapp.core.model

/** Состояние VPN-подключения, которое отображается в UI. */
sealed interface VpnConnectionState {
    /** Туннель не создан, трафик идёт напрямую. */
    data object Disconnected : VpnConnectionState

    /** Идёт TCP-подключение и рукопожатие SelfMadeGuard. */
    data object Connecting : VpnConnectionState

    /** TUN-интерфейс создан, сервер подтвердил рукопожатие. */
    data class Connected(val sinceEpochMillis: Long) : VpnConnectionState

    /** Пользователь или система инициировали остановку туннеля. */
    data object Disconnecting : VpnConnectionState

    /** Подключение не удалось или было разорвано. */
    data class Failed(val reason: String) : VpnConnectionState
}
