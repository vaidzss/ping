# Contributing to Ping

Ping is early-stage (Phase 1 of the roadmap in [docs/VISION.md](docs/VISION.md)) and
built in the open. Contributions, bug reports, and design pushback are all welcome —
this doc explains how to get productive quickly.

## Before you start

Read, in this order:

1. [README.md](README.md) — what the app does and the module layout.
2. [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the pieces fit together and why.
3. [protocol/SPEC.md](protocol/SPEC.md) — the exact wire format, if you're touching
   `core`.
4. [docs/SECURITY.md](docs/SECURITY.md) — what the current design does and does not
   protect against. Read this before proposing any crypto/identity change.

If you're picking up an existing issue or proposing a new one, a short comment
describing your intended approach before you write code saves everyone time —
especially for anything touching `core/mesh` or `core/crypto`, where subtle changes can
have mesh-wide correctness or security implications.

## Development setup

Requires JDK 17+ and the Android SDK (`local.properties` → `sdk.dir`).

```
gradlew.bat :core:test :tools:simulator:test    # core logic + mesh simulation tests
gradlew.bat :android:app:assembleDebug          # Android APK
```

See [docs/TESTING.md](docs/TESTING.md) for how to test the app end-to-end with only one
physical phone (a desktop "node" joins the mesh over LAN, standing in for a second
device).

## Where to make changes

- **`core/`** — the actual mesh logic. Platform-independent, heavily unit-tested. Most
  correctness bugs and most new mesh features belong here. If you're not sure whether
  something belongs in `core` or a platform layer, prefer `core`: anything here is
  automatically exercised by `tools/simulator`'s multi-node scenarios, which is far
  cheaper to iterate on than a real device.
- **`tools/simulator/`** — add a test here for any new mesh-wide behavior (routing
  changes, DTN sync changes, rate limiting). These tests run dozens of virtual nodes
  through churn/partition scenarios as plain JUnit tests — no radios, no emulators.
- **`tools/node/`** — the desktop dev tool. Keep it minimal; it exists to make one-phone
  testing possible, not as a second product.
- **`android/app/`** — UI and platform glue (BLE transport, foreground service,
  Compose). Should stay thin: if logic here would be useful to a future iOS build too,
  it likely belongs in `core` instead.

## Code style

- Kotlin, following the conventions already in the codebase — read a neighboring file
  before adding a new one.
- Prefer explaining *why* in a comment over *what*; the code should already say what it
  does. The existing files lean toward short comments that record a non-obvious
  constraint or the reason a past bug forced a particular shape — match that style
  rather than adding narration.
- No new `catch (_: Exception) {}` (or equivalent silent swallow) in the packet
  receive/relay/transport path. A meaningful chunk of this project's early history was
  spent removing exactly these — see the `MeshNode.onFrameRejected` /
  `onDeliveryDropped` / `LanMeshTransport.onDiagnostic` callbacks in
  [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#diagnostics-no-silent-drops). If a new
  failure path can occur, surface it through one of those (or add a new one) rather than
  swallowing it.
- Don't add abstractions, config flags, or generality for hypothetical future needs.
  Three similar lines beat a premature interface.

## Testing expectations

- New `core` behavior needs a unit test in `core/src/test` and, for anything affecting
  routing/relay/DTN behavior across multiple nodes, a scenario in `tools/simulator`.
- UI changes should be manually verified against a real device or the LAN-lane
  desktop-node setup in `docs/TESTING.md` — the Compose previews don't exercise the mesh
  service.
- `gradlew.bat :core:test :tools:simulator:test :android:app:assembleDebug` should pass
  before opening a PR.

## Reporting security issues

See the "Reporting a vulnerability" section of [docs/SECURITY.md](docs/SECURITY.md) —
please don't put exploit details in a public issue.

## License

Ping is licensed under the **GNU Affero General Public License v3.0** (see
[LICENSE](LICENSE)) — chosen specifically because Ping's roadmap includes a hosted
component (the civic-reporting/legal-aid tier in Phase 6 of
[docs/VISION.md](docs/VISION.md)), and AGPL's network-use clause keeps any hosted
version of this code open too, not just the client. By submitting a contribution, you
agree it will be distributed under the same license.
