## Summary

<!-- What does this change do, and why? Link related issues with "Fixes #123" or "Refs #123". -->

## Component(s)

- [ ] `desktop-app` (Rust)
- [ ] `android-app` (Kotlin)
- [ ] `protocol-spec.md`
- [ ] Docs / governance
- [ ] CI

## Roadmap phase

<!-- Which docs/roadmap.md phase does this belong to, if any? -->

## Testing

<!-- What did you actually run? Be specific: `cargo test`, `./gradlew testDebugUnitTest`, manual on-device testing, etc. -->

-

## Hardware verification

<!-- If this touches WASAPI capture, AudioTrack/BT routing, or mDNS/NSD, say
what you tested on real hardware (device/OS versions) vs. only compiled or
reasoned about. It's fine to say "compile-checked only, not run on a device"
— see CONTRIBUTING.md → Hardware verification. -->

-

## Protocol changes

<!-- If this changes anything in protocol-spec.md: confirm both desktop-app
and android-app were updated in this PR, and whether protocol_version was
bumped. Write "N/A" if unaffected. -->

-

## Checklist

- [ ] I read `AGENTS.md` / `CONTRIBUTING.md` for this repo's conventions.
- [ ] Relevant lints/tests pass locally (`cargo test && cargo clippy` and/or
      `./gradlew testDebugUnitTest lint`, as applicable).
- [ ] I updated `CHANGELOG.md` under `[Unreleased]` if this is user-facing.
- [ ] I updated `docs/roadmap.md` checkboxes if this completes a roadmap item.
