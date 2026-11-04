package com.haruchka.testvpnapp

import android.content.Context
import com.haruchka.testvpnapp.core.vpn.VpnController
import com.haruchka.testvpnapp.core.vpn.VpnSettingsRepository
import com.haruchka.testvpnapp.core.vpn.VpnStateStore

/**
 * Ручной DI-граф приложения. Для демо достаточно одной точки сборки,
 * без Hilt/Koin.
 */
class AppGraph(context: Context) {

    val settingsRepository: VpnSettingsRepository = VpnSettingsRepository(context)

    val vpnController: VpnController = VpnController(
        context = context,
        stateStore = VpnStateStore.shared,
    )
}
