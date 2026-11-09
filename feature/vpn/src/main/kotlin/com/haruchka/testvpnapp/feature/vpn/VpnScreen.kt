package com.haruchka.testvpnapp.feature.vpn

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnLogEntry
import com.haruchka.testvpnapp.core.model.VpnMode
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import com.haruchka.testvpnapp.core.model.VpnTrafficStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stateless-экран. Всё состояние приходит из Orbit-ViewModel. */
@Composable
fun VpnScreen(
    state: VpnUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onModeChange: (VpnMode) -> Unit,
    onShowLogs: () -> Unit,
    onLogout: () -> Unit,
    username: String,
    onWireGuardConfigRequested: () -> Unit,
    onWireGuardConfigCleared: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onOpenVpnSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Header(onShowLogs = onShowLogs, onLogout = onLogout, username = username) }
        if (state.isLoading) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            ModeSelector(
                mode = state.mode,
                enabled = !state.isActive && !state.isLoading,
                onModeChange = onModeChange,
            )
        }
        item {
            StatusHero(
                state = state,
                onAction = { if (state.isActive) onDisconnect() else onConnect() },
            )
        }
        when (state.mode) {
            VpnMode.SIMPLIFIED -> item {
                EndpointCard(
                    state = state,
                    onHostChange = onHostChange,
                    onPortChange = onPortChange,
                    onTokenChange = onTokenChange,
                )
            }

            VpnMode.REAL -> item {
                WireGuardCard(
                    state = state,
                    onRequestConfig = onWireGuardConfigRequested,
                    onClearConfig = onWireGuardConfigCleared,
                )
            }
        }
        if (state.runtime.isConnected) {
            item { TrafficCard(state) }
        }
        item { LogCard(state.logs) }
        item { AlwaysOnCard(onOpenSettings = onOpenVpnSettings) }
        item { AboutCard(mode = state.mode) }
        item {
            Text(
                text = stringResource(R.string.footer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

}

@Composable
private fun Header(
    onShowLogs: () -> Unit,
    onLogout: () -> Unit,
    username: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.header_subtitle, username),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onShowLogs) {
            Text(stringResource(R.string.logs))
        }
        TextButton(onClick = onLogout) {
            Text(stringResource(R.string.logout))
        }
    }
}

@Composable
private fun ModeSelector(
    mode: VpnMode,
    enabled: Boolean,
    onModeChange: (VpnMode) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ModeButton(
                text = stringResource(R.string.mode_simplified),
                selected = mode == VpnMode.SIMPLIFIED,
                enabled = enabled,
                onClick = { onModeChange(VpnMode.SIMPLIFIED) },
                modifier = Modifier.weight(1f),
            )
            ModeButton(
                text = stringResource(R.string.mode_real),
                selected = mode == VpnMode.REAL,
                enabled = enabled,
                onClick = { onModeChange(VpnMode.REAL) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(14.dp),
        colors = if (selected) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatusHero(
    state: VpnUiState,
    onAction: () -> Unit,
) {
    val connection = state.connection
    val accent by animateColorAsState(
        targetValue = statusColor(connection),
        label = "statusColor",
    )
    val isBusy = connection is VpnConnectionState.Connecting ||
        connection is VpnConnectionState.Disconnecting

    val pulseTransition = rememberInfiniteTransition(label = "statusPulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    val glowAlpha = if (state.isActive) 0.12f + 0.10f * pulse else 0.06f

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .background(accent.copy(alpha = glowAlpha), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(184.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.28f),
                                MaterialTheme.colorScheme.surface,
                            ),
                        ),
                    )
                    .border(2.dp, accent.copy(alpha = 0.65f), CircleShape)
                    .clickable(enabled = !isBusy && !state.isLoading, onClick = onAction),
                contentAlignment = Alignment.Center,
            ) {
                PowerGlyph(
                    color = accent,
                    modifier = Modifier.size(62.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = statusTitle(connection),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = statusSubtitle(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onAction,
            enabled = !isBusy && !state.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = if (connection is VpnConnectionState.Connected) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(buttonLabel(connection))
        }
    }
}

@Composable
private fun PowerGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.09f
        val inset = size.minDimension * 0.12f
        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 280f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = Offset(size.width / 2f, size.height * 0.06f),
            end = Offset(size.width / 2f, size.height * 0.50f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun WireGuardCard(
    state: VpnUiState,
    onRequestConfig: () -> Unit,
    onClearConfig: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.wg_profile_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (state.hasWireGuardConfig) {
                Text(
                    text = stringResource(R.string.wg_profile_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ProfileRow(stringResource(R.string.wg_address), state.wireGuardAddress ?: "—")
                ProfileRow(stringResource(R.string.wg_endpoint), state.wireGuardEndpoint ?: "—")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onRequestConfig,
                        enabled = state.fieldsEnabled && !state.isProvisioningWireGuard,
                    ) {
                        Text(stringResource(R.string.wg_update))
                    }
                    TextButton(
                        onClick = onClearConfig,
                        enabled = state.fieldsEnabled && !state.isProvisioningWireGuard,
                    ) {
                        Text(stringResource(R.string.wg_delete))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.wg_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onRequestConfig,
                    enabled = state.fieldsEnabled && !state.isProvisioningWireGuard,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isProvisioningWireGuard) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        if (state.isProvisioningWireGuard) {
                            stringResource(R.string.wg_provisioning)
                        } else {
                            stringResource(R.string.wg_get)
                        },
                    )
                }
            }

            state.inputError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = stringResource(R.string.wg_ipv6_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EndpointCard(
    state: VpnUiState,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
) {
    var tokenVisible by remember { mutableStateOf(false) }
    val canEditToken = state.fieldsEnabled && !state.isLoggedIn

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.selfmadeguard_server_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = state.host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.host_label)) },
                placeholder = { Text(stringResource(R.string.host_placeholder)) },
                singleLine = true,
                enabled = state.fieldsEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.port,
                    onValueChange = onPortChange,
                    label = { Text(stringResource(R.string.port_label)) },
                    placeholder = { Text(stringResource(R.string.port_placeholder)) },
                    singleLine = true,
                    enabled = state.fieldsEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.token,
                    onValueChange = onTokenChange,
                    label = {
                        Text(
                            if (state.isLoggedIn) {
                                stringResource(R.string.psk_session)
                            } else {
                                stringResource(R.string.psk_label)
                            },
                        )
                    },
                    placeholder = { Text(stringResource(R.string.psk_placeholder)) },
                    singleLine = true,
                    enabled = canEditToken,
                    visualTransformation = if (tokenVisible && canEditToken) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = if (canEditToken) {
                        {
                            TextButton(
                                onClick = { tokenVisible = !tokenVisible },
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = if (tokenVisible) stringResource(R.string.hide) else stringResource(R.string.show),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1.7f),
                )
            }

            state.inputError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = if (state.isLoggedIn) {
                    stringResource(R.string.psk_hint_logged_in)
                } else {
                    stringResource(R.string.psk_hint_logged_out)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrafficCard(state: VpnUiState) {
    val server = state.runtime.server
    val stats = state.runtime.stats

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatDuration(state.connectedDurationSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TrafficMetric(
                    title = stringResource(R.string.received),
                    value = formatBytes(stats.bytesIn),
                    detail = if (state.mode == VpnMode.SIMPLIFIED) {
                        packetsLabel(stats.packetsIn)
                    } else {
                        stringResource(R.string.mode_wireguard)
                    },
                    modifier = Modifier.weight(1f),
                )
                VerticalDivider(
                    modifier = Modifier.height(52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                TrafficMetric(
                    title = stringResource(R.string.sent),
                    value = formatBytes(stats.bytesOut),
                    detail = if (state.mode == VpnMode.SIMPLIFIED) {
                        packetsLabel(stats.packetsOut)
                    } else {
                        stringResource(R.string.mode_wireguard)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = state.endpoint ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (state.mode == VpnMode.REAL) {
                        stringResource(R.string.mode_wireguard)
                    } else {
                        stringResource(R.string.mtu_format, server?.mtu?.toString() ?: "—")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrafficMetric(
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogCard(logs: List<VpnLogEntry>) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.log_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (logs.isEmpty()) {
                Text(
                    text = stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                logs.asReversed().take(6).forEach { entry ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = formatter.format(Date(entry.timestampMillis)),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlwaysOnCard(onOpenSettings: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.always_on_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.always_on_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.open_vpn_settings))
            }
        }
    }
}

@Composable
private fun AboutCard(mode: VpnMode) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            when (mode) {
                VpnMode.SIMPLIFIED -> {
                    Text(
                        text = stringResource(R.string.about_selfmadeguard_service),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.about_selfmadeguard_data),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                VpnMode.REAL -> {
                    Text(
                        text = stringResource(R.string.about_wireguard),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.about_wireguard_storage),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                text = stringResource(R.string.about_nat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun statusColor(connection: VpnConnectionState): Color = when (connection) {
    VpnConnectionState.Disconnected -> Color(0xFF94A3B8)
    VpnConnectionState.Connecting, VpnConnectionState.Disconnecting -> Color(0xFFF59E0B)
    is VpnConnectionState.Connected -> Color(0xFF22C55E)
    is VpnConnectionState.Failed -> Color(0xFFEF4444)
}
@Composable
private fun statusTitle(connection: VpnConnectionState): String = when (connection) {
    VpnConnectionState.Disconnected -> stringResource(R.string.status_disconnected)
    VpnConnectionState.Connecting -> stringResource(R.string.status_connecting)
    is VpnConnectionState.Connected -> stringResource(R.string.status_connected)
    VpnConnectionState.Disconnecting -> stringResource(R.string.status_disconnecting)
    is VpnConnectionState.Failed -> stringResource(R.string.status_failed)
}

@Composable
private fun statusSubtitle(state: VpnUiState): String {
    val endpoint = state.endpoint ?: stringResource(R.string.default_server)
    return when (val connection = state.connection) {
        VpnConnectionState.Disconnected ->
            if (state.mode == VpnMode.REAL) {
                stringResource(R.string.status_wireguard_disconnected)
            } else {
                stringResource(R.string.status_direct)
            }

        VpnConnectionState.Connecting ->
            stringResource(R.string.status_handshake, endpoint)
        is VpnConnectionState.Connected ->
            stringResource(
                R.string.status_connected_endpoint,
                endpoint,
                formatDuration(state.connectedDurationSeconds),
            )
        VpnConnectionState.Disconnecting -> stringResource(R.string.status_closing)
        is VpnConnectionState.Failed -> connection.reason
    }
}

@Composable
private fun buttonLabel(connection: VpnConnectionState): String = when (connection) {
    VpnConnectionState.Disconnected -> stringResource(R.string.button_connect)
    VpnConnectionState.Connecting -> stringResource(R.string.button_connecting)
    is VpnConnectionState.Connected -> stringResource(R.string.button_disconnect)
    VpnConnectionState.Disconnecting -> stringResource(R.string.button_disconnecting)
    is VpnConnectionState.Failed -> stringResource(R.string.button_connect_again)
}

@Composable
private fun packetsLabel(count: Long): String {
    val value = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(R.plurals.packets, value, value)
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

@Composable
private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> stringResource(R.string.bytes_b, bytes)
    bytes < 1_024 * 1_024 -> stringResource(
        R.string.bytes_kb,
        String.format(Locale.ROOT, "%.1f", bytes / 1_024.0),
    )
    bytes < 1_024L * 1_024 * 1_024 -> stringResource(
        R.string.bytes_mb,
        String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0),
    )
    else -> stringResource(
        R.string.bytes_gb,
        String.format(Locale.ROOT, "%.2f", bytes / 1_073_741_824.0),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF8FAFC)
@Composable
private fun VpnScreenConnectedPreview() {
    val now = System.currentTimeMillis()
    MaterialTheme {
        VpnScreen(
            state = VpnUiState(
                isLoading = false,
                isLoggedIn = true,
                username = "demo",
                mode = VpnMode.SIMPLIFIED,
                host = "10.0.2.2",
                port = "8443",
                token = "demo",
                runtime = VpnRuntimeState(
                    connection = VpnConnectionState.Connected(now - 125_000),
                    server = VpnServerConfig(),
                    stats = VpnTrafficStats(
                        bytesIn = 128_450,
                        bytesOut = 42_120,
                        packetsIn = 87,
                        packetsOut = 43,
                    ),
                ),
                nowMillis = now,
                logs = listOf(
                    VpnLogEntry(now - 5_000, "Ready to connect"),
                    VpnLogEntry(now - 3_000, "Connecting to 10.0.2.2:8443…"),
                    VpnLogEntry(now - 1_000, "Tunnel established: 10.0.2.2:8443"),
                ),
            ),
            onConnect = {},
            onDisconnect = {},
            onModeChange = {},
            onShowLogs = {},
            onLogout = {},
            username = "demo",
            onWireGuardConfigRequested = {},
            onWireGuardConfigCleared = {},
            onHostChange = {},
            onPortChange = {},
            onTokenChange = {},
            onOpenVpnSettings = {},
        )
    }
}
