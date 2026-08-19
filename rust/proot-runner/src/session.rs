use std::ffi::CString;

use libc::{c_int, pid_t};

use crate::pty::{open_pty, set_raw_mode, Pty};

#[derive(Debug)]
pub enum SessionError {
    Pty(String),
    Spawn(String),
}

impl std::fmt::Display for SessionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SessionError::Pty(e) => write!(f, "pty error: {e}"),
            SessionError::Spawn(e) => write!(f, "spawn error: {e}"),
        }
    }
}

pub struct Session {
    pub pid: pid_t,
    pub pty: Pty,
}

pub fn spawn_session(argv: &[String], prog: &str, env: &[String]) -> Result<Session, SessionError> {
    let pty = open_pty().map_err(|e| SessionError::Pty(format!("open_pty: {e}")))?;
    unsafe {
        let pid = libc::fork();
        if pid < 0 {
            return Err(SessionError::Spawn(format!("fork: {e}", e = std::io::Error::last_os_error())));
        }
        if pid == 0 {
            // child
            libc::setsid();
            libc::ioctl(pty.slave, libc::TIOCSCTTY, 0);
            let _ = set_raw_mode(pty.slave);
            libc::dup2(pty.slave, 0);
            libc::dup2(pty.slave, 1);
            libc::dup2(pty.slave, 2);
            libc::close(pty.master);
            libc::close(pty.slave);
            let prog_c = CString::new(prog).unwrap();
            let argv_c: Vec<CString> = argv
                .iter()
                .map(|a| CString::new(a.as_str()).unwrap())
                .collect();
            let mut argv_p: Vec<*const libc::c_char> =
                argv_c.iter().map(|c| c.as_ptr()).collect();
            argv_p.push(std::ptr::null());
            let env_c: Vec<CString> = env.iter().map(|e| CString::new(e.as_str()).unwrap()).collect();
            let mut env_p: Vec<*const libc::c_char> =
                env_c.iter().map(|c| c.as_ptr()).collect();
            env_p.push(std::ptr::null());
            libc::execve(prog_c.as_ptr(), argv_p.as_ptr(), env_p.as_ptr());
            libc::_exit(127);
        }
        libc::setpgid(pid, pid);
        libc::close(pty.slave);
        Ok(Session { pid, pty })
    }
}

pub fn stop_session(session: &Session) -> bool {
    unsafe {
        libc::kill(-session.pid, libc::SIGTERM);
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        loop {
            let mut status: c_int = 0;
            let r = libc::waitpid(session.pid, &mut status, libc::WNOHANG);
            if r == session.pid {
                return true;
            }
            if std::time::Instant::now() > deadline {
                libc::kill(-session.pid, libc::SIGKILL);
                libc::waitpid(session.pid, &mut status, 0);
                return true;
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use libc::c_void;

    #[test]
    fn spawn_echo_reads_output() {
        let env = vec!["PATH=/bin:/usr/bin".to_string()];
        let session = spawn_session(&["echo".into(), "session-alive".into()], "/bin/echo", &env)
            .expect("spawn echo");
        let mut buf = [0u8; 128];
        let n = unsafe { libc::read(session.pty.master, buf.as_mut_ptr() as *mut c_void, buf.len()) };
        let out = String::from_utf8_lossy(&buf[..n as usize]).to_string();
        assert_eq!(out.trim(), "session-alive");
        stop_session(&session);
    }

    #[test]
    fn spawn_sh_interactive_echo() {
        let env = vec!["PATH=/bin:/usr/bin".to_string(), "PS1=$ ".to_string()];
        let session = spawn_session(&["sh".into(), "-i".into()], "/bin/sh", &env)
            .expect("spawn sh");
        let cmd = b"echo pty-works\n";
        unsafe {
            libc::write(session.pty.master, cmd.as_ptr() as *const c_void, cmd.len());
        }
        let mut buf = [0u8; 256];
        let mut acc = String::new();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
        while !acc.contains("pty-works") && std::time::Instant::now() < deadline {
            let n = unsafe { libc::read(session.pty.master, buf.as_mut_ptr() as *mut c_void, buf.len()) };
            if n > 0 {
                acc.push_str(&String::from_utf8_lossy(&buf[..n as usize]));
            }
        }
        assert!(acc.contains("pty-works"), "output was: {acc}");
        stop_session(&session);
    }

    #[test]
    fn stop_kills_sleeping_child() {
        let env = vec!["PATH=/bin:/usr/bin".to_string()];
        let session = spawn_session(&["sleep".into(), "60".into()], "/bin/sleep", &env)
            .expect("spawn sleep");
        assert!(stop_session(&session));
        let r = unsafe { libc::kill(session.pid, 0) };
        assert_eq!(r, -1); // ESRCH — reaped
    }
}
