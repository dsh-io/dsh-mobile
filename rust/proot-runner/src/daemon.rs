use std::os::unix::ffi::OsStrExt;
use std::path::Path;
use std::time::Instant;

/// proot argv for a background (non-TTY) command inside the rootfs.
/// Identical to command::build_proot_args except the shell/-l tail is
/// replaced by the raw command.
pub fn build_daemon_args(rootfs: &Path, cmd: &[String]) -> Vec<String> {
    build_daemon_args_with_binds(rootfs, &[], cmd)
}

/// Build daemon arguments with explicit host-to-guest bind mounts.
///
/// The dsh package lives next to the rootfs in Android app storage, so it is
/// not visible after `proot -r` unless it is bound into the guest explicitly.
/// Bind options must appear before the command (`/usr/bin/env`).
pub fn build_daemon_args_with_binds(
    rootfs: &Path,
    binds: &[(String, String)],
    cmd: &[String],
) -> Vec<String> {
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
    ];
    for (host, guest) in binds {
        args.push("-b".to_string());
        args.push(format!("{host}:{guest}"));
    }
    args.extend([
        "/usr/bin/env".to_string(),
        "-i".to_string(),
        "HOME=/root".to_string(),
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".to_string(),
        "TERM=xterm-256color".to_string(),
    ]);
    args.extend_from_slice(cmd);
    args
}

/// Fork and exec a background process (no PTY) with stdout/stderr appended
/// to log_path. Returns the child pid. The child is its own process group
/// leader (setsid), so stop_pgid can kill the whole group.
///
/// Device-verified fallback (deprecated harness-mobile project): Android 15+
/// denies direct exec of app-data ELF even below the SDK-35 target (OEM
/// enforcement), surfacing as EACCES. proot is NDK/bionic-linked, so
/// /system/bin/linker64 can load it — on EACCES the child retries once with
/// the linker as the executable (argv[0] = linker64, original argv appended).
/// Rootfs (glibc) binaries are never exec'd from app data — they run under
/// proot inside the container — so only the proot binary needs this fallback.
pub fn spawn_daemon(
    argv: &[String],
    prog: &str,
    env: &[String],
    log_path: &Path,
) -> Result<libc::pid_t, String> {
    // Android's JVM process is multi-threaded. Between fork and exec the
    // child may only use async-signal-safe operations: Rust allocation,
    // CString construction, and filesystem helpers can deadlock on locks
    // held by a thread that did not survive fork. Prepare everything first.
    if let Some(dir) = log_path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("mkdir log dir: {e}"))?;
    }
    let log_c = std::ffi::CString::new(log_path.as_os_str().as_bytes())
        .map_err(|_| "log path contains NUL".to_string())?;
    let prog_c = std::ffi::CString::new(prog).map_err(|_| "program contains NUL".to_string())?;
    let argv_c: Vec<std::ffi::CString> = argv
        .iter()
        .map(|arg| {
            std::ffi::CString::new(arg.as_str()).map_err(|_| "argument contains NUL".to_string())
        })
        .collect::<Result<_, _>>()?;
    let mut argv_p: Vec<*const libc::c_char> = argv_c.iter().map(|c| c.as_ptr()).collect();
    argv_p.push(std::ptr::null());
    let env_c: Vec<std::ffi::CString> = env
        .iter()
        .map(|entry| {
            std::ffi::CString::new(entry.as_str())
                .map_err(|_| "environment entry contains NUL".to_string())
        })
        .collect::<Result<_, _>>()?;
    let mut env_p: Vec<*const libc::c_char> = env_c.iter().map(|c| c.as_ptr()).collect();
    env_p.push(std::ptr::null());
    let linker = std::ffi::CString::new("/system/bin/linker64").expect("static linker path");
    let mut argv_linker: Vec<*const libc::c_char> = Vec::with_capacity(argv_p.len() + 1);
    argv_linker.push(linker.as_ptr());
    argv_linker.extend(argv_p.iter().copied()); // argv_p already ends with null
    let flags = libc::O_WRONLY | libc::O_CREAT | libc::O_APPEND;
    let log_fd = unsafe { libc::open(log_c.as_ptr(), flags, 0o644) };

    unsafe {
        let parent_pid = libc::getpid();
        let pid = libc::fork();
        if pid < 0 {
            if log_fd >= 0 {
                libc::close(log_fd);
            }
            return Err(format!("fork: {}", std::io::Error::last_os_error()));
        }
        if pid == 0 {
            // Do not leave an orphan proot/node tree if Android kills the app
            // process. The parent check closes the race where it dies between
            // fork() and PR_SET_PDEATHSIG.
            if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM) != 0
                || libc::getppid() != parent_pid
            {
                libc::_exit(126);
            }
            if libc::setsid() < 0 {
                // stop_pgid relies on pid also being the process-group id.
                // Running without a new session would make shutdown hang or
                // signal an unrelated group after pid reuse.
                libc::_exit(126);
            }
            let devnull = libc::open(
                b"/dev/null\0".as_ptr() as *const libc::c_char,
                libc::O_RDONLY,
            );
            if devnull >= 0 {
                libc::dup2(devnull, 0);
                libc::close(devnull);
            }
            if log_fd >= 0 {
                libc::dup2(log_fd, 1);
                libc::dup2(log_fd, 2);
                libc::close(log_fd);
            }
            libc::execve(prog_c.as_ptr(), argv_p.as_ptr(), env_p.as_ptr());
            if std::io::Error::last_os_error().raw_os_error() == Some(libc::EACCES) {
                libc::execve(linker.as_ptr(), argv_linker.as_ptr(), env_p.as_ptr());
            }
            libc::_exit(127);
        }
        if log_fd >= 0 {
            libc::close(log_fd);
        }
        Ok(pid)
    }
}

/// SIGTERM the process group of pid, escalate to SIGKILL after 3s.
/// Returns true when the process is confirmed dead. If the child was already
/// reaped elsewhere (ECHILD), it is dead — return immediately instead of
/// burning the full 3s window.
pub fn stop_pgid(pid: libc::pid_t) -> bool {
    unsafe {
        let mut status: libc::c_int = 0;
        // Check/reap before signalling. Supervision may already have reaped a
        // crashed child; signalling -pid first risks hitting a rapidly reused
        // process-group id that no longer belongs to us.
        let initial = libc::waitpid(pid, &mut status, libc::WNOHANG);
        if initial == pid {
            return true;
        }
        if initial < 0 && std::io::Error::last_os_error().raw_os_error() == Some(libc::ECHILD) {
            return true;
        }

        libc::kill(-pid, libc::SIGTERM);
        let deadline = Instant::now() + std::time::Duration::from_secs(3);
        loop {
            let r = libc::waitpid(pid, &mut status, libc::WNOHANG);
            if r == pid {
                return true;
            }
            if r < 0 && std::io::Error::last_os_error().raw_os_error() == Some(libc::ECHILD) {
                return true; // already reaped — dead
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
    use std::path::PathBuf;

    #[test]
    fn daemon_args_have_no_login_shell() {
        let args = build_daemon_args(
            &PathBuf::from("/data/files/rootfs/debian"),
            &[
                "node".into(),
                "--expose-internals".into(),
                "/data/files/dsh/bin.js".into(),
            ],
        );
        assert_eq!(&args[0..2], &["-0", "-r"]);
        assert_eq!(args[2], "/data/files/rootfs/debian");
        assert!(args.contains(&"--kill-on-exit".to_string()));
        let i_pos = args.iter().position(|a| a == "-i").unwrap();
        assert_eq!(args[i_pos + 1], "HOME=/root");
        let cmd_start = args.iter().position(|a| a == "node").unwrap();
        assert_eq!(
            &args[cmd_start..],
            &["node", "--expose-internals", "/data/files/dsh/bin.js"]
        );
        assert!(!args.contains(&"-l".to_string()));
    }

    #[test]
    fn daemon_bind_is_visible_before_guest_command() {
        let args = build_daemon_args_with_binds(
            &PathBuf::from("/data/files/rootfs/debian"),
            &[("/data/files/dsh".into(), "/root/dsh".into())],
            &["node".into(), "/root/dsh/lib/bin.js".into()],
        );
        let bind_pos = args
            .iter()
            .position(|a| a == "/data/files/dsh:/root/dsh")
            .unwrap();
        let env_pos = args.iter().position(|a| a == "/usr/bin/env").unwrap();
        let cmd_pos = args.iter().position(|a| a == "node").unwrap();
        assert_eq!(args[bind_pos - 1], "-b");
        assert!(bind_pos < env_pos && env_pos < cmd_pos);
    }

    #[test]
    fn spawn_daemon_appends_log_and_stop_reaps() {
        let dir = std::env::temp_dir().join(format!("dsh-daemon-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let log = dir.join("dsh.log");
        let env = vec!["PATH=/bin:/usr/bin".to_string()];
        let pid = spawn_daemon(
            &[
                "/bin/sh".into(),
                "-c".into(),
                "echo daemon-alive; sleep 60".into(),
            ],
            "/bin/sh",
            &env,
            &log,
        )
        .expect("spawn daemon");
        // wait for the log line (generous deadline: cargo runs tests in
        // parallel and slow runners can delay the child's first output)
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
        loop {
            if std::fs::read_to_string(&log)
                .map(|s| s.contains("daemon-alive"))
                .unwrap_or(false)
            {
                break;
            }
            assert!(std::time::Instant::now() < deadline, "log never got output");
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        assert!(stop_pgid(pid));
        let r = unsafe { libc::kill(pid, 0) };
        assert_eq!(r, -1); // ESRCH — reaped
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn spawn_daemon_exit_127_on_bad_prog_is_reaped() {
        let dir = std::env::temp_dir().join(format!("dsh-daemon-badprog-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let log = dir.join("dsh.log");
        let pid = spawn_daemon(
            &["/nonexistent/binary".into()],
            "/nonexistent/binary",
            &[],
            &log,
        )
        .expect("spawn daemon");
        let mut status: libc::c_int = 0;
        let reaped = unsafe { libc::waitpid(pid, &mut status, 0) };
        assert_eq!(reaped, pid);
        assert!(libc::WIFEXITED(status));
        assert_eq!(libc::WEXITSTATUS(status), 127);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn stop_pgid_on_already_reaped_child_returns_immediately() {
        let dir = std::env::temp_dir().join(format!("dsh-stop-reaped-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let log = dir.join("dsh.log");
        let pid =
            spawn_daemon(&["/bin/true".into()], "/bin/true", &[], &log).expect("spawn daemon");
        let mut status: libc::c_int = 0;
        assert_eq!(unsafe { libc::waitpid(pid, &mut status, 0) }, pid);
        // stop_pgid must detect ECHILD and return instantly (no 3s wait).
        let start = std::time::Instant::now();
        assert!(stop_pgid(pid));
        assert!(start.elapsed() < std::time::Duration::from_secs(1));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
