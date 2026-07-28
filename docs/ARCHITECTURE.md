# Ping — Architecture

This document explains how the pieces fit together: what runs where, how a message
travels from one phone to another with no internet in between, and why the code is
split the way it is. For the exact bytes on the wire, see [protocol/SPEC.md](../protocol/SPEC.md).
For the crypto/identity threat model, see [SECURITY.md](SECURITY.md). For the product
roadmap, see [VISION.md](VISION.md).

## Modules, and why they're split this way

```
core/            platform-independent Kotlin — no Android, no JVM-only APIs beyond
                 what the JDK itself provides. This is the actual mesh: wire codec,
                 router, DTN store, crypto, blob store. Everything here is unit-tested
                 and runs identically on Android, the desktop node, and the simulator.
tools/simulator/ JVM-only. Runs dozens of MeshNode instances against in-memory
                 transports through churn/partition/dense-crowd scenarios, as JUnit
                 tests. This is how mesh-wide behavior (flooding, DTN sync, rate
                 limiting) gets tested without needing real radios or real phones.
tools/node/      JVM desktop app. Wraps core in a LAN transport and a REPL, so a
                 laptop can join the mesh as a full participant. Exists so the app can
                 be developed and tested with a single physical phone (see
                 docs/TESTING.md) — this is a dev tool, not a shipping product.
android/app/     The actual product. BLE + LAN transports, a foreground service that
                 keeps the mesh alive in the background, and the Compose UI.
```

`core` is deliberately the only module that matters for correctness. `tools/node` and
`android/app` are both thin: they each provide a `MeshTransport`, load or unlock an
`Identity`, construct one `MeshNode`, and wire its callbacks to a UI (a println loop, or
Compose state). If mesh behavior is ever wrong, the bug is almost certainly in `core`
and reproducible in a `tools/simulator` test — reach for that before debugging on a
real device.

## Data flow: one message, end to end

1. **UI → `MeshNode.send()` / `sendDirectChat()`.** The caller (Android service, node
   CLI) never touches the wire format directly.
2. **`MeshRouter.prepareOutbound()`** builds a `Packet`: assigns TTL (density-clamped —
   see below), timestamp, sender id. For CHAT/SOS it's signed with the sender's Ed25519
   key; for DMs the payload is sealed first (`SealedBox`, X25519 ECDH → HKDF → ChaCha20-
   Poly1305) so relays only ever see ciphertext.
3. **`PacketCodec.encode()`** serializes to the fixed-header binary format (see SPEC.md)
   and pads to a size bucket — uniform packet sizes are a deliberate anti-fingerprinting
   choice (Bridgefy shipped plaintext, size-revealing headers; this doesn't).
4. **`MeshTransport.broadcast()`** hands the frame to whatever radio is live —
   `BleMeshTransport` (Android, GATT) or `LanMeshTransport` (desktop node, and the
   Android app's LAN lane) — or both at once via `CompositeMeshTransport`.
5. **On a receiving node**, the transport calls `onFrame`, `MeshNode.receiveFrame()`
   decodes it, and hands it to `MeshRouter.onReceive()`.
6. **The router decides two independent things**: deliver locally? (recipient is us, or
   it's a broadcast) and relay onward? (TTL not expired, not a duplicate per
   `DedupCache`, not over the per-origin rate limit — SOS and MEDIA_CHUNK are exempt).
   A packet can do both, either, or neither.
7. **`MeshNode.deliver()`** verifies the signature against the sender's key (if known;
   unknown-key packets deliver unverified, since offline mesh availability beats crypto
   purity here), decrypts if it's a sealed DM addressed to us, and calls `onMessage`.
8. **Every silent-drop point along this path reports why** via `onFrameRejected` /
   `onDeliveryDropped` / `LanMeshTransport.onDiagnostic` — see the "Diagnostics" section
   below. This wasn't originally the case; see the commit history around
   `LanMeshTransport` for what silent failure cost in debugging time.

## Store-carry-forward (DTN)

Every phone is a data mule. `BundleStore` keeps encoded CHAT/SOS broadcast packets
(24h lifetime, 8-copy Spray-and-Wait budget, SOS eviction-protected) so a message sent
while the recipient is unreachable still arrives once *any* path of intermittent contact
connects sender and recipient — no continuous route ever needs to exist.

On every new neighbor contact (`peerUp` → `maybeSyncWith`, rate-limited to once per peer
per 30s), a node sends a `SUMMARY_VECTOR` (list of bundle ids it holds) to the new
neighbor. The neighbor replies with `BUNDLE_PULL` for whatever it's missing, and the
first node re-injects those bundles onto the transport as ordinary packets, which then
flow through the normal deliver/relay path like anything else.

## Presence, identity announce, and the DM ordering problem

Two different things use the same `PRESENCE` packet type with different TTL:

- **TTL=1 (`sendPresence`)**: "I am a direct neighbor, right now." Registers the sender
  as a live neighbor (35s expiry) and triggers a DTN sync. Sent on a timer and
  immediately on link-up.
- **TTL=full (`sendAnnounce`)**: "Here are my keys and name" — spread multi-hop so a
  distant node (not a direct neighbor) can still verify signatures and seal DMs to this
  node. Receivers register the keys in `PeerDirectory` but do *not* treat the sender as
  a direct neighbor from this alone.

This split exists because of a real bug: a DM typed within the first few seconds of two
devices meeting used to fail, because the recipient's X25519 key hadn't propagated yet
at DM-send time. The fix (see `onNeighborUp` in `MeshNode`) is to fire an immediate
presence + announce the instant a transport link comes up, instead of waiting for the
next heartbeat tick.

`PeerDirectory.register()` enforces **self-authenticating key binding**: it rejects any
announcement whose signing key doesn't hash to the claimed NodeId. This stops someone
from claiming another device's NodeId while presenting their own keys. It does *not*
authenticate the human-readable display name — see [SECURITY.md](SECURITY.md) for what
that does and doesn't mean.

## Media transfer

Photos go through the same control lane as everything else, capped at 1 MiB
(`MeshNode.MAX_AUTO_FETCH_BYTES`) — bitchat-style, no separate bulk channel exists yet
(planned: Wi-Fi Direct / Nearby Connections for larger transfers, see VISION.md Phase 2).
`offerMedia()` puts the blob in `BlobStore` (content-addressed: filename is the blob's
own SHA-256) and broadcasts a `MEDIA_OFFER`. Interested peers reply `MEDIA_REQUEST`; the
holder chunks and sends `MEDIA_CHUNK`s (recipient-addressed, unicast, unsigned —
integrity comes from the content hash, not a signature). `IncomingTransfer` reassembles
chunks and **verifies the full SHA-256 before accepting anything** — a forged or
corrupted transfer is discarded whole, not partially trusted.

## Identity and login (Android app)

The mesh identity (`core/crypto/Identity`) is a device keypair: Ed25519 for signing,
X25519 for key agreement, generated once. It has nothing to do with usernames or
passwords by itself — a NodeId is just `SHA-256(Ed25519 public key)`.

The Android app layers an account on top of that keypair, modeled on Briar and Signal
Desktop rather than a typical mobile app: there is no account server, so "sign up"
means *generate a local identity and pick a callsign*, and "log in" means *unlock that
identity with a password*, nothing more.

- **`core/crypto/PasswordVault`**: PBKDF2-HMAC-SHA256 (210,000 iterations) derives a key
  from the password + a random salt; ChaCha20-Poly1305 seals the identity's 64-byte
  private key export under that key. Platform-independent — testable in `core`, no
  Android dependency.
- **`android/app/service/IdentityStore`**: owns the SharedPreferences record (callsign +
  sealed identity blob), exposes `signUp` / `login` / `hasAccount`. On first sign-up on
  a device that already has an old Keystore-wrapped identity (pre-login builds), it
  *adopts* that identity's keys rather than generating fresh ones, so a device's
  already-verified NodeId and mesh history survive the upgrade.
- **In-process handoff, not Intent extras**: `MainActivity` unlocks the identity (via
  sign-up or login), then calls `MeshForegroundService.start(context, identity)`, which
  stashes it in a static field read once by `onCreate()`. The private key never gets
  serialized into an `Intent` or written anywhere except the sealed SharedPreferences
  blob. A consequence: if Android kills the app process, the service cannot restart
  itself with `START_STICKY` alone — there's no key in memory to restart with, so it
  stops itself and waits for the user to log in again. This is intentional (see
  SECURITY.md) — the password is genuinely the only thing unlocking the key, not merely
  a UI gate in front of an always-available key.

There is no password recovery. Losing the password means generating a new identity —
the same trade-off Briar makes, and for the same reason: any recovery path is a copy of
the key living somewhere else, which is exactly what a serverless, no-custodian design
is trying to avoid.

## At-rest encryption beyond the identity key (Android app)

`PasswordVault` only ever seals one thing: the identity's private key, once, at
signup/login. Everything else the app persists locally — chat history, received/sent
photos — goes through a second, separate mechanism, because reusing `PasswordVault`'s
approach for those wouldn't work: it re-derives its key via ~210k PBKDF2 iterations on
every seal, which is the right cost for one login but far too slow to pay on every
single chat message.

- **`core/crypto/StorageVault`**: derives a key from the *unlocked* identity's private
  key material via HKDF-SHA256 (not from the password directly), then seals with
  ChaCha20-Poly1305. Because this key gets reused across many seals (unlike
  `PasswordVault`'s one-shot use, or `SealedBox`'s fresh ephemeral key per DM), every
  seal draws its own random 12-byte nonce rather than relying on a zero-nonce shortcut.
  Deriving from the identity rather than the password means the expensive PBKDF2 step
  only happens once, at login, while storage encryption stays tied to the same trust
  boundary: nothing is readable without the password, because nothing is readable
  without the identity the password unlocks.
- **`android/app/service/MessageLog`**: seals each chat message individually before
  appending it as a line to `messages.jsonl` — Base64-encoded, since raw
  ChaCha20-Poly1305 ciphertext can contain a byte that looks like a newline and would
  otherwise corrupt the file's line-based format.
- **`core/blob/BlobStore`**: takes optional `seal`/`open` hooks applied only to the
  bytes written to and read from disk. The content hash used for mesh-wide addressing
  (dedup, `MEDIA_OFFER`/`MEDIA_CHUNK` integrity checks) is always computed over the
  *plaintext*, before sealing — encryption at rest never touches the wire protocol. This
  also keeps `BlobStore` usable by the desktop node, which has no password/identity
  unlock concept at all: its `seal`/`open` hooks simply stay `null`, today's plaintext
  behavior, unchanged.

## Diagnostics: no silent drops

A large fraction of the project's early debugging time went into chasing failures that
produced *no output anywhere* — a DM that vanished with no trace on either device. The
fix wasn't one bug; it was systematically replacing every `catch (_: Exception) {}` in
the receive/relay/transport path with a callback that explains what happened:

- `MeshNode.onFrameRejected` — bytes arrived but didn't decode as a packet.
- `MeshNode.onDeliveryDropped` — a packet addressed to us wasn't delivered (duplicate,
  signature mismatch, wrong recipient, sealed box wouldn't open).
- `LanMeshTransport.onDiagnostic` — transport-level failures: failed dial, failed
  handshake, a write that killed a link, a duplicate-connection race.
- `CompositeMeshTransport.onDiagnostic` — one lane throwing on start/stop/broadcast used
  to take every other lane down with it (`lanes.forEach { it.start() }` never reaches
  lane 2 if lane 1 throws); each lane is now isolated, and this says which one failed.

All four are wired into both the node CLI (prints to the console) and the Android app
(amber "system" chat entries, see `LogEntry` in `MainActivity.kt`). If you're debugging
a mesh issue and nothing is showing up, that itself is now a bug — every drop point has
a callback; if one is silent, it's a gap to close, not a signal to work around.

### Beacon traffic is throttled, not timer-driven

`GPS_BEACON` used to go out on a fixed 30s timer regardless of whether the phone had
moved — for a stationary phone (the common case in a shelter or a fixed post), that's
identical traffic re-forwarded by every relay hop across the mesh, forever, paid for in
battery by devices that aren't even the sender. `BeaconThrottle` (core, radio-agnostic)
gates each fix behind either real movement (15m) or a slow heartbeat (3 min) so a beacon
only goes out when it's actually new information or a peer's copy is going stale.

### A "DUPLICATE" delivery drop is often harmless, not a failure

`onDeliveryDropped` fires for a duplicate exactly as loudly as for a forged signature —
intentionally, per the no-silent-drops policy above. But a duplicate reaching that
callback doesn't necessarily mean anything failed: `PacketCodec.messageId()` is a hash of
the packet's own bytes (sender, timestamp, type, payload), so it only fires for a literal
byte-for-byte repeat of a packet the dedup cache already recorded — which happens
routinely and harmlessly (the same frame arriving over two transport lanes at once, or a
full BLE-fragment retransmission after a reconnect) precisely *because* the first copy
already got through and was already processed. Seeing "DROPPED ... DUPLICATE" is evidence
the mesh is working, not evidence something is stuck.

### BLE send queues can get stuck, not just links

Both BLE send paths — `notifyCharacteristicChanged()` (server/peripheral) and
`writeCharacteristic()` (client) — only allow one send in flight at a time, gated by a
completion callback (`onNotificationSent` / `onCharacteristicWrite`). If that callback
ever goes missing (the peer disconnects mid-send, a stale link gets pruned while a send
to it is outstanding, or the OS stack just drops it), the gating flag gets stuck `true`
forever and silently freezes every future send on that path — for the server side, to
*every* connected peer, since `notifying` isn't per-link. A multi-hundred-chunk photo or
video transfer is exactly what's likely to trigger this, which is why a one-chunk chat
message could keep working right up until the first large transfer wedged everything.
`pruneStaleLinks()` (called from the same 2s tick as the zombie-link check) now also
watches how long a send has been "in flight" and force-resumes the queue if it's been
stuck past `SEND_STUCK_TIMEOUT_MS` (6s — deliberately much shorter than the 25s zombie-link
window, since this is "one callback went missing," not "the whole link is dead").

### Location is a long-lived session, not a per-call request

The first cut of `LocationFixProvider` issued a fresh bounded `getCurrentLocation()` /
`requestSingleUpdate()` per caller, cancelled at that caller's own timeout. That's fine
when `NETWORK_PROVIDER` can resolve quickly, but with no Wi-Fi or mobile data — the
disaster scenario this app is built for — network-based location can't resolve at all,
leaving only a raw GPS cold fix, which can take 30s+ indoors. A per-call timeout of even
12s cancels that acquisition before it ever completes, and the next call starts the same
cold acquisition over from nothing — it can never finish. `LocationFixProvider` now starts
one `requestLocationUpdates()` session at service startup and never cancels it; every
caller (the periodic beacon, an SOS) just reads whatever that session has produced so far,
so a slow fix that finally lands still benefits every future call, not just the one that
happened to be waiting when it arrived.

## Where a new transport or platform would plug in

Everything above `MeshTransport` (routing, DTN, crypto, blob store) is radio-agnostic.
Adding a new lane — LoRa (Phase 4), Wi-Fi Direct for bulk media, iOS
MultipeerConnectivity (Phase 3) — means implementing the four-method `MeshTransport`
interface (`start`, `stop`, `broadcast`, plus the `onFrame`/`onPeerConnected`/
`onPeerDisconnected` callbacks) and, if it should run alongside existing lanes rather
than replace them, adding it to a `CompositeMeshTransport`. Nothing else in `core` needs
to know a new radio exists.
