package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.model.VpnServerConfig

/** Причина, по которой поля GuardWire-формы не прошли проверку. */
enum class GuardWireConfigError {
    HOST_EMPTY,
    PORT_INVALID,
    TOKEN_EMPTY,
}

class GuardWireConfigException(
    val reason: GuardWireConfigError,
) : IllegalArgumentException(reason.name)

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
            trimmedHost.isEmpty() -> GuardWireConfigError.HOST_EMPTY
            parsedPort == null || parsedPort !in 1..65_535 -> GuardWireConfigError.PORT_INVALID
            token.isBlank() -> GuardWireConfigError.TOKEN_EMPTY
            else -> null
        }

        if (error != null) {
            return Result.failure(GuardWireConfigException(error))
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
