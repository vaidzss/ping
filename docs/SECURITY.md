# Ping — Security Model

Ping is **pre-release, unaudited software**. This document describes what the
current design protects against, what it explicitly does not, and where the sharp
edges are — so that using it, testing it, or contributing to it happens with accurate
expectations rather than assumed guarantees. Nothing here should be read as a claim of
review by an independent third party; Phase 5 (see [VISION.md](VISION.md)) is where that
happens, before any real-world emergency deployment.

If you find a genuine vulnerability, please report it privately rather than opening a
public issue — see [CONTRIBUTING.md](../CONTRIBUTING.md) for how.

## Threat model

Ping assumes a mesh of mutually-distrusting devices carrying each other's traffic —
by design (store-carry-forward), a message you send passes through phones you don't
control and have no relationship with. The design goals, in order:

1. **A relay cannot read message content it is not the recipient of.** Direct messages
   are sealed to the recipient's key before they touch the transport; broadcast chat is
   plaintext by design (it's meant for everyone in range) but SOS/GPS/chat text never
   depends on a relay being honest to stay confidential when addressed 1:1.
2. **A relay cannot forge a message as coming from someone else.** CHAT and SOS packets
   are signed; a signature that doesn't verify against the claimed sender's known key is
   dropped before delivery, not just flagged.
3. **A relay cannot silently corrupt media in transit.** Blobs are content-addressed
   (filename = SHA-256 of content); a reassembled transfer that doesn't hash-match is
   discarded whole.
4. **The mesh should not be crashable or fingerprintable by a malicious peer.** Malformed
   frames are rejected without crashing the node (see `PacketCodec` error handling);
   packets are padded to uniform size buckets so passive observers can't distinguish
   message types or lengths from the radio traffic alone.
5. **A single device losing its password should not expose past messages to whoever
   finds the phone**, beyond what's already unencrypted on disk. See "What's protected
   at rest" below for the actual boundary — this is a goal the current design partially,
   not fully, achieves.

## What's *not* in scope (by design, for now)

- **Sender anonymity.** Every signed packet reveals the sender's NodeId (a public hash
  of their signing key) to everyone who relays it. Ping is not Tor; it optimizes for
  delivery in a disaster zone, not for hiding who is talking.
- **Forward secrecy for direct messages.** DMs use a sealed-box construction (ephemeral
  X25519 → HKDF-SHA256 → ChaCha20-Poly1305) specifically because it works when the
  recipient is offline — the sender doesn't need an interactive handshake. The trade-off
  is that if a recipient's long-term X25519 private key is ever compromised, every
  sealed DM ever sent to them (that an attacker recorded) becomes readable. Interactive
  Noise XX sessions with forward secrecy for *live* conversations are planned (Phase 1,
  see protocol/SPEC.md) but not yet built.
- **Display name authentication.** A device can broadcast any callsign it wants; only
  the signing key ↔ NodeId binding is cryptographically enforced
  (`PeerDirectory.register()`), not the human-readable name attached to it. The
  Roster/verified-badge UI reflects "we've seen a self-consistent key announcement from
  this NodeId before," not "this callsign belongs to a specific real person" — that
  binding is currently established out of band, by the in-person QR contact exchange
  (`ContactCard`), which is the only step in the current design where a human actually
  confirms who they're talking to.
- **Metadata resistance beyond uniform packet sizing.** Timing, relay hop-count
  patterns, and RF fingerprinting are not addressed.
- **Protection against a compromised or malicious relay dropping your traffic.** Nothing
  stops a relay from simply not forwarding a packet. Redundant flooding paths (TTL,
  multiple neighbors) are the mitigation, not a cryptographic guarantee.

## The password / login model, precisely

Ping has no account server — "signing up" and "logging in" both happen entirely on
one device, with no network call. This is deliberate: a serverless design means there is
no central point that can be compelled, breached, or shut down to deny the app's use.
See [ARCHITECTURE.md](ARCHITECTURE.md#identity-and-login-android-app) for the
implementation; the security-relevant properties are:

- **The password is not a login gate in front of an always-decrypted key.** It is the
  actual encryption key input (via PBKDF2-SHA256, 210,000 iterations) for the identity's
  private key material, sealed with ChaCha20-Poly1305. Without the correct password, the
  ciphertext on disk (`meshaid_identity` SharedPreferences) does not yield the private
  key — there's no separate "reset password" path that bypasses this, because there's
  nothing to reset it *from*.
- **There is no password recovery, by construction.** Any recovery mechanism (a hint,
  a server-side reset, a recovery code stored elsewhere) is itself a second way to reach
  the same key, which weakens exactly the property this design is trying to provide.
  Forgetting the password means generating a new identity; existing peers who verified
  the old NodeId will need to re-verify the new one.
- **What's *not* covered**: the callsign is stored in plaintext alongside the sealed
  blob (it has to be, to display "log in as X" before the password is entered). Chat
  history, received photos, and message timestamps/metadata are encrypted too (see
  below) but not *by the password directly* — a distinct key, derived from the unlocked
  identity via HKDF (`StorageVault`, not `PasswordVault`), so the ~210k-iteration PBKDF2
  cost isn't paid on every message. That key only exists transiently, after a
  successful login — so everything it protects is exactly as available as the identity
  itself: nothing without the password, everything once unlocked.
- **Process death means re-login.** The unlocked identity lives only in memory
  (a static field, handed off in-process from the login screen to the foreground
  service — never serialized to an Intent or disk in decrypted form). If Android kills
  the app process, the mesh service cannot silently resurrect itself; it stops and waits
  for the next login. This is the correct behavior for the threat model, but it does
  mean the mesh goes offline on that device until someone re-enters the password —
  worth knowing before relying on a phone as an always-on relay.

## What's protected at rest, and what isn't

| Data | At rest | Notes |
|---|---|---|
| Identity private key | Encrypted (password-derived key) | `PasswordVault`, see above |
| Chat history (`messages.jsonl`) | Encrypted (identity-derived key) | `StorageVault`, one line sealed per message |
| Received/sent photos | Encrypted (identity-derived key) | `StorageVault`, applied inside `BlobStore` — the content hash used for mesh-wide addressing is still computed over the plaintext, only the on-disk bytes are sealed |
| Callsign | Plaintext | Needed to render the login prompt before auth |

"App-private storage" still means another app can't read it without root/a compromised
OS — this table is about what an attacker with access to the unlocked phone's
filesystem (or a backup of it) can see, not about cross-app isolation, which Android
provides regardless.

## Crypto primitives in use

All via Bouncy Castle's **lightweight API** (`org.bouncycastle.crypto.*` — no JCA
provider registration, to avoid clashing with Android's bundled Conscrypt/BC):

| Purpose | Primitive |
|---|---|
| Signing | Ed25519 |
| Key agreement | X25519 |
| Sealed DM encryption | ephemeral X25519 ECDH → HKDF-SHA256 → ChaCha20-Poly1305 |
| Identity-at-rest encryption | PBKDF2-HMAC-SHA256 (210k iterations) → ChaCha20-Poly1305 |
| Chat/media-at-rest encryption | HKDF-SHA256 (from the identity key) → ChaCha20-Poly1305, random nonce per seal |
| NodeId derivation | SHA-256 of the Ed25519 public key |
| Content addressing (blobs) | SHA-256 |

None of these are novel constructions — they're standard primitives composed in
well-known patterns (Noise X for sealed mail, PBKDF2 for password-based key
derivation). The novelty, such as it is, is in the mesh routing and store-carry-forward
layers, not the cryptography.

## Reporting a vulnerability

This is an early-stage, unfunded open-source project without a dedicated security
contact yet. Until one exists, please open a GitHub issue marked clearly as a security
report but **omit exploit details from the public issue body** — ask for a way to share
details privately, and a maintainer will follow up. Given the project's current stage
(pre-Phase-5, not yet used in a real emergency deployment), please use judgment about
urgency accordingly.
