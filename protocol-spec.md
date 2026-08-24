# audio-relay protocol specification

**Version: 1** (draft, pre-1.0 — breaking changes are still expected)

This is the canonical definition of the wire protocol between `windows-app`
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
    `config.toml`, see `windows-app/README.md`).
  - `name` — human-readable hostname, e.g. `DESKTOP-A1B2C3`.
  - `protocol_version` — this document's version, as an integer (`1`).

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
  |<----------- HELLO_ACK ----------------|
  |                                        |
  | (first pairing)                       |
  |------- PAIR_REQUEST{code} ----------->|
  |<---------- PAIR_OK{...} --------------|
  |                                        |
  | (already paired — reconnect)          |
  |----- REPAIR{device_id, proof} ------->|
  |<---------- PAIR_OK{...} --------------|
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

### 4.2 Message reference

| `type` | Sender | Fields | Purpose |
|---|---|---|---|
| `HELLO` | phone | `protocol_version`, `device_id`, `device_name`, `audio_port` | Opens the session, announces the phone's identity, protocol version, and the UDP port it has already bound and is listening on for audio (not necessarily the same as the TCP control port). The laptop sends audio datagrams to `(tcp_peer_ip, audio_port)`. |
| `HELLO_ACK` | laptop | `protocol_version`, `device_id`, `device_name`, `paired: bool` | Laptop's identity; `paired` tells the phone whether this laptop already remembers it (skip straight to `REPAIR`) or needs `PAIR_REQUEST`. |
| `PAIR_REQUEST` | phone | `code` (string, 6 digits) | First-time pairing: user typed the code shown on the laptop's UI. |
| `REPAIR` | phone | `device_id`, `proof` | Reconnecting a previously-paired device. `proof` = `HMAC-SHA256(persisted_key, device_id \|\| nonce_from_HELLO_ACK)` (nonce is a `nonce` field added to `HELLO_ACK` on this path), hex-encoded. Proves the phone holds the previously-derived key without resending it. |
| `PAIR_OK` | laptop | `session_id` (hex, 8 bytes), `session_key` (hex, 32 bytes) — **only present on `PAIR_REQUEST` flow**; omitted on successful `REPAIR`, which reuses the persisted key | Pairing/reconnect succeeded. On first pairing, derives and transmits the session key (see §5.2); the control channel itself must be encrypted for this one message using the pairing code as a pre-shared secret, so the session key never crosses the network in the clear even on first pair. |
| `PAIR_FAIL` | laptop | `reason` | Wrong code / unknown device_id / bad proof. Phone should let the user retry. |
| `CAPABILITIES` | both | `sample_rate` (Hz), `channels` | Exchanged after pairing so both sides agree on format before audio starts. Laptop sends what it's actually capturing; phone acks with what it will play (normally matches — see §3, receivers should still honor the header per-packet). |
| `PING` | either | `t` (sender's monotonic ms) | Heartbeat, sent every 1s by both sides independently. |
| `PONG` | either | `t` (echoed from the `PING`) | Reply to `PING`. 3 consecutive missed `PONG`s (3s) ⇒ treat as disconnected, close sockets, laptop stops sending audio, phone starts mDNS re-browse with exponential backoff (see `docs/architecture.md` §7). |
| `BYE` | either | — | Clean disconnect notice, sent before closing the socket on purpose (user hit Stop/Disconnect). Distinguishes an intentional stop from a dropped connection so the other side doesn't immediately try to reconnect. |

## 5. Pairing & key derivation

1. Laptop generates a random 6-digit `code` (uniform over `000000`–`999999`,
   cryptographically random) and displays it in its UI. The code is never
   sent over the network except as the `PAIR_REQUEST.code` field, and only
   ever after the phone's user has read and typed it — it is a
   user-transcribed shared secret, not a protocol secret.
2. Phone sends `PAIR_REQUEST{code}`.
3. Laptop verifies the code matches what it's currently displaying (and
   hasn't expired — codes are valid for 5 minutes, then regenerated).
4. Both sides derive a 32-byte session key via **HKDF-SHA256**:
   `key = HKDF(ikm = code, salt = device_id_phone || device_id_laptop, info
   = "audio-relay-session-v1", length = 32)`.
5. Laptop persists `{device_id: phone_id, session_key, device_name}` to
   `config.toml` (see `windows-app/README.md`) so future connections can use
   `REPAIR` instead of asking for the code again. Phone persists the same
   tuple (keyed by laptop's `device_id`) in its local storage.
6. On every connection (first pair or reconnect), the laptop mints a fresh
   random `session_id` (used in the UDP nonce, §3.1) and sends it in
   `PAIR_OK`.

This is intentionally lightweight — it is a **pairing code**, not a
password: it only has to resist a casual "random neighbor on the same
Wi-Fi/hotspot guesses their way in" attacker during the ~5 minute window
it's displayed and typed once, not a sustained offline attack. Real
confidentiality of the audio stream comes from the derived session key + the
per-packet ChaCha20-Poly1305 encryption, not from the code's length.

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
