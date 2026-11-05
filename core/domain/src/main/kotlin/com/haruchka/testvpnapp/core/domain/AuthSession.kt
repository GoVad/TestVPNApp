package com.haruchka.testvpnapp.core.domain

/** Результат успешного входа или продления сессии. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val username: String,
    val accessExpiresAtEpochSeconds: Long,
    val refreshExpiresAtEpochSeconds: Long,
    val wireGuardConfig: String?,
)

/** Refresh-токен истёк или отозван: нужен повторный вход. */
class AuthSessionExpiredException : Exception("Auth session expired")
