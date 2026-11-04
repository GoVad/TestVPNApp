package com.haruchka.testvpnapp.core.domain

/** Контракт auth/control-plane. */
interface AuthService {
    suspend fun login(baseUrl: String, username: String, password: String): AuthSession
}
