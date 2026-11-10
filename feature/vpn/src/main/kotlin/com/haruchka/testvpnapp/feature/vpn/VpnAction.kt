package com.haruchka.testvpnapp.feature.vpn

import com.haruchka.testvpnapp.core.model.VpnMode

/**
 * Все события UI, адресованные ViewModel.
 *
 * Экран не знает, какие use case-и стоят за действием: он отправляет экшен через
 * [VpnViewModel.onAction], а ViewModel сама решает, что с ним делать.
 */
sealed interface VpnAction {
    /** Нажата кнопка подключения. */
    data object Connect : VpnAction

    /** Нажата кнопка отключения. */
    data object Disconnect : VpnAction

    /** Отправлена форма входа. */
    data class Login(
        val baseUrl: String,
        val username: String,
        val password: String,
    ) : VpnAction

    /** Пользователь изменил любое поле формы входа. */
    data object AuthInputChanged : VpnAction

    /** Выход из аккаунта. */
    data object Logout : VpnAction

    /** Ответ на запрос POST_NOTIFICATIONS. */
    data class NotificationPermissionResult(val granted: Boolean) : VpnAction

    /** Ответ на системный диалог согласия на VPN. */
    data class VpnConsentResult(val granted: Boolean) : VpnAction

    /** Переключён режим туннеля. */
    data class ModeChanged(val mode: VpnMode) : VpnAction

    /** Запрошен профиль WireGuard с control-plane. */
    data object WireGuardConfigRequested : VpnAction

    /** Удалён сохранённый профиль WireGuard. */
    data object WireGuardConfigCleared : VpnAction

    /** Изменён адрес сервера. */
    data class HostChanged(val value: String) : VpnAction

    /** Изменён порт сервера. */
    data class PortChanged(val value: String) : VpnAction

    /** Изменён PSK/access token. */
    data class TokenChanged(val value: String) : VpnAction

    /** Очищен журнал. */
    data object LogsCleared : VpnAction

    /** Открыть системные настройки VPN. */
    data object OpenVpnSettings : VpnAction
}
