# AGENTS.md — dsh-mobile

dsh-mobile embeds a minimal Debian arm64 rootfs + Node + the dsh engine in an
Android APK and runs it under proot (Termux fork) in a foreground service,
rendered in a WebView. Design and rationale: `docs/superpowers/specs/2026-08-18-dsh-mobile-design.md`. Execution order and gates: `docs/superpowers/plans/2026-08-18-dsh-mobile.md`.

## Engine invariants (device-verified — never regress these)

These were measured on real phones in the predecessor projects. Every engine
change must preserve them:

- **Single-flight**: every concurrent entry point (activity `onCreate`/`onResume`,
  service restart, crash-retry) goes through one CAS (`engineFlowRunning`,
  `STARTING`). No second start or extract while one is running.
- **90s cooldown**: `START_COOLDOWN_MS = 90_000L` between starts; a start
  attempt inside the window returns immediately. Clear the cooldown as soon as
  the old process is confirmed dead (hung-process kill, not timeout expiry).
- **Never kill a boot in progress**: node cold boot takes 20-45s (slow devices
  more). The watchdog's "never came up" window must be ≥ 90s; `SIGTERM` then
  `SIGKILL` after 3s is for the *hung* case only (proot `stop_pgid`).
- **W^X**: extracted executables (proot, node, binjs) are made read-only after
  extraction — a writable ELF is refused by several OEM exec policies. Never
  overwrite a read-only file; check `exists()` first (skip-if-exists).
- **Exec denial**: `targetSdk` stays 34 — Android 15+ denies exec of app-data
  ELF binaries for targetSdk ≥ 35. Best-effort `security.android.exec` stamping
  via setfattr (batches of 64, 30s cap, silent on failure) covers OEMs that
  ignore targetSdk.
- **WebView retry**: `onPageFinished` fires even on error pages, so a boolean
  cleared there never retries. Track errors per load: `pendingError` set in
  `onPageStarted`, cleared by a main-frame error, retried with 2s→30s backoff.
- **noCompress**: `.xz`/`.gz` assets must stay Stored in the APK (`noCompress +=
  listOf("xz","gz")`); double-compression breaks `openFd()` on device. CI has a
  `unzip -v | grep Defl` regression check.
- **Hash assets**: `.sha256` files are bare 64-hex (no filename), compared
  string-against-string by `NativeLib.extractVerified`.
- **proot argv**: `argv[0]` is the proot path itself (not a symlink); on
  `EACCES` from `execv`, fall back to `[linker64, proot, ...args]`.

## Build discipline

- Heavy builds run only in cloud CI: NDK/cargo-ndk Android libs, Gradle APK,
  rootfs (debootstrap), proot (NDK clang). Locally: `cargo test --workspace`
  (host) plus code and doc writing only.
- JNI: Rust cdylib is `dshmobile_jni` (jni-bridge Cargo.toml `[lib] name`),
  symbols `Java_com_dshio_dshmobile_NativeLib_*`, class `com/dshio/dshmobile/NativeLib`.
- ABI is arm64-v8a only; Rust target `aarch64-linux-android`.
- `probe_ptrace_on_host` in jni-bridge is `#[ignore]` (sandboxed hosts block
  ptrace); CI ubuntu runners pass it, and the device checklist covers it.

## Local dev environment

- `statx` in this sandbox is unreliable: shell `stat -c %a` and
  `std::fs::metadata` can show stale permission values. Verify permissions with
  `libc::stat`/`libc::chmod` (used in the Rust extractor) or python
  `os.stat`/`os.chmod`.