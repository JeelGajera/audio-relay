# Releasing

Releases are cut by pushing a `v*` tag. `.github/workflows/release.yml` does
the rest: builds the Windows executable, the Linux binary, and the Android
APK/AAB, generates checksums, and publishes a GitHub Release with notes
taken from `CHANGELOG.md`.

## One-time setup: Android signing

Android refuses to install an unsigned APK, so a release build needs a
keystore. **Nothing about it goes in the repository** — the build reads it
from environment variables, and CI supplies those from repository secrets.

Generate a keystore once and keep it safe. Losing it means you can never ship
an update that upgrades an existing install; users would have to uninstall
first.

```sh
keytool -genkeypair -v \
  -keystore audio-relay-release.jks \
  -alias audio-relay \
  -keyalg RSA -keysize 4096 -validity 10000
```

Then add four repository secrets under **Settings → Secrets and variables →
Actions**:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 audio-relay-release.jks` (macOS: `base64 -i audio-relay-release.jks`) |
| `ANDROID_KEYSTORE_PASSWORD` | The keystore password you chose above |
| `ANDROID_KEY_ALIAS` | `audio-relay` |
| `ANDROID_KEY_PASSWORD` | The key password (often the same as the keystore password) |

Without these the release still builds, but the artifacts are named
`-unsigned` and cannot be installed. That is deliberate: it keeps the release
path testable from a fork, and makes an unsigned build impossible to mistake
for a real one.

## Cutting a release

1. **Update the version in both places.** They are separate files with no
   shared source, and the workflow fails the build if they disagree:
   - `desktop-app/Cargo.toml` → `version`
   - `android-app/app/build.gradle.kts` → `versionName` (and bump
     `versionCode`, which must increase for every Play Store upload)
2. **Write the changelog.** Everything under `## [Unreleased]` in
   `CHANGELOG.md` becomes the release notes verbatim.
3. **Rehearse first.** Run the workflow manually from the Actions tab
   (**release → Run workflow**) with the version number. This builds
   everything and publishes nothing, so a broken pipeline is found before a
   tag exists rather than after.
4. **Tag and push:**
   ```sh
   git tag -a v0.2.0 -m "v0.2.0"
   git push origin v0.2.0
   ```

A tag with a suffix — `v0.2.0-rc1`, `v0.2.0-beta` — is published as a
prerelease automatically. The version check compares against the base version,
so `v0.2.0-rc1` builds correctly from sources declaring `0.2.0`.

## What gets published

| Artifact | Notes |
|---|---|
| `audio-relay-<version>-windows-x86_64.zip` | Portable `.exe` plus LICENSE and README |
| `audio-relay-<version>-windows-x86_64.exe` | The bare executable, for anyone who would rather not unzip |
| `audio-relay-<version>-linux-x86_64.tar.gz` | Portable binary plus LICENSE and README; requires PulseAudio or PipeWire's `pipewire-pulse` layer, standard on any desktop distro with audio |
| `audio-relay-<version>.apk` | Installed directly on a phone |
| `audio-relay-<version>.aab` | Play Store submission format — **not** installable by hand |
| `SHA256SUMS.txt` | Checksums for everything above |

## Known limitations

- **The Windows executable is not code-signed.** Authenticode signing needs a
  paid certificate, so SmartScreen warns on first run. The checksums are the
  only integrity check available; point users at them.
- **No Play Store upload.** The `.aab` is produced so this is a drop-in later
  (it needs a Play Console account and a service-account JSON), but nothing
  publishes it today.
- **No macOS build.** Capture there has no system-audio loopback API without
  a virtual device or a ScreenCaptureKit rewrite. Supporting it is a new
  capture backend, not a packaging change — see `docs/architecture.md` §2.1.
- **The Linux capture backend is unverified against a real PulseAudio/PipeWire
  server.** It's only ever been compile- and lint-checked against
  `libpulse-dev` in CI — see `docs/roadmap.md` Phase 0.
