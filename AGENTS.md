# AGENTS.md

Instructions for AI coding agents (and humans who want the terse version)
working in this repository. This file is the canonical machine-readable
guide; `CLAUDE.md` just points here so Claude Code picks it up automatically.

## What this project is

A two-sided relay: a Rust app on Windows captures system audio via WASAPI
loopback and streams it over the LAN to a Kotlin Android app, which plays it
back through whatever Bluetooth device is already connected. Full design
rationale is in `docs/architecture.md`; the phased build plan is in
`docs/roadmap.md`; the wire format is the single source of truth in
`protocol-spec.md`.

**Read `protocol-spec.md` before touching anything in `windows-app/src/protocol/`
or `android-app/.../network/`.** The two apps do not share code (different
languages, different platforms) — the spec is what keeps them compatible.
If you change the wire format, update the spec first, bump its version note,
then update both implementations in the same PR.

## Repo layout

```
windows-app/    Rust binary — capture, protocol, network, minimal UI
android-app/    Kotlin/Gradle app — discovery, network, audio, foreground service, UI
protocol-spec.md   Wire format + control messages (canonical)
docs/           Architecture, roadmap, latency budget
```

## Build / test / lint commands

### windows-app (Rust)

```sh
cd windows-app
cargo build              # debug build
cargo test                # unit tests (protocol/config/network logic — platform-independent)
cargo clippy --all-targets -- -D warnings
cargo fmt --check
```

Note: `src/capture/` is gated behind `#[cfg(target_os = "windows")]` because
WASAPI only exists on Windows. On a non-Windows machine, `cargo build`/`test`
still compile and run everything else (protocol, network, config) — that's
intentional so CI and non-Windows contributors can validate most of the
codebase. Full binary builds and any capture-path changes need a Windows
machine (or the `windows-app-ci` GitHub Actions job, which runs on
`windows-latest`) to actually verify.

### android-app (Kotlin)

```sh
cd android-app
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lint
./gradlew ktlintCheck     # if the ktlint plugin is present; otherwise follow AOSP style
```

Unit tests (jitter buffer, packet parsing, sequence-gap handling) should not
need an emulator. Anything touching `AudioTrack` routing, `NsdManager`, or
the foreground service's lifecycle needs a real device or emulator with
Bluetooth — call this out explicitly in the PR description rather than
claiming it's verified.

## Conventions

- **Commits:** imperative mood, scoped prefix when it helps
  (`windows-app: fix sequence wraparound in jitter calc`). Keep commits
  focused; don't mix protocol changes with unrelated refactors.
- **Rust:** `rustfmt` defaults, `clippy` clean (`-D warnings` in CI — don't
  add `#[allow]` to silence a real lint without a comment explaining why).
  Keep platform-specific code behind `cfg` gates rather than `#[cfg(windows)]`
  sprinkled ad hoc through shared modules — isolate it in `capture/` and
  anything else that's genuinely Windows-only.
- **Kotlin:** standard Android/Kotlin style (4-space indent, no wildcard
  imports). Prefer coroutines/Flow over raw threads/callbacks for anything
  new. Keep `AudioTrack`/socket code off the main thread.
- **No new heavyweight dependencies** (Electron/Tauri/WebView equivalents,
  large frameworks) without discussing first — the whole point of this
  project is a small, dependency-light footprint (single portable `.exe`,
  no bundled runtime). See `docs/architecture.md` §2.2 for why Tauri/Electron
  were rejected for the Windows UI.
- **Don't invent codecs/formats not in `protocol-spec.md`.** v1 is
  deliberately raw PCM (see spec's rationale). If you want Opus or another
  codec, that's a `docs/roadmap.md` Phase 7 discussion, not a drive-by change
  — the packet header already reserves a `codec_id` byte for this.

## Things that need real hardware to verify

This project's riskiest assumptions are hardware/OS behaviors that can't be
confirmed by reading code or running on a CI runner:

1. WASAPI loopback capture actually works from a non-admin account on the
   target Windows versions.
2. `AudioTrack` in `PERFORMANCE_MODE_LOW_LATENCY` + `USAGE_MEDIA` actually
   routes to A2DP earbuds, not the phone speaker.
3. mDNS advertise/browse works over an Android-hosted hotspot, not just a
   home router (multicast is the thing most likely to misbehave there).

These are `docs/roadmap.md` Phase 0 validation spikes. If you're an agent
without access to the physical devices, say so explicitly rather than
claiming these are verified — leave a note in the PR and/or a tracking issue
instead of marking the related roadmap item done.

## Do not

- Do not commit secrets, session keys, or paired-device config files (see
  `.gitignore` — `%LOCALAPPDATA%\AudioRelay\config.toml` and Android
  equivalents are runtime state, never repo content).
- Do not silently change the UDP packet header layout — it's a breaking
  protocol change and needs a version bump + spec update + both apps
  touched together.
- Do not add telemetry/analytics/network calls beyond LAN discovery and the
  relay itself. This app talks to your own phone on your own network and
  nothing else.
