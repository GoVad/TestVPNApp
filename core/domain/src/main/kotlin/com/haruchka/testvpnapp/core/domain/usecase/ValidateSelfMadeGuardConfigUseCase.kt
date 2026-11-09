package com.haruchka.testvpnapp.core.domain.usecase

import com.haruchka.testvpnapp.core.model.VpnServerConfig

/** Причина, по которой поля SelfMadeGuard-формы не прошли проверку. */
enum class SelfMadeGuardConfigError {
    HOST_EMPTY,
    PORT_INVALID,
    TOKEN_EMPTY,
}

class SelfMadeGuardConfigException(
    val reason: SelfMadeGuardConfigError,
) : IllegalArgumentException(reason.name)

/** Проверяет поля формы SelfMadeGuard и собирает конфиг сервера. */
class ValidateSelfMadeGuardConfigUseCase {
    operator fun invoke(
        host: String,
        port: String,
        token: String,
    ): Result<VpnServerConfig> {
        val trimmedHost = host.trim()
        val parsedPort = port.trim().toIntOrNull()

        val error = when {
            trimmedHost.isEmpty() -> SelfMadeGuardConfigError.HOST_EMPTY
            parsedPort == null || parsedPort !in 1..65_535 -> SelfMadeGuardConfigError.PORT_INVALID
            token.isBlank() -> SelfMadeGuardConfigError.TOKEN_EMPTY
            else -> null
        }

        if (error != null) {
            return Result.failure(SelfMadeGuardConfigException(error))
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
