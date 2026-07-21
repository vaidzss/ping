# MeshAid

**Chat, share location, and send photos/videos with no internet — built for emergencies.**

Phone-to-phone mesh over Bluetooth LE (chat/GPS/SOS, multi-hop, store-carry-forward) with
on-demand Wi-Fi links for media, and optional LoRa nodes for kilometer-scale text range.
See [docs/VISION.md](docs/VISION.md) for the roadmap (including the planned India civic
reporting & legal-aid tier) and [protocol/SPEC.md](protocol/SPEC.md) for the wire format.

## Modules

- `core/` — platform-independent Kotlin: wire codec, mesh relay (TTL flooding + dedup),
  DTN outbox, identity/crypto, content-addressed blob store. Heavily unit-tested.
- `tools/simulator/` — JVM mesh simulator: runs dozens of virtual nodes through churn,
  partition, and dense-crowd scenarios as JUnit tests.
- `tools/node/` — desktop mesh node (LAN lane): lets a laptop join the mesh, so the app
  can be tested with a single phone. See [docs/TESTING.md](docs/TESTING.md).
- `android/app/` — Android app: BLE + LAN transports, foreground mesh service, Compose UI.

## Build

Requires JDK 17+ and the Android SDK (`local.properties` → `sdk.dir`).

```
gradlew.bat :core:test :tools:simulator:test    # core logic + mesh simulation tests
gradlew.bat :android:app:assembleDebug          # Android APK
```
