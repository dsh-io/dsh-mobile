# dsh-mobile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an Android APK that runs DeepSeek Harness (`dsh`) on ARM phones — embedded proot + minimal Debian rootfs + dsh-arm64 package, WebView main screen, terminal page for manual ops.

**Architecture:** Fork of `lemonhub-io/proot-apk` at commit `f09b898` (Prootix). Keep the Rust core (proot-runner PTY/argv/process mgmt, rootfs-manager safe extract) and the CI signing/release pipeline as-is; remove the distro-catalog/download UI; add a foreground service + WebView screen around a non-TTY proot daemon that runs `dsh web`; keep the terminal screen. Assets (proot binary, minimal Debian rootfs, dsh-arm64 tarball) are embedded in the APK.

**Tech Stack:** Kotlin + Jetpack Compose (Android), Rust (JNI via cargo-ndk), termux terminal-view, proot (GPLv2, NDK-built), debootstrap (CI), GitHub Actions.

## Global Constraints

- Package/namespace/appId: `com.dshio.dshmobile` (was `com.prootapk.app`); Rust JNI symbols must be `Java_com_dshio_dshmobile_NativeLib_*`
- Signing env vars renamed: `DSHMOBILE_KEYSTORE_B64`, `DSHMOBILE_STORE_PASS`, `DSHMOBILE_KEY_PASS`, `DSHMOBILE_KEY_ALIAS`
- dsh baseline: `dsh-arm64-0.1.0-rc.6.tar.gz` from `dsh-io/dsh-arm64` Release `v0.1.0-rc.6` (sha256 `b8aa98f46f750bd909a105434a271276baab8893e786805b5a613cfc2fb65ba6`)
- Rootfs: minimal Debian bookworm arm64, Node 22 (glibc), pruned (docs/man/locale/apt caches)
- App: minSdk 26, targetSdk 35, compileSdk 35, abiFilters arm64-v8a only
- Rootfs dir on device: `files/rootfs/debian`; dsh install dir: `files/dsh`; proot binary: `files/proot/proot`; logs: `files/logs/dsh.log`
- dsh runs as: `proot -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root --kill-on-exit /usr/bin/env -i HOME=/root PATH=... TERM=xterm-256color node --expose-internals <dsh>/node_modules/@deepseek-ai/dsh/lib/bin.js web`
- WebView URL: `http://127.0.0.1:3080` (cleartext allowed only for 127.0.0.1 via network security config)
- All Rust code keeps the baseline's existing tests passing; new Rust code ships with new tests
- No new third-party Android dependencies beyond the baseline's set

---

### Task 1: Fork Baseline + Rebrand to dsh-mobile

**Files:**
- Copy: `rust/`, `app/`, `tools/`, `.github/`, `gradle/`, `gradlew`, `gradlew.bat`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `.gitignore` from `/tmp/opencode/proot-apk` (proot-apk repo, commit `f09b898`) into the repo root
- Delete: `app/src/main/java/com/prootapk/app/catalog/Distros.kt`, `app/src/main/java/com/prootapk/app/download/Downloader.kt`, `app/src/main/java/com/prootapk/app/ui/DistroListScreen.kt`, `app/src/main/java/com/prootapk/app/ui/DownloadScreen.kt`
- Modify: all remaining files that reference `com.prootapk.app` / `PROOTIX_*` / `prootix`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: renamed package `com.dshio.dshmobile` with `NativeLib` class (same externals as baseline: `initNative`, `buildProotArgs`, `rootfsVerifyExtract`, `rootfsDelete`, `probePtrace`, static `onRustPanic`)

- [ ] **Step 1: Copy the baseline**

```bash
cp -r /tmp/opencode/proot-apk/rust /tmp/opencode/proot-apk/app /tmp/opencode/proot-apk/tools \
      /tmp/opencode/proot-apk/.github /tmp/opencode/proot-apk/gradle \
      /tmp/opencode/proot-apk/gradlew /tmp/opencode/proot-apk/gradlew.bat \
      /tmp/opencode/proot-apk/gradle.properties /tmp/opencode/proot-apk/settings.gradle.kts \
      /tmp/opencode/proot-apk/build.gradle.kts /tmp/opencode/proot-apk/.gitignore .
rm -rf app/src/main/java/com/prootapk/app/catalog app/src/main/java/com/prootapk/app/download \
       app/src/main/java/com/prootapk/app/ui/DistroListScreen.kt app/src/main/java/com/prootapk/app/ui/DownloadScreen.kt
```

- [ ] **Step 2: Rename package in Kotlin**

Move `app/src/main/java/com/prootapk/app` → `app/src/main/java/com/dshio/dshmobile`; change the `package` lines in `MainActivity.kt`, `NativeLib.kt`, `ui/TerminalScreen.kt` to `package com.dshio.dshmobile` / `package com.dshio.dshmobile.ui`.

- [ ] **Step 3: Rename in Rust JNI layer**

In `rust/jni-bridge/src/lib.rs`:
- Panic hook `find_class("com/prootapk/app/NativeLib")` → `"com/dshio/dshmobile/NativeLib"`
- All 5 exported symbol prefixes `Java_com_prootapk_app_NativeLib_` → `Java_com_dshio_dshmobile_NativeLib_` (initNative, buildProotArgs, rootfsVerifyExtract, rootfsDelete, probePtrace)

- [ ] **Step 4: Rename in Gradle + manifest**

- `app/build.gradle.kts`: `namespace = "com.dshio.dshmobile"`, `applicationId = "com.dshio.dshmobile"`; signing props `PROOTIX_KEYSTORE_B64|STORE_PASS|KEY_PASS|KEY_ALIAS` → `DSHMOBILE_KEYSTORE_B64|STORE_PASS|KEY_PASS|KEY_ALIAS`; keystore file name `prootix-release.keystore` → `dshmobile-release.keystore`; `abiFilters += listOf("arm64-v8a")` (drop x86_64)
- `settings.gradle.kts`: `rootProject.name = "dsh-mobile"`
- `app/src/main/AndroidManifest.xml`: `package`-related references → `com.dshio.dshmobile` (manifest uses namespace; check for `android:name="com.prootapk.app."` prefixes)
- `tools/gen-keystore.sh`: rename outputs/env vars to DSHMOBILE_*

- [ ] **Step 5: Update CI workflow**

In `.github/workflows/android.yml`:
- `cargo ndk -t arm64-v8a -t x86_64` → `cargo ndk -t arm64-v8a`
- `./tools/build-proot.sh x86_64-linux-android` line: remove
- Artifact name `proot-apk` → `dsh-mobile-apk`
- Release rename `prootix-${GITHUB_REF_NAME}.apk` → `dsh-mobile-${GITHUB_REF_NAME}.apk`
- Secrets: `PROOTIX_KEYSTORE_B64` etc → `DSHMOBILE_*`

- [ ] **Step 6: Verify Rust still compiles and tests pass locally**

Run: `cd rust && cargo test --workspace`
Expected: all baseline tests pass (command.rs arg order, session pty echo, extract/traversal, jni init).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: fork proot-apk@f09b898, rebrand to dsh-mobile (com.dshio.dshmobile)"
```

---

### Task 2: Rust — daemon spawn + extractVerified JNI exports

**Files:**
- Create: `rust/proot-runner/src/daemon.rs`
- Modify: `rust/proot-runner/src/lib.rs`, `rust/jni-bridge/src/lib.rs`
- Test: `rust/proot-runner/src/daemon.rs` (inline tests), `rust/jni-bridge/src/lib.rs` (existing tests kept green)

**Interfaces:**
- Consumes: `proot_runner::command::build_proot_args` pattern from Task 1 (kept), `rootfs_manager::extract::install_rootfs(tarball: &Path, dest: &Path, expected_sha256: &str) -> Result<(), String>` (baseline)
- Produces:
  - `proot_runner::daemon::build_daemon_args(rootfs: &Path, cmd: &[String]) -> Vec<String>` — proot argv WITHOUT the trailing shell `-l` (shell arg is replaced by `cmd`)
  - `proot_runner::daemon::spawn_daemon(argv: &[String], prog: &str, env: &[String], log_path: &Path) -> Result<libc::pid_t, String>` — fork, setsid, redirect stdout/stderr (append) to log file, execve, returns child pid (no PTY)
  - `proot_runner::daemon::stop_pgid(pid: libc::pid_t) -> bool` — `kill(-pid, SIGTERM)`, wait up to 3s, then SIGKILL (extracted from baseline `stop_session`)
  - JNI: `Java_com_dshio_dshmobile_NativeLib_startDsh(env, class, dsh_dir: JString, log_path: JString) -> jint` — pid on success, negative errno-style (-1 spawn fail, -22 EINVAL) on failure
  - JNI: `Java_com_dshio_dshmobile_NativeLib_stopDsh(env, class, pid: jint) -> jboolean`
  - JNI: `Java_com_dshio_dshmobile_NativeLib_extractVerified(env, class, tarball: JString, expected_sha256: JString, dest: JString) -> jint` — 0 OK, -1 failure, -22 bad args

- [ ] **Step 1: Write failing test for build_daemon_args**

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    #[test]
    fn daemon_args_have_no_login_shell() {
        let args = build_daemon_args(
            &PathBuf::from("/data/files/rootfs/debian"),
            &["node".into(), "--expose-internals".into(), "/data/files/dsh/bin.js".into()],
        );
        assert_eq!(&args[0..2], &["-0", "-r"]);
        assert_eq!(args[2], "/data/files/rootfs/debian");
        assert!(args.contains(&"--kill-on-exit".to_string()));
        let i_pos = args.iter().position(|a| a == "-i").unwrap();
        assert_eq!(args[i_pos + 1], "HOME=/root");
        // the command appears verbatim after env, no "-l" suffix
        let cmd_start = args.iter().position(|a| a == "node").unwrap();
        assert_eq!(&args[cmd_start..], &["node", "--expose-internals", "/data/files/dsh/bin.js"]);
        assert!(!args.contains(&"-l".to_string()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd rust && cargo test -p proot-runner daemon_args`
Expected: FAIL (module `daemon` not found)

- [ ] **Step 3: Write failing test for spawn_daemon + stop_pgid**

```rust
    #[test]
    fn spawn_daemon_appends_log_and_stop_reaps() {
        let dir = std::env::temp_dir().join(format!("dsh-daemon-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let log = dir.join("dsh.log");
        let env = vec!["PATH=/bin:/usr/bin".to_string()];
        let pid = spawn_daemon(
            &["/bin/sh".into(), "-c".into(), "echo daemon-alive; sleep 60".into()],
            "/bin/sh",
            &env,
            &log,
        )
        .expect("spawn daemon");
        // wait for the log line
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            if std::fs::read_to_string(&log).map(|s| s.contains("daemon-alive")).unwrap_or(false) {
                break;
            }
            assert!(std::time::Instant::now() < deadline, "log never got output");
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        assert!(stop_pgid(pid));
        let r = unsafe { libc::kill(pid, 0) };
        assert_eq!(r, -1); // ESRCH — reaped
        let _ = std::fs::remove_dir_all(&dir);
    }
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd rust && cargo test -p proot-runner spawn_daemon`
Expected: FAIL (function not found)

- [ ] **Step 5: Implement daemon.rs**

```rust
use std::path::{Path, PathBuf};
use std::time::Instant;

/// proot argv for a background (non-TTY) command inside the rootfs.
/// Identical to command::build_proot_args except the shell/-l tail is
/// replaced by the raw command.
pub fn build_daemon_args(rootfs: &Path, cmd: &[String]) -> Vec<String> {
    let mut args = vec![
        "-0".to_string(),
        "-r".to_string(),
        rootfs.to_string_lossy().to_string(),
        "-b".to_string(),
        "/dev".to_string(),
        "-b".to_string(),
        "/proc".to_string(),
        "-b".to_string(),
        "/sys".to_string(),
        "-w".to_string(),
        "/root".to_string(),
        "--kill-on-exit".to_string(),
        "/usr/bin/env".to_string(),
        "-i".to_string(),
        "HOME=/root".to_string(),
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".to_string(),
        "TERM=xterm-256color".to_string(),
    ];
    args.extend_from_slice(cmd);
    args
}

/// Fork and exec a background process (no PTY) with stdout/stderr appended
/// to log_path. Returns the child pid. The child is its own process group
/// leader (setsid), so stop_pgid can kill the whole group.
pub fn spawn_daemon(
    argv: &[String],
    prog: &str,
    env: &[String],
    log_path: &Path,
) -> Result<libc::pid_t, String> {
    unsafe {
        let pid = libc::fork();
        if pid < 0 {
            return Err(format!("fork: {}", std::io::Error::last_os_error()));
        }
        if pid == 0 {
            libc::setsid();
            let devnull = libc::open(b"/dev/null\0".as_ptr() as *const libc::c_char, libc::O_RDONLY);
            if devnull >= 0 {
                libc::dup2(devnull, 0);
                libc::close(devnull);
            }
            if let Some(dir) = log_path.parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            let flags = libc::O_WRONLY | libc::O_CREAT | libc::O_APPEND;
            let log_fd = libc::open(
                log_path.to_string_lossy().as_ptr() as *const libc::c_char,
                flags,
                0o644,
            );
            if log_fd >= 0 {
                libc::dup2(log_fd, 1);
                libc::dup2(log_fd, 2);
                libc::close(log_fd);
            }
            let prog_c = std::ffi::CString::new(prog).unwrap();
            let argv_c: Vec<std::ffi::CString> =
                argv.iter().map(|a| std::ffi::CString::new(a.as_str()).unwrap()).collect();
            let mut argv_p: Vec<*const libc::c_char> = argv_c.iter().map(|c| c.as_ptr()).collect();
            argv_p.push(std::ptr::null());
            let env_c: Vec<std::ffi::CString> =
                env.iter().map(|e| std::ffi::CString::new(e.as_str()).unwrap()).collect();
            let mut env_p: Vec<*const libc::c_char> = env_c.iter().map(|c| c.as_ptr()).collect();
            env_p.push(std::ptr::null());
            libc::execve(prog_c.as_ptr(), argv_p.as_ptr(), env_p.as_ptr());
            libc::_exit(127);
        }
        Ok(pid)
    }
}

/// SIGTERM the process group of pid, escalate to SIGKILL after 3s.
pub fn stop_pgid(pid: libc::pid_t) -> bool {
    unsafe {
        libc::kill(-pid, libc::SIGTERM);
        let deadline = Instant::now() + std::time::Duration::from_secs(3);
        loop {
            let mut status: libc::c_int = 0;
            let r = libc::waitpid(pid, &mut status, libc::WNOHANG);
            if r == pid {
                return true;
            }
            if Instant::now() > deadline {
                libc::kill(-pid, libc::SIGKILL);
                libc::waitpid(pid, &mut status, 0);
                return true;
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn daemon_args_have_no_login_shell() {
        let args = build_daemon_args(
            &PathBuf::from("/data/files/rootfs/debian"),
            &["node".into(), "--expose-internals".into(), "/data/files/dsh/bin.js".into()],
        );
        assert_eq!(&args[0..2], &["-0", "-r"]);
        assert_eq!(args[2], "/data/files/rootfs/debian");
        assert!(args.contains(&"--kill-on-exit".to_string()));
        let i_pos = args.iter().position(|a| a == "-i").unwrap();
        assert_eq!(args[i_pos + 1], "HOME=/root");
        let cmd_start = args.iter().position(|a| a == "node").unwrap();
        assert_eq!(&args[cmd_start..], &["node", "--expose-internals", "/data/files/dsh/bin.js"]);
        assert!(!args.contains(&"-l".to_string()));
    }

    #[test]
    fn spawn_daemon_appends_log_and_stop_reaps() {
        let dir = std::env::temp_dir().join(format!("dsh-daemon-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let log = dir.join("dsh.log");
        let env = vec!["PATH=/bin:/usr/bin".to_string()];
        let pid = spawn_daemon(
            &["/bin/sh".into(), "-c".into(), "echo daemon-alive; sleep 60".into()],
            "/bin/sh",
            &env,
            &log,
        )
        .expect("spawn daemon");
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        loop {
            if std::fs::read_to_string(&log).map(|s| s.contains("daemon-alive")).unwrap_or(false) {
                break;
            }
            assert!(std::time::Instant::now() < deadline, "log never got output");
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        assert!(stop_pgid(pid));
        let r = unsafe { libc::kill(pid, 0) };
        assert_eq!(r, -1);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
```

- [ ] **Step 6: Register the module**

In `rust/proot-runner/src/lib.rs` add: `pub mod daemon;`

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd rust && cargo test -p proot-runner`
Expected: PASS (3 new daemon tests + baseline command/session tests)

- [ ] **Step 8: Add the three JNI exports**

In `rust/jni-bridge/src/lib.rs`, after `probePtrace`:

```rust
const ENOENT: jint = -2;
const EINVAL: jint = -22;
// (ENOENT already declared above; do not redeclare)

#[no_mangle]
pub extern "system" fn Java_com_dshio_dshmobile_NativeLib_startDsh(
    mut env: JNIEnv,
    _class: JClass,
    dsh_dir: JString,
    log_path: JString,
) -> jint {
    let Ok(dsh) = get_str(&mut env, &dsh_dir) else { return EINVAL };
    let Ok(log) = get_str(&mut env, &log_path) else { return EINVAL };
    let rootfs = files_dir().join("rootfs").join("debian");
    if !rootfs.is_dir() {
        return ENOENT;
    }
    let bin = PathBuf::from(&dsh)
        .join("node_modules")
        .join("@deepseek-ai")
        .join("dsh")
        .join("lib")
        .join("bin.js");
    if !bin.is_file() {
        return ENOENT;
    }
    let cmd = vec![
        "node".to_string(),
        "--expose-internals".to_string(),
        bin.to_string_lossy().to_string(),
        "web".to_string(),
    ];
    let argv = proot_runner::daemon::build_daemon_args(&rootfs, &cmd);
    let env = vec![
        "PATH=/system/bin:/system/xbin".to_string(),
        "HOME=/data".to_string(),
    ];
    match proot_runner::daemon::spawn_daemon(&argv, "/usr/bin/env", &env, &PathBuf::from(log)) {
        Ok(pid) => pid,
        Err(_) => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dshio_dshmobile_NativeLib_stopDsh(
    _env: JNIEnv,
    _class: JClass,
    pid: jint,
) -> jboolean {
    if pid <= 0 {
        return 0;
    }
    proot_runner::daemon::stop_pgid(pid) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_dshio_dshmobile_NativeLib_extractVerified(
    mut env: JNIEnv,
    _class: JClass,
    tarball: JString,
    expected_sha256: JString,
    dest: JString,
) -> jint {
    let Ok(t) = get_str(&mut env, &tarball) else { return EINVAL };
    let Ok(sha) = get_str(&mut env, &expected_sha256) else { return EINVAL };
    let Ok(d) = get_str(&mut env, &dest) else { return EINVAL };
    match rootfs_manager::extract::install_rootfs(
        &PathBuf::from(t),
        &PathBuf::from(d),
        &sha,
    ) {
        Ok(()) => 0,
        Err(_) => -1,
    }
}
```

Note: `startDsh` spawns `/usr/bin/env` as the proot target program (argv already contains env's `-i` flag sequence from `build_daemon_args`), with the host PATH/HOME restored — matching how the baseline terminal session passes its environment through proot.

- [ ] **Step 9: Verify workspace compiles and tests pass**

Run: `cd rust && cargo test --workspace`
Expected: PASS (all baseline + new tests)

- [ ] **Step 10: Commit**

```bash
git add rust/
git commit -m "feat(rust): daemon spawn (no-TTY proot) + startDsh/stopDsh/extractVerified JNI exports"
```

---

### Task 3: Kotlin — DshService, WebView screen, first-run extract

**Files:**
- Create: `app/src/main/java/com/dshio/dshmobile/DshService.kt`, `app/src/main/java/com/dshio/dshmobile/ui/WebviewScreen.kt`, `app/src/main/java/com/dshio/dshmobile/ui/ExtractScreen.kt`, `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/java/com/dshio/dshmobile/NativeLib.kt`, `app/src/main/java/com/dshio/dshmobile/MainActivity.kt`, `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts` (deps unchanged; add nothing)
- Delete: `app/src/main/java/com/dshio/dshmobile/catalog/`, `app/src/main/java/com/dshio/dshmobile/download/` (already removed in Task 1)

**Interfaces:**
- Consumes: `NativeLib.startDsh(dshDir: String, logPath: String): Int`, `NativeLib.stopDsh(pid: Int): Boolean`, `NativeLib.extractVerified(tarballPath: String, expectedSha256: String, dest: String): Int` (Task 2)
- Produces:
  - `DshService` (foreground, channel "dsh"): `fun startDshAndWait()`, `fun stopDsh()`, exposes pid via companion `var runningPid: Int`
  - `MainActivity`: state machine `ExtractProgress → Starting → Ready → Error`; bottom bar with two buttons: "Harness" (WebView) and "Terminal" (existing TerminalScreen, distro = "debian", shell = "/bin/bash")

- [ ] **Step 1: Update NativeLib.kt**

```kotlin
package com.dshio.dshmobile

import android.util.Log

object NativeLib {
    init {
        System.loadLibrary("dshmobile_jni")
    }

    external fun initNative(filesDir: String)
    external fun buildProotArgs(distro: String, shell: String): Array<String>?
    external fun rootfsVerifyExtract(tarballPath: String, expectedSha256: String): Int
    external fun rootfsDelete(distro: String): Int
    external fun probePtrace(): Boolean
    external fun startDsh(dshDir: String, logPath: String): Int
    external fun stopDsh(pid: Int): Boolean
    external fun extractVerified(tarballPath: String, expectedSha256: String, dest: String): Int

    @JvmStatic
    fun onRustPanic(msg: String) {
        Log.e("NativeLib", "Rust panic: $msg")
    }
}
```

(The baseline already loads the lib in an `init` block — keep that pattern, only the library name changes.)

- [ ] **Step 2: Add network security config**

Create `app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 3: Update AndroidManifest.xml**

Add inside `<application>` (keep existing MainActivity entry):

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application
    ...
    android:networkSecurityConfig="@xml/network_security_config"
    ...>

    <service
        android:name=".DshService"
        android:exported="false"
        android:foregroundServiceType="dataSync" />
</application>
```

Note: `POST_NOTIFICATIONS` is a runtime permission on API 33+ — request it in MainActivity (`registerForActivityResult(ActivityResultContracts.RequestPermission())`) before starting the service.

- [ ] **Step 4: Write DshService.kt**

```kotlin
package com.dshio.dshmobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class DshService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pollJob: Job? = null

    companion object {
        const val CHANNEL_ID = "dsh"
        const val ACTION_START = "com.dshio.dshmobile.START"
        const val ACTION_STOP = "com.dshio.dshmobile.STOP"
        @Volatile var runningPid: Int = -1
        @Volatile var isReady: Boolean = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "dsh runtime", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDshInternal()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startForeground(1, buildNotification())
        }
        if (runningPid <= 0) {
            val dshDir = File(filesDir, "dsh").absolutePath
            val logPath = File(filesDir, "logs/dsh.log").absolutePath
            val pid = NativeLib.startDsh(dshDir, logPath)
            if (pid <= 0) {
                stopSelf()
                return START_NOT_STICKY
            }
            runningPid = pid
            pollJob = scope.launch { pollReady(pid) }
        }
        return START_STICKY
    }

    private fun pollReady(pid: Int) {
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url("http://127.0.0.1:3080").build()
        for (i in 0 until 60) {
            if (runningPid != pid) return
            try {
                client.newCall(request).execute().use { resp ->
                    if (resp.code == 200) {
                        isReady = true
                        return
                    }
                }
            } catch (_: Exception) {
            }
            delay(1000)
        }
        isReady = false
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("dsh")
            .setContentText("Harness runtime running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    private fun stopDshInternal() {
        pollJob?.cancel()
        if (runningPid > 0) {
            NativeLib.stopDsh(runningPid)
            runningPid = -1
        }
        isReady = false
    }

    override fun onDestroy() {
        stopDshInternal()
        super.onDestroy()
    }
}
```

- [ ] **Step 5: Write WebviewScreen.kt**

```kotlin
package com.dshio.dshmobile.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebviewScreen(modifier: Modifier = Modifier) {
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val reloadJob = remember { arrayOfNulls<Job>(1) }
    val webView = remember {
        WebView(androidx.compose.ui.platform.LocalContext.current).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView,
                    errorCode: Int,
                    description: String,
                    failingUrl: String?,
                ) {
                    scheduleReload()
                }
            }
            loadUrl("http://127.0.0.1:3080")
        }
    }

    fun scheduleReload() {
        reloadJob[0]?.cancel()
        reloadJob[0] = scope.launch {
            delay(2000)
            webView.loadUrl("http://127.0.0.1:3080")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            reloadJob[0]?.cancel()
            webView.destroy()
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)
}
```

- [ ] **Step 6: Write ExtractScreen.kt**

```kotlin
package com.dshio.dshmobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ExtractScreen(
    progressText: String,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("Retry") }
        } else {
            CircularProgressIndicator()
            Text(progressText, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
```

- [ ] **Step 7: Rewrite MainActivity.kt**

```kotlin
package com.dshio.dshmobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dshio.dshmobile.ui.ExtractScreen
import com.dshio.dshmobile.ui.TerminalScreen
import com.dshio.dshmobile.ui.WebviewScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private sealed interface AppState {
        data class Extracting(val text: String) : AppState
        data class Error(val message: String) : AppState
        object Starting : AppState
        object Ready : AppState
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeLib.initNative(filesDir.absolutePath)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                var state by remember { mutableStateOf<AppState>(AppState.Extracting("Checking installation…")) }
                var tab by remember { mutableStateOf(0) }
                if (state is AppState.Ready) {
                    Scaffold(
                        bottomBar = {
                            BottomAppBar {
                                TextButton(onClick = { tab = 0 }) { Text("Harness") }
                                TextButton(onClick = { tab = 1 }) { Text("Terminal") }
                            }
                        },
                    ) { padding ->
                        if (tab == 0) {
                            WebviewScreen(Modifier.fillMaxSize().padding(padding))
                        } else {
                            TerminalScreen(
                                filesDir = filesDir,
                                distro = "debian",
                                shell = "/bin/bash",
                                noSeccomp = false,
                                onExit = { tab = 0 },
                            )
                        }
                    }
                } else {
                    ExtractScreen(
                        progressText = (state as? AppState.Extracting)?.text ?: "Starting…",
                        error = (state as? AppState.Error)?.message,
                        onRetry = { startExtract() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            ensureProotBinary()
            val missing = ensureAssetsExtracted()
            if (missing != null) {
                state = AppState.Error(missing)
                return@launch
            }
            val intent = Intent(this@MainActivity, DshService::class.java)
            intent.action = DshService.ACTION_START
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            var waited = 0
            while (!DshService.isReady && waited < 90 && DshService.runningPid > 0) {
                Thread.sleep(1000)
                waited++
            }
            state = if (DshService.isReady) AppState.Ready else AppState.Error("dsh failed to start — see logs/dsh.log")
        }
    }

    private fun ensureProotBinary() {
        val dir = File(filesDir, "proot").apply { mkdirs() }
        val target = File(dir, "proot")
        if (target.canExecute()) return
        assets.open("proot/proot-aarch64").use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        target.setExecutable(true)
    }

    private fun ensureAssetsExtracted(): String? {
        // rootfs: assets/rootfs/debian.tar.xz + .sha256 → files/rootfs/debian
        val rootfsTar = File(filesDir, "downloads/rootfs.tar.xz")
        if (!File(filesDir, "rootfs/debian/bin/sh").exists()) {
            copyAssetToFile("rootfs/debian.tar.xz", rootfsTar)
            val sha = assets.open("rootfs/debian.tar.xz.sha256").bufferedReader().use { it.readText().trim() }
            val rc = NativeLib.extractVerified(rootfsTar.absolutePath, sha, File(filesDir, "rootfs/debian").absolutePath)
            if (rc != 0) return "Rootfs extraction failed (rc=$rc). ~1GB of free storage is required."
        }
        // dsh package: assets/dsh/dsh-arm64-0.1.0-rc.6.tar.gz + .sha256 → files/dsh
        val dshTar = File(filesDir, "downloads/dsh.tar.gz")
        if (!File(filesDir, "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js").exists()) {
            copyAssetToFile("dsh/dsh-arm64-0.1.0-rc.6.tar.gz", dshTar)
            val sha = assets.open("dsh/dsh-arm64-0.1.0-rc.6.tar.gz.sha256").bufferedReader().use { it.readText().trim() }
            val rc = NativeLib.extractVerified(dshTar.absolutePath, sha, File(filesDir, "dsh").absolutePath)
            if (rc != 0) return "dsh package extraction failed (rc=$rc)."
        }
        return null
    }

    private fun copyAssetToFile(asset: String, target: File) {
        target.parentFile?.mkdirs()
        assets.open(asset).use { input -> target.outputStream().use { out -> input.copyTo(out) } }
    }
}
```

- [ ] **Step 8: Rename the JNI library**

In `rust/jni-bridge/Cargo.toml` change the lib name so `System.loadLibrary("dshmobile_jni")` resolves:

```toml
[lib]
name = "dshmobile_jni"
crate-type = ["cdylib"]
```

This makes `cargo ndk` emit `libdshmobile_jni.so` into `jniLibs`.

- [ ] **Step 9: Commit**

```bash
git add app/ rust/jni-bridge/Cargo.toml
git commit -m "feat(android): DshService foreground runtime + WebView screen + first-run asset extract"
```

---

### Task 4: Rootfs build + assets pipeline + CI workflow

**Files:**
- Create: `rootfs/build-rootfs.sh`, `rootfs/node-version.txt` (content: `v22.23.2`)
- Modify: `.github/workflows/android.yml` (two jobs: rootfs + build+release)

**Interfaces:**
- Consumes: dsh-arm64 Release `v0.1.0-rc.6` (tarball + sha256sums.txt), rootfs artifact from CI job 1
- Produces: APK with `assets/rootfs/debian.tar.xz` (+ `.sha256`), `assets/dsh/dsh-arm64-0.1.0-rc.6.tar.gz` (+ `.sha256`), `assets/proot/proot-aarch64`

- [ ] **Step 1: Write rootfs/build-rootfs.sh**

```bash
#!/usr/bin/env bash
set -euo pipefail

# Builds a minimal Debian bookworm arm64 rootfs with Node 22 (glibc),
# pruned for embedding in the dsh-mobile APK. Run on an arm64 host (or
# under qemu). Must be run as root (debootstrap).
#
# Usage: sudo ./build-rootfs.sh [output-dir]   (default: ./out)

OUT="${1:-out}"
NODE_VER="$(cat "$(dirname "$0")/node-version.txt")"
ROOT="$(mktemp -d)/rootfs"

if [ "$(id -u)" != "0" ]; then
  echo "!! run as root (debootstrap requires it)" >&2
  exit 2
fi
if [ "$(uname -m)" != "aarch64" ] && [ "$(uname -m)" != "arm64" ]; then
  echo "!! must run on arm64 (or qemu-user under binfmt)" >&2
  exit 2
fi

echo "==> debootstrap bookworm arm64"
debootstrap --arch=arm64 --variant=minbase --include=ca-certificates \
  bookworm "${ROOT}" http://deb.debian.org/debian

echo "==> install Node ${NODE_VER} (glibc)"
curl -fsSL "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-arm64.tar.xz" -o /tmp/node.tar.xz
tar xJf /tmp/node.tar.xz -C "${ROOT}/usr" --strip-components=1 --exclude="*/share/doc" \
  --exclude="*/share/man" --exclude="*/include"
rm -f /tmp/node.tar.xz

echo "==> prune"
rm -rf "${ROOT}/var/lib/apt/lists" "${ROOT}/var/cache/apt" \
       "${ROOT}/usr/share/doc" "${ROOT}/usr/share/man" "${ROOT}/usr/share/locale" \
       "${ROOT}/usr/share/info" "${ROOT}/usr/share/common-licenses" \
       "${ROOT}/root/.bashrc"
find "${ROOT}/usr/lib" -name '*.a' -delete
find "${ROOT}" -type f -name '*.pyc' -delete

echo "==> tar.xz"
mkdir -p "${OUT}"
tar cJf "${OUT}/debian.tar.xz" -C "${ROOT}" .
sha256sum "${OUT}/debian.tar.xz" > "${OUT}/debian.tar.xz.sha256"
ls -la "${OUT}/debian.tar.xz"
echo "==> rootfs done"
```

- [ ] **Step 2: Build the rootfs on this machine (arm64 host)**

Run: `sudo ./rootfs/build-rootfs.sh /tmp/rootfs-out` (install debootstrap if missing: `apt-get install -y debootstrap`)
Expected: `debian.tar.xz` (a few hundred MB uncompressed, ~120-200MB compressed) + `.sha256`.

- [ ] **Step 3: Verify the rootfs runs dsh under proot on this machine**

```bash
PROOT_BIN=$(command -v proot || echo /nonexistent)
if [ ! -x "$PROOT_BIN" ]; then
  echo "installing proot"; apt-get install -y proot
fi
mkdir -p /tmp/rootfs-test/dsh
# extract dsh package (already built locally):
tar xzf /root/projects/dsh-arm64/dist/dsh-arm64-0.1.0-rc.6.tar.gz -C /tmp/rootfs-test/dsh
# extract rootfs:
tar xJf /tmp/rootfs-out/debian.tar.xz -C /tmp/rootfs-test/rootfs
# boot dsh web inside proot:
timeout 60 proot -0 -r /tmp/rootfs-test/rootfs -b /dev -b /proc -b /sys -w /root --kill-on-exit \
  /usr/bin/env -i HOME=/root \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin TERM=xterm-256color \
  /usr/bin/node --expose-internals /tmp/rootfs-test/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js web \
  > /tmp/rootfs-test/boot.log 2>&1 || true
grep -ci "error" /tmp/rootfs-test/boot.log || true
head -2 /tmp/rootfs-test/boot.log
```

Expected: log contains `dsh web: http://127.0.0.1:3080` and no node-pty errors. This is the critical integration proof: glibc node + node-pty inside the minimal rootfs under proot.

- [ ] **Step 4: Rewrite the CI workflow**

`.github/workflows/android.yml` becomes (full file):

```yaml
name: android-build
on:
  push:
    branches: [main]
    tags: ["v*"]
  workflow_dispatch:

jobs:
  rootfs:
    runs-on: ubuntu-24.04-arm
    steps:
      - uses: actions/checkout@v7

      - name: Build minimal rootfs
        run: |
          sudo ./rootfs/build-rootfs.sh /tmp/rootfs-out

      - name: Upload rootfs artifact
        uses: actions/upload-artifact@v5
        with:
          name: dsh-rootfs
          path: /tmp/rootfs-out/

  build:
    needs: rootfs
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Android SDK
        uses: android-actions/setup-android@v4
        with:
          packages: "platform-tools build-tools;35.0.0 platforms;android-35 ndk;26.3.11579264"

      - name: Install Rust Android targets
        run: |
          rustup toolchain install stable
          rustup target add aarch64-linux-android
          cargo install cargo-ndk --locked

      - name: Build proot binary
        run: |
          export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/26.3.11579264"
          ./tools/build-proot.sh aarch64-linux-android

      - name: Build Rust JNI libs
        run: |
          export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/26.3.11579264"
          cd rust && cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release

      - name: Download rootfs artifact
        uses: actions/download-artifact@v5
        with:
          name: dsh-rootfs
          path: /tmp/rootfs

      - name: Fetch dsh-arm64 release package
        run: |
          mkdir -p app/src/main/assets/dsh app/src/main/assets/rootfs
          cp /tmp/rootfs/debian.tar.xz app/src/main/assets/rootfs/
          cp /tmp/rootfs/debian.tar.xz.sha256 app/src/main/assets/rootfs/
          curl -fsSL -o app/src/main/assets/dsh/dsh-arm64-0.1.0-rc.6.tar.gz \
            "https://github.com/dsh-io/dsh-arm64/releases/download/v0.1.0-rc.6/dsh-arm64-0.1.0-rc.6.tar.gz"
          curl -fsSL -o app/src/main/assets/dsh/dsh-arm64-0.1.0-rc.6.tar.gz.sha256 \
            "https://github.com/dsh-io/dsh-arm64/releases/download/v0.1.0-rc.6/sha256sums.txt"
          # keep only the tarball line
          grep 'dsh-arm64-0.1.0-rc.6.tar.gz' app/src/main/assets/dsh/dsh-arm64-0.1.0-rc.6.tar.gz.sha256 \
            | cut -d' ' -f1 > app/src/main/assets/dsh/.sha
          mv app/src/main/assets/dsh/.sha app/src/main/assets/dsh/dsh-arm64-0.1.0-rc.6.tar.gz.sha256
          ls -la app/src/main/assets/dsh/ app/src/main/assets/rootfs/

      - name: Build APK
        run: ./gradlew assembleRelease --no-daemon
        env:
          DSHMOBILE_KEYSTORE_B64: ${{ secrets.DSHMOBILE_KEYSTORE_B64 }}
          DSHMOBILE_STORE_PASS: ${{ secrets.DSHMOBILE_STORE_PASS }}
          DSHMOBILE_KEY_PASS: ${{ secrets.DSHMOBILE_KEY_PASS }}
          DSHMOBILE_KEY_ALIAS: ${{ secrets.DSHMOBILE_KEY_ALIAS }}

      - name: Verify APK
        run: |
          APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
          echo "APK=$APK"
          unzip -t "$APK" | tail -1
          ls -la "$APK"
          $ANDROID_SDK_ROOT/build-tools/35.0.0/aapt dump badging "$APK" | head -3
          $ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner verify --print-certs "$APK" > /tmp/certs.txt
          grep -q "Signer #1 certificate DN" /tmp/certs.txt || (echo "APK IS NOT SIGNED" && exit 1)
          unzip -l "$APK" | grep -E "assets/(rootfs/debian.tar.xz|dsh/dsh-arm64)" 

      - name: Rust host tests
        run: cd rust && cargo test --workspace

      - name: Upload APK artifact
        uses: actions/upload-artifact@v5
        with:
          name: dsh-mobile-apk
          path: app/build/outputs/apk/release/*.apk

  release:
    needs: build
    if: github.ref_type == 'tag'
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - name: Download APK
        uses: actions/download-artifact@v5
        with:
          name: dsh-mobile-apk
          path: dist

      - name: Rename with version
        run: |
          APK=$(ls dist/*.apk | head -1)
          mv "$APK" "dist/dsh-mobile-${GITHUB_REF_NAME}.apk"

      - name: Verify release APK signature
        run: |
          $ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner verify --print-certs "dist/dsh-mobile-${GITHUB_REF_NAME}.apk" | grep -q "Signer #1 certificate DN" \
            || (echo "RELEASE APK IS NOT SIGNED" && exit 1)

      - name: Publish release
        uses: softprops/action-gh-release@v2
        with:
          files: dist/*.apk
          generate_release_notes: true
```

- [ ] **Step 5: Run the Rust tests once more locally and commit**

```bash
cd rust && cargo test --workspace && cd ..
git add rootfs/ .github/workflows/android.yml
git commit -m "ci: rootfs job + assets pipeline + dsh-mobile release flow"
```

---

### Task 5: Docs, License, README

**Files:**
- Create: `LICENSE` (MIT), `README.md` (rewrite), `docs/manual-test-checklist.md` (rewrite), `docs/third-party-notices.md`
- Modify: `.gitignore` (keep baseline)

- [ ] **Step 1: Write LICENSE (MIT, dsh-io)**

```text
MIT License

Copyright (c) 2026 dsh-io

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- [ ] **Step 2: Write README.md**

```markdown
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
  notification; exit via the notification or by stopping the app.

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
```

- [ ] **Step 3: Write docs/third-party-notices.md**

```markdown
# Third-party notices

## proot (GPLv2)

This app embeds a compiled `proot` binary built from
https://github.com/proot-me/proot. proot is licensed under the GNU General
Public License v2. The complete corresponding source code is available at
https://github.com/proot-me/proot (and the exact build script used is
`tools/build-proot.sh` in this repository). You may request a copy of the
source from the maintainers of this project.

## termux terminal-view (Apache-2.0)

The terminal emulator is provided by termux-app's terminal-view component:
https://github.com/termux/termux-app (Apache License 2.0).

## dsh / dsh-arm64 (MIT)

DeepSeek Harness (dsh) is MIT-licensed: https://github.com/deepseek-ai/deepseek-harness.
dsh-arm64 is MIT-licensed: https://github.com/dsh-io/dsh-arm64.
```

- [ ] **Step 4: Rewrite docs/manual-test-checklist.md**

```markdown
# Manual Test Checklist

Device: arm64 Android 8.0–15+ (plus at least one strict-SELinux vendor device if available).
Build: APK artifact from the CI `android-build` workflow.

## Fresh install & first run

- [ ] Fresh install → extraction progress shown; completes without error (~1.5GB free storage needed)
- [ ] Foreground notification appears ("dsh runtime running")
- [ ] WebView shows the dsh web UI at 127.0.0.1:3080 (chat screen visible)
- [ ] Notification permission prompt shown on API 33+; denied still works (service degrades gracefully)

## Harness tab

- [ ] First-run init flow works (API key entry + profile creation) and persists after restart
- [ ] Chat round-trip works (send a message, get a reply)
- [ ] Settings page loads and saves
- [ ] Kill app from recents → notification gone, no orphan proot (`adb shell ps -A | grep proot` empty)
- [ ] Reopen app → service restarts, WebView reconnects

## Terminal tab

- [ ] Tap terminal icon → `#` prompt (Debian bash inside rootfs)
- [ ] `echo hello` prints `hello`; `cat /etc/os-release` shows Debian bookworm
- [ ] `node --version` prints v22.x (glibc node)
- [ ] Rotation/resize: `stty size` reports new size after rotating
- [ ] Back button closes the terminal session; process group reaped
- [ ] Non-zero session exit offers "Retry with compatibility mode" (PROOT_NO_SECCOMP)

## Storage & hygiene

- [ ] App dir clean: `adb shell run-as com.dshio.dshmobile ls files/` shows `rootfs/`, `dsh/`, `proot/`, `downloads/`, `logs/`
- [ ] `dsh.log` contains the boot line `dsh web: http://127.0.0.1:3080` and no node-pty errors
```

- [ ] **Step 5: Commit**

```bash
git add LICENSE README.md docs/
git commit -m "docs: README, MIT license, third-party notices, device checklist"
```

---

### Task 6: Publish — repo, CI, release, hand-off

**Files:** none (release operations)

- [ ] **Step 1: Create the repo and push**

```bash
gh repo create dsh-io/dsh-mobile --public --source . --push \
  --description "DeepSeek Harness (dsh) as an Android app — embedded proot + minimal Debian rootfs + WebView"
git ls-remote origin main   # verify push (never trust git log alone)
gh repo edit dsh-io/dsh-mobile --add-topic deepseek --add-topic android --add-topic arm64 \
  --add-topic proot --add-topic aarch64 --add-topic webview --add-topic kotlin --add-topic rust
```

- [ ] **Step 2: Generate keystore and configure secrets**

Run: `./tools/gen-keystore.sh` (baseline script, DSHMOBILE_* names) — save outputs.
Add 4 GitHub secrets to the repo (DSHMOBILE_KEYSTORE_B64 / STORE_PASS / KEY_PASS / KEY_ALIAS) via `gh secret set`.

- [ ] **Step 3: Watch CI until green**

Run: `gh run list --repo dsh-io/dsh-mobile --limit 3`
Expected: rootfs + build jobs succeed (first run ~15-25 min: debootstrap + proot NDK build + cargo + gradle).

- [ ] **Step 4: Download APK artifact and check size**

```bash
gh run download --repo dsh-io/dsh-mobile --name dsh-mobile-apk --dir /tmp/dsh-apk
ls -la /tmp/dsh-apk/*.apk   # expect ~250MB
unzip -l /tmp/dsh-apk/*.apk | grep -E "assets/(rootfs/debian.tar.xz|dsh/dsh-arm64)"
```

- [ ] **Step 5: Tag and release**

```bash
git tag v0.1.0 && git push origin v0.1.0
gh run list --repo dsh-io/dsh-mobile --limit 1   # wait for release job
gh release view v0.1.0 --repo dsh-io/dsh-mobile  # APK attached
```

- [ ] **Step 6: Hand off to device testing**

Report to the user: APK URL, install steps, and the `docs/manual-test-checklist.md`
items to run on a real device (especially: first-run extract, WebView load,
terminal bash, background kill recovery). The only unverifiable-before-device
risk is node-pty behavior under proot on Android (verified on Linux host proot
in Task 4 Step 3; Android-specific /dev/pts differences are device-test items).