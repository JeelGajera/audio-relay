# audio-relay protocol specification

**Version: 2** (draft, pre-1.0 — breaking changes are still expected)

**v2 change (security fix):** the pairing code is no longer sent over the
network in `PAIR_REQUEST` — v1's design let a passive LAN eavesdropper
capture the code (and the device IDs used as the HKDF salt) during pairing
and independently recompute the session key. `PAIR_REQUEST` now carries a
challenge-response proof instead, the same way `REPAIR` already avoided
sending the session key — see §4.2 and §5.

This is the canonical definition of the wire protocol between `desktop-app`
and `android-app`. Both implementations must match this document exactly.
If you change the protocol, update this file first, bump the version below,
and update both apps in the same PR — see `AGENTS.md`.

All multi-byte integers are **big-endian (network byte order)** unless noted.

---

## 1. Overview

Two channels, both initiated by the phone connecting to the laptop:

| Channel | Transport | Purpose | Frequency |
|---|---|---|---|
| Control | TCP | Discovery handshake, pairing, capability exchange, heartbeat, clean disconnect | Low (handshake + 1/s heartbeat) |
| Audio | UDP | PCM audio frames | High (one packet per ~5-10ms chunk) |

Discovery (finding the laptop's IP/port in the first place) happens over
mDNS/DNS-SD, described in §5.

## 2. Discovery (mDNS)

The Windows app advertises a service:

- **Service type:** `_audiorelay._udp.local.`
- **Port:** the TCP control port (audio's UDP port is sent separately in
  `HELLO`, see §4.1 — mDNS only needs to get the phone to the control port).
- **TXT record keys:**
  - `id` — a stable device ID (UUIDv4, generated once and persisted in
    `config.toml`, see `desktop-app/README.md`).
  - `name` — human-readable hostname, e.g. `DESKTOP-A1B2C3`.
  - `protocol_version` — this document's version, as an integer (`2`).

The Android app browses for `_audiorelay._udp` via `NsdManager`. Multiple
laptops on the same network show up as multiple resolved services; the user
picks one (or the app auto-connects to the last-paired `id` if seen).

## 3. UDP audio packet format

Byte layout — fields are packed back-to-back with no padding (**not**
4-byte-aligned rows), header is **13 bytes** total:

```
byte offset:  0        1  2  3  4    5  6  7  8    9      10     11 12
             +--------+--------------+--------------+------+------+-----+
             |codec_id| sequence(u32)| timestamp(u32)|rate  |chans |rsvd |
             |  (u8)  |  big-endian  |   big-endian  |(u8)  |(u8)  |(u16)|
             +--------+--------------+--------------+------+------+-----+
byte offset:  13                                                      end
             +----------------------------------------------------------+
             |                 payload (variable length)                |
             +----------------------------------------------------------+
```

| Field | Type | Notes |
|---|---|---|
| `codec_id` | u8 | `0x00` = raw PCM, signed 16-bit little-endian interleaved samples. All other values reserved for future use (e.g. `0x01` = Opus — see `docs/roadmap.md` Phase 7). A receiver **must** drop packets with an unrecognized `codec_id` rather than attempt to play them. |
| `sequence` | u32 | Monotonically increasing per session, starts at 0, wraps on overflow. Used by the jitter buffer for loss/reorder/dedup detection. |
| `timestamp_ms` | u32 | Sender's monotonic clock at capture time, milliseconds, wraps on overflow (~49.7 days — irrelevant given sessions are hours at most). Used for clock-drift correction, not for absolute time. |
| `sample_rate_id` | u8 | `0` = 44100 Hz, `1` = 48000 Hz. Others reserved. |
| `channels` | u8 | `1` = mono, `2` = stereo. v1 only ever sends `2` (captures the mix format directly), but receivers should honor whatever is sent. |
| `reserved` | u16 | Must be `0` on send, ignored on receive. Reserved for a future per-packet flags field. |
| `payload` | bytes | Raw interleaved PCM samples, `sample_rate_id`/`channels` describe how to interpret them. Length is implicit (whatever remains in the UDP datagram after the 13-byte header). |

### 3.1 Encryption

Once a session key has been established via pairing (§5), the **payload**
(not the header — the receiver needs `sequence`/`timestamp_ms` unencrypted
to run the jitter buffer even on a packet it ultimately fails to decrypt) is
encrypted with **ChaCha20-Poly1305**, using:

- **Key:** the 32-byte session key derived in §5.
- **Nonce:** 12 bytes, constructed as `session_id (8 bytes) || sequence (4
  bytes, big-endian)`. `session_id` is a random 8-byte value chosen by the
  laptop at pairing/reconnect time and sent in `PAIR_OK`/`CAPABILITIES`;
  reusing `sequence` (already in the header) as part of the nonce means we
  don't need a separate counter, but it means **a session key must never be
  reused across sessions with the same `session_id`** — always mint a new
  random `session_id` per connection.
- The Poly1305 authentication tag (16 bytes) is appended after the
  encrypted payload. A receiver that fails authentication must drop the
  packet (treat as loss for jitter-buffer purposes) and must not play the
  payload.

Before pairing completes there is no session key; the audio channel simply
does not run until `PAIR_OK` has been exchanged on the control channel.

## 4. TCP control channel

Newline-delimited JSON, one message per line (`\n`-terminated UTF-8 JSON
objects). This channel is low-frequency — human-readable JSON costs nothing
here and makes debugging with `nc`/`netcat` trivial.

Every message has a `type` field. Unknown `type` values must be ignored
(forward compatibility), not treated as a protocol error.

### 4.1 Handshake sequence

```
Phone                                   Laptop
  |------------- HELLO ------------------>|
  |<-------- HELLO_ACK{nonce} ------------|
  |                                        |
  | (first pairing — user typed `code`    |
  |  shown on the laptop's UI)            |
  |----- PAIR_REQUEST{proof} ------------>|
  |<---------- PAIR_OK{session_id} -------|
  |                                        |
  | (already paired — reconnect)          |
  |----- REPAIR{device_id, proof} ------->|
  |<---------- PAIR_OK{session_id} -------|
  |                                        |
  |<------- CAPABILITIES{...} ------------|
  |-------- CAPABILITIES{...} ----------->|
  |                                        |
  |<===== audio flows over UDP now ======>|
  |                                        |
  |-------------- PING ------------------>|
  |<------------- PONG -------------------|
  |          (repeats every 1s)           |
  |                                        |
  |-------------- BYE -------------------->|
```

Neither `code` nor `session_key` ever crosses the network, on either path —
both `PAIR_REQUEST` and `REPAIR` are challenge-response proofs against the
`nonce` from `HELLO_ACK`, and both sides derive the session key
independently (see §5).

### 4.2 Message reference

| `type` | Sender | Fields | Purpose |
|---|---|---|---|
| `HELLO` | phone | `protocol_version`, `device_id`, `device_name`, `audio_port` | Opens the session, announces the phone's identity, protocol version, and the UDP port it has already bound and is listening on for audio (not necessarily the same as the TCP control port). The laptop sends audio datagrams to `(tcp_peer_ip, audio_port)`. |
| `HELLO_ACK` | laptop | `protocol_version`, `device_id`, `device_name`, `paired: bool`, `nonce` (hex, 8 bytes) | Laptop's identity; `paired` tells the phone whether this laptop already remembers it (send `REPAIR`) or needs `PAIR_REQUEST`. `nonce` is a fresh random value, **always sent** (not just on the `paired` path), used as the challenge for whichever proof the phone sends next. |
| `PAIR_REQUEST` | phone | `proof` (hex) | First-time pairing. `proof` = `HMAC-SHA256(code, phone_device_id \|\| nonce_from_HELLO_ACK)`, hex-encoded, where `code` is the 6-digit code the user read off the laptop's UI and typed into the phone. **The code itself is never sent** — this proves the phone's user typed the same code the laptop is displaying, without putting it on the wire. See §5. |
| `REPAIR` | phone | `device_id`, `proof` | Reconnecting a previously-paired device. `proof` = `HMAC-SHA256(persisted_key, device_id \|\| nonce_from_HELLO_ACK)`, hex-encoded. Proves the phone holds the previously-derived key without resending it. |
| `PAIR_OK` | laptop | `session_id` (hex, 8 bytes) | Pairing/reconnect succeeded. No key material is ever included — on both `PAIR_REQUEST` and `REPAIR`, each side independently derives (or already holds) the same session key; see §5. `session_id` seeds the UDP nonce (§3.1). |
| `PAIR_FAIL` | laptop | `reason` | Wrong code / unknown device_id / bad proof. Phone should let the user retry (same connection, same `nonce` — see §5). |
| `CAPABILITIES` | both | `sample_rate` (Hz), `channels` | Exchanged after pairing so both sides agree on format before audio starts. Laptop sends what it's actually capturing; phone acks with what it will play (normally matches — see §3, receivers should still honor the header per-packet). |
| `PING` | either | `t` (sender's monotonic ms) | Heartbeat, sent every 1s by both sides independently. |
| `PONG` | either | `t` (echoed from the `PING`) | Reply to `PING`. 3 consecutive missed `PONG`s (3s) ⇒ treat as disconnected, close sockets, laptop stops sending audio, phone starts mDNS re-browse with exponential backoff (see `docs/architecture.md` §7). |
| `BYE` | either | — | Clean disconnect notice, sent before closing the socket on purpose (user hit Stop/Disconnect). Distinguishes an intentional stop from a dropped connection so the other side doesn't immediately try to reconnect. |

## 5. Pairing & key derivation

**Neither the pairing code nor the derived session key is ever transmitted,
on either the first-pairing or reconnect path.** Both sides derive the same
session key independently from values they already have; the network only
ever carries a proof that each side did so correctly. (v1 of this spec sent
the code in `PAIR_REQUEST` and the key in `PAIR_OK`, which let a passive LAN
eavesdropper recover both by capturing one pairing exchange — see the v2
changelog note at the top of this file.)

1. Laptop generates a random 6-digit `code` (uniform over `000000`–`999999`,
   cryptographically random) and displays it in its UI. The code is a
   user-transcribed shared secret — the phone's user reads it off the
   laptop's screen and types it into the phone — and is never sent over the
   network in any form.
2. Laptop sends a fresh random 8-byte `nonce` (hex-encoded) in every
   `HELLO_ACK`, regardless of whether it already knows this phone.
3. **First pairing:** phone computes
   `proof = HMAC-SHA256(key = code, msg = phone_device_id || nonce)`,
   hex-encoded, and sends `PAIR_REQUEST{proof}`. Laptop computes the same
   HMAC using the code it's currently displaying (rejecting if none is
   currently valid — codes are valid for 5 minutes, then regenerated) and
   compares it to the received proof **in constant time**. A match proves
   the phone's user typed the same code the laptop is showing, without the
   code ever appearing on the wire.
4. **Reconnect:** phone computes
   `proof = HMAC-SHA256(key = persisted_session_key, msg = phone_device_id || nonce)`
   and sends `REPAIR{device_id, proof}`; laptop looks up the persisted key
   for `device_id` and compares in constant time, as above.
5. On a successful `PAIR_REQUEST`, both sides derive the 32-byte session key
   via **HKDF-SHA256**:
   `key = HKDF(ikm = code, salt = device_id_phone || device_id_laptop, info
   = "audio-relay-session-v1", length = 32)` — the laptop computes this once
   it has verified the proof; the phone computes the exact same value
   locally from the code it typed, without waiting for anything from the
   laptop. On a successful `REPAIR`, both sides already hold the persisted
   key from the original pairing — nothing new is derived.
6. Laptop persists `{device_id: phone_id, session_key, device_name}` to
   `config.toml` (see `desktop-app/README.md`) so future connections can use
   `REPAIR` instead of asking for the code again. Phone persists the same
   tuple (keyed by laptop's `device_id`) in its local storage.
7. On every connection (first pair or reconnect), the laptop mints a fresh
   random `session_id` (used in the UDP nonce, §3.1) and sends it in
   `PAIR_OK`.

The 6-digit code is intentionally lightweight — it is a **pairing code**,
not a password: it only has to resist a casual "random neighbor on the same
Wi-Fi/hotspot guesses their way in" attacker during the ~5 minute window
it's displayed and typed once, not a sustained offline attack, and an
online guesser is limited to `MAX_PAIR_ATTEMPTS` (5) tries per connection.
Real confidentiality of the audio stream comes from the derived session key
+ the per-packet ChaCha20-Poly1305 encryption, not from the code's length —
but as of v2, an eavesdropper who isn't actively guessing the code no
longer gets the key for free just by capturing the pairing exchange.

## 6. Compatibility / versioning

- `protocol_version` is exchanged in `HELLO`/`HELLO_ACK` and in the mDNS TXT
  record. A mismatch should be surfaced to the user ("laptop app is a
  different version") rather than silently attempting to interoperate —
  this project has no cross-version compatibility guarantee pre-1.0.
- Adding new optional fields to existing JSON messages is not a breaking
  change (readers must ignore unknown fields). Adding a new `type` is not
  breaking (readers must ignore unknown types). Changing the UDP header
  layout, removing/renaming a field, or changing a field's meaning **is**
  breaking and requires a `protocol_version` bump.
