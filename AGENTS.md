# AGENTS.md

## Что это за репозиторий

`GuardWire VPN` — учебное Android-приложение, демонстрирующее собственный VPN-клиент:
`VpnService` + TUN, foreground-сервис, протокол GuardWire/1, модульная архитектура и MVVM + Orbit MVI.

Человеческая спецификация: `README.md`.

## Стек и окружение

- Kotlin 2.2.10, AGP 9.2.1, Gradle 9.4.1, JDK 17.
- Jetpack Compose (BOM 2026.02.01), Orbit MVI 12.0.1.
- `compileSdk 37`, `targetSdk 36`, `minSdk 24`.
- Модули: `:app`, `:feature:vpn`, `:core:vpn`, `:core:protocol`, `:core:model`.
- `:core:model` и `:core:protocol` — чистый Kotlin/JVM, без Android API.

## Команды

- Сборка debug: `./gradlew :app:assembleDebug`
- Unit-тесты протокола: `./gradlew :core:protocol:test`
- Lint: `./gradlew :app:lintDebug`
- Установка: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Демо-сервер: `python3 tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo`
- Для физического устройства по USB: `adb reverse tcp:8443 tcp:8443`, затем host `127.0.0.1`.

## Архитектура

- Направление зависимостей: `app → feature:vpn → core:vpn → core:protocol → core:model`.
- Не протаскивай Android/Compose/`VpnService` в нижние слои и не ломай направление зависимостей.
- Поток состояния: `GuardWireVpnService → VpnStateStore.shared → VpnController.state → VpnViewModel (Orbit) → Compose`.
- Permission-диалоги, snackbar и навигационные события — только через `VpnSideEffect`.
- Compose-экран должен оставаться stateless; бизнес-логика и валидация — в `VpnViewModel`.
- Новые модули/фичи подключай через `settings.gradle.kts` и `gradle/libs.versions.toml`.

## Инварианты

- Сокет туннеля обязан проходить через `VpnService.protect()` **до** `connect()`, иначе трафик попадёт в собственный TUN.
- `GuardWireVpnService` обязан вызывать `startForeground` в `onCreate`/`onStartCommand`; для API 34+ используется тип `specialUse` и permission `FOREGROUND_SERVICE_SPECIAL_USE`.
- Сервис объявлен с `android:permission="android.permission.BIND_VPN_SERVICE"` и `exported="true"`, чтобы система могла к нему привязаться.
- Запись GuardWire-кадров синхронизирована: DATA и keep-alive/PING могут идти из разных корутин.
- При изменении формата кадров/рукопожатия обновляй `GuardWireCodecTest` и `GuardWireClientTest`.
- При отмене подключения проверяй `ensureActive()` после блокирующих IO-вызовов и закрывай сокет/TUN.
- UI-тексты и комментарии — на русском; имена кода — на английском.

## Безопасность

- Не коммить `local.properties`, `.idea/`, `build/`, `*.jks`, `*.keystore` и файлы подписи.
- GuardWire — учебный протокол без шифрования. Не выдавай его за production-VPN.
- Токен в SharedPreferences — осознанное упрощение демо; для реального приложения используй Keystore.

## Проверки перед завершением задачи

1. `./gradlew :core:protocol:test :app:assembleDebug`
2. При изменениях UI/сервиса — `./gradlew :app:lintDebug`
3. При изменениях permission/FGS/VPN — ручная проверка на API 34+.
4. Убедись, что не появились новые untracked-артефакты сборки и IDE.
