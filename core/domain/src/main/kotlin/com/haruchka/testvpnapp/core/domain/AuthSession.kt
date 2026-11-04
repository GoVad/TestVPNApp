package com.haruchka.testvpnapp.core.domain

/** Результат успешного входа в auth/control-plane. */
data class AuthSession(
    val token: String,
    val username: String,
    val expiresAtEpochSeconds: Long,
    val wireGuardConfig: String?,
)
