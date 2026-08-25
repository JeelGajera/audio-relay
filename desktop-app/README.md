# desktop-app

Rust app: loopback capture (WASAPI on Windows, PulseAudio's Simple API on
Linux) → packetizer → UDP, plus the mDNS advertisement, TCP pairing/control
channel, and a minimalist, configurable `egui` UI (Home / Settings / About —
pick which output device to relay, tune latency, manage paired phones, see
build info). See `docs/architecture.md` (repo root) for the full design
rationale and `protocol-spec.md` for the wire format.

## Building

```sh
cargo build            # debug
cargo build --release  # release (LTO + stripped, see Cargo.toml [profile.release])
```

On Linux you'll also need `libpulse-dev` (or your distro's equivalent —
e.g. `libpulse-dev` on Debian/Ubuntu, `pulseaudio-libs-devel` on Fedora)
installed for the PulseAudio bindings to link.

Real capture requires a matching OS: WASAPI on Windows
(`#[cfg(target_os = "windows")]`), PulseAudio's Simple API on Linux
(`#[cfg(target_os = "linux")]`) — each lives in its own submodule of
`src/capture/mod.rs`, with a stub for any other OS. Everything else
(`protocol`, `network`, `config`, `state`) is platform-independent and
builds/tests on any OS, and on Linux the real capture backend builds and
tests too — which is how this crate gets meaningful CI coverage from a
`ubuntu-latest` job as well as `windows-latest`, see
`.github/workflows/desktop-app-ci.yml`.

## Running

```sh
cargo run --release
```

On first run this generates a random device ID and writes
`%LOCALAPPDATA%\AudioRelay\config.toml` on Windows, or
`$XDG_CONFIG_HOME/audiorelay/config.toml` on Linux. The status window shows
a 6-digit pairing code — enter it in the Android app to pair. No installer,
no admin/root rights: this is meant to be run straight from wherever you put
the binary (`Downloads\`, `%LOCALAPPDATA%\AudioRelay\`, `~/.local/bin`,
wherever).

## Module map

| Module | Responsibility |
|---|---|
| `capture/` | Loopback capture — WASAPI on Windows, PulseAudio Simple API on Linux, stub elsewhere |
| `protocol/packet.rs` | UDP audio packet encode/decode |
| `protocol/control.rs` | TCP control-channel message types |
| `protocol/crypto.rs` | Pairing code, HKDF session-key derivation, ChaCha20-Poly1305 payload encryption |
| `network/discovery.rs` | mDNS advertisement (`_audiorelay._udp`) |
| `network/control_channel.rs` | Accepts phone connections, runs pairing + heartbeat |
| `network/audio_sender.rs` | Encrypts and sends PCM frames over UDP |
| `config.rs` | Loads/saves `config.toml` (device ID, paired devices) |
| `state.rs` | Shared runtime state read/written by network tasks and the UI (incl. latency mode, capture device selection) |
| `ui/home.rs` | Status, pairing code, streaming on/off |
| `ui/settings.rs` | Capture device picker, latency mode, paired-device management |
| `ui/about.rs` | Version, build commit/date, GitHub link, license/third-party info |
| `build.rs` | Injects `GIT_HASH`/`GIT_COMMIT_DATE` at compile time for the About tab |

## Testing

```sh
cargo test              # unit tests for protocol/config/state (no hardware needed)
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

Unit tests cover packet encode/decode, control-message (de)serialization
and forward-compat handling, key derivation, payload encryption, config
persistence, and pairing-code state — none of it requires a Windows or Linux
audio server to run, so it's exactly what `desktop-app-ci`'s
`check-cross-platform` job runs on every PR (and its `build-linux` job goes
further, compiling and testing the real PulseAudio backend too).

What unit tests **can't** cover — and what still needs a real machine or a
running audio server, see `docs/roadmap.md` Phase 0:

- Whether WASAPI loopback capture actually works from a non-admin account
  on your Windows version.
- Whether PulseAudio monitor-source capture (`capture::linux_impl`) actually
  works against a real running PulseAudio or PipeWire server — no sandbox
  in this project's history has had either running, so this backend has
  only ever been compile- and lint-checked against `libpulse-dev`.
- End-to-end audio quality/latency once paired with the Android app.

## Known limitations (tracked in `docs/roadmap.md`)

- Sample rate/channel count sent in `CAPABILITIES` is currently hardcoded
  (48kHz/stereo) rather than read from the live capture format; the actual
  per-packet header still carries the true format, so a mismatch here is
  informational, not a correctness bug — but it should read from
  `CaptureFormat` once capture and control-channel setup are sequenced
  together.
- No process-specific loopback ("capture just this app") — captures
  whatever the *selected* output device is playing, system-wide (you can
  now choose *which* output device, just not a specific app on it).
- Capture-device enumeration/selection (`src/capture/mod.rs`) is written
  against the real `wasapi` 0.15.0 crate API (verified by reading its
  source, not guessed — `DeviceCollection::get_nbr_devices`/
  `get_device_at_index`/`get_id`/`get_friendlyname` all confirmed), but the
  restart-on-device-change path itself hasn't run on a real Windows machine
  with multiple output devices yet — see `docs/roadmap.md` Phase 0.
- Likewise, `capture::linux_impl` is written against the real
  `libpulse-binding`/`libpulse-simple-binding` API (verified by reading
  their source), and compiles/lints/tests cleanly against `libpulse-dev`,
  but has never been connected to an actual PulseAudio or PipeWire daemon —
  see `docs/roadmap.md` Phase 0. The Simple API's blocking `read()` also has
  no way to be interrupted mid-call, so a device-selection change is only
  noticed between chunks (a few milliseconds of extra restart latency, not a
  correctness issue).
