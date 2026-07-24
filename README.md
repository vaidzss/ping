# Ping

**Chat, share location, and send photos/videos with no internet — built for emergencies.**

Phone-to-phone mesh over Bluetooth LE (chat/GPS/SOS, multi-hop, store-carry-forward) with
on-demand Wi-Fi links for media, and optional LoRa nodes for kilometer-scale text range.
Every account is local-only — pick a callsign and password on-device (no server, no
recovery, same model as Briar/Signal Desktop) — see [docs/SECURITY.md](docs/SECURITY.md)
for exactly what that does and doesn't protect.

Ping is free software, licensed under the [AGPL-3.0](LICENSE), and open to
contributions — see [CONTRIBUTING.md](CONTRIBUTING.md) to get started.

**Status: pre-release (Phase 1 of 6), unaudited.** See [docs/VISION.md](docs/VISION.md)
for the roadmap (including the planned India civic reporting & legal-aid tier),
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the system fits together, and
[protocol/SPEC.md](protocol/SPEC.md) for the exact wire format.

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

## Documentation

- [docs/VISION.md](docs/VISION.md) — product roadmap, Phase 0 through 6.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the modules fit together, message
  data flow, identity/login model.
- [docs/SECURITY.md](docs/SECURITY.md) — threat model, crypto primitives, what's
  protected and what isn't.
- [docs/TESTING.md](docs/TESTING.md) — testing the app with only one physical phone.
- [protocol/SPEC.md](protocol/SPEC.md) — wire format and mesh protocol rules.
- [CONTRIBUTING.md](CONTRIBUTING.md) — how to get started contributing.
