# Architecture & design rationale

This document captures the *why* behind this project's design — the
alternatives considered, the constraints, and the honest latency budget.
For the *what* (wire format), see [`protocol-spec.md`](../protocol-spec.md).
For the *when* (build order), see [`roadmap.md`](roadmap.md).

## 0. Is this feasible without admin rights or a driver?

Yes. Every hard constraint maps onto an existing, documented, user-mode API:

| Constraint | Satisfied by |
|---|---|
| No admin install | Portable single `.exe`, runs from `%LOCALAPPDATA%` or `Downloads\` |
| No virtual audio driver | WASAPI **loopback capture** (user-mode, built into Windows since Vista) |
| No kernel/service install | WASAPI is a COM API called from a normal process |
| Phone stays the Bluetooth endpoint | Phone never proxies raw BT audio — Android's normal audio routing sends whatever the app plays to the already-connected A2DP device automatically |
| Works over phone hotspot, no Internet | Plain LAN sockets + mDNS, no cloud/relay server involved |
| Low latency | Achievable in the ~120–260ms range end-to-end (dominated by Bluetooth A2DP, not the relay pipeline — see §6) |

The one thing worth setting expectations on up front: **Bluetooth A2DP
itself typically adds 100–200ms**, true of every audio-relay tool that
exists — it's a Bluetooth/codec limitation, not something a Wi-Fi relay app
can remove. This project's job is to not add much *on top* of that.

## 1. System architecture overview

```
┌─────────────────────────┐        ┌──────────────────────────┐
│   Windows/Linux Laptop   │        │   Android Phone           │
│                          │        │                            │
│  Loopback capture (WASAPI│  UDP   │  UDP audio receiver        │
│   or PulseAudio)         │ (PCM)  │         │                  │
│         ▼                │───────▶│         ▼                  │
│  Framer + sequencer      │  LAN/  │  Jitter buffer             │
│         │                │hotspot │         │                  │
│         ▼                │        │         ▼                  │
│  UDP socket (audio)      │        │  AudioTrack (low-latency,  │
│                          │        │  USAGE_MEDIA) → routed by  │
│  TCP socket (control:    │  TCP   │  Android to whatever BT    │
│  discovery ack, pairing, │◀──────▶│  device is already active  │
│  heartbeat, reconnect)   │        │                            │
│                          │        │  Foreground Service +      │
│  mDNS advertise/browse   │  mDNS  │  NSD (mDNS) advertise/     │
│  ("_audiorelay._udp")    │◀──────▶│  browse                    │
└─────────────────────────┘        └──────────────────────────┘
```

Two independent apps, both required: a Windows app (pure user-mode,
portable, minimal status window) and an Android app (native, foreground
service, holds the socket even with the screen off).

### 1.1 Why native Android, not a browser

Browser-based (Web Audio/WebRTC in Chrome for Android) was seriously
considered and rejected for v1:

- Android Chrome suspends/throttles background tabs and applies aggressive
  audio-focus/Doze restrictions once the screen is off — exactly the
  "everyday real-time use" scenario this needs to survive.
- No way to run a foreground service with a wake lock from a tab, so
  reconnection-after-sleep is much less reliable.
- WebRTC would still need a signaling path and a media-engine bridge on the
  Windows side to inject non-microphone PCM into an `RTCPeerConnection` —
  real work, for a receiver that's *less* reliable than a native app.
- A native app gets `AudioTrack` in `PERFORMANCE_MODE_LOW_LATENCY`, direct
  control over buffer sizes, and a `MediaSession` so it behaves like a
  normal media player to Android (survives Doze the same way Spotify does).

A browser-based receiver stays a documented future option (roadmap Phase 7)
if a zero-install phone side ever becomes a priority, but it's the wrong
trade-off for reliability + latency today.

## 2. Desktop component

### 2.1 Audio capture

**Windows:**
- **API:** WASAPI loopback capture in shared mode, event-driven (not
  polling) for lowest latency.
- No driver, no admin — this is a standard `IAudioClient` activated on the
  default render endpoint with `AUDCLNT_STREAMFLAGS_LOOPBACK`. Documented,
  stable, used by every legitimate "record what you hear" tool on Windows.
- Capture the mix format the endpoint is already using (48kHz, stereo is
  typical) rather than forcing a resample at the driver level; the receiver
  honors whatever `sample_rate_id`/`channels` is in each packet (see
  `protocol-spec.md` §3), so there's no need to force a fixed rate.
- **Future-only, not v1:** process-specific loopback (`ACTIVATE_AUDIO_INTERFACE_PARAMS`
  process-loopback mode, Win10 2004+) for "capture just Spotify" —
  architected for (codec/format fields are per-packet, not global), not
  built, in v1.

**Linux:**
- **API:** the PulseAudio Simple API (`libpulse-simple-binding`), recording
  from the selected sink's `.monitor` source — the same "record what you
  hear" trick as WASAPI loopback, just PulseAudio's version of it. No
  driver, no root, no PipeWire-specific code needed: PipeWire's
  `pipewire-pulse` compatibility layer serves the same PulseAudio protocol,
  so this one backend covers classic PulseAudio and modern PipeWire distros
  alike (Ubuntu 22.04+, Fedora, etc.).
- Device enumeration and default-sink resolution go through
  `libpulse-binding`'s async introspection API (`Context` + a blocking
  `Mainloop::iterate` poll) since the Simple API has no introspection of
  its own; the actual audio read is the Simple API's blocking `read()`.
- Unlike WASAPI, requests a fixed 48kHz stereo S16LE stream rather than
  whatever rate the sink happens to be running at — PulseAudio resamples
  internally to match, so this backend never needs WASAPI's "map whatever
  rate the endpoint hands back onto the protocol's two supported rates"
  logic (see `protocol-spec.md` §3).
- Rejected for this: hand-rolled PipeWire bindings (a much larger binding
  surface for the value) and the `cpal` crate (a new heavyweight dependency
  per `AGENTS.md`'s "discuss first" rule, and not naturally suited to
  loopback-monitor recording).

### 2.2 Language/runtime choice: Rust

| Option | Verdict |
|---|---|
| **Rust** (`wasapi` + `windows` crates on Windows, `libpulse-binding`/`libpulse-simple-binding` on Linux, `tokio`, `egui`/`eframe` for UI) | **Chosen.** Single static binary per platform, zero runtime dependency, smallest attack surface, lowest overhead. The `wasapi` crate wraps loopback capture cleanly on Windows; the two `libpulse-*-binding` crates are safe wrappers over `libpulse-sys`/`libpulse-simple-sys`, not hand-rolled FFI, on Linux — neither platform needs raw COM/C calls. |
| C# + NAudio | Faster to prototype, but a self-contained single-file publish is 60–100MB and still needs the .NET runtime bundled — heavier for a "download and run" utility. |
| C++ raw WASAPI | Maximum control, smallest binary, but slowest to build correctly (COM lifetime bugs, no memory safety). |

**Electron/Tauri are deliberately avoided for the UI.** WebView2 is usually
present on Win11 but not guaranteed everywhere, and it reintroduces an
install-adjacent dependency. `egui`/`eframe` is pure Rust, compiles into the
same static binary, no WebView — keeps the "just an exe" promise airtight.

### 2.3 Desktop-side responsibilities

1. Loopback-capture the default output endpoint continuously.
2. Chunk into small frames (5–10ms, e.g. 480 samples @ 48kHz) and stamp each
   with a monotonically increasing sequence number + timestamp.
3. Advertise itself on the LAN via mDNS (`_audiorelay._udp.local`, TXT
   record with hostname + a short device ID).
4. Run a TCP control channel per connected phone: pairing handshake,
   capability exchange, heartbeat, clean disconnect/reconnect signaling.
5. Stream raw PCM frames over UDP to the paired phone's audio port once
   paired.
6. Persist last-paired device (ID + derived session key + last-known name)
   under `%LOCALAPPDATA%\AudioRelay\config.toml` on Windows, or
   `$XDG_CONFIG_HOME/audiorelay/config.toml` on Linux, so re-pairing isn't
   needed every launch.
7. Minimal UI: connection status, paired device name, pairing code when
   unpaired, Start/Stop, latency-mode toggle (Low/Balanced).

## 3. Android component

### 3.1 Language/runtime: Kotlin, native app

Flutter was considered and set aside: low-latency `AudioTrack` and precise
buffer control aren't well exposed through Flutter's audio plugins, and this
app would end up writing a Kotlin platform channel for the one part that
matters most anyway.

### 3.2 Android-side responsibilities

1. **NSD** (Network Service Discovery) to browse for `_audiorelay._udp` —
   Android's built-in mDNS wrapper, no extra library needed.
2. TCP control channel to the chosen laptop: pairing, heartbeat, capability
   negotiation.
3. `DatagramSocket` UDP receiver for the audio stream.
4. **Jitter buffer:** small ring buffer targeting ~20–40ms depth (tunable —
   larger on a hotspot connection than on a solid router). Sequence numbers
   detect loss/reorder; on a gap, conceal with a short fade-to-silence
   rather than complex FEC for v1.
5. **AudioTrack**, `PERFORMANCE_MODE_LOW_LATENCY`, `USAGE_MEDIA` /
   `CONTENT_TYPE_MUSIC`. `USAGE_MEDIA` is what lets Android's normal
   audio-routing logic send the stream to whatever device is already the
   active output — including the earbuds the user paired themselves.
   Deliberately **not** `USAGE_VOICE_COMMUNICATION`/SCO — that route is
   mono and low quality.
6. **Foreground Service** (`FOREGROUND_SERVICE_MEDIA_PLAYBACK` type on
   Android 14+) + a `MediaSession`, so the OS treats this like Spotify
   rather than a background process it's free to kill. Holds a partial
   `WakeLock` and a `MulticastLock` (needed for mDNS to keep working with
   the screen off).
7. Auto-reconnect: `ConnectivityManager` callbacks catch Wi-Fi/hotspot
   changes and IP churn, re-run discovery, and resume without user action.
8. Minimal UI: connected laptop name, current output device, latency
   indicator, Connect/Disconnect.

## 4. Network protocol summary

Full detail in `protocol-spec.md`. Key decisions:

### 4.1 Codec: raw PCM for v1, not Opus

Deliberate, LAN-specific call:

- Bandwidth is not the constraint — 48kHz/16-bit stereo PCM is ~1.5 Mbps,
  trivial for any Wi-Fi network or phone hotspot.
- A codec buys bandwidth at the cost of encode+decode latency (typically
  5–20ms combined) and CPU on both ends, for zero benefit on a local
  network.
- Raw PCM keeps the pipeline dead simple: capture → packetize → socket →
  jitter buffer → play.
- **v2 escape hatch:** the packet header reserves a `codec_id` byte from
  day one, so adding Opus later (if real-world testing on a weak hotspot
  shows it's worth it) is a non-breaking upgrade, not a redesign.

### 4.2 Transport split

Audio over UDP (loss-tolerant, latency-sensitive); pairing/heartbeat/control
over TCP (low-frequency, needs reliability, latency doesn't matter there).

## 5. Discovery & pairing

- **Discovery:** mDNS/DNS-SD both directions — Windows advertises via the
  `mdns-sd` Rust crate, Android browses via `NsdManager`. Works identically
  on a home router or the phone's own hotspot, since mDNS only needs
  multicast on the local subnet.
- **Pairing:** Windows generates a random 6-digit code and displays it; the
  user enters it once on the phone. The code seeds an HKDF-derived session
  key (see `protocol-spec.md` §5), which then encrypts UDP audio payloads
  with ChaCha20-Poly1305.
- After first pairing, both sides remember each other, so normal daily use
  is "open both apps, they reconnect automatically" — no code re-entry.

## 6. Latency budget (honest, not aspirational)

| Stage | Typical | Notes |
|---|---|---|
| WASAPI capture buffer | 3–10ms | Event-driven, small buffer |
| Packetization | ~5–10ms | One packet per chunk |
| Network (router Wi-Fi) | 1–5ms | LAN, same subnet |
| Network (phone hotspot) | 5–15ms | Slightly higher, still local |
| Jitter buffer (Android) | 20–40ms | The main *tunable* knob — trade latency for glitch-resistance here |
| AudioTrack low-latency buffer | 10–20ms | Android's floor, device-dependent |
| **Pipeline subtotal** | **~45–90ms** | This is what the app actually controls |
| Bluetooth A2DP (SBC/AAC) | 100–200ms | Inherent to the earbuds' codec — not fixable by this app |
| Bluetooth aptX-LL / LE Audio (LC3), if earbuds support it | 40–80ms | Automatically better if the earbuds negotiate it |
| **Realistic end-to-end total** | **~150–290ms** | Fine for video/music/general desktop audio; noticeably behind for twitch-reaction gaming |

Worth stating plainly rather than overpromising: this is a "watch videos,
listen to music, sit in meetings" latency profile, not a competitive-gaming
one — and that ceiling is set by Bluetooth, not by this project.

## 7. Reliability & reconnection

- Heartbeat (1s) on the control channel; 3 missed beats → mark
  disconnected, stop feeding audio, start mDNS re-browse with exponential
  backoff.
- Wi-Fi/hotspot IP changes: both platforms expose network-change callbacks
  (`ConnectivityManager` on Android; adapter/route-change notifications on
  Windows) — trigger re-discovery rather than assuming the old socket still
  works.
- Laptop sleep/wake and phone sleep/wake: control-channel heartbeat
  naturally detects these as a disconnect; reconnect flow resumes without
  user action.
- Packet loss concealment: short fade-to-silence over the missing span
  rather than repeating stale samples (repeating audibly "stutters" more
  than a brief dip).

## 8. Future enhancements (architected for, not built in v1)

- Opus codec fallback for weak/congested networks (packet header already
  reserves a codec-ID byte).
- Process-specific loopback capture ("capture just this app").
- Browser-based receiver as a zero-install alternative, once/if the native
  app's reliability patterns are proven and worth porting.
- Microphone-back-to-laptop path — explicitly out of scope for v1: this is
  a one-way relay, not a VoIP app.

## 9. Summary

Every hard constraint (no admin/root, no driver, phone stays the BT endpoint)
maps cleanly onto APIs that already exist for exactly this purpose: WASAPI
loopback on Windows, PulseAudio monitor-source capture on Linux, and normal
`USAGE_MEDIA` audio routing plus NSD/mDNS on Android. The only
externally-imposed ceiling is Bluetooth A2DP latency,
which no software on either end can remove — everything else in the
pipeline is within this project's control and budgeted at under 100ms.
