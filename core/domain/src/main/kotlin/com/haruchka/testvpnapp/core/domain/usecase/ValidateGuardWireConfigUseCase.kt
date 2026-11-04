package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.model.VpnServerConfig

/** Проверяет поля формы GuardWire и собирает конфиг сервера. */
class ValidateGuardWireConfigUseCase {
    operator fun invoke(
        host: String,
        port: String,
        token: String,
    ): Result<VpnServerConfig> {
        val trimmedHost = host.trim()
        val parsedPort = port.trim().toIntOrNull()

        val error = when {
            trimmedHost.isEmpty() -> "Укажите адрес GuardWire-сервера"
            parsedPort == null || parsedPort !in 1..65_535 ->
                "Порт должен быть числом от 1 до 65535"
            token.isBlank() -> "Укажите PSK-токен: он нужен для шифрования"
            else -> null
        }

        if (error != null) {
            return Result.failure(IllegalArgumentException(error))
        }

        return Result.success(
            VpnServerConfig(
                host = trimmedHost,
                port = requireNotNull(parsedPort),
                token = token.trim(),
            ),
        )
    }
}
