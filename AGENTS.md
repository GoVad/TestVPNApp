# AGENTS.md

## Repository purpose

`SelfMadeGuard VPN` is an educational Android VPN client. It demonstrates:

- `VpnService` + TUN, routes, DNS and `protect(socket)`;
- foreground service, `onRevoke` and notification actions;
- an educational SelfMadeGuard/2 protocol and a real WireGuard mode;
- modular architecture, Hilt, MVVM + Orbit MVI and use cases.

Public documentation: `README.md`.

`theory.md` contains local production notes and **is not committed**. Do not link
to it from README or treat it as part of the public repository.

## Stack and environment

- Kotlin 2.2.10, AGP 9.2.1, Gradle 9.4.1, JDK 17.
- Compose BOM 2026.02.01, Orbit 12.0.1, WireGuard 1.0.20260102, Hilt 2.60.1 + KSP.
- `compileSdk 37`, `targetSdk 36`, `minSdk 24`.
- Modules: `:app`, `:feature:vpn`, `:core:domain`, `:core:data`, `:core:vpn`,
  `:core:vpn:selfmadeguard`, `:core:vpn:wireguard`, `:core:selfmadeguard-protocol`,
  `:core:model`.
- `:core:model`, `:core:domain` and `:core:selfmadeguard-protocol` are pure
  Kotlin/JVM and must not depend on Android APIs.
- Python 3 plus `cryptography` for local servers.
- `openssl` for the local HTTPS certificate.

## Commands

Build, tests and lint:

```bash
./gradlew :core:domain:test :core:selfmadeguard-protocol:test :app:assembleDebug :app:lintDebug
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Python environment:

```bash
./tools/setup-python.sh
```

One-command SelfMadeGuard demo without root:

```bash
./tools/demo-up.sh
```

Defaults: auth on `8090`, SelfMadeGuard demo on `8444`. Override ports if needed:

```bash
AUTH_PORT=8091 DEMO_PORT=8444 ./tools/demo-up.sh
```

The demo script uses a separate token file
`tools/.selfmadeguard-demo-token` and does not route internet traffic.

HTTPS certificate:

```bash
./tools/gen-auth-cert.sh <LAN_IP>
```

This writes local keys to `tools/.selfmadeguard-certs/` and copies the public CA to
`core/data/src/main/res/raw/auth_ca.pem`. If the LAN IP changes, rerun the script;
the APK does not need rebuilding while the CA stays the same.

Auth/control-plane server:

```bash
python3 tools/selfmadeguard-auth-server.py \
    --host 0.0.0.0 --port 8090 \
    --user demo:demo --disable-wireguard
```

- HTTPS is enabled by default and requires the generated certificate.
- Access TTL is 15 minutes; refresh TTL is 30 days.
- `POST /auth/logout` revokes the session and the WireGuard peer.
- `--no-tls` is for debugging only. The Android app rejects `http://` because
  `usesCleartextTraffic=false`.

Real WireGuard provisioning:

```bash
sudo ./tools/wireguard-server-setup.sh

sudo python3 tools/selfmadeguard-auth-server.py \
    --host 0.0.0.0 --port 8090 \
    --user demo:demo \
    --wg-interface wg0 \
    --wg-endpoint <LAN_IP>:51820
```

The auth server provisions the WireGuard peer and the client fetches its profile
via `POST /auth/wireguard`. Manual `.conf` paste is not part of the UI flow.

SelfMadeGuard demo server with a static PSK:

```bash
python3 tools/selfmadeguard-demo-server.py \
    --host 0.0.0.0 --port 8443 --token demo
```

Full Linux SelfMadeGuard server with TUN + NAT, static PSK:

```bash
sudo python3 tools/selfmadeguard-linux-server.py \
    --host 0.0.0.0 --port 8443 --token demo
```

Full Linux SelfMadeGuard server with the token issued by the auth server:

```bash
sudo python3 tools/selfmadeguard-linux-server.py \
    --host 0.0.0.0 --port 8443 \
    --auth-token-file tools/.selfmadeguard-token
```

Do not mix these modes: a server started with `--token demo` will reject the
random access token issued by the auth server.

## Architecture

Dependency direction:

```text
app -> feature:vpn -> core:domain -> core:model
app -> core:data   -> core:domain
app -> core:vpn    -> core:vpn:selfmadeguard -> core:selfmadeguard-protocol -> core:model
                   -> core:vpn:wireguard
                   -> core:domain
```

- `core:domain`: contracts, domain models and use cases. No Android.
- `core:data`: DataStore/Keystore, auth HTTP client, store implementations.
- `core:vpn`: thin `VpnSession` facade and DI composition of both engines.
- `core:vpn:selfmadeguard`: educational SelfMadeGuard/2 engine: `VpnService`, TUN,
  always-on, reconnect and foreground notification.
- `core:vpn:wireguard`: real WireGuard engine: `GoBackend`, config parsing and
  its foreground notification.
- `feature:vpn`: Compose UI, Orbit ViewModel and side effects.
- `app`: Hilt entry point and activity.

The engines must not depend on each other. Only `:core:vpn` knows both and picks
the implementation for `VpnSession`.

Do not leak Android, Compose or `VpnService` into lower layers. Do not break the
dependency direction.

State flow:

```text
SelfMadeGuardVpnService / WireGuardController
  -> VpnStateStore
  -> VpnConnectionUseCase.observeState()
  -> VpnViewModel
  -> Compose
```

- `VpnViewModel` must call use cases, not stores, repositories or services directly.
- Use cases are provided by `VpnUseCaseModule` in `:feature:vpn`; the domain stays
  free of Hilt and Android.
- UI depends on contracts (`VpnSession`, `AuthService`, `VpnSettingsStore`) and
  Hilt binds implementations with `@Binds`.
- Permission dialogs, snackbars and navigation events go through `VpnSideEffect`.
- New modules must be added to `settings.gradle.kts` and `gradle/libs.versions.toml`.

## Localization and code style

- UI strings live in `strings.xml`: English is the default locale, Russian is in
  `values-ru/strings.xml`.
- Diagnostic strings in Android modules are English-only `strings.xml`.
- `core:selfmadeguard-protocol` is pure JVM, so its internal exception messages are English
  constants instead of Android resources.
- Keep code comments in Russian. Use English identifiers.
- Do not hardcode user-facing strings in Compose or ViewModels.

## Invariants

- The tunnel socket must pass through `VpnService.protect()` **before** `connect()`,
  otherwise its packets enter the app's own TUN.
- `SelfMadeGuardVpnService` must call `startForeground` in `onCreate`/`onStartCommand`.
  On API 34+ use the `specialUse` FGS type and `FOREGROUND_SERVICE_SPECIAL_USE`.
- The VPN service is declared with
  `android:permission="android.permission.BIND_VPN_SERVICE"` and is an
  `@AndroidEntryPoint`. Do not override the WireGuard library service.
- Hilt: use `@HiltAndroidApp`, `@HiltViewModel`, `@AndroidEntryPoint`. KSP requires
  `android.disallowKotlinSourceSets=false` with AGP 9.
- SelfMadeGuard frame writes are synchronized. Payloads use AES-256-GCM; the frame
  type is authenticated as AAD; every frame has a random IV.
- The access token is the SelfMadeGuard handshake PSK. Never log or expose it.
- If handshake/crypto changes, update the Kotlin and Python servers together and
  update `SelfMadeGuardCodecTest`, `SelfMadeGuardCryptoTest`, `SelfMadeGuardClientTest`.
- After blocking I/O during cancellation, call `ensureActive()` and close the
  socket/TUN.
- IPv6 is intentionally not routed; WireGuard configs containing IPv6 are rejected.
- Always-on is implemented for SelfMadeGuard only. The real WireGuard mode uses the
  library's own `VpnService`.
- WireGuard peers are added to the live `wg0` interface but are not persisted in
  `/etc/wireguard/wg0.conf`. After a host reboot, log in again or re-provision.

## Security

- Never commit `local.properties`, `.idea/`, `build/`, `*.jks`, `*.keystore` or
  signing files.
- `tools/.selfmadeguard-certs/` contains local private keys and must stay untracked.
  Only `core/data/src/main/res/raw/auth_ca.pem` is public.
- SelfMadeGuard/2 uses ECDH P-256 + AES-256-GCM, but it is an unaudited educational
  scheme. Never present it as production VPN security.
- Access/refresh tokens and the WireGuard config are encrypted with Android
  Keystore and stored in DataStore. Do not log them.
- The auth server uses a local self-signed CA. This is not a production CA setup.

## Checks before finishing a task

1. `./gradlew :core:domain:test :core:selfmadeguard-protocol:test :app:assembleDebug`
2. For UI/service changes: `./gradlew :app:lintDebug`
3. For permission/FGS/VPN changes: manual check on API 34+.
4. Do not leave new untracked build or IDE artifacts.
5. CI: `.github/workflows/android.yml` runs the same tests, build and lint on push/PR.
   It installs `platforms;android-37.0` and `build-tools;37.0.0`.
