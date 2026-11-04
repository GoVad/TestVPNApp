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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.haruchka.testvpnapp.core.model.VpnConnectionState
import com.haruchka.testvpnapp.core.model.VpnRuntimeState
import com.haruchka.testvpnapp.core.model.VpnServerConfig
import com.haruchka.testvpnapp.core.model.VpnTrafficStats
import java.util.Locale

/** Stateless-экран. Всё состояние приходит из Orbit-ViewModel. */
@Composable
fun VpnScreen(
    state: VpnUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Header() }
        item {
            StatusHero(
                state = state,
                onAction = { if (state.isActive) onDisconnect() else onConnect() },
            )
        }
        item {
            EndpointCard(
                state = state,
                onHostChange = onHostChange,
                onPortChange = onPortChange,
                onTokenChange = onTokenChange,
            )
        }
        if (state.runtime.isConnected) {
            item { TrafficCard(state) }
        }
        item { LogCard(state.logs) }
        item { AboutCard() }
        item {
            Text(
                text = "Учебный проект · GuardWire/2",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "GuardWire",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Демонстрационный VPN на VpnService",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = "DEMO",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
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
                    .clickable(enabled = !isBusy, onClick = onAction),
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
            enabled = !isBusy,
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
private fun EndpointCard(
    state: VpnUiState,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
) {
    var tokenVisible by remember { mutableStateOf(false) }

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
                text = "Сервер GuardWire",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = state.host,
                onValueChange = onHostChange,
                label = { Text("Host или IP") },
                placeholder = { Text("10.0.2.2") },
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
                    label = { Text("Порт") },
                    placeholder = { Text("8443") },
                    singleLine = true,
                    enabled = state.fieldsEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.token,
                    onValueChange = onTokenChange,
                    label = { Text("PSK-токен") },
                    placeholder = { Text("demo") },
                    singleLine = true,
                    enabled = state.fieldsEnabled,
                    visualTransformation = if (tokenVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { tokenVisible = !tokenVisible },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text(
                                text = if (tokenVisible) "Скрыть" else "Показать",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
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
                text = "Токен — общий PSK для аутентификации handshake. Он не " +
                    "передаётся по сети. Хост 10.0.2.2 из эмулятора указывает " +
                    "на компьютер с GuardWire-сервером.",
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
                    text = "Статистика",
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
                    title = "Получено",
                    value = formatBytes(stats.bytesIn),
                    detail = "${stats.packetsIn} пакетов",
                    modifier = Modifier.weight(1f),
                )
                VerticalDivider(
                    modifier = Modifier.height(52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                TrafficMetric(
                    title = "Отправлено",
                    value = formatBytes(stats.bytesOut),
                    detail = "${stats.packetsOut} пакетов",
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
                    text = server?.endpoint ?: "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "MTU ${server?.mtu ?: "—"}",
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
                text = "Журнал",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (logs.isEmpty()) {
                Text(
                    text = "Событий пока нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                logs.asReversed().forEach { entry ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = entry.time,
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
private fun AboutCard() {
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
                text = "Как это работает",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "GuardWireVpnService наследует VpnService, создаёт TUN через " +
                    "VpnService.Builder и показывает foreground-уведомление.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "IP-пакеты из TUN упаковываются в кадры GuardWire и уходят " +
                    "по TCP. Кадры шифруются AES-256-GCM, а ключ выводится из " +
                    "эфемерного ECDH P-256. Ответы сервера пишутся обратно в TUN.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Клиент не делает NAT/tun2socks: интернет появится только с сервером, " +
                    "который маршрутизирует пакеты (например, guardwire-linux-server.py).",
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
private fun statusTitle(connection: VpnConnectionState): String = when (connection) {
    VpnConnectionState.Disconnected -> "Отключено"
    VpnConnectionState.Connecting -> "Подключение…"
    is VpnConnectionState.Connected -> "Защищено"
    VpnConnectionState.Disconnecting -> "Отключение…"
    is VpnConnectionState.Failed -> "Ошибка"
}

private fun statusSubtitle(state: VpnUiState): String {
    val endpoint = state.runtime.server?.endpoint ?: "сервер"
    return when (val connection = state.connection) {
        VpnConnectionState.Disconnected -> "Трафик идёт напрямую"
        VpnConnectionState.Connecting -> "Рукопожатие с $endpoint"
        is VpnConnectionState.Connected ->
            "$endpoint · ${formatDuration(state.connectedDurationSeconds)}"
        VpnConnectionState.Disconnecting -> "Закрываем туннель"
        is VpnConnectionState.Failed -> connection.reason
    }
}

private fun buttonLabel(connection: VpnConnectionState): String = when (connection) {
    VpnConnectionState.Disconnected -> "Подключиться"
    VpnConnectionState.Connecting -> "Подключение…"
    is VpnConnectionState.Connected -> "Отключиться"
    VpnConnectionState.Disconnecting -> "Отключение…"
    is VpnConnectionState.Failed -> "Подключиться снова"
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

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes Б"
    bytes < 1_024 * 1_024 -> String.format(Locale.ROOT, "%.1f КБ", bytes / 1_024.0)
    bytes < 1_024L * 1_024 * 1_024 -> String.format(Locale.ROOT, "%.1f МБ", bytes / 1_048_576.0)
    else -> String.format(Locale.ROOT, "%.2f ГБ", bytes / 1_073_741_824.0)
}

@Preview(showBackground = true, backgroundColor = 0xFFF8FAFC)
@Composable
private fun VpnScreenConnectedPreview() {
    val now = System.currentTimeMillis()
    MaterialTheme {
        VpnScreen(
            state = VpnUiState(
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
                    VpnLogEntry("12:00:00", "Готов к подключению"),
                    VpnLogEntry("12:00:02", "Подключение к 10.0.2.2:8443…"),
                    VpnLogEntry("12:00:03", "Туннель установлен: 10.0.2.2:8443"),
                ),
            ),
            onConnect = {},
            onDisconnect = {},
            onHostChange = {},
            onPortChange = {},
            onTokenChange = {},
        )
    }
}
