package com.haruchka.testvpnapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.haruchka.testvpnapp.feature.vpn.VpnRoute
import com.haruchka.testvpnapp.ui.theme.TestVPNAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TestVPNAppTheme {
                VpnRoute()
            }
        }
    }
}
