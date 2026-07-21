# MeshAid Wire Protocol — v1

Binary, fixed-header packet format for the BLE control lane. Design goals: tiny (BLE-friendly),
uniform-size on the wire (traffic-analysis resistance — Bridgefy's plaintext-ID mistake is the
anti-pattern), versioned, signature-capable.

## Packet layout (big-endian)

```
offset  size  field
0       1     version        (0x01)
1       1     type           (see Packet types)
2       1     ttl            (0..7; relays decrement; density clamp may lower)
3       1     flags          bit0 HAS_RECIPIENT, bit1 SIGNED, bit2 ENCRYPTED
4       8     timestampMs    (sender clock, ms since epoch)
12      8     senderId       (first 8 bytes of SHA-256 of sender Ed25519 public key)
[20]    8     recipientId    (present iff HAS_RECIPIENT)
next    2     payloadLen     (u16; max 65535, but mesh lane enforces much lower caps)
next    n     payload
[next]  64    signature      (present iff SIGNED; Ed25519 over header+payload with ttl zeroed —
                              ttl mutates in flight and must not break the signature)
next    *     padding        (zeros, up to the next size bucket)
```

**Size buckets:** encoded packets are padded to the next of {320, 640, 1280, 4096} bytes.
Packets larger than 4096 are sent unpadded (bulk lane only). Decoders read `payloadLen` and
ignore trailing padding.

**Message id (dedup key):** first 16 bytes of SHA-256(senderId ‖ timestampMs ‖ type ‖ payload).

## Packet types

| value | type            | payload |
|-------|-----------------|---------|
| 0x01  | CHAT            | encrypted or plaintext chat body |
| 0x02  | GPS_BEACON      | lat/lon/accuracy/battery (fixed 20 bytes) |
| 0x03  | SOS             | GPS_BEACON body + optional utf-8 note — max priority, never rate-limited on relay |
| 0x04  | PRESENCE        | display name + capabilities bitmask |
| 0x05  | ACK             | messageId being acknowledged |
| 0x10  | MEDIA_OFFER     | blob hash (32) + size (u32) + mime tag (u8) + thumbnail (≤32 KiB) |
| 0x11  | MEDIA_REQUEST   | blob hash + chunk bitmap request |
| 0x12  | MEDIA_CHUNK     | blob hash + chunk index (u32) + chunk bytes (bulk lane) |
| 0x20  | SUMMARY_VECTOR  | DTN sync: list of bundle ids held |
| 0x21  | BUNDLE_PULL     | list of bundle ids requested |
| 0x30  | HANDSHAKE       | Noise handshake material |

## Mesh rules (control lane)

- **Flooding:** relay iff ttl > 0 after decrement AND message id not in dedup cache.
- **Dedup cache:** 1000-entry LRU, 5-minute expiry.
- **Density clamp:** when a node sees ≥6 direct neighbors, it clamps relayed broadcast ttl to 5
  (FireChat dense-crowd collapse countermeasure).
- **Rate limiting:** per-origin token bucket on relays; SOS exempt (emergencies are never
  throttled) and MEDIA_CHUNK exempt (demand-driven, bounded by an explicit request; dedup+TTL
  still bound its flooding cost).
- **Payload caps:** control lane rejects payloads > 4 KiB except MEDIA_CHUNK.
  Any compressed payload declares its inflated size and is rejected before decompression if it
  exceeds the cap (decompression-bomb defense).
- **Presence:** PRESENCE is always sent with ttl = 1 (direct neighbors only, never relayed);
  payload is the utf-8 display name. Receivers treat the sender as a direct neighbor and expire
  it after 35 s of silence. Presence also triggers DTN sync with previously unseen peers.

## Payload formats

- **SUMMARY_VECTOR / BUNDLE_PULL:** `count(u8)` + count × 16-byte message ids (max 255/packet;
  larger vectors are chunked across packets). Sent recipient-addressed with ttl 1 on peer contact,
  at most once per peer per 30 s.
- **MEDIA_OFFER:** `blobHash(32) | totalSize(u32) | mimeTag(u8) | chunkSize(u16)`. Mime tags:
  0 octet, 1 jpeg, 2 png, 3 mp4. Control-lane media is capped at 1 MiB (bitchat-style);
  larger media waits for the bulk lane.
- **MEDIA_REQUEST:** `blobHash(32)`, recipient-addressed to the offerer (multi-hop OK).
- **MEDIA_CHUNK:** `blobHash(32) | index(u32) | data(≤2048)`, recipient-addressed to the
  requester. Chunks are unverified in flight; the receiver accepts the blob only if the full
  SHA-256 matches the offered content address (forged/corrupt transfers are discarded whole).

## DTN bundles (store-carry-forward)

- A bundle wraps one encoded packet destined for a node not currently reachable.
- Default lifetime 24 h; copy budget 8 (Spray-and-Wait); SOS bundles get priority eviction
  protection.
- On peer contact: exchange SUMMARY_VECTOR, pull missing bundles via BUNDLE_PULL.

## Crypto

- Identity: Ed25519 (signing) + X25519 (ECDH) generated on first run; NodeId derived from the
  Ed25519 public key.
- Live sessions: Noise XX (mutual auth + forward secrecy). *(Planned — Phase 1.)*
- Offline recipients: sealed envelope — ephemeral X25519 → HKDF-SHA256 → ChaCha20-Poly1305
  (Noise X pattern trade-off: no forward secrecy for sealed mail).
- Contact exchange: QR code `meshaid://contact?v=1&name=…&ed=…&x=…` (base64url keys), verified
  in person, fully offline.
