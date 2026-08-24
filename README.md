# audio-relay

Low-latency Windows → Android audio relay, played back through whatever
Bluetooth device your phone already has connected.

No admin rights. No virtual audio driver. No cloud service. Your phone stays
the Bluetooth endpoint — this project just gets your laptop's audio to it
over the local network (Wi-Fi or the phone's own hotspot) so Android's normal
audio routing can send it on to your earbuds/speaker exactly like it would
for Spotify.

```
Laptop (Windows)                          Phone (Android)
┌─────────────────────────┐               ┌───────────────────────────┐
│ WASAPI loopback capture  │   UDP (PCM)   │ UDP receiver → jitter buf │
│  → framer/sequencer      │──────────────▶│  → AudioTrack (USAGE_MEDIA)│
│ TCP control (pairing,    │◀─────────────▶│  → routed to your BT      │
│  heartbeat, reconnect)   │   TCP + mDNS  │    device by Android       │
└─────────────────────────┘               └───────────────────────────┘
```

## Status

Early, active development. See [`docs/roadmap.md`](docs/roadmap.md) for the
phased build plan and what's implemented so far. This is **not yet a
finished product** — treat it as a working scaffold you can build on, not a
polished release.

## Why this exists

Full rationale, the alternatives that were considered and rejected, and the
honest latency budget live in [`docs/architecture.md`](docs/architecture.md).
Short version: Bluetooth A2DP itself adds ~100–200ms, and no software on
either end can remove that. This project's job is to not add much on top of
it — realistic end-to-end latency is in the **~150–290ms** range, which is
fine for video/music/meetings and not intended for competitive gaming.

## Project layout

```
audio-relay/
├── windows-app/       # Rust — WASAPI loopback capture, network, control
├── android-app/       # Kotlin — receiver, jitter buffer, playback, service
├── protocol-spec.md   # Canonical wire protocol — keep both apps in sync
└── docs/              # Architecture, roadmap, latency budget
```

## Building

### Windows app (Rust)

Requires the Rust toolchain (stable) and Windows (WASAPI is a Windows-only
API — the crate isolates this behind `#[cfg(target_os = "windows")]` so the
protocol/network/config modules can still be built and unit-tested on any
OS).

```sh
cd windows-app
cargo build
cargo test
```

See [`windows-app/README.md`](windows-app/README.md) for details.

### Android app (Kotlin)

Requires Android Studio (or the command-line SDK) with API 34+.

```sh
cd android-app
./gradlew assembleDebug
```

See [`android-app/README.md`](android-app/README.md) for details.

## Contributing

Contributions are welcome — see [`CONTRIBUTING.md`](CONTRIBUTING.md) for the
workflow, coding standards, and how the phased roadmap maps to good
first-issue-sized work. Please also read
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

If you're an AI coding agent (or a human using one) working in this repo,
read [`AGENTS.md`](AGENTS.md) first — it has the build/test commands and
repo conventions the human-facing docs don't spell out.

## Security

Found a vulnerability (e.g. in the pairing/encryption handshake)? Please
follow the responsible-disclosure process in [`SECURITY.md`](SECURITY.md)
rather than opening a public issue.

## License

[MIT](LICENSE)
