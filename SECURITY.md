# Security Policy

audio-relay moves audio from your laptop to your phone over your local
network, and includes a pairing/encryption handshake (see
[`protocol-spec.md`](protocol-spec.md) §5) specifically so that only a
device you've explicitly paired can receive or inject audio. Bugs in that
handshake, in the session-key derivation, or in the packet-encryption layer
are the highest-priority class of issue this project has.

## Supported Versions

This project is pre-1.0 and does not yet have tagged releases. Until a
first tagged release exists, only the `main` branch is supported/receives
fixes.

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for security vulnerabilities.

Instead, use GitHub's private vulnerability reporting for this repository
(**Security → Report a vulnerability** on the repo's GitHub page). If that
isn't available to you, open a normal issue titled only "Security contact
needed" with no details, and a maintainer will follow up with a private
channel.

Please include:

- A description of the vulnerability and its impact (e.g. "unauthenticated
  device can inject audio into an active session", "pairing code is
  brute-forceable because...").
- Steps to reproduce, or a proof-of-concept if you have one.
- The component affected (`windows-app`, `android-app`, or the protocol
  itself) and version/commit.

## What to expect

This is a small open-source project maintained on a best-effort basis —
there's no SLA, but security reports are treated as the top priority. You
should get an acknowledgment as soon as a maintainer sees the report, and
we'll credit you in the fix's changelog entry unless you ask not to be.

## Scope

In scope: the pairing handshake, session-key derivation, UDP payload
encryption, and any code path that could let an unpaired device on the same
network read or inject audio, or crash either app from network input.

Out of scope: the underlying OS APIs this project calls (WASAPI, AudioTrack,
mDNS/NSD) — report those to the OS vendor. Also out of scope: Bluetooth A2DP
itself, which this project doesn't implement or control.
