# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
follows [Semantic Versioning](https://semver.org/) once it has a first
tagged release.

## [Unreleased]

### Security

- **Protocol v2**: the pairing code is no longer sent over the wire.
  `PAIR_REQUEST` previously carried the raw code in cleartext, letting a
  passive LAN eavesdropper recompute the session key from that one packet.
  It now carries an `HMAC-SHA256(code, phone_device_id || nonce)` proof
  instead — a fresh nonce from every `HELLO_ACK` — and both sides derive
  the session key locally via HKDF once the laptop verifies the proof, so
  the code and the key itself never cross the network. This also fixes a
  correctness bug where `PAIR_OK` never actually carried the session key it
  was supposed to on first pairing, which meant pairing could not
  previously complete. See `protocol-spec.md` §5 for the updated flow.
- Repair-flow proof verification (`verify_repair_proof`) now uses a
  constant-time comparison (`subtle::ConstantTimeEq`) instead of `==` on
  hex strings, closing a timing side-channel on the laptop side.

### Added

- **Clock-drift correction** (roadmap Phase 5): the receiver now acts on each
  packet's `timestamp_ms`. Sender and receiver clocks are never exactly
  equal, so over a long session the jitter buffer used to leak one way —
  filling until it dropped a whole chunk, or draining into permanent
  concealment silence. Buffer depth is now regulated back to the target by
  dropping or duplicating a single PCM frame at a time (~21µs, inaudible;
  a whole-chunk correction is the ~10ms click this avoids), with a deadband
  and rate limit so ordinary network jitter provokes no correction at all.
- **Network-change handling** (roadmap Phase 4): a `ConnectivityManager`
  default-network callback tears down the session and restarts NSD discovery
  when the phone moves between networks — Wi-Fi to hotspot, SSID switch, or a
  DHCP renewal onto a different subnet — instead of waiting for three
  heartbeats to time out. Debounced, so one transition causes one restart.
  Shown in the UI as its own status rather than a generic disconnect.
- **Reconnect backoff supervision**: a dropped session now schedules its own
  retry (1s doubling to a 30s ceiling, reset by a successful stream or a
  network change). Previously reconnection depended entirely on NSD choosing
  to re-announce the service, which it has no obligation to do — so a dropped
  connection could simply never recover.

- Project scaffolding: contribution/governance docs (`AGENTS.md`,
  `CLAUDE.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`),
  architecture and roadmap docs, CI workflows.
- `protocol-spec.md`: canonical UDP audio packet format and TCP control
  message schema.
- `windows-app`: WASAPI loopback capture, packetizer, mDNS advertise, TCP
  control channel with pairing handshake + heartbeat, UDP audio sender,
  ChaCha20-Poly1305 payload encryption, TOML config persistence.
- `android-app`: NSD discovery, TCP control channel client, UDP receiver
  with sequence-aware jitter buffer and packet-loss concealment,
  low-latency `AudioTrack` playback via `USAGE_MEDIA`, foreground service
  with wake lock + multicast lock.
- Configurable UI on both sides, three screens each (Home / Settings /
  About): pick which output device audio is captured from (Windows) or
  played to (Android — Bluetooth/wired/USB/speaker, or automatic), tune
  latency/jitter-buffer depth, manage paired devices, and an About screen
  with version, build commit/date, GitHub link, and license/third-party
  info on both apps.

### Fixed

- A session that ended while waiting for the user to type a pairing code left
  the pairing prompt on screen with nothing behind it.
- `CancellationException` was caught and swallowed alongside real errors in
  the connection coroutine, so a deliberately cancelled session completed as
  though it had finished normally.

### Verified

- `windows-app`: full `cargo test`/`clippy`/`fmt` pass, including a
  cross-implementation known-answer test for the ChaCha20-Poly1305 payload
  encryption.
- `android-app`: every platform-independent module (`AudioPacket`,
  `ControlMessage`, `Crypto`, `JitterBuffer`, `ReconnectBackoff`) compiled
  and unit-tested against the project's actual Kotlin 1.9.24/`kotlinx`
  toolchain on a plain JVM — 47 tests — including the Android-side half of
  that same cross-implementation crypto vector, and a discrete-event
  simulation of a drifting sender that asserts buffer depth converges and
  that jitter alone does not provoke correction. See `docs/roadmap.md`
  Phase 0 and `android-app/README.md`.

### Known gaps

- Phase 0 hardware validation spikes (WASAPI on non-admin accounts,
  `AudioTrack` → A2DP routing, mDNS over an Android hotspot) have not been
  verified on physical devices in this environment — see
  `docs/roadmap.md`.
- The Android Gradle Plugin/SDK build itself is unverified in this
  environment (no route to `dl.google.com`) — everything touching the
  Android framework (`RelayService`, `AudioReceiver`, `PlaybackTrack`,
  `NsdDiscovery`, the UI) is written and reviewed but not compiled here.
- Clock-drift correction is validated in simulation, not against two real
  crystals over a multi-hour session. Its constants (deadband, smoothing,
  correction rate) are reasoned defaults that on-device testing may want to
  revisit.
- Network-change handling cannot be exercised here at all — it needs a real
  device actually moving between networks.
- No packaging/release pipeline yet (single portable `.exe`, signed APK).
