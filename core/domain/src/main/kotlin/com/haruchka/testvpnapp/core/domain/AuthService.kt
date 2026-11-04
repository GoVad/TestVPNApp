package com.haruchka.testvpnapp.core.domain

/** Контракт auth/control-plane. */
interface AuthService {
    suspend fun login(baseUrl: String, username: String, password: String): AuthSession

    suspend fun refresh(baseUrl: String, refreshToken: String): AuthSession

    /** Запрашивает у сервера новый WireGuard-конфиг для текущего access-токена. */
    suspend fun provisionWireGuard(baseUrl: String, accessToken: String): String

    suspend fun logout(baseUrl: String, refreshToken: String)
}
