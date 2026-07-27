# Ping — Vision & Roadmap

## What this is

Ping lets people **chat, share live GPS location, and send images/videos with no internet, no cellular, no servers** — built for disaster zones (floods, earthquakes) and areas where connectivity is shut down, when communication matters most.

Every phone running the app is a node: messages hop phone-to-phone over Bluetooth LE, media moves over on-demand Wi-Fi links, and every device carries messages forward for others (store-carry-forward). Optional ~$30 LoRa radios extend text/GPS range to kilometers.

## Now: the offline emergency mesh (Phases 0–5)

1. **Phase 0 — Protocol spike:** ✅ multi-hop BLE relay and airplane-mode video transfer proven on real phones.
2. **Phase 1 — Android MVP:** ✅ done. BLE mesh chat, GPS pins on a relative-position mesh map,
   photo transfer, SOS broadcast, QR contact verification.
3. **Phase 2 — Media pipeline:** ✅ done. Content-addressed blobs, resumable chunked transfer,
   hop-by-hop media relay (the router is type-agnostic — this fell out of the general TTL/dedup
   design, not a separate feature), HEVC video compression.
4. **Phase 3 — iOS:** not started. Same shared `core`; MultipeerConnectivity for iOS↔iOS media;
   BLE interop with Android.
5. **Phase 4 — LoRa lane:** not started. Pair Meshtastic-class nodes for kilometers of text/GPS
   range; gateway phones stitch distant clusters.
6. **Phase 5 — Hardening:** in progress. First real two-phone field test (BLE mesh chat, SOS,
   photo broadcast) surfaced and fixed several real-hardware bugs invisible to the simulator —
   see "What real-device testing found" below. Still open: battery profiling, external security
   review, store review prep.

### What Phase 1/2 actually ship

- **Mesh chat** — broadcast channel + per-friend encrypted DM threads, QR-verified contacts.
- **Mesh Map** — no tile server or bundled map data (needs internet or a shipped tile pack,
  which cuts against the zero-infrastructure pitch); instead a relative-position radar, you at
  the center, every peer with a GPS fix plotted by bearing and distance.
- **Photo + video sharing** — photos over the control lane as-is; video re-encoded to HEVC at a
  starved bitrate (350 kbps) and an 8s cap so a whole clip fits the same 1 MiB budget.
- **SOS broadcast** — GPS-tagged emergency broadcast, never rate-limited on relay.
- **Battery-aware beaconing** — `BeaconThrottle` gates ambient GPS_BEACON traffic behind real
  movement or a slow heartbeat, so a stationary phone doesn't cost every relay hop on the mesh
  forever.

### What real-device testing found

Two phones over real BLE surfaced bugs the simulator (in-memory transports) structurally can't:

- **BLE GATT server notifications had zero flow control.** `notifyCharacteristicChanged()`
  allows only one outstanding notification at a time; calling it again before
  `onNotificationSent()` fires for the previous one silently drops data. A one-chunk chat
  message got lucky often enough to look like it worked; a multi-hundred-chunk photo transfer
  never survived it. Fixed by pacing the server side the same way the client-write side
  already was.
- **Location was passive-only.** `getLastKnownLocation()` reads whatever's cached and returns
  null forever on a phone where no other app has recently asked for GPS — which is exactly why
  SOS shipped with no coordinates and the mesh map never showed anyone. `LocationFixProvider`
  now actively requests a fresh fix instead.
- **OEM battery managers kill background BLE.** ColorOS/OxygenOS/MIUI-class skins throttle BLE
  scanning/advertising in a backgrounded foreground service unless the app is explicitly
  exempted from battery optimization — the app now prompts for that exemption at startup.
- **Denied permissions failed completely silently** — the mesh just never started, with no
  explanation anywhere. Now surfaces exactly what's missing.

Full detail in [docs/ARCHITECTURE.md](ARCHITECTURE.md)'s "Diagnostics" section.

## Scaling local storage

Chat history, friends, and identity currently live in flat files and SharedPreferences
(`MessageLog`, `FriendStore`, `IdentityStore` — see [docs/ARCHITECTURE.md](ARCHITECTURE.md)),
deliberately simple while the data model (threads, friends, media) is still moving.
The planned upgrade, once that settles, is a real on-device database — SQLite via
SQLDelight is the leading candidate — behind the same `StorageVault` at-rest-encryption
boundary already in place: schema and query capability change, the encryption model
doesn't. This is Android-app scope only; it doesn't touch `core`, the wire protocol, or
the desktop node, none of which need a database to do their job.

## Later: civic reporting & legal aid for India (Phase 6)

Once the mesh works, Ping grows a second, **online** tier — a platform for India where people can:

- **Report injustice live** — record what happened (text, photo, video) as it happens, even offline. Evidence is captured, hashed, and cryptographically signed on-device the moment it's recorded.
- **Upload when any connectivity appears** — the same store-carry-forward queue that relays emergency messages syncs reports up to the platform opportunistically.
- **Seek legal help** — connect reports to lawyers, legal-aid organizations, and NGOs who can act on them.

### Why the offline architecture makes this stronger

The mesh isn't separate from the civic platform — it is the **capture layer**:

- **Signed at the source:** every message/media blob is signed with the reporter's device key and content-addressed (hash = identity), giving evidence tamper-evidence and provenance from the moment of capture.
- **Unstoppable capture:** shutdowns can block upload, but not recording and mesh relay. Reports queue in the DTN outbox and travel phone-to-phone until any one device finds internet.
- **Privacy by construction:** end-to-end encryption and no central servers in the capture path; the reporter chooses when a report goes public.

### Scope notes (Phase 6, not built yet)

- India-first: localization, alignment with legal-aid ecosystem (e.g., DLSA/NALSA-style services), moderation and verification workflows.
- Requires a real backend (the first server-side component, with its own
  server-side database — a different scaling problem than the on-device one above),
  identity/abuse safeguards, and careful legal review.
- Current architecture decisions already made compatible: content-addressed media, signed packets, store-carry-forward queueing.
