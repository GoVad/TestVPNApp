package com.haruchka.testvpnapp.feature.vpn

import android.content.Intent

/** Одноразовые события ViewModel → UI. */
sealed interface VpnSideEffect {
    /** Показать системный диалог согласия на использование VPN. */
    data class LaunchVpnConsent(val intent: Intent) : VpnSideEffect

    /** Запросить POST_NOTIFICATIONS (Android 13+). */
    data object RequestNotificationPermission : VpnSideEffect

    /** Показать короткое сообщение в snackbar. */
    data class ShowMessage(val message: String) : VpnSideEffect
}
