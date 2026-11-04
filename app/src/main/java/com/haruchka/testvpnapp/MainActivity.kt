package com.haruchka.testvpnapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.haruchka.testvpnapp.feature.vpn.VpnRoute
import com.haruchka.testvpnapp.ui.theme.TestVPNAppTheme

class MainActivity : ComponentActivity() {

    private val graph by lazy { AppGraph(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TestVPNAppTheme {
                VpnRoute(
                    controller = graph.vpnController,
                    settingsRepository = graph.settingsRepository,
                )
            }
        }
    }
}
