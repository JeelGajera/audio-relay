# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
follows [Semantic Versioning](https://semver.org/) once it has a first
tagged release.

## [Unreleased]

### Added

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

### Verified

- `windows-app`: full `cargo test`/`clippy`/`fmt` pass, including a
  cross-implementation known-answer test for the ChaCha20-Poly1305 payload
  encryption.
- `android-app`: every platform-independent module (`AudioPacket`,
  `ControlMessage`, `Crypto`, `JitterBuffer`) compiled and unit-tested
  against the project's actual Kotlin 1.9.24/`kotlinx` toolchain on a plain
  JVM, including the Android-side half of that same cross-implementation
  crypto vector — see `docs/roadmap.md` Phase 0 and `android-app/README.md`.

### Known gaps

- Phase 0 hardware validation spikes (WASAPI on non-admin accounts,
  `AudioTrack` → A2DP routing, mDNS over an Android hotspot) have not been
  verified on physical devices in this environment — see
  `docs/roadmap.md`.
- The Android Gradle Plugin/SDK build itself is unverified in this
  environment (no route to `dl.google.com`) — everything touching the
  Android framework (`RelayService`, `AudioReceiver`, `PlaybackTrack`,
  `NsdDiscovery`, the UI) is written and reviewed but not compiled here.
- Clock-drift correction from packet timestamps and the adaptive/tunable
  jitter-buffer depth (Phase 5) are implemented at a basic level, not fully
  tuned.
- No packaging/release pipeline yet (single portable `.exe`, signed APK).
