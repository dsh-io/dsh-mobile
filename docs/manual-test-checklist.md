# Manual Test Checklist

Device: arm64 Android 8.0–15+ (plus at least one strict-SELinux vendor device if available).
Build: APK artifact from the CI `android-build` workflow.

## Fresh install & first run

- [ ] Fresh install → extraction progress shown (rootfs, then dsh); completes without error (~1.5GB free storage needed)
- [ ] With low free space → first-run screen shows "Insufficient storage" error; retry after freeing space works
- [ ] Foreground notification appears ("Harness runtime running") with a Stop button
- [ ] WebView shows the dsh web UI at 127.0.0.1:3080 (chat screen visible)
- [ ] Notification permission prompt shown on API 33+; denied still works (service degrades gracefully)

## Harness tab

- [ ] First-run init flow works (API key entry + profile creation) and persists after restart
- [ ] Chat round-trip works (send a message, get a reply)
- [ ] Settings page loads and saves
- [ ] Swipe app from recents → foreground service survives; notification stays; dsh keeps running
- [ ] Reopen app → WebView reconnects to the running service (no re-extract, no restart)
- [ ] Stop via the notification's Stop button → notification gone, service stopped, no orphan proot (`adb shell ps -A | grep proot` empty)
- [ ] Crash recovery: `adb shell kill -9 <proot pid>` → service auto-restarts dsh, WebView reconnects
- [ ] Repeated failures (kill dsh 4 times in a row: 3 auto-restarts + 1 final crash) → persistent notification with the last log lines appears

## Terminal tab

- [ ] Tap terminal icon → `#` prompt (Debian bash inside rootfs)
- [ ] `echo hello` prints `hello`; `cat /etc/os-release` shows Debian bookworm
- [ ] `node --version` prints v22.x (glibc node)
- [ ] Rotation/resize: `stty size` reports new size after rotating
- [ ] Back button closes the terminal session; process group reaped
- [ ] Non-zero session exit offers "Retry with compatibility mode" (PROOT_NO_SECCOMP)

## Device compatibility (learned from the deprecated harness-mobile project)

- [ ] Android 15+ device: engine starts — direct exec may be denied; verify the linker64 fallback fires (`adb logcat | grep -i linker64`) and the harness reaches Ready
- [ ] Huawei/EMUI or Android 10 device: engine + node-pty load cleanly — no `mmap`/`dlopen` PROT_EXEC errors in logcat (W^X write-bit stripping works)
- [ ] Reinstall without clearing data (`adb install -r`): app still extracts/runs — no EACCES on the read-only proot binary / rootfs files
- [ ] Kill the app mid-extraction (`adb shell am force-stop` during extract, then relaunch): retry succeeds — no permanent "extract failed" (write-bit restore)
- [ ] Rotation or home-back during extraction: no double-extract / no crash (single-flight CAS)
- [ ] Slow boot (throttled/low-end device): engine reaches Ready within ~90s without a crash loop; kill it mid-boot → exactly one restart (cooldown/CAS), no EADDRINUSE storm

## Storage & hygiene

- [ ] App dir clean: `adb shell run-as com.dshio.dshmobile ls files/` shows `rootfs/`, `dsh/`, `proot/`, `downloads/`, `logs/`
- [ ] `dsh.log` contains `127.0.0.1:3080` and no node-pty/error lines
