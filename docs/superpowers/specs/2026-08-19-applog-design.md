# Design — AppLog unified logging system

Date: 2026-08-19
Status: approved

## Problem

Diagnosing on-device failures today is nearly impossible: the Android shell
logs nothing (CAS decisions, cooldown hits, watchdog transitions, crash
restarts are all invisible), Kotlin crashes are unrecorded, the engine's
`logs/dsh.log` grows unbounded and unstructured, and there is no way to view
or export anything without adb + a file explorer.

## Solution

A single-process `AppLog` singleton (pure Kotlin stdlib, zero new
dependencies) that funnels every log line to three sinks:

1. **In-memory ring buffer** (last 2000 entries) — powers the real-time
   viewer via `MutableStateFlow`.
2. **Rolling file** (`files/logs/app.log`, 512KB × 2) — survives restart;
   rotated in the app process.
3. **logcat mirror** (`android.util.Log`, tag `DeepCode`) — adb debugging.

### Components

- `AppLog` — `v/d/i/w/e(tag, msg)` API, thread-safe (`synchronized`), each
  call emits a `LogEntry(timestampMs, level, tag, message)` into the ring,
  appends to the rolling file, mirrors to logcat.
- Engine logs (`files/logs/dsh.log`) stay the engine's raw output. The
  service rotates it (>1MB → `dsh.log.1`) at each start. The viewer and the
  crash bundle read both `app.log` and `dsh.log` tail.
- Crash capture: a `Thread.setDefaultUncaughtExceptionHandler` wrapper
  appends a timestamped stack trace + the dsh.log tail (30 lines) to
  `logs/crash.log`, then delegates to the previous handler (never swallows).
- Native crash: `daemon.rs`'s reaper already receives the child's waitpid
  status; it now writes `exit status: signal=N` into the log so a SIGSEGV
  of node/proot is visible instead of an unexplained silence.

### Instrumentation points

- MainActivity: CAS winner/loser, extract phases + wall time, storage check.
- DshService: start decision chain (cooldown hit/cleared, STARTING CAS),
  watchdog transitions (healthy / down / never-came-up / crash), restart
  count, onCrash branch (fatal vs retry).

### Viewer UI

Third bottom-nav tab "Logs": mono-font LazyColumn, severity color coding
(E red, W amber, I teal, D grey), follows latest, severity filter chips, and
a Share action that exports the merged log bundle (app.log + dsh.log tail)
via FileProvider + `ACTION_SEND`.

### Build / test

- No new Gradle or Rust dependencies. One unit test added for the daemon
  reaper's signal formatting; Kotlin side is verified by the CI compile.
- FileProvider needs `res/xml/file_paths.xml` + manifest `<provider>` +
  a `cache-path` entry for the exported bundle.