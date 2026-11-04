# GuardWire VPN

**Учебный VPN-клиент для Android.** Демонстрирует, как устроено собственное
VPN-приложение: `VpnService`, TUN-интерфейс, foreground-сервис, два режима
(учебный GuardWire/2 и реальный WireGuard), модульная архитектура и MVVM + Orbit MVI.

> **Статус: демо.** В приложении два режима:
> **«Упрощённая»** — учебный GuardWire/2 (ECDH P-256 + AES-GCM),
> **«Реальная»** — официальная библиотека WireGuard (UDP, Noise_IK).
> Оба режима не заменяют production-VPN, но реальный режим использует
> проверенный протокол. Для интернета нужен сервер с маршрутизацией.

Как перенести это в реальный проект — см. **[theory.md](theory.md)**.

## Быстрый старт

```bash
# 0. если cryptography ещё нет — создайте venv:
./tools/setup-python.sh
#    и далее используйте .venv/bin/python вместо python3

# 1. поднять полноценный тестовый VPN-сервер (Linux, нужен root)
sudo python3 tools/guardwire-linux-server.py --host 0.0.0.0 --port 8443 --token demo
# если включён ufw: sudo ufw allow 8443/tcp

# 2. собрать приложение
./gradlew :app:assembleDebug
```

Если `cryptography` уже установлен в системном Python, venv не обязателен:
проверьте `python3 -c "import cryptography"` и запускайте через `python3`.
Если пакета нет — `./tools/setup-python.sh` и `.venv/bin/python`.
Не используйте `pip install --break-system-packages`.

Телефон и ПК должны быть в одной локальной сети. Сервер напечатает LAN-адрес
компьютера — его и укажите в приложении вместе с портом `8443` и токеном
`demo`.

Для реального режима поднимите WireGuard-сервер на этом же ПК:

```bash
sudo apt install wireguard-tools
sudo ./tools/wireguard-server-setup.sh
# клиентский конфиг: ~/guardwire-wireguard/phone.conf
```

Если нужен только handshake и проверка GuardWire без маршрутизации:

```bash
python3 tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo
```

Для эмулятора host — `10.0.2.2`. Для физического устройства по USB можно
выполнить `adb reverse tcp:8443 tcp:8443` и указать host `127.0.0.1`.

---

## 1. Зачем это нужно

Проект отвечает на вопрос «как написать свой VPN-клиент под Android», не
пряча ключевые механики за библиотеками:

- как получить системное разрешение на VPN;
- как поднять TUN через `VpnService.Builder`;
- как не зациклить трафик собственного туннеля через `protect(socket)`;
- как показать постоянное уведомление foreground-сервиса;
- как отделить протокол от Android и UI;
- как организовать состояние и permission-flow через MVVM + Orbit.

### Цели

- Рабочий демонстрационный туннель: рукопожатие, передача IP-пакетов,
  keep-alive, статистика, корректное отключение.
- Читаемая модульная архитектура, которую можно использовать как скелет.
- Понятная спецификация GuardWire/2 и воспроизводимый демо-сервер.

### Не-цели

- Продакшен-VPN и сертифицированная криптография.
- Защита от всех активных атак и формальный аудит протокола.
- Обфускация трафика.
- Полноценный tun2socks на клиенте: NAT выполняет Linux-сервер.
- IPv6, split tunneling, always-on VPN, DNS leak protection.
- Публикация в Google Play.

---

## 2. Пользовательские сценарии

### 2.1. Первое подключение

1. Пользователь открывает приложение.
2. Вводит host/IP, порт и токен GuardWire-сервера.
3. Нажимает «Подключиться».
4. Приложение запрашивает разрешение на уведомления (Android 13+).
5. Приложение показывает системный диалог согласия на VPN.
6. После согласия запускается foreground-сервис, устанавливается GuardWire-
   соединение, создаётся TUN.
7. UI показывает статус «Защищено», время сессии, входящий/исходящий трафик и
   журнал событий.
8. В системном статус-баре появляется значок VPN, в шторке — уведомление с
   кнопкой «Отключить».

### 2.2. Отключение

- Кнопка «Отключиться» в приложении или действие в уведомлении.
- Сервис закрывает TUN и сокет, останавливает корутины, убирает уведомление
  и переводит состояние в «Отключено».

### 2.3. Ошибка подключения

- Сервер недоступен, handshake отклонён, TUN не создался или соединение
  оборвалось.
- UI показывает причину, сервис останавливается, VPN-интерфейс не остаётся
  в полурабочем состоянии.
- Повторное нажатие «Подключиться снова» запускает новую попытку.

### 2.4. Отзыв разрешения системой

- Пользователь отключает VPN в настройках или система отзывает разрешение.
- Вызывается `onRevoke()`, туннель закрывается, UI получает состояние
  «Ошибка: VPN-подключение отозвано системой».

### 2.5. Повторный запуск приложения

- Настройки и зашифрованные секреты сохраняются в DataStore и подставляются в форму.
- Если процесс приложения был убит, VPN-сервис также останавливается; новое
  состояние начинается с «Отключено».

---

## 3. Функциональные требования

| ID | Требование | Реализация |
|----|------------|------------|
| FR-1 | Системный запрос согласия на VPN | `VpnService.prepare()` + `ActivityResultContracts.StartActivityForResult` |
| FR-2 | Запуск VPN только после согласия | `VpnController.connect()` вызывается из `onVpnConsentResult(granted = true)` |
| FR-3 | Foreground-сервис с постоянным уведомлением | `GuardWireVpnService` и `VpnNotificationService`, `startForeground` |
| FR-4 | Создание TUN с заданными параметрами | `VpnService.Builder`: `10.8.0.2/32`, `0.0.0.0/0`, DNS `1.1.1.1`, MTU 1400 |
| FR-5 | Защита сокета туннеля от собственного TUN | `VpnService.protect(socket)` до `connect()` |
| FR-6 | Рукопожатие GuardWire/2 с PSK-аутентификацией | ECDH P-256 + HMAC-SHA256, `GuardWireClient` + `GuardWireHandshakeCodec` |
| FR-7 | Двусторонняя передача IP-пакетов | `GuardWireTunnel`: TUN → DATA-кадры и DATA → TUN |
| FR-8 | Keep-alive и обнаружение мёртвого соединения | KEEP_ALIVE каждый интервал, PING раз в два интервала, read timeout 4×интервала |
| FR-9 | Статистика трафика | `TrafficMeter` → `VpnStateStore` → Orbit state |
| FR-10 | Корректное отключение и отзыв | `shutdown()`/`onRevoke()`, закрытие TUN, сокета и корутин |
| FR-11 | Отображение ошибок | `VpnConnectionState.Failed` + snackbar для ошибок ввода |
| FR-12 | Сохранение настроек и секретов | DataStore; токен и WireGuard-конфиг шифруются Android Keystore |
| FR-13 | Stateless Compose UI | `VpnScreen` принимает состояние и колбэки |
| FR-14 | MVVM + Orbit | `VpnViewModel` с `intent` → `reduce` → `postSideEffect` |
| FR-15 | Шифрование кадров | AES-256-GCM, случайный IV, тип кадра в AAD |
| FR-16 | Реальный режим WireGuard | `com.wireguard.android:tunnel`, `WireGuardController`, UDP + Noise_IK |
| FR-17 | Отдельный экран логов | `VpnLogStore` + `VpnLogsScreen` |
| FR-18 | Запрет IPv6 | IPv4-only маршруты; IPv6 в WireGuard-конфиге отклоняется |

---

## 4. UX и состояния

### 4.1. Экран

- Заголовок `GuardWire`, бейдж `DEMO` и кнопка «Логи».
- Переключатель «Упрощённая / Реальная».
- Статус: «Отключено», «Подключение…», «Защищено», «Отключение…», «Ошибка».
- Кнопка действия: «Подключиться», «Отключиться», «Подключиться снова».
- Для упрощённого режима: host/IP, порт, PSK-токен.
- Для реального режима: зашифрованный WireGuard-профиль (address, endpoint).
- Карточка статистики: длительность, входящий/исходящий трафик, пакеты,
  endpoint и MTU.
- Журнал последних событий + отдельный экран «Логи».
- Блок «Как это работает» и предупреждение об ограничениях демо.

### 4.2. Состояния

| Состояние | Заголовок | Поля формы | Основное действие |
|-----------|-----------|------------|-------------------|
| `Disconnected` | Отключено | доступны | Подключиться |
| `Connecting` | Подключение… | заблокированы | — (кнопка disabled) |
| `Connected` | Защищено | заблокированы | Отключиться |
| `Disconnecting` | Отключение… | заблокированы | — (кнопка disabled) |
| `Failed` | Ошибка | доступны | Подключиться снова |

### 4.3. Permission-flow

```
Нажатие «Подключиться»
        │
        ├─ POST_NOTIFICATIONS не выдан (API 33+)
        │       └─ системный запрос → результат (отказ не блокирует VPN)
        │
        ├─ VpnService.prepare() == null
        │       └─ сразу запуск сервиса
        │
        └─ VpnService.prepare() != null
                └─ системный диалог VPN → OK/Cancel
                        ├─ OK → запуск сервиса
                        └─ Cancel → ошибка в UI
```

---

## 5. Архитектура

### 5.1. Модули

```
:app
 ├── :feature:vpn → :core:domain → :core:model
 ├── :core:data   → :core:domain
 └── :core:vpn    → :core:domain
                  → :core:protocol → :core:model
```

`core:domain` — общий центр контрактов; реализации лежат в `core:data`
(хранение и сеть) и `core:vpn` (Android/VpnService).

| Модуль | Назначение | Технологии |
|--------|------------|------------|
| `:app` | Composition root: `@HiltAndroidApp`, Activity, тема | Compose, Hilt |
| `:feature:vpn` | Экран, Orbit ViewModel, side effects; зависит только от контрактов и use case-ов | Compose, Orbit, Hilt |
| `:core:domain` | Контракты, доменные модели и use case-и: `VpnSession`, `AuthService`, stores, `WireGuardEngine` | Kotlin/JVM, coroutines |
| `:core:data` | DataStore + Keystore, auth HTTP-клиент, реализации состояния/логов | Android, DataStore, Hilt |
| `:core:vpn` | `VpnService`, GuardWire-туннель, WireGuard-контроллер, уведомления | Android SDK, WireGuard, Hilt |
| `:core:protocol` | GuardWire/2: кадры, handshake, ECDH/AES, TCP-клиент | Kotlin/JVM |
| `:core:model` | Модели состояния и конфигурации | Kotlin/JVM |

### 5.2. Правила зависимостей

- `:core:model` не знает про Android, Compose, `VpnService`.
- `:core:domain` не знает про Android и содержит только контракты/модели.
- `:core:protocol` знает только модели и `java.net`.
- `:core:data` реализует контракты домена через DataStore/Keystore/HTTP.
- `:core:vpn` реализует платформенные контракты через `VpnService`/WireGuard.
- `:feature:vpn` зависит от `:core:domain`, а не от реализаций.
- `:app` — Hilt entry point; реализации подключаются через `@Binds`.

### 5.3. Поток состояния

```
GuardWireVpnService / WireGuardController
        │  состояние, статистика, логи
        ▼
VpnStateStore / VpnLogStore (Hilt @Singleton, :core:data)
        │
        ▼
VpnConnectionUseCase / VpnLogsUseCase
        │
        ▼
VpnViewModel (Hilt + Orbit container)
        │  intent → reduce → postSideEffect
        ▼
VpnScreen (stateless Compose)
```

- `VpnViewModel` зависит только от use case-ов, а не от store/repository/service.
- Use case-и зависят от контрактов домена, а не от Hilt/Android.
- Сервисы зависят от `VpnStateRepository`/`VpnLogRepository`, а не от
  конкретных store-классов.
- Реализации: `VpnController` (`:core:vpn`), `AuthRepository`,
  `VpnSettingsRepository`, `VpnLogStore`, `VpnStateStore` (`:core:data`).
- `GuardWireVpnService` получает зависимости через `@AndroidEntryPoint`.
- `VpnViewModel` создаётся через `hiltViewModel()`.
- UI не пишет в state напрямую.
- Permission-диалоги и snackbar — только через `VpnSideEffect`.
- Проверки `VpnService.prepare()` и разрешения на уведомления остаются в
  ViewModel: это платформенные API, а не данные и не VPN-сервис.

### 5.4. Use cases

Сценарии лежат в `:core:domain/usecase` и не знают про Android, Hilt и Compose.

| Use case | Что делает |
|----------|------------|
| `LoadVpnSettingsUseCase` | читает настройки, проверяет срок токена, разбирает WireGuard-профиль |
| `LoginUseCase` | логин, сохранение access/refresh и WireGuard-конфига |
| `EnsureAuthSessionUseCase` | продление access через refresh до истечения сессии |
| `LogoutUseCase` | отзыв refresh на сервере, отключение и очистка локальных секретов |
| `VpnConnectionUseCase` | подключение в обоих режимах, отключение, наблюдение за состоянием |
| `ValidateGuardWireConfigUseCase` | проверка host/port/PSK и сборка `VpnServerConfig` |
| `SaveWireGuardConfigUseCase` | проверка конфига, построение профиля и сохранение |
| `VpnSettingsUseCase` | сохранение режима и GuardWire-параметров, удаление WireGuard-конфига |
| `VpnLogsUseCase` | наблюдение за журналом, запись и очистка |

ViewModel вызывает сценарии, а Hilt-модуль `VpnUseCaseModule` в
`:feature:vpn` собирает их из контрактов домена.

### 5.5. Hilt

- `GuardWireApp` помечен `@HiltAndroidApp`.
- `MainActivity` и `GuardWireVpnService` — `@AndroidEntryPoint`.
- `VpnViewModel` — `@HiltViewModel`.
- Синглтоны: `VpnStateStore`, `VpnLogStore`, `VpnController`,
  `WireGuardController`, `VpnSettingsRepository`, `AuthRepository`.
- `app`, `core:data`, `core:vpn`, `feature:vpn` используют Hilt Gradle Plugin + KSP.
- `DataBindingsModule` (`:core:data`) и `VpnBindingsModule` (`:core:vpn`)
  связывают контракты домена с реализациями (`@Binds`).
- `VpnUseCaseModule` (`:feature:vpn`) собирает use case-и из контрактов.
- Для AGP 9 built-in Kotlin в `gradle.properties` стоит
  `android.disallowKotlinSourceSets=false` — KSP добавляет сгенерированные
  исходники через `kotlin.sourceSets`.

### 5.6. Startup-гейт

При запуске нельзя сразу показывать логин: DataStore/Keystore ещё читают
сессию и WireGuard-профиль, иначе получается «рваный» переход
«логин → основной экран».

```
MainActivity
  → VpnViewModel.startupState = CHECKING
  → StartupScreen («Проверка сессии и профиля…»)
  → load DataStore + Keystore
  → access-токен валиден? ── да ──► основной экран
  → нет, refresh валиден? ── да ──► POST /auth/refresh → основной экран
  → нет/refresh истёк ─────────────► LoginScreen
```

`StartupScreen` — кастомный второй splash после системного.

### 5.7. Auth-flow

```
LoginScreen
   │ baseUrl, username, password
   ▼
VpnViewModel.onLogin
   ▼
AuthRepository ── POST /auth/login ──► guardwire-auth-server (8090)
   │                                      │ scrypt-проверка пароля
   │                                      │ access (15 мин) + refresh (30 дней)
   │                                      │ + WireGuard peer
   ◄──── access, refresh, сроки, wireguard ┘
   │
   ├─ access → DataStore (зашифрован Keystore) → PSK для GuardWire
   ├─ refresh → DataStore (зашифрован Keystore)
   └─ wireguard → конфиг с приватным ключом на устройстве → DataStore
```

При старте и перед подключением `EnsureAuthSessionUseCase` проверяет access.
Если он истёк, а refresh ещё жив, клиент вызывает `POST /auth/refresh`,
получает новую пару токенов и сохраняет её. Повторный логин нужен только
после истечения refresh.

`GuardWire`-сервер читает активный access-токен из `tools/.guardwire-token`
и использует его как PSK. Auth-сервер с `--wg-interface wg0` добавляет peer в
живой интерфейс WireGuard и возвращает параметры для конфига.

`POST /auth/logout` отзывает refresh-сессию, удаляет peer и активный
access-токен на сервере. Кнопка «Выйти» вызывает его перед локальной очисткой.

### 5.8. Сеть, reconnect и always-on

`GuardWireVpnService` регистрирует `ConnectivityManager.NetworkCallback`:

- при потере или смене сети туннель закрывается и запускается переподключение;
- попытки идут с задержкой 1, 2, 4, 8, 16 секунд;
- после 5 неудач состояние переходит в `Failed`, сервис показывает ошибку;
- при успехе счётчик попыток и таймер соединения сбрасываются.

Always-on:

```
Система (always-on VPN)
  → Intent action = android.net.VpnService
  → GuardWireVpnService.handleAlwaysOn()
  → читает сохранённый GuardWire-профиль
  → EnsureAuthSessionUseCase продлевает access при необходимости
  → поднимает туннель без Activity
```

В UI есть кнопка «Открыть настройки VPN»: Android не позволяет включить
always-on программно, пользователь выбирает GuardWire в системных настройках.
Для реального WireGuard-режима always-on не настроен — библиотека использует
собственный `VpnService`.

---

## 6. Протокол GuardWire/2

Транспорт — TCP. Все числа big-endian.

Схема:

- эфемерный ECDH P-256 для получения общего секрета;
- PSK-токен для аутентификации handshake (HMAC-SHA256);
- HKDF-SHA256 для вывода AES-256 ключа;
- AES-256-GCM для каждого кадра.

### 6.1. Рукопожатие

Клиент:

| Поле | Размер | Значение |
|------|--------|----------|
| magic | 9 | ASCII `GUARDWIRE` |
| version | 1 | `2` |
| mtu | 2 | MTU клиента |
| clientNonce | 16 | случайный nonce |
| clientPubLen | 2 | длина публичного ключа |
| clientPub | N | эфемерный ECDH P-256 ключ (X.509 DER) |
| proof | 32 | HMAC-SHA256(PSK, transcript) |

`transcript` клиента:

```
magic || version || mtu || clientNonce || clientPubLen || clientPub
```

Сервер:

| Поле | Размер | Значение |
|------|--------|----------|
| magic | 9 | ASCII `GUARDWIRE` |
| version | 1 | `2` |
| status | 1 | `0` — успех, иначе отказ |
| messageLen | 2 | длина сообщения |
| message | N | UTF-8 причина/приветствие |
| serverNonce | 16 | случайный nonce |
| serverPubLen | 2 | длина публичного ключа |
| serverPub | N | эфемерный ECDH P-256 ключ |
| proof | 32 | HMAC-SHA256(PSK, transcript) |

`transcript` сервера:

```
magic || version || status ||
clientNonce || clientPubLen || clientPub ||
serverNonce || serverPubLen || serverPub
```

После успешного handshake обе стороны вычисляют:

```
shared = ECDH(ownPrivate, peerPublic)
key    = HKDF-SHA256(
    IKM  = shared,
    salt = clientNonce || serverNonce,
    info = "GuardWire/2 session key",
    len  = 32
)
```

Свойства:

- токен **не передаётся** по сети — только HMAC-доказательство его знания;
- эфемерные ECDH-ключи дают прямую секретность (forward secrecy);
- неверный PSK или подмена ключей приводят к отказу handshake;
- сообщение и статус отказа не считаются секретными.

Таймауты:

- TCP connect — 10 секунд;
- рукопожатие — 10 секунд;
- чтение после рукопожатия — `4 × keepAlive`, но не меньше 20 секунд.

### 6.2. Кадры

```
+--------+------------------+-----------------------------+
| type   | payload length   | encrypted payload           |
| 1 байт | 2 байта (BE)     | IV (12) + ciphertext + tag  |
+--------+------------------+-----------------------------+
```

- `encrypted payload = AES-256-GCM(key, iv, plaintext, aad = type)`;
- IV — 12 случайных байт на каждый кадр;
- tag — 16 байт (128 бит);
- тип кадра передаётся в associated data, поэтому его подмена ломает GCM;
- для пустого payload шифруется только tag, кадр остаётся валидным.

| Тип | Код | Назначение |
|-----|-----|------------|
| DATA | `0x01` | целый IP-пакет из/в TUN |
| KEEP_ALIVE | `0x02` | проверка живости |
| DISCONNECT | `0x03` | штатное закрытие |
| PING | `0x04` | запрос PONG |
| PONG | `0x05` | ответ на PING |

Поведение:

- клиент отправляет KEEP_ALIVE каждый интервал;
- каждый второй интервал отправляет PING;
- сервер обязан ответить PONG;
- при `DISCONNECT`, EOF, ошибке GCM или read timeout туннель завершается;
- запись кадров синхронизирована, потому что DATA и keep-alive идут из разных
  корутин.

### 6.3. Почему не чистый RSA

RSA не используется для шифрования каждого пакета: это медленно, а размер
блока ограничен ключом. «Чистый RSA» также не даёт аутентифицированного
шифрования сам по себе.

Возможная альтернатива — RSA-OAEP для транспорта AES-ключа, но тогда нужен
стабильный публичный ключ сервера и его пиннинг в приложении. Для демо
PSK-ECDH проще: общий секрет уже есть в настройках, файлы ключей не нужны,
а эфемерный ECDH даёт forward secrecy.

---

## 7. Android-интеграция

### 7.1. TUN

- Адрес: `10.8.0.2/32`.
- Маршрут: `0.0.0.0/0` — весь IPv4-трафик.
- DNS: `1.1.1.1`.
- MTU: `1400`.
- `setBlocking(true)` — чтение TUN блокирующее.
- `setMetered(false)` на API 29+.

### 7.2. Сервис и уведомление

- `GuardWireVpnService : VpnService()`.
- `startForeground` вызывается в `onCreate`, канал `guardwire_vpn`,
  важность `IMPORTANCE_LOW`.
- Для API 34+ тип `specialUse`, permission
  `FOREGROUND_SERVICE_SPECIAL_USE`.
- В уведомлении есть действие «Отключить».
- `android:permission="android.permission.BIND_VPN_SERVICE"`,
  `android:exported="true"` — система должна иметь возможность привязаться.
- `onRevoke()` и `onDestroy()` корректно закрывают туннель.

### 7.3. Защита сокета

Перед `connect()` клиент вызывает `VpnService.protect(socket)`. Без этого
пакеты самого GuardWire-соединения попадут в TUN, и туннель зациклится.

### 7.4. Отмена подключения

Блокирующие IO-вызовы не прерываются `Job.cancel()` мгновенно. После
`GuardWireClient.connect()` и `establish()` сервис вызывает `ensureActive()`,
а при отмене закрывает сокет и TUN. Публикация состояния `Connected`
синхронизирована с флагом `stopping`, чтобы отключение во время подключения
не оставило «висящий» туннель.

---

## 8. Разрешения

| Permission | Зачем | Если отказано |
|------------|-------|---------------|
| `INTERNET` | TCP-соединение с GuardWire-сервером | подключение невозможно |
| `POST_NOTIFICATIONS` | foreground-уведомление | VPN работает, уведомление может быть скрыто |
| `FOREGROUND_SERVICE` | длительная работа сервиса | запуск сервиса невозможен |
| `FOREGROUND_SERVICE_SPECIAL_USE` | тип FGS `specialUse` | запуск на API 34+ невозможен |
| `BIND_VPN_SERVICE` | доступ системы к `VpnService` | системная привязка не произойдёт |

Согласие на VPN (`VpnService.prepare`) не является обычным runtime-permission:
его нельзя выдать из кода, только через системный диалог.

---

## 9. Сборка и запуск

### Требования

- JDK 17;
- Android SDK с `compileSdk 37`;
- Android 7.0+ (`minSdk 24`);
- Python 3 — для тестовых серверов;
- Python-пакет `cryptography`; проще всего через `./tools/setup-python.sh`
  (создаёт `.venv`), либо `sudo apt install python3-cryptography`;
- Linux, root и `iptables` — только для полноценного сервера с NAT.

### Сборка

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

### WireGuard-сервер (реальный режим)

На Linux-ПК с root:

```bash
sudo apt install wireguard-tools
sudo ./tools/wireguard-server-setup.sh
```

Скрипт создаёт `/etc/wireguard/wg0.conf`, поднимает интерфейс и кладёт
клиентский конфиг в `~/guardwire-wireguard/phone.conf` (не в репозиторий).
В приложении выберите режим **«Реальная»** и вставьте этот конфиг через
кнопку «Вставить конфиг». Конфиг с IPv6 будет отклонён.

Проверка:

```bash
sudo wg show
adb shell ping -c 2 10.66.0.1
adb shell ping -c 2 8.8.8.8
adb shell 'curl -s https://ifconfig.me; echo'
```

### Auth-сервер и вход

Для проверки только логина и GuardWire (без прав root):

```bash
python3 tools/guardwire-auth-server.py \
    --host 0.0.0.0 --port 8090 \
    --user demo:demo \
    --disable-wireguard
```

GuardWire-сервер должен читать выданный токен:

```bash
python3 tools/guardwire-demo-server.py \
    --host 0.0.0.0 --port 8443 \
    --auth-token-file tools/.guardwire-token
```

Для реального режима auth-сервер запускается с root и сам добавляет WireGuard peer:

```bash
sudo python3 tools/guardwire-auth-server.py \
    --host 0.0.0.0 --port 8090 \
    --user demo:demo \
    --wg-interface wg0 \
    --wg-endpoint 192.168.100.4:51820

# по умолчанию: access — 15 минут, refresh — 30 дней
# можно переопределить:
#   --access-ttl 900 --refresh-ttl 2592000
```

В приложении на экране входа:

- auth-сервер: `http://192.168.100.4:8090`
- логин: `demo`
- пароль: `demo`

Для упрощённого режима токен автоматически становится PSK. Для реального
режима auth-сервер выдаёт параметры WireGuard, а приватный ключ генерируется
на устройстве. Токен и WireGuard-конфиг хранятся в DataStore зашифрованными.

> Auth-сервер использует HTTP без TLS — это только для теста в доверенной
> локальной сети. В приложении для этого включён `usesCleartextTraffic`.
> Для production нужен HTTPS.

#### Auth API

`POST /auth/login`:

```json
{
  "username": "demo",
  "password": "demo",
  "device_name": "android",
  "wg_public_key": "<base64 public key>"
}
```

Ответ:

```json
{
  "access_token": "<short-lived token>",
  "refresh_token": "<long-lived token>",
  "username": "demo",
  "access_expires_at": 1793831027.89,
  "refresh_expires_at": 1796423027.89,
  "wireguard": {
    "server_public_key": "<base64>",
    "endpoint": "192.168.100.4:51820",
    "client_ip": "10.66.0.3/32",
    "dns": "1.1.1.1",
    "mtu": 1420
  }
}
```

`POST /auth/refresh`:

```json
{ "refresh_token": "<refresh token>" }
```

Возвращает новую пару `access_token` / `refresh_token`; старый refresh
инвалидируется (rotation).

`POST /auth/logout`:

```json
{ "refresh_token": "<refresh token>" }
```

Отзывает сессию, удаляет WireGuard peer и активный access-токен.

`GET /auth/health` возвращает статус, список пользователей и число сессий
без секретов.

### Полноценный тестовый сервер (Linux, TUN + NAT)

Сервер создаёт TUN `guardwire0`, включает IPv4 forwarding и NAT через
интерфейс default-маршрута. Пакеты из телефона попадают в TUN, ядро Linux
маршрутизирует их в интернет, а ответы возвращаются на телефон.

Трафик внутри TCP шифруется AES-256-GCM, а `--token` работает как PSK и по
сети не передаётся.

```bash
sudo python3 tools/guardwire-linux-server.py \
    --host 0.0.0.0 \
    --port 8443 \
    --token demo
```

Сервер напечатает LAN-адрес компьютера, например:

```
enp3s0: host=192.168.100.4  port=8443  (PSK задан)
```

Его нужно указать в приложении вместе с тем же PSK-токеном, что передан
серверу через `--token`. Телефон и ПК должны быть в одной подсети.

Что делает сервер:

- создаёт TUN `10.8.0.1/24`, MTU 1400;
- при необходимости включает `net.ipv4.ip_forward`;
- добавляет `MASQUERADE` для `10.8.0.0/24` через LAN-интерфейс;
- добавляет FORWARD-правила для трафика TUN ↔ LAN;
- при `Ctrl+C` удаляет свои правила, TUN и возвращает прежний `ip_forward`;
- принимает одного клиента за раз.

Проверить план команд без выполнения:

```bash
python3 tools/guardwire-linux-server.py --dry-run
```

Если включён `ufw`, откройте порт:

```bash
sudo ufw allow 8443/tcp
```

Требуется `/dev/net/tun` и CAP_NET_ADMIN. На Ubuntu это означает запуск через
`sudo`. Порт должен быть доступен с телефона в той же локальной сети.

> Это тестовый сервер для доверенной локальной сети. Не выставляйте его в
> интернет: протокол учебный и не проходил аудит, а PSK хранится в настройках
> приложения. Трафик шифруется, но это не production-VPN.

### Демо-сервер без маршрутизации

Если нужен только handshake, зашифрованные кадры и проверка UI без реального
интернета:

```bash
python3 tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo
```

Сервер выполняет GuardWire/2 handshake, расшифровывает кадры, отвечает на PING
и пишет статистику. IP-пакеты он **не маршрутизирует**.

### Эмулятор

В приложении:

- host: `10.0.2.2`
- port: `8443`
- token: `demo`

### Физическое устройство по USB

```bash
adb reverse tcp:8443 tcp:8443
```

В приложении: host `127.0.0.1`, port `8443`, token `demo`.

### Физическое устройство по локальной сети

1. Запустите полноценный сервер на ПК.
2. Возьмите LAN-IP из его вывода, например `192.168.100.4`.
3. В приложении укажите этот IP, порт `8443` и токен `demo`.
4. Разрешите входящий TCP `8443` в firewall/ufw.

> С демо-сервером без NAT интернет на телефоне пропадёт до отключения VPN:
> маршрут `0.0.0.0/0` забирает трафик, а сервер его отбрасывает. С
> полноценным Linux-сервером трафик идёт через ПК и NAT и интернет работает.

---

## 10. Тестирование

### Автоматические тесты

```bash
./gradlew :core:domain:test :core:protocol:test
```

Покрыто:

- use case-и: валидация GuardWire-конфига, загрузка/продление сессии, logout;
- кодирование/декодирование кадров;
- пустой payload;
- обрезанные данные;
- неизвестный тип кадра;
- рукопожатие: успех, отказ, неверный PSK;
- ECDH P-256 + HKDF + AES-256-GCM: обмен ключами, шифрование кадров,
  отказ при подмене типа кадра;
- интеграционный тест клиента с локальным `ServerSocket`:
  encrypted handshake → DATA → PING → PONG.

### Ручной чек-лист

- [ ] Первый запуск: показан экран «Отключено».
- [ ] Отказ от уведомлений не блокирует VPN.
- [ ] Отказ от системного VPN-диалога показывает ошибку.
- [ ] Согласие запускает foreground-сервис и показывает статус «Защищено».
- [ ] Статистика и время обновляются.
- [ ] «Отключиться» убирает уведомление и возвращает трафик.
- [ ] Кнопка «Отключить» в уведомлении работает.
- [ ] Недоступный сервер даёт понятную ошибку.
- [ ] Отзыв VPN в настройках переводит UI в состояние ошибки.
- [ ] После поворота экрана состояние не теряется.

### Проверено вручную

- Устройства: Samsung SM-A525F (Android 14) и Xiaomi 21121119SG (Android 13).
- Пройдены permission-диалоги, подключение к демо-серверу, TUN, статистика,
  отключение и сценарий ошибки.
- Hilt: `@HiltAndroidApp`, `@AndroidEntryPoint` Activity/VpnService,
  `@HiltViewModel`, внедрение синглтонов и сервисов проверено на устройстве.
- Startup-гейт: при валидной сессии логин не мелькает, сразу основной экран.
- Auth: вход `demo/demo`, access + refresh, продление сессии при истёкшем
  access и серверный logout с удалением токена проверены.
- Reconnect: при выключении/включении Wi-Fi сервис сделал несколько попыток
  с backoff и восстановил туннель.
- WireGuard provisioning: auth-сервер под root добавил peer `10.66.0.3`,
  приложение получило конфиг и реальный режим прошёл ping/curl.
- Linux-сервер: создание TUN, handshake, PING/PONG, прохождение ICMP-пакета
  через TUN и корректный cleanup проверены в изолированном network namespace.
- Полный LAN-сценарий с NAT не запускался автоматически: для него нужен
  `sudo` и открытый входящий порт.
- Проверена зашифрованная связка: Android-клиент ↔ Python demo-сервер
  (ECDH + AES-GCM) по локальной сети.
- Linux-сервер с шифрованием: handshake, зашифрованный ICMP через TUN,
  правила NAT и cleanup проверены в network namespace.
- Реальный режим WireGuard проверен на телефоне: handshake, ping 10.66.0.1,
  ping 8.8.8.8, DNS и curl через туннель; конфиг сохраняется в DataStore.

---

## 11. Ограничения и безопасность

- **Криптография учебная.** ECDH + AES-GCM реализованы корректно по базовым
  принципам, но протокол не проходил аудит; не используйте его для реальной
  защиты данных.
- **Один активный PSK на сессию.** Access-токен ротируется при refresh, logout
  отзывает сессию и peer, но отдельных ключей на каждое устройство нет.
- **Нет tun2socks.** Клиент не разбирает TCP/UDP и не проксирует их в сокеты;
  сервер должен сам маршрутизировать IP-пакеты.
- **IPv6 запрещён.** Маршрутизируется только IPv4; IPv6-конфиг отклоняется.
- **Нет split tunneling и DNS leak protection.**
- **Always-on поддержан для GuardWire.** Системный always-on поднимает
  `GuardWireVpnService` и берёт сохранённый профиль. Для реального
  WireGuard-режима always-on не настроен: библиотека использует свой
  `VpnService`.
- **Секреты в DataStore.** Access/refresh-токены и WireGuard-конфиг
  шифруются ключом Android Keystore; PSK не показывается в UI. `allowBackup`
  отключён, но общего аудита и ротации ключей нет.
- **Auth-сервер использует HTTP без TLS.** Только для доверенной тестовой сети;
  в приложении включён `usesCleartextTraffic`.
- **Один активный refresh и один peer на пользователя.** Повторный логин
  заменяет предыдущий; несколько устройств одновременно не поддерживаются.
- **Logout best-effort.** Если сервер недоступен, локальные токены удаляются,
  но refresh на сервере живёт до истечения TTL.
- **WireGuard peer добавляется в живой `wg0`, но не в `/etc/wireguard/wg0.conf`.**
  После перезагрузки ПК нужен повторный вход.
- **Refresh-токен хранится в DataStore.** На сервере хранится только его
  SHA-256; при logout/истечении нужен повторный вход.
- **Hilt-синглтоны живут в процессе.** Это нормально для приложения, но
  для фоновых/удалённых процессов нужен отдельный scoped-граф.
- **`specialUse` FGS type.** Для Google Play требуется декларация назначения и
  соблюдение политики.
- **Маршрут по умолчанию.** Реальное приложение должно продумать маршруты,
  исключения приложений и защиту от утечек.

---

## 12. Возможные улучшения

- Аудит GuardWire/2 и/или переход на TLS/Noise с проверенным стеком.
- Ротация PSK, несколько пользователей и отзыв доступа.
- Полноценный tun2socks с разбором TCP/UDP.
- IPv6 и управление маршрутами.
- Split tunneling и `addDisallowedApplication`.
- Always-on для реального WireGuard-режима.
- Блокировка трафика при падении туннеля (kill switch) через always-on.
- Серверный control plane с БД и несколькими устройствами.
- Аудит криптографии и ротация ключей.
- Тесты ViewModel с Orbit test-раннером.
- CI: сборка, unit-тесты, lint.

---

## 13. Карта файлов

| Файл | Что показывает |
|------|----------------|
| [GuardWireVpnService.kt](core/vpn/src/main/kotlin/com/haruchka/testvpnapp/core/vpn/GuardWireVpnService.kt) | `VpnService`, TUN, always-on, reconnect, foreground-уведомление |
| [GuardWireTunnel.kt](core/vpn/src/main/kotlin/com/haruchka/testvpnapp/core/vpn/GuardWireTunnel.kt) | TUN ↔ GuardWire, keep-alive, счётчики |
| [VpnSession.kt](core/domain/src/main/kotlin/com/haruchka/testvpnapp/core/domain/VpnSession.kt) | контракт фасада VPN для UI |
| [AuthService.kt](core/domain/src/main/kotlin/com/haruchka/testvpnapp/core/domain/AuthService.kt) | контракт auth/control-plane |
| [VpnSettingsStore.kt](core/domain/src/main/kotlin/com/haruchka/testvpnapp/core/domain/VpnSettingsStore.kt) | контракт хранения настроек |
| [VpnController.kt](core/vpn/src/main/kotlin/com/haruchka/testvpnapp/core/vpn/VpnController.kt) | реализация фасада: запуск, остановка |
| [LoadVpnSettingsUseCase.kt](core/domain/src/main/kotlin/com/haruchka/testvpnapp/core/domain/usecase/LoadVpnSettingsUseCase.kt) | загрузка настроек и проверка сессии |
| [LoginUseCase.kt](core/domain/src/main/kotlin/com/haruchka/testvpnapp/core/domain/usecase/LoginUseCase.kt) | вход и сохранение выданного профиля |
| [EnsureAuthSessionUseCase.kt](core/domain/src/main/kotlin/com/haruchka/testvpnapp/core/domain/usecase/EnsureAuthSessionUseCase.kt) | продление access через refresh |
| [VpnConnectionUseCase.kt](core/domain/src/main/kotlin/com/haruchka/testvpnapp/core/domain/usecase/VpnConnectionUseCase.kt) | подключение/отключение и состояние |
| [VpnLogsUseCase.kt](core/domain/src/main/kotlin/com/haruchka/testvpnapp/core/domain/usecase/VpnLogsUseCase.kt) | журнал приложения |
| [VpnUseCaseModule.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/di/VpnUseCaseModule.kt) | Hilt-сборка use case-ов |
| [VpnBindingsModule.kt](core/vpn/src/main/kotlin/com/haruchka/testvpnapp/core/vpn/di/VpnBindingsModule.kt) | Hilt @Binds платформенных реализаций |
| [DataBindingsModule.kt](core/data/src/main/kotlin/com/haruchka/testvpnapp/core/data/di/DataBindingsModule.kt) | Hilt @Binds data-слоя |
| [WireGuardController.kt](core/vpn/src/main/kotlin/com/haruchka/testvpnapp/core/vpn/WireGuardController.kt) | реальный режим на библиотеке WireGuard |
| [VpnSettingsRepository.kt](core/data/src/main/kotlin/com/haruchka/testvpnapp/core/data/VpnSettingsRepository.kt) | DataStore + Keystore для секретов |
| [AuthRepository.kt](core/data/src/main/kotlin/com/haruchka/testvpnapp/core/data/AuthRepository.kt) | HTTP-клиент auth-сервера |
| [VpnLogStore.kt](core/data/src/main/kotlin/com/haruchka/testvpnapp/core/data/VpnLogStore.kt) | общий журнал событий |
| [VpnStateStore.kt](core/data/src/main/kotlin/com/haruchka/testvpnapp/core/data/VpnStateStore.kt) | единый источник состояния |
| [GuardWireClient.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireClient.kt) | TCP-подключение, `protect(socket)`, handshake |
| [GuardWireCrypto.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireCrypto.kt) | ECDH, HKDF, HMAC, AES-GCM |
| [GuardWireFrameCodec.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireFrameCodec.kt) | формат кадров |
| [GuardWireHandshakeCodec.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireHandshakeCodec.kt) | формат рукопожатия |
| [VpnViewModel.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/VpnViewModel.kt) | Hilt + Orbit: intents → use cases → reduce → side effects |
| [LoginScreen.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/LoginScreen.kt) | вход по логину/паролю |
| [VpnScreen.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/VpnScreen.kt) | stateless Compose UI |
| [VpnLogsScreen.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/VpnLogsScreen.kt) | отдельный экран логов |
| [StartupScreen.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/StartupScreen.kt) | startup-гейт до логина/основного экрана |
| [GuardWireApp.kt](app/src/main/java/com/haruchka/testvpnapp/GuardWireApp.kt) | `@HiltAndroidApp` |
| [MainActivity.kt](app/src/main/java/com/haruchka/testvpnapp/MainActivity.kt) | `@AndroidEntryPoint`, Compose-экран |
| [guardwire-demo-server.py](tools/guardwire-demo-server.py) | минимальный сервер для проверки handshake и кадров |
| [guardwire_crypto.py](tools/guardwire_crypto.py) | серверная и клиентская криптография GuardWire/2 |
| [guardwire-auth-server.py](tools/guardwire-auth-server.py) | логин/пароль, токены и WireGuard provisioning |
| [guardwire-linux-server.py](tools/guardwire-linux-server.py) | Linux TUN + NAT: реальный интернет через VPN |
| [wireguard-server-setup.sh](tools/wireguard-server-setup.sh) | разовая настройка тестового WireGuard-сервера |
| [theory.md](theory.md) | пошаговый путь от демо к production-VPN |
