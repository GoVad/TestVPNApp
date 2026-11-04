# AGENTS.md

## Что это за репозиторий

`GuardWire VPN` — учебное Android-приложение, демонстрирующее собственный VPN-клиент:
`VpnService` + TUN, GuardWire/2 и WireGuard, foreground-сервис, модульная архитектура, Hilt и MVVM + Orbit MVI.

Спецификация: `README.md`; путь к production: `theory.md`.

## Стек и окружение

- Kotlin 2.2.10, AGP 9.2.1, Gradle 9.4.1, JDK 17.
- Compose (BOM 2026.02.01), Orbit 12.0.1, WireGuard 1.0.20260102, Hilt 2.60.1 + KSP.
- `compileSdk 37`, `targetSdk 36`, `minSdk 24`.
- Модули: `:app`, `:feature:vpn`, `:core:domain`, `:core:data`, `:core:vpn`, `:core:protocol`, `:core:model`.
- `:core:model`, `:core:protocol` и `:core:domain` — чистый Kotlin/JVM, без Android API.

## Команды

- Сборка/тесты/lint: `./gradlew :app:assembleDebug :core:domain:test :core:protocol:test :app:lintDebug`
- Установка: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Auth: `python3 tools/guardwire-auth-server.py --port 8090 --user demo:demo --disable-wireguard` (для WireGuard — sudo + `--wg-interface wg0`). Access TTL 15 мин, refresh TTL 30 дней; logout отзывает сессию и peer.
- Демо-сервер без NAT: `python3 tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo`
- Полный сервер с TUN+NAT (Linux, root): `sudo python3 tools/guardwire-linux-server.py --host 0.0.0.0 --port 8443 --token demo`
- Python: `./tools/setup-python.sh` (venv + cryptography); WireGuard: `sudo ./tools/wireguard-server-setup.sh`.

## Архитектура

- Направление зависимостей: `app → feature:vpn → core:domain → core:model`;
  `core:data → core:domain`; `core:vpn → core:domain, core:protocol → core:model`.
- `core:domain` — только Kotlin-контракты и доменные модели, без Android API.
- `core:data` — DataStore/Keystore, auth-клиент и реализации store-контрактов.
- `core:vpn` — только платформа: `VpnService`, TUN, WireGuard, уведомления.
- Не протаскивай Android/Compose/`VpnService` в нижние слои и не ломай направление зависимостей.
- Поток состояния: `GuardWireVpnService` / `WireGuardController` → Hilt `VpnStateStore` → `VpnConnectionUseCase.observeState()` → `VpnViewModel` → Compose.
- `GuardWireVpnService` переподключается при смене/потере сети (backoff 1–16 с) и поддерживает системный always-on через action `android.net.VpnService`.
- Access-токен короткий, refresh — длинный; `EnsureAuthSessionUseCase` продлевает сессию до подключения.
- Permission-диалоги, snackbar и навигационные события — только через `VpnSideEffect`.
- UI зависит от контрактов (`VpnSession`, `AuthService`, `VpnSettingsStore`) и use case-ов, Hilt связывает реализации через `@Binds`; Compose stateless, логика в ViewModel.
- `VpnViewModel` не вызывает store/repository/service напрямую: только use case-и из `core:domain/usecase`.
- Use case-и собираются Hilt-модулем `VpnUseCaseModule` в `:feature:vpn`; домен остаётся без Hilt/Android.
- Новые модули/фичи подключай через `settings.gradle.kts` и `gradle/libs.versions.toml`.

## Инварианты

- Сокет туннеля обязан проходить через `VpnService.protect()` **до** `connect()`, иначе трафик попадёт в собственный TUN.
- `GuardWireVpnService` обязан вызывать `startForeground` в `onCreate`/`onStartCommand`; для API 34+ используется тип `specialUse` и permission `FOREGROUND_SERVICE_SPECIAL_USE`.
- VPN-сервис объявлен с `android:permission="android.permission.BIND_VPN_SERVICE"`; `GuardWireVpnService` — `@AndroidEntryPoint`, библиотечный WireGuard-сервис не переопределяй.
- Hilt: `@HiltAndroidApp`, `@HiltViewModel`, `@AndroidEntryPoint`; KSP требует `android.disallowKotlinSourceSets=false` для AGP 9.
- Запись GuardWire-кадров синхронизирована; payload шифруется AES-256-GCM, тип кадра идёт в AAD, IV случайный на кадр.
- Токен — это PSK для HMAC-аутентификации handshake; не передавай и не логируй его.
- При изменении handshake/криптографии синхронно правь Kotlin и Python-серверы, обновляй `GuardWireCodecTest`, `GuardWireCryptoTest`, `GuardWireClientTest`.
- При отмене подключения проверяй `ensureActive()` после блокирующих IO-вызовов и закрывай сокет/TUN.
- IPv6 намеренно не маршрутизируется; WireGuard-конфиг с IPv6 отклоняется.
- UI-тексты и комментарии — на русском; имена кода — на английском.

## Безопасность

- Не коммить `local.properties`, `.idea/`, `build/`, `*.jks`, `*.keystore` и файлы подписи.
- GuardWire/2 использует ECDH P-256 + AES-256-GCM, но это учебная схема без аудита. Не выдавай её за production-VPN.
- PSK и WireGuard-конфиг шифруются ключом Android Keystore и лежат в DataStore; не логируй их.

## Проверки перед завершением задачи

1. `./gradlew :core:domain:test :core:protocol:test :app:assembleDebug`
2. При изменениях UI/сервиса — `./gradlew :app:lintDebug`
3. При изменениях permission/FGS/VPN — ручная проверка на API 34+.
4. Убедись, что не появились новые untracked-артефакты сборки и IDE.
