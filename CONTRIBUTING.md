# Contributing to audio-relay

Thanks for considering a contribution. This project relays your own audio
from your own laptop to your own phone — it's meant to stay small, auditable,
and dependency-light, so contributions that keep that spirit are especially
welcome.

## Before you start

- Skim [`docs/architecture.md`](docs/architecture.md) for the design
  rationale and [`docs/roadmap.md`](docs/roadmap.md) for the phased plan —
  it tells you what's intentionally not built yet vs. what's a bug.
- Read [`protocol-spec.md`](protocol-spec.md) if you're touching networking
  code on either side. It's the single source of truth for the wire format;
  the two apps must never drift from it independently.
- For anything bigger than a small fix (new dependency, protocol change,
  new subsystem), open an issue first to discuss the approach before writing
  code. Small fixes, doc corrections, and tests can just be a PR.

## Development setup

- **Windows app:** Rust stable, `cargo build`/`cargo test`/`cargo clippy` —
  see [`desktop-app/README.md`](desktop-app/README.md).
- **Android app:** Android Studio or the command-line SDK, API 34+ — see
  [`android-app/README.md`](android-app/README.md).
- Full build/test commands for agents and humans alike are in
  [`AGENTS.md`](AGENTS.md).

## Workflow

1. Fork the repo and create a branch off `main` (`git checkout -b
   short-description`).
2. Make your change. Keep commits focused — don't mix a protocol change with
   an unrelated refactor.
3. Add or update tests for anything with testable logic (packet
   encode/decode, jitter buffer behavior, config persistence, sequence/drift
   math). UI and hardware-routing behavior can't always be unit tested —
   say so in the PR instead of skipping silently.
4. Run the relevant lints/tests locally (see above) before opening the PR.
5. Open a PR against `main`. Fill in the PR template — in particular, be
   explicit about what you verified on real hardware vs. what you didn't
   (see "Hardware verification" below).
6. Address review feedback. Once approved and CI is green, a maintainer will
   merge.

## Commit messages

Imperative mood, and prefix with the component when it disambiguates:

```
desktop-app: fix sequence wraparound in jitter calc
android-app: fade to silence on packet-loss gap instead of repeating samples
protocol: reserve codec_id=1 for future Opus support
docs: correct latency budget table for aptX-LL
```

## Coding standards

See `AGENTS.md` → **Conventions** for the authoritative list (formatting,
lint requirements, dependency policy). The short version: `rustfmt` +
clippy-clean on the Rust side, standard Kotlin/Android style on the Android
side, and no new heavyweight UI frameworks (see architecture doc §2.2 for
why Electron/Tauri/WebView were rejected).

## Hardware verification

Several behaviors in this project (WASAPI loopback on a non-admin account,
`AudioTrack` routing to A2DP vs. phone speaker, mDNS over an Android hotspot)
can only be confirmed on real devices — see `docs/roadmap.md` Phase 0. If
your PR touches one of these paths:

- State plainly in the PR description what you tested on real hardware
  (device/OS versions) and what you only compiled/reasoned about.
- Don't mark a Phase 0 roadmap item "done" without a real device test behind
  it.
- It's completely fine to submit code you've only compile-checked — just say
  so, so a reviewer with the right hardware can verify before merge.

## Reporting bugs / requesting features

Use the issue templates under `.github/ISSUE_TEMPLATE/`. For security
issues (e.g. in the pairing/encryption handshake), see
[`SECURITY.md`](SECURITY.md) instead of a public issue.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). Be
respectful; disagreements about design are fine and expected, personal
attacks aren't.

## License

By contributing, you agree your contributions are licensed under this
project's [MIT License](LICENSE).
