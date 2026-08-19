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
│  startDsh(dshDir, logPath) → pid               │
│  stopDsh(pid) / extractVerified(tar,sha,dest)  │
│  (terminal sessions use the existing pty path) │
├─ Rust core (fork, keep as-is) ─────────────────┤
│  proot-runner: pty.rs + session.rs + command.rs│
│  rootfs-manager: safe extract + atomic install │
│  jni-bridge: existing exports + startDsh/stop  │
│  /extractVerified                              │
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
     traversal-safe tar extraction, atomic install via temp-dir rename, plus
     two device-verified hardening behaviors (from the deprecated
     harness-mobile project): restore the write bit before overwriting an
     existing target (idempotent retry after an interrupted run), and strip
     write bits from executables and `.so`/`.node` files after writing
     (vendor W^X: Huawei/EMUI refuse exec and mmap PROT_EXEC of writable
     files).
   - `jni-bridge` (lib.rs): existing JNI exports plus three new ones —
     `startDsh(dshDir, logPath) -> pid` (spawns the proot daemon running
     `node --expose-internals <dsh>/lib/bin.js web` without a TTY, falling
     back to `/system/bin/linker64` when Android 15+ denies the direct exec
     of the app-data proot ELF),
     `stopDsh(pid)` (terminate process group), and
     `extractVerified(tarball, sha256, dest)` (hash-checked extract to an
     explicit destination). Terminal sessions reuse the existing pty-based
     exports unchanged.

2. **Kotlin/UI** — three screens:
   - First-run extract screen: per-asset progress text, storage space check
     (≈1.5GB free needed), and error/retry guidance.
   - WebView main screen (default entry): loads `http://127.0.0.1:3080` once
     the service reports ready; reconnects on disconnect.
   - Terminal screen (kept from baseline): `proot → /bin/bash` inside the
     rootfs, spawned on demand, killed when the screen is closed.

3. **DshService** — Android foreground service with a persistent notification
   (with a Stop action button); owns the dsh runtime process (non-TTY proot
   session), supervises it (auto-restart with a cap of 3 attempts), and
   reports readiness to the UI. Start protection (device-verified double-start
   races in the deprecated harness-mobile project): a process-level
   single-flight CAS, a 90s cooldown between real starts (cold node boot
   takes 20-45s; the cooldown clears the moment the previous process is
   confirmed dead), and a never-ready window of 90s so a slow boot is never
   killed mid-flight. The terminal session is NOT part of the service; it is
   a short-lived screen-scoped session.

4. **Assets** — everything needed for offline use is embedded in the APK:
   - `proot` static binary (arm64), built with the NDK in CI (baseline's
     `tools/build-proot.sh`); after first extract the app strips its write
     bit (vendor W^X) and never overwrites it again (reinstall idempotency).
   - `rootfs.tar.xz`: minimal Debian arm64 built by `debootstrap`, pruned of
     docs/man/locale/apt caches, with glibc Node 22 installed.
   - `dsh-arm64-0.1.0-rc.6.tar.gz`: downloaded from the dsh-arm64 GitHub
     Release at build time (never bundled by hand).
   `.xz`/`.gz` assets are excluded from AGP's asset compression
   (`noCompress += ["xz", "gz"]`): they are already compressed, and double
   compression breaks `openFd()` on device and bloats the APK.

## Data flow

1. App start → check whether rootfs/dsh are already extracted in app-private
   storage; if not, extract from assets with progress.
2. Start DshService → Rust spawns `proot [debian rootfs] node
   --expose-internals <dsh>/node_modules/@deepseek-ai/dsh/lib/bin.js web`.
3. Service polls `http://127.0.0.1:3080` until ready → MainActivity loads the
   URL in the WebView.
4. First run of the harness UI walks the user through API key and profile
   setup (dsh built-in init; no pre-baked profiles).
5. App exit (notification Stop button, or force-stop in settings) → stop
   service → kill proot process group.

## Error handling

- Insufficient storage / extract failure → first-run screen shows the error
  and required space (≈1.5GB); retry button. Retries are idempotent: the
  extractor restores write bits before overwriting, so an interrupted run
  never leaves a permanently EACCES'd tree.
- proot spawn failure → diagnostic view with captured stdout/stderr.
- dsh process crash (endpoint 127.0.0.1:3080 stops responding) → the service
  restarts dsh with a cap of 3 attempts; after the cap it shows a persistent
  notification with the last log lines and a Stop action.
- Double-start / crash-loop (device-observed EADDRINUSE in the deprecated
  harness-mobile project): a single-flight CAS plus a 90s start cooldown
  (cleared when the previous process is confirmed dead) prevent overlapping
  boots; a hung process holding port 3080 is killed via SIGTERM→SIGKILL group
  escalation before the next start.
- Android 15+ exec denial: the app targets SDK 34 (keeps direct app-data
  exec on Android 10-14); on devices where exec is denied anyway, the daemon
  spawn falls back to `/system/bin/linker64`, and extracted executables get
  the `security.android.exec` attribute stamped (best-effort).
- Vendor W^X (Huawei/EMUI): executables and `.so`/`.node` files are extracted
  non-writable — a writable file is refused both for exec and for
  mmap PROT_EXEC (dlopen fails).
- WebView disconnect → automatic reload loop with backoff; dsh restarts are
  handled by the service's supervision, and the WebView reconnects on reload.
  The reload state machine tracks errors per load (onPageFinished fires even
  for error pages), so a failed page is retried with 2s→30s exponential
  backoff until a real page arrives.
- Port 3080 conflict: proot does NOT isolate networking — the guest shares the
  host loopback, so a bind on 127.0.0.1:3080 does conflict with any other
  process holding the port. In practice nothing on Android binds 3080, but if
  it happens dsh exits and the service's restart logic covers the failure.

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
   apksigner, plus a noCompress regression check that `.xz`/`.gz` assets are
   Stored, not Defl) → upload APK artifact. App targets SDK 34 (not 35) —
   see Error handling for the exec-denial rationale.

Expected APK size: ~100-200MB (rootfs ~75-150MB compressed + dsh package ~50MB
+ proot; dsh-arm64's own node+dsh rootfs snapshot compresses to ~75MB).

## Testing

| Layer | What it verifies | Where |
|---|---|---|
| Rust `cargo test --workspace` | argv construction, extraction safety + W^X write-bit stripping + idempotent re-extract, process mgmt | CI + local |
| Local Linux integration | `proot` + built rootfs + node + dsh web boot (host proot behaves like on-device proot; the Android-specifics — /dev/pts, seccomp, SELinux — are device-test items) | Local machine |
| Manual on-device checklist | first-run extract, WebView load, terminal shell, dsh web UI (chat + settings), disconnect/reconnect, background kill recovery, notification persistence | Real ARM device (ADB install) |
| Device compatibility checklist | Android 15+ exec denial (linker64 fallback), Huawei/EMUI W^X (no PROT_EXEC errors), reinstall-without-clear idempotency, mid-extract kill retry, slow boot / double-start (no EADDRINUSE storm) | Real devices, incl. Android 15+ and EMUI |

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
  the baseline's x86_64 proot build step is removed from CI).
- Multiple distro management / runtime distro downloads (the baseline's
  DistroList and Download screens are removed).