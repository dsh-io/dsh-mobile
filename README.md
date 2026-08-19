# dsh-mobile

Run [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (`dsh`)
on an Android ARM device — no Termux, no root, no toolchain.

The APK embeds a minimal Debian rootfs (built with debootstrap), a compiled
`proot` binary, and the official [dsh-arm64](https://github.com/dsh-io/dsh-arm64)
deployment package. On first launch everything is extracted to the app's
private storage, a foreground service starts `dsh web` inside proot, and the
harness UI renders in a full-screen WebView. A terminal page (proot →
`/bin/bash`) is available for manual operations.

## Install

Download the APK from [Releases](../../releases) and install it
(`adb install` or side-loading; allow "install unknown apps").

Requirements: Android 8.0+ (API 26), arm64 device, ~1.5GB free storage.

## Usage

- **Harness tab**: the dsh web UI. First run walks you through API key and
  profile setup (dsh built-in init).
- **Terminal tab**: a shell inside the embedded Debian rootfs, for manual
  operations or troubleshooting.
- The runtime service keeps running in the background with a persistent
  notification; stop it via the notification's Stop button (swiping the app
  from recents does NOT stop the foreground service — force-stop in settings
  works too).

## How it works

| Piece | Role |
| --- | --- |
| `rust/proot-runner` | proot argv construction, PTY sessions, daemon (no-TTY) spawn |
| `rust/rootfs-manager` | SHA256 verification + safe tar extraction + atomic install |
| `rust/jni-bridge` | JNI exports (startDsh/stopDsh/extractVerified/…) |
| `app/` | Kotlin: foreground service, WebView screen, terminal screen |
| `rootfs/build-rootfs.sh` | builds the minimal Debian rootfs (CI job 1) |
| `.github/workflows/android.yml` | rootfs build → assets → APK → release |

The dsh baseline is pinned (`dsh-arm64-0.1.0-rc.6`); upgrading the baseline is
a new app release.

## Development

Rust correctness checks run on any machine:

```bash
cd rust && cargo test --workspace
```

Heavy builds (rootfs, proot, APK) run in CI:

```bash
gh run list --repo dsh-io/dsh-mobile
```

Local rootfs build (arm64 host only):

```bash
sudo ./rootfs/build-rootfs.sh /tmp/rootfs-out
```

See `docs/manual-test-checklist.md` for the on-device test checklist.

## License & third-party notices

- This project: MIT (see LICENSE).
- proot: GPLv2 — see `docs/third-party-notices.md` for source offer.
- termux terminal-view: Apache-2.0 — see `docs/third-party-notices.md`.
- dsh / dsh-arm64: MIT. This is an independent distribution, not affiliated
  with DeepSeek.
