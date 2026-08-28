# Changelog

All notable changes to this project are documented here. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); this project
follows [Semantic Versioning](https://semver.org/) starting with this first
tagged release. Future releases will list changes *relative to* the
previous tag, the normal way — this entry is different on purpose: there is
no previous release to diff against, so instead of a changelog it's a
feature manifest of what `v0.1.0` actually ships.

## [0.1.0] - 2026-08-28 - Initial release

### What it does

A two-sided relay: a desktop app (Windows via WASAPI loopback, Linux via
PulseAudio/PipeWire monitor-source capture) captures system audio and
streams it over the local network to an Android app, which plays it back
through whatever Bluetooth device is already connected. No admin rights, no
virtual audio driver, no cloud service — see
[`docs/architecture.md`](docs/architecture.md) for the full rationale.

### Desktop app (`desktop-app`, Rust)

- Loopback capture on Windows (WASAPI) and Linux (PulseAudio Simple API,
  transparently covering PipeWire distros through `pipewire-pulse`) —
  pick which output device to relay, or leave it on the system default.
  Falls back to the system default automatically if the selected device
  disappears (e.g. a Bluetooth speaker gets disconnected), rather than
  retrying a device that no longer exists.
- **"Also play locally while relaying"** — on by default (this laptop's own
  output keeps playing, same as before); turn it off to mute this laptop
  while streaming, so only the phone plays it.
- Low/Balanced latency mode (~5ms vs ~10ms capture chunks), changeable live.
- mDNS advertisement, TCP control channel (pairing, heartbeat, reconnect),
  UDP audio streaming, TOML config persistence.
- A pairing code is always visible whenever nothing is actively streaming —
  including after a disconnect, not just on first launch — so recovering
  from a stale pairing (a different phone, or one that forgot this laptop)
  never requires restarting the app.
- Full design system: left nav rail, dark/light/system appearance, a
  breathing status pill while streaming, a live output level meter,
  per-digit pairing-code boxes, a Windows 11 Mica backdrop with the system
  accent colour, an embedded app icon, and a licenses page grouped by
  license rather than a raw per-crate table.
- Packaged as a portable binary — no installer, no admin/root. Windows gets
  a `.exe`; Linux gets both a plain binary (`.tar.gz`) and an installable
  `.deb`.

### Android app (`android-app`, Kotlin/Compose)

- NSD (mDNS) discovery, TCP control channel, UDP receiver with a
  sequence-aware jitter buffer (loss concealed with a brief fade to silence,
  not a repeated chunk) and timestamp-based clock-drift correction, so
  latency stays put over a long session instead of slowly creeping as the
  two clocks diverge.
- Low-latency `AudioTrack` playback (`USAGE_MEDIA`) — Android's normal
  routing sends it to whichever Bluetooth/wired/USB device (or the phone
  speaker) is already active, or a specific one you pick in Settings.
- A **Start/Stop switch right on the Home screen** — not just the
  notification's Stop action, which used to be the only way to stop it
  without force-closing the app.
- Foreground service with a wake lock and multicast lock, so discovery and
  playback keep working with the screen off; auto-reconnect with backoff on
  a dropped session, and automatic re-discovery when the phone changes
  networks (Wi-Fi to hotspot, a new SSID, a DHCP renewal onto a different
  subnet).
- Forgetting a paired laptop is handled properly end-to-end: it clears the
  auto-reconnect pointer too (not just the saved key), and if the laptop
  still thinks you're paired but you no longer have its key, the app falls
  back to asking for a fresh pairing code instead of retrying a reconnect
  that could never succeed.
- Material You dynamic colour, a real dark mode, edge-to-edge, a splash
  screen, an animated level visualiser, and a licenses page grouped by
  license.

### Relay reliability

Defects found during real-device testing, each of which independently
degraded or killed the one thing this app exists to do. All are covered by
regression tests or by a measurement probe:

- **Capture arrived in bursts, not as a stream (Linux).** The single worst
  defect in the project. `pa_simple_new` was given no `BufferAttr`, so
  PulseAudio chose its default fragment size — documented as "something
  like 2s". Measured against a real PipeWire server: 194 of every 200 reads
  returned instantly, then the stream **stalled for 341ms**. Audio was
  therefore delivered as ~34 chunks at once followed by a third of a second
  of nothing, which no reasonable jitter buffer can hide, and which dumped
  tens of UDP packets into the network at once for a hotspot to drop. With
  an explicit fragment size the median gap is 10.65ms and the worst case
  11.11ms. The `capture_delivery_cadence` probe measures this on demand.
- **Windows: a silent second tore down the capture stream.** A WASAPI
  loopback stream only raises events while the endpoint is actually
  rendering, so with nothing playing the event wait times out — which the
  code treated as a failure and answered by rebuilding the whole stream,
  COM device enumeration included, every single second, clipping the start
  of whatever played next. Silence is this app's resting state; it is now
  handled as such.
- **Windows: the capture buffer had no headroom.** `IAudioClient::Initialize`
  was given the hardware *minimum* period as its buffer capacity. Shared
  mode runs at the engine's *default* period, and the minimum only applies
  to exclusive mode, which loopback cannot use — so the buffer was smaller
  than a single engine period and any late read overran it.
- **The jitter buffer was configured in packets, so it was ~18ms deep.**
  Buffer depth is now set in milliseconds (default 120ms, adjustable 30–400ms)
  and converted to packets against the size actually observed on the wire.
  Counting packets tied the setting to how the sender happened to be
  packetising: the shipped default of "3 chunks" meant ~18ms, and even the
  maximum setting only reached ~36ms — well under normal Wi-Fi jitter, let
  alone a phone hotspot.
- **The capture→sender queue was unbounded.** An unbounded queue between a
  real-time producer and a slower consumer does not buffer, it accumulates
  — every queued chunk is delay that never comes back. It is now bounded
  and drops to stay current, so a stall costs a brief dropout instead of
  permanent lag.
- **The level meter ran on the audio path.** RMS was computed and pushed
  into a `StateFlow` once per packet — ~165 times a second — driving that
  many Compose recompositions on a deadline-sensitive loop. Now throttled
  to ~25/s.
- **Session teardown resurrected the audio track, leaking one per
  reconnect.** `close()` releases the `AudioTrack` while the playback loop
  is blocked inside `write` — that release is what unblocks it — so the
  resulting `IllegalStateException` was indistinguishable from a track
  dying mid-session, and the recovery path rebuilt it. Teardown therefore
  quietly revived what it was tearing down: the loop never exited, kept
  animating the visualiser with no audio, could not be stopped, and left
  behind a live `AudioTrack` playing silence plus a permanently blocked
  thread. Every reconnect leaked another, which is why playback got
  progressively choppier the longer the app ran and only a restart helped.
  Shutdown is now explicit and terminal.
- **A zero-length write counted as success.** `AudioTrack.write` returns 0
  when it accepted nothing — a track that is not playing — and returns it
  immediately, so the playback loop spun at CPU speed. That pegged a core
  (the UI going unresponsive) and, because each iteration also advances the
  jitter buffer's expected sequence, raced playback past the sender until
  it desynced into silence.
- **Playback health is now logged** every 10s (`adb logcat -s AudioRelay`):
  buffer depth against target, concealment percentage, late packets,
  resyncs, latency trims and measured clock drift. Choppiness has several
  possible causes that sound identical from the outside, and this is what
  distinguishes real packet loss from a starved buffer or a misfiring
  correction.
- **Uneven MTU splitting caused periodic chopping.** Splitting a 1920-byte
  chunk as `[1168, 752]` meant packet sizes alternated on the wire. The
  receiver infers packet duration from the packets it receives — nothing in
  the protocol tells it — and sizes its target depth, concealment length and
  latency-trim threshold from that, so all three oscillated packet to
  packet: the target swung between 20 and 31 chunks. Depth sitting normally
  at 31 was then read as backlog the moment a large packet arrived, and the
  latency trim discarded ~11 packets of good audio. Audible as a small cut
  every few seconds against otherwise clean playback. Chunks are now split
  into equal packets, and the receiver additionally requires a new packet
  size to repeat before adopting it, so no future packetisation can
  reintroduce the oscillation.
- **Accumulated backlog became permanent latency.** Any transient stall —
  a descheduled receive loop, a burst of Wi-Fi retransmits — queued audio
  that nothing ever shed, leaving playback running seconds behind live for
  the rest of the session. The drift correction cannot fix this: it moves
  one ~21µs PCM frame per ten packets, so draining 200ms of excess would
  take about ten minutes. The jitter buffer now detects standing depth well
  above its target and skips forward to the configured depth, trading one
  brief discontinuity for correct latency from then on. The UDP receive
  buffer was also sized down, since audio queued in the kernel is latency
  the receiver cannot see or correct.
- **A refused pairing key was retried forever instead of re-pairing.** The
  worst of the pairing defects. When the laptop rejected the phone's stored
  key, that arrived as an ordinary `IOException` — indistinguishable from a
  dropped connection — so the reconnect supervisor retried it on a backoff,
  with the same stale key, indefinitely. The laptop meanwhile sat showing a
  pairing code that the phone never asked anyone to type. There was no
  escape from the loop short of clearing app data. A refusal is now a
  distinct, permanent outcome: the stale key is discarded and pairing
  restarts on the same connection, prompting for the code.
- **A mistyped code cost the whole connection.** Rejection tore the session
  down and dropped back into the reconnect backoff, so the next try was a
  fresh connection rather than another go at the prompt already on screen.
  The prompt now re-asks in place, up to a few attempts, and says the code
  was refused instead of failing silently.
- **Endless retrying now explains itself.** After several failed attempts
  the UI stops implying success is imminent and says what to check — both
  apps open, same Wi-Fi, guest networks usually blocked — while continuing
  to retry underneath, and offers the laptop list so the user can act.
- **Forgetting a device left its session running.** On both sides:
  forgetting erased the stored key but not the live connection, so audio
  kept flowing over a key that had just been revoked. The laptop stayed in
  "Streaming" and therefore never displayed a pairing code, and the phone
  kept trying to resume a session it could no longer prove it owned — so
  pairing was never offered and the only way back was restarting both apps.
  Forgetting now ends the session on whichever side it happens, and the
  peer is dropped through a fresh handshake that correctly asks for a code.
- **An explicit Connect tap could be silently ignored.** `connectTo`
  refuses to start while an attempt is in flight, so tapping Connect during
  an automatic retry did nothing — which read as the app connecting by
  itself and retrying forever instead of prompting. A user tap now preempts
  whatever is in flight.
- **Small robustness gaps:** the UDP receive socket used the OS default
  buffer (a short burst overflowed it, dropping audio in the kernel where
  the jitter buffer's loss handling could not see it), and `AudioTrack` was
  allocated at exactly `getMinBufferSize`, leaving no tolerance for a late
  write.

- **Every audio packet was IP-fragmented.** A 10ms chunk of 48kHz stereo
  16-bit PCM is 1920 bytes, which with the header and auth tag made a
  1949-byte datagram — well over the 1472-byte limit for UDP over a normal
  1500-byte MTU. Every packet therefore travelled as two IP fragments, and
  losing either one lost the whole packet, roughly doubling the effective
  loss rate. On a phone hotspot, where loss is already common, this is the
  "song keeps cutting out" symptom. The sender now splits a captured chunk
  across as many MTU-safe packets as it takes
  (`packet::MAX_DATAGRAM_BYTES`); each is independently sequenced, so no
  protocol change was needed.
- **Playback could wedge permanently.** If the play position ever ran past
  the sender, the jitter buffer classified every subsequent packet as
  "too late" and discarded it — forever. Audio went silent while the
  desktop kept capturing and transmitting normally, and only restarting the
  app recovered it. The buffer now detects both a wildly-diverged sequence
  position and sustained starvation, and re-prebuffers from wherever the
  sender actually is.
- **A dead `AudioTrack` turned the playback loop into a busy spin.**
  `AudioTrack.write()`'s return value was ignored, so when the track died —
  which is what happens when the audio route changes underneath it, e.g.
  Bluetooth disconnecting mid-stream — every write failed instantly instead
  of blocking. The loop then spun at CPU speed, and since each iteration
  also advanced the jitter buffer's expected sequence number, playback
  raced past the sender within seconds and hit the lockup above. Writes are
  now checked, a dead track is rebuilt, and a failing write backs off.
- **Concealment silence assumed a fixed 10ms packet size.** The sender's
  actual packet length depends on its latency mode and the MTU split, so
  concealing a lost packet could insert more audio than was lost — a drift
  the correction loop then had to fight. The receiver now learns the real
  size from the packets it receives.

### Fixed

- The desktop app **crashed on switching appearance to Light or Dark**
  (`Failed to find Name("subtitle") in Style::text_styles`). egui keeps a
  separate `Style` per theme, and `Context::set_style` writes only the
  currently-active one, so the app's custom text styles were installed into
  whichever theme happened to be active at startup and the other theme was
  left on egui's stock style. Now installed into both.

### Security

- The pairing code never crosses the network. `PAIR_REQUEST` carries an
  `HMAC-SHA256(code, phone_device_id || nonce)` proof instead of the code
  itself — a fresh nonce every connection — and both sides derive the
  session key locally via HKDF once the laptop verifies the proof. UDP
  audio payloads are encrypted with ChaCha20-Poly1305. See
  [`protocol-spec.md`](protocol-spec.md) §5.
- Repair-flow proof verification uses a constant-time comparison, closing a
  timing side-channel on the laptop side.

### Verified

- `desktop-app`: full `cargo test`/`clippy`/`fmt` pass — 74 tests, including
  a cross-implementation known-answer test for the ChaCha20-Poly1305
  payload encryption against Android's implementation. The Windows-only
  endpoint-mute code (for "Also play locally while relaying") is
  additionally cross-compiled and clippy-checked against the real
  `x86_64-pc-windows-gnu` target and the `windows` crate's actual API.
- `android-app`: full `./gradlew assembleDebug testDebugUnitTest lint`
  pass, including a discrete-event simulation of a drifting sender that
  asserts jitter-buffer depth converges and that ordinary network jitter
  alone never provokes a drift correction.
- The `.deb` package is built and its contents verified directly (`cargo
  deb`, then `dpkg-deb --contents`/`--info` against the actual output, and
  the extracted binary run) — not just assumed to work from the
  configuration.

### Known gaps

See [`docs/roadmap.md`](docs/roadmap.md) Phase 0 for the full list — in
short: WASAPI capture, `AudioTrack` → A2DP routing, and mDNS over an actual
Android hotspot have not yet been exercised on physical hardware in this
project's history. Clock-drift correction is validated in simulation, not
against two real crystals over a multi-hour session. No macOS build (no
system-audio loopback API without a new capture backend — see
`docs/architecture.md` §2.1). No Play Store upload (the `.aab` is produced,
nothing publishes it).
