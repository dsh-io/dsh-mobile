# dsh-mobile — Android App for DeepSeek Harness (dsh) on ARM devices

- Date: 2026-08-18
- Status: approved
- Baseline: fork of `lemonhub-io/proot-apk` at commit `f09b898` (Prootix)

## Goal

Ship an Android APK that runs DeepSeek Harness (`dsh`) on an ARM phone/tablet with
no Termux, no root, and no toolchain. The app embeds a proot-based Debian rootfs,
the official dsh-arm64 deployment package, and presents the harness UI in a
full-screen WebView, with a terminal page available for manual operations.

## Architecture

```
┌─ Kotlin layer ─────────────────────────────────┐
│  MainActivity: first-run extract progress →    │
│    WebView full-screen (default)               │
│  TerminalScreen: proot → /bin/bash (on demand) │
│  DshService: foreground service (keeps alive)  │
├─ JNI bridge (fork, extend) ────────────────────┤
│  startDsh(rootfs, dsh) → pid                   │
│  stopDsh(pid)                                  │
│  (terminal sessions use the existing pty path) │
├─ Rust core (fork, keep as-is) ─────────────────┤
│  proot-runner: pty.rs + session.rs + command.rs│
│  rootfs-manager: safe extract + atomic install │
│  jni-bridge: existing exports + startDsh/stop  │
├─ assets ───────────────────────────────────────┤
│  proot static binary (NDK build, fork keeps)   │
│  rootfs.tar.xz (custom minimal Debian arm64)   │
│  dsh-arm64-<baseline>.tar.gz (dsh Release)     │
└────────────────────────────────────────────────┘
```

### Components

1. **Rust core** — taken from the Prootix baseline without functional change:
   - `proot-runner` (pty.rs, session.rs, command.rs): PTY creation, proot argv
     construction, fork/exec lifecycle, process-group stop.
   - `rootfs-manager` (extract.rs, verify.rs): SHA256 verification, path-
     traversal-safe tar extraction, atomic install via temp-dir rename.
   - `jni-bridge` (lib.rs): existing JNI exports plus two new ones —
     `startDsh(rootfsPath, dshPath) -> pid` (spawns `proot node
     --expose-internals <dsh>/lib/bin.js web` without a TTY) and
     `stopDsh(pid)` (terminate process group). Terminal sessions reuse the
     existing pty-based exports unchanged.

2. **Kotlin/UI** — three screens:
   - First-run extract screen: progress + error guidance (space check, ~1GB
     needed).
   - WebView main screen (default entry): loads `http://127.0.0.1:3080` once
     the service reports ready; reconnects on disconnect.
   - Terminal screen (kept from baseline): `proot → /bin/bash` inside the
     rootfs, spawned on demand, killed when the screen is closed.

3. **DshService** — Android foreground service with a persistent notification;
   owns the dsh runtime process (non-TTY proot session) and reports readiness
   to the UI. The terminal session is NOT part of the service; it is a
   short-lived screen-scoped session.

4. **Assets** — everything needed for offline use is embedded in the APK:
   - `proot` static binary (arm64), built with the NDK in CI (baseline's
     `tools/build-proot.sh`).
   - `rootfs.tar.xz`: minimal Debian arm64 built by `debootstrap`, pruned of
     docs/man/locale/apt caches, with glibc Node 22 installed.
   - `dsh-arm64-0.1.0-rc.6.tar.gz`: downloaded from the dsh-arm64 GitHub
     Release at build time (never bundled by hand).

## Data flow

1. App start → check whether rootfs/dsh are already extracted in app-private
   storage; if not, extract from assets with progress.
2. Start DshService → Rust spawns `proot [debian rootfs] node
   --expose-internals <dsh>/node_modules/@deepseek-ai/dsh/lib/bin.js web`.
3. Service polls `http://127.0.0.1:3080` until ready → MainActivity loads the
   URL in the WebView.
4. First run of the harness UI walks the user through API key and profile
   setup (dsh built-in init; no pre-baked profiles).
5. App exit (user action) → stop service → kill proot process group.

## Error handling

- Insufficient storage / extract failure → first-run screen shows the error
  and required space; retry button.
- proot spawn failure → diagnostic view with captured stdout/stderr.
- dsh process crash → service restarts with a cap (3 attempts), then shows a
  persistent notification with the last log lines.
- WebView disconnect → automatic reload loop with backoff; if the service is
  dead, restart it and reload.
- Port 3080 conflict: impossible in practice — dsh runs inside the proot
  namespace, bound to 127.0.0.1 inside it.

## Build pipeline

Two CI jobs (single workflow):

1. **Rootfs job** (`rootfs/` directory in repo): on an arm64 runner (or qemu
   fallback), `debootstrap` a minimal Debian (bookworm) for arm64, prune
   (docs, man, locale, apt caches), install Node 22 (glibc), tar.xz the result,
   upload as an artifact.
2. **APK job** (baseline pipeline kept): JDK 17 + Android SDK (platform 35,
   build-tools 35.0.0, NDK 26.3) → `tools/build-proot.sh aarch64-linux-android`
   → `cargo ndk` for arm64-v8a → download rootfs artifact + dsh-arm64
   `v0.1.0-rc.6` tarball → place into `app/src/main/assets/` → `gradlew
   assembleRelease` with signing secrets → verify (unzip -t, aapt badging,
   apksigner) → upload APK artifact.

Expected APK size: ~250MB (rootfs ~150MB compressed + dsh package 50MB + proot).

## Testing

| Layer | What it verifies | Where |
|---|---|---|
| Rust `cargo test --workspace` | argv construction, extraction safety, process mgmt | CI + local |
| Local Linux integration | `proot` + built rootfs + node + dsh web boot (no Android differences: /dev/pts, seccomp, SELinux) | Local machine |
| Manual on-device checklist | first-run extract, WebView load, terminal shell, dsh web UI (chat + settings), disconnect/reconnect, background kill recovery, notification persistence | Real ARM device (ADB install) |

The manual checklist lives in `docs/manual-test-checklist.md` (rewritten from
the baseline).

## Licensing & branding

- proot: GPLv2 — README keeps the third-party notice and source offer
  (baseline already documents this).
- termux terminal-view: Apache-2.0 — notice kept.
- dsh / dsh-arm64: MIT — README states this is an independent distribution,
  not affiliated with DeepSeek.
- The project itself gets an MIT LICENSE (the baseline has none).
- App name: "dsh" (no official logo/trademark hints).

## Release

- Tag `v<version>` on the main branch triggers CI; the pipeline publishes a
  GitHub Release with the APK (baseline release step kept).
- App versioning is independent of the dsh baseline: app v0.1.0 embeds dsh
  `0.1.0-rc.6`. A dsh baseline upgrade is a new app release (decoupled from
  dsh-arm64's own release cadence).

## Out of scope (YAGNI)

- In-app upgrade of the dsh package (baseline upgrades ship with new APKs).
- x86_64 / older-Android support (first version: arm64-v8a only, minSdk 26;
  the baseline's x86_64 proot build stays in CI but the APK ships arm64 only).
- Multiple distro management / runtime distro downloads (the baseline's
  DistroList and Download screens are removed).