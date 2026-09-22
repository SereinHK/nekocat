# NekoChat (喵聊)

**English** · [简体中文](README.zh-CN.md)

[![Build](https://github.com/SereinHK/nekocat/actions/workflows/build.yml/badge.svg)](https://github.com/SereinHK/nekocat/actions/workflows/build.yml)

A fully offline LAN chat app for Android. No Wi-Fi router, no mobile data, no server — devices talk to each other directly over Bluetooth or the local network. No account, no backend, no cloud.

- **Three transports, one protocol** — Bluetooth Classic (RFCOMM), Bluetooth Low Energy (GATT), and Wi-Fi LAN (TCP) share the same frame format and mesh logic
- **Mesh chat** — three or more devices chat together; messages relay hop by hop and are de-duplicated by id
- **One-to-one private chat** — visually distinct from group messages
- **QR pairing** — in Wi-Fi mode, scan a QR code instead of typing IP addresses
- **Genuinely offline** — no analytics, no telemetry, and not a single line of code that makes an outbound request

## Quick start

Requires **JDK 17–21** and **Android SDK Platform 37** (Miuix 0.9.3 needs `compileSdk >= 37`).
`local.properties` points at your local SDK; adjust it if you build on another machine.

```bash
./gradlew :app:assembleDebug          # Windows: gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install it on two or more devices. **Both ends must use the same transport**, then:

**Bluetooth Classic (RFCOMM)** — the most stable option
1. Pair the two devices in the system Bluetooth settings first
2. Open the app on both and tap **Start**
3. On one device go to **Devices → Paired devices** and tap **Connect** on the other

**Bluetooth Low Energy (BLE)** — no pairing needed
1. Turn Bluetooth on on both devices (no pairing) and pick BLE
2. Tap **Start** on both — they find and connect to each other automatically

**Wi-Fi LAN (TCP)** — highest bandwidth, longest range
1. Put both devices on the same router/hotspot and pick Wi-Fi
2. Tap **Start** on both
3. On the host, tap **Show QR code** in the **Devices** page; on the other, scan it (or type the IP manually)

For three or more devices: just start them all. With RFCOMM one acts as the server and the rest connect to it; over BLE/Wi-Fi any pair can connect directly.

If it will not connect, check the **current status** line at the bottom of the app's **Settings** page — it shows what is happening in real time.
BLE also logs every step under the `NekoChatBle` tag; `watch-ble-log.bat` in this repo captures it with one double-click (Windows).

## Release signing (optional)

Release builds are unsigned by default — signing keys do not belong in a repository. To produce a distributable release build, generate a key and create `keystore.properties` in the repository root (it is already in `.gitignore`):

```bash
keytool -genkeypair -v -keystore nekochat.jks -alias nekochat -keyalg RSA -keysize 2048 -validity 10000
```

```properties
storeFile=nekochat.jks
storePassword=your-password
keyAlias=nekochat
keyPassword=your-password
```

`./gradlew :app:assembleRelease` then produces a signed `app-release.apk`. Without that file the build still succeeds and simply yields `app-release-unsigned.apk`.

## Notes

- The UI is currently Simplified Chinese only. The code and identifiers are documented in Chinese as well.
- Chat history lives in memory only — closing the app clears it. Nothing is written to disk.

Licensed under [`LICENSE`](LICENSE) (MIT).
