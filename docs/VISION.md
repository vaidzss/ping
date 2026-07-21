# MeshAid — Vision & Roadmap

## What this is

MeshAid lets people **chat, share live GPS location, and send images/videos with no internet, no cellular, no servers** — built for disaster zones (floods, earthquakes) and areas where connectivity is shut down, when communication matters most.

Every phone running the app is a node: messages hop phone-to-phone over Bluetooth LE, media moves over on-demand Wi-Fi links, and every device carries messages forward for others (store-carry-forward). Optional ~$30 LoRa radios extend text/GPS range to kilometers.

## Now: the offline emergency mesh (Phases 0–5)

1. **Phase 0 — Protocol spike:** prove multi-hop BLE relay and airplane-mode video transfer on real phones.
2. **Phase 1 — Android MVP:** BLE mesh chat, GPS pins on an offline map, photo transfer, SOS broadcast, QR contact verification.
3. **Phase 2 — Media pipeline:** content-addressed blobs, resumable chunked transfer, hop-by-hop media relay, HEVC compression.
4. **Phase 3 — iOS:** same shared core; MultipeerConnectivity for iOS↔iOS media; BLE interop with Android.
5. **Phase 4 — LoRa lane:** pair Meshtastic-class nodes for kilometers of text/GPS range; gateway phones stitch distant clusters.
6. **Phase 5 — Hardening:** field tests, battery profiling, external security review, store review prep.

## Later: civic reporting & legal aid for India (Phase 6)

Once the mesh works, MeshAid grows a second, **online** tier — a platform for India where people can:

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
- Requires a real backend (the first server-side component), identity/abuse safeguards, and careful legal review.
- Current architecture decisions already made compatible: content-addressed media, signed packets, store-carry-forward queueing.
