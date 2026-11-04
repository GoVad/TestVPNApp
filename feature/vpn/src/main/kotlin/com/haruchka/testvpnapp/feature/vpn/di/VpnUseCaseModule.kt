package com.haruchka.testvpnapp.feature.vpn.di

import com.haruchka.testvpnapp.core.domain.AuthService
import com.haruchka.testvpnapp.core.domain.VpnLogRepository
import com.haruchka.testvpnapp.core.domain.VpnSession
import com.haruchka.testvpnapp.core.domain.VpnSettingsStore
import com.haruchka.testvpnapp.core.domain.WireGuardProfileParser
import com.haruchka.testvpnapp.core.domain.usecase.EnsureAuthSessionUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LoadVpnSettingsUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LoginUseCase
import com.haruchka.testvpnapp.core.domain.usecase.LogoutUseCase
import com.haruchka.testvpnapp.core.domain.usecase.ProvisionWireGuardUseCase
import com.haruchka.testvpnapp.core.domain.usecase.ValidateGuardWireConfigUseCase
import com.haruchka.testvpnapp.core.domain.usecase.VpnConnectionUseCase
import com.haruchka.testvpnapp.core.domain.usecase.VpnLogsUseCase
import com.haruchka.testvpnapp.core.domain.usecase.VpnSettingsUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Собирает use case-и для экрана VPN.
 *
 * Домен не зависит от Hilt: провайдеры живут в UI-модуле, а зависимости
 * приходят через контракты домена.
 */
@Module
@InstallIn(SingletonComponent::class)
object VpnUseCaseModule {

    @Provides
    fun provideLoadVpnSettingsUseCase(
        settings: VpnSettingsStore,
        profileParser: WireGuardProfileParser,
    ): LoadVpnSettingsUseCase = LoadVpnSettingsUseCase(settings, profileParser)

    @Provides
    fun provideLoginUseCase(
        authService: AuthService,
        settings: VpnSettingsStore,
        profileParser: WireGuardProfileParser,
    ): LoginUseCase = LoginUseCase(authService, settings, profileParser)

    @Provides
    fun provideEnsureAuthSessionUseCase(
        settings: VpnSettingsStore,
        authService: AuthService,
    ): EnsureAuthSessionUseCase = EnsureAuthSessionUseCase(settings, authService)

    @Provides
    fun provideLogoutUseCase(
        session: VpnSession,
        settings: VpnSettingsStore,
        authService: AuthService,
    ): LogoutUseCase = LogoutUseCase(session, settings, authService)

    @Provides
    fun provideVpnConnectionUseCase(
        session: VpnSession,
    ): VpnConnectionUseCase = VpnConnectionUseCase(session)

    @Provides
    fun provideValidateGuardWireConfigUseCase(): ValidateGuardWireConfigUseCase =
        ValidateGuardWireConfigUseCase()

    @Provides
    fun provideProvisionWireGuardUseCase(
        authService: AuthService,
        settings: VpnSettingsStore,
        profileParser: WireGuardProfileParser,
    ): ProvisionWireGuardUseCase = ProvisionWireGuardUseCase(
        authService = authService,
        settings = settings,
        profileParser = profileParser,
    )

    @Provides
    fun provideVpnSettingsUseCase(
        settings: VpnSettingsStore,
    ): VpnSettingsUseCase = VpnSettingsUseCase(settings)

    @Provides
    fun provideVpnLogsUseCase(
        logs: VpnLogRepository,
    ): VpnLogsUseCase = VpnLogsUseCase(logs)
}
