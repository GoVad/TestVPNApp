# GuardWire VPN

**Учебный VPN-клиент для Android.** Демонстрирует, как устроено собственное
VPN-приложение: `VpnService`, TUN-интерфейс, foreground-сервис, собственный
протокол поверх TCP, модульная архитектура и MVVM + Orbit MVI.

> **Статус: демо.** GuardWire/2 — учебный протокол из этого репозитория, а не
> промышленный стандарт. Трафик шифруется ECDH P-256 + AES-256-GCM, но схема
> не проходила аудит. Для реального интернета нужен сервер с маршрутизацией:
> в комплекте есть Linux TUN + NAT.

Как перенести это в реальный проект — см. **[theory.md](theory.md)**.

## Быстрый старт

```bash
# 0. один раз: venv и зависимости Python-серверов
./tools/setup-python.sh

# 1. поднять полноценный тестовый VPN-сервер (Linux, нужен root)
sudo .venv/bin/python tools/guardwire-linux-server.py --host 0.0.0.0 --port 8443 --token demo
# если включён ufw: sudo ufw allow 8443/tcp

# 2. собрать приложение
./gradlew :app:assembleDebug
```

Если `cryptography` уже установлен в системном Python, venv не обязателен:
проверьте `python3 -c "import cryptography"` и запускайте через `python3`.
Не используйте `pip install --break-system-packages` — лучше venv.

Телефон и ПК должны быть в одной локальной сети. Сервер напечатает LAN-адрес
компьютера — его и укажите в приложении вместе с портом `8443` и токеном
`demo`.

Если нужен только handshake и проверка кадров без маршрутизации в интернет:

```bash
.venv/bin/python tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo
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
| FR-6 | Рукопожатие GuardWire/2 с PSK-аутентификацией | ECDH P-256 + HMAC-SHA256, `GuardWireClient` + `GuardWireHandshakeCodec` |
| FR-7 | Двусторонняя передача IP-пакетов | `GuardWireTunnel`: TUN → DATA-кадры и DATA → TUN |
| FR-8 | Keep-alive и обнаружение мёртвого соединения | KEEP_ALIVE каждый интервал, PING раз в два интервала, read timeout 4×интервала |
| FR-9 | Статистика трафика | `TrafficMeter` → `VpnStateStore` → Orbit state |
| FR-10 | Корректное отключение и отзыв | `shutdown()`/`onRevoke()`, закрытие TUN, сокета и корутин |
| FR-11 | Отображение ошибок | `VpnConnectionState.Failed` + snackbar для ошибок ввода |
| FR-12 | Сохранение настроек сервера | `VpnSettingsRepository` на `SharedPreferences` |
| FR-13 | Stateless Compose UI | `VpnScreen` принимает состояние и колбэки |
| FR-14 | MVVM + Orbit | `VpnViewModel` с `intent` → `reduce` → `postSideEffect` |
| FR-15 | Шифрование кадров | AES-256-GCM, случайный IV, тип кадра в AAD |

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
| `:core:protocol` | GuardWire/2: кадры, handshake, ECDH/AES, TCP-клиент | Kotlin/JVM |
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

### Полноценный тестовый сервер (Linux, TUN + NAT)

Сервер создаёт TUN `guardwire0`, включает IPv4 forwarding и NAT через
интерфейс default-маршрута. Пакеты из телефона попадают в TUN, ядро Linux
маршрутизирует их в интернет, а ответы возвращаются на телефон.

Трафик внутри TCP шифруется AES-256-GCM, а `--token` работает как PSK и по
сети не передаётся.

```bash
sudo .venv/bin/python tools/guardwire-linux-server.py \
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
.venv/bin/python tools/guardwire-demo-server.py --host 0.0.0.0 --port 8443 --token demo
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
./gradlew :core:protocol:test
```

Покрыто:

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

- Устройство: Samsung SM-A525F, Android 14.
- Пройдены permission-диалоги, подключение к демо-серверу, TUN, статистика,
  отключение и сценарий ошибки.
- Linux-сервер: создание TUN, handshake, PING/PONG, прохождение ICMP-пакета
  через TUN и корректный cleanup проверены в изолированном network namespace.
- Полный LAN-сценарий с NAT не запускался автоматически: для него нужен
  `sudo` и открытый входящий порт.
- Проверена зашифрованная связка: Android-клиент ↔ Python demo-сервер
  (ECDH + AES-GCM) по локальной сети.
- Linux-сервер с шифрованием: handshake, зашифрованный ICMP через TUN,
  правила NAT и cleanup проверены в network namespace.

---

## 11. Ограничения и безопасность

- **Криптография учебная.** ECDH + AES-GCM реализованы корректно по базовым
  принципам, но протокол не проходил аудит; не используйте его для реальной
  защиты данных.
- **Один общий PSK.** Нет ротации ключей, отдельных учёток и отзыва доступа.
  Токен `demo` годится только для теста; берите длинный случайный PSK.
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

- Аудит GuardWire/2 и/или переход на TLS/Noise с проверенным стеком.
- Ротация PSK, несколько пользователей и отзыв доступа.
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
| [GuardWireCrypto.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireCrypto.kt) | ECDH, HKDF, HMAC, AES-GCM |
| [GuardWireFrameCodec.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireFrameCodec.kt) | формат кадров |
| [GuardWireHandshakeCodec.kt](core/protocol/src/main/kotlin/com/haruchka/testvpnapp/core/protocol/GuardWireHandshakeCodec.kt) | формат рукопожатия |
| [VpnViewModel.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/VpnViewModel.kt) | Orbit: intents → reduce → side effects |
| [VpnScreen.kt](feature/vpn/src/main/kotlin/com/haruchka/testvpnapp/feature/vpn/VpnScreen.kt) | stateless Compose UI |
| [MainActivity.kt](app/src/main/java/com/haruchka/testvpnapp/MainActivity.kt) | сборка экрана и DI-графа |
| [guardwire-demo-server.py](tools/guardwire-demo-server.py) | минимальный сервер для проверки handshake и кадров |
| [guardwire_crypto.py](tools/guardwire_crypto.py) | серверная и клиентская криптография GuardWire/2 |
| [guardwire-linux-server.py](tools/guardwire-linux-server.py) | Linux TUN + NAT: реальный интернет через VPN |
| [theory.md](theory.md) | пошаговый путь от демо к production-VPN |
