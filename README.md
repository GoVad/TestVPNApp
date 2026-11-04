# GuardWire VPN

**Учебный VPN-клиент для Android.** Демонстрирует, как устроено собственное
VPN-приложение: `VpnService`, TUN-интерфейс, foreground-сервис, собственный
протокол поверх TCP, модульная архитектура и MVVM + Orbit MVI.

> **Статус: демо.** GuardWire/1 — учебный протокол из этого репозитория, а не
> промышленный стандарт. Приложение не шифрует трафик и не выполняет NAT,
> поэтому не предназначено для реальной защиты соединения.

## Быстрый старт

```bash
# 1. поднять демо-сервер
python3 tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo

# 2. собрать приложение
./gradlew :app:assembleDebug
```

Затем в приложении: **host** `10.0.2.2`, **port** `8443`, **token** `demo` для
эмулятора. Для физического устройства по USB выполните
`adb reverse tcp:8443 tcp:8443` и укажите host `127.0.0.1`. Демо-сервер не
маршрутизирует IP-пакеты, поэтому интернет через туннель не появится — это
ожидаемо.

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
- Понятная спецификация GuardWire/1 и воспроизводимый демо-сервер.

### Не-цели

- Продакшен-VPN и защита трафика.
- Шифрование, аутентификация трафика, обфускация.
- Полноценный tun2socks/NAT и реальный выход в интернет.
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

- Настройки сервера сохраняются в `SharedPreferences` и подставляются в форму.
- Если процесс приложения был убит, VPN-сервис также останавливается; новое
  состояние начинается с «Отключено».

---

## 3. Функциональные требования

| ID | Требование | Реализация |
|----|------------|------------|
| FR-1 | Системный запрос согласия на VPN | `VpnService.prepare()` + `ActivityResultContracts.StartActivityForResult` |
| FR-2 | Запуск VPN только после согласия | `VpnController.connect()` вызывается из `onVpnConsentResult(granted = true)` |
| FR-3 | Foreground-сервис с постоянным уведомлением | `GuardWireVpnService`, `startForeground`, канал `guardwire_vpn` |
| FR-4 | Создание TUN с заданными параметрами | `VpnService.Builder`: `10.8.0.2/32`, `0.0.0.0/0`, DNS `1.1.1.1`, MTU 1400 |
| FR-5 | Защита сокета туннеля от собственного TUN | `VpnService.protect(socket)` до `connect()` |
| FR-6 | Рукопожатие GuardWire/1 | `GuardWireClient` + `GuardWireHandshakeCodec` |
| FR-7 | Двусторонняя передача IP-пакетов | `GuardWireTunnel`: TUN → DATA-кадры и DATA → TUN |
| FR-8 | Keep-alive и обнаружение мёртвого соединения | KEEP_ALIVE каждый интервал, PING раз в два интервала, read timeout 4×интервала |
| FR-9 | Статистика трафика | `TrafficMeter` → `VpnStateStore` → Orbit state |
| FR-10 | Корректное отключение и отзыв | `shutdown()`/`onRevoke()`, закрытие TUN, сокета и корутин |
| FR-11 | Отображение ошибок | `VpnConnectionState.Failed` + snackbar для ошибок ввода |
| FR-12 | Сохранение настроек сервера | `VpnSettingsRepository` на `SharedPreferences` |
| FR-13 | Stateless Compose UI | `VpnScreen` принимает состояние и колбэки |
| FR-14 | MVVM + Orbit | `VpnViewModel` с `intent` → `reduce` → `postSideEffect` |

---

## 4. UX и состояния

### 4.1. Экран

- Заголовок `GuardWire` и бейдж `DEMO`.
- Круглая кнопка питания: цвет и свечение зависят от состояния.
- Статус: «Отключено», «Подключение…», «Защищено», «Отключение…», «Ошибка».
- Кнопка действия: «Подключиться», «Отключиться», «Подключиться снова».
- Карточка сервера: host/IP, порт, токен (с переключателем видимости).
- Карточка статистики: длительность, входящий/исходящий трафик, пакеты,
  endpoint и MTU.
- Журнал последних событий.
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
 └── :feature:vpn
      └── :core:vpn
           └── :core:protocol
                └── :core:model
```

| Модуль | Назначение | Технологии |
|--------|------------|------------|
| `:app` | Activity, тема, ручной DI-граф | Compose, Activity |
| `:feature:vpn` | Экран, Orbit ViewModel, side effects | Compose, Orbit |
| `:core:vpn` | `VpnService`, TUN-мост, контроллер, настройки | Android SDK |
| `:core:protocol` | GuardWire/1: кадры, handshake, TCP-клиент | Kotlin/JVM |
| `:core:model` | Модели состояния и конфигурации | Kotlin/JVM |

### 5.2. Правила зависимостей

- `:core:model` не знает про Android, Compose, `VpnService`.
- `:core:protocol` знает только модели и `java.net`.
- `:core:vpn` не знает про Compose и Orbit.
- `:feature:vpn` не знает, как устроен TUN.
- `:app` — единственное место сборки графа объектов.

### 5.3. Поток состояния

```
GuardWireVpnService
        │  состояние, статистика
        ▼
VpnStateStore.shared (StateFlow)
        │
        ▼
VpnController.state
        │
        ▼
VpnViewModel (Orbit container)
        │  intent → reduce → postSideEffect
        ▼
VpnScreen (stateless Compose)
```

- `VpnStateStore.shared` — осознанное упрощение демо вместо bound-сервиса/DI.
- UI не пишет в store напрямую.
- Permission-диалоги и snackbar — только через `VpnSideEffect`.

---

## 6. Протокол GuardWire/1

Транспорт — TCP. Все числа big-endian.

### 6.1. Рукопожатие

Клиент:

| Поле | Размер | Значение |
|------|--------|----------|
| magic | 9 | ASCII `GUARDWIRE` |
| version | 1 | `1` |
| token length | 2 | длина токена |
| token | N | UTF-8 токен |
| mtu | 2 | MTU клиента |

Сервер:

| Поле | Размер | Значение |
|------|--------|----------|
| magic | 9 | ASCII `GUARDWIRE` |
| version | 1 | `1` |
| status | 1 | `0` — успех, иначе отказ |
| message length | 2 | длина сообщения |
| message | N | UTF-8 причина/приветствие |

Таймауты:

- TCP connect — 10 секунд;
- рукопожатие — 10 секунд;
- чтение после рукопожатия — `4 × keepAlive`, но не меньше 20 секунд.

### 6.2. Кадры

```
+--------+------------------+-----------------+
| type   | payload length   | payload         |
| 1 байт | 2 байта (BE)     | N байт          |
+--------+------------------+-----------------+
```

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
- при `DISCONNECT`, EOF или read timeout туннель считается завершённым;
- запись кадров синхронизирована, потому что DATA и keep-alive идут из разных
  корутин.

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
- Python 3 — только для демо-сервера.

### Сборка

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

### Демо-сервер

```bash
python3 tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo
```

Сервер выполняет handshake, читает кадры, отвечает на PING и пишет статистику.
IP-пакеты он **не маршрутизирует**.

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

> Во время активного туннеля маршрут `0.0.0.0/0` забирает весь трафик
> устройства, а демо-сервер его отбрасывает. Интернет на телефоне пропадёт до
> отключения VPN. Это ожидаемое поведение демо.

---

## 10. Тестирование

### Автоматические тесты

```bash
./gradlew :core:protocol:test
```

Покрыто:

- кодирование/декодирование кадров;
- пустой payload;
- обрезанные данные;
- неизвестный тип кадра;
- рукопожатие: успех, отказ, чужая сигнатура;
- интеграционный тест клиента с локальным `ServerSocket`:
  handshake → DATA → PING → PONG.

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

- Устройство: Samsung SM-A525F, Android 14.
- Пройдены permission-диалоги, подключение к демо-серверу, TUN, статистика,
  отключение и сценарий ошибки.

---

## 11. Ограничения и безопасность

- **Нет шифрования.** Токен — идентификатор, а не защита трафика.
- **Нет tun2socks.** Клиент не разбирает TCP/UDP и не проксирует их в сокеты;
  сервер должен сам маршрутизировать IP-пакеты.
- **Нет IPv6, split tunneling, always-on VPN, DNS leak protection.**
- **Токен хранится в обычных `SharedPreferences`.** Для продакшена нужен
  Keystore/EncryptedSharedPreferences.
- **`VpnStateStore.shared` — глобальный объект.** Для демо достаточно; в
  большом приложении состояние лучше отдавать через bound-сервис/DI.
- **`specialUse` FGS type.** Для Google Play требуется декларация назначения и
  соблюдение политики.
- **Маршрут по умолчанию.** Реальное приложение должно продумать маршруты,
  исключения приложений и защиту от утечек.

---

## 12. Возможные улучшения

- TLS или Noise-подобный handshake, шифрование payload.
- Полноценный tun2socks с разбором TCP/UDP.
- IPv6 и управление маршрутами.
- Split tunneling и `addDisallowedApplication`.
- Always-on VPN и блокировка при падении туннеля.
- Keystore/EncryptedSharedPreferences для токена и профилей.
- Bound-сервис или DI (Hilt/Koin) вместо глобального store.
- Тесты ViewModel с Orbit test-раннером.
- CI: сборка, unit-тесты, lint.

---

## 13. Карта файлов

| Файл | Что показывает |
|------|----------------|
| [GuardWireVpnService.kt](core/vpn/src/main/kotlin/com/haruchka/testvpnapp/core/vpn/GuardWireVpnService.kt) | наследование `VpnService`, TUN, foreground-уведомление |
| [GuardWireTunnel.kt](core/vpn/src/main/kotlin/com/haruchka/testvpnapp/core/vpn/GuardWireTunnel.kt) | TUN ↔ GuardWire, keep-alive, счётчики |
| [VpnController.kt](core/vpn/src/main/kotlin/com/haruchka/testvpnapp/core/vpn/VpnController.kt) | фасад для UI: consent, запуск, остановка |
| [VpnStateStore.kt](core/vpn/src/main/kotlin/com/haruchka/testvpnapp/core/vpn/VpnStateStore.kt) | единый источник состояния |
| [GuardWireClient.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireClient.kt) | TCP-подключение, `protect(socket)`, handshake |
| [GuardWireFrameCodec.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireFrameCodec.kt) | формат кадров |
| [GuardWireHandshakeCodec.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireHandshakeCodec.kt) | формат рукопожатия |
| [VpnViewModel.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/VpnViewModel.kt) | Orbit: intents → reduce → side effects |
| [VpnScreen.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/VpnScreen.kt) | stateless Compose UI |
| [MainActivity.kt](app/src/main/java/com/haruchka/testvpnapp/MainActivity.kt) | сборка экрана и DI-графа |
| [guardwire-demo-server.py](tools/guardwire-demo-server.py) | минимальный сервер для проверки |
