use libc::{c_char, c_int, O_NOCTTY, O_RDWR};

pub struct Pty {
    pub master: c_int,
    pub slave: c_int,
}

impl Drop for Pty {
    fn drop(&mut self) {
        unsafe {
            libc::close(self.master);
            libc::close(self.slave);
        }
    }
}

pub fn open_pty() -> Result<Pty, c_int> {
    unsafe {
        let master = libc::posix_openpt(O_RDWR | O_NOCTTY);
        if master < 0 {
            return Err(errno());
        }
        if libc::grantpt(master) != 0 {
            return Err(errno());
        }
        if libc::unlockpt(master) != 0 {
            return Err(errno());
        }
        let mut name: [c_char; 64] = [0; 64];
        if libc::ptsname_r(master, name.as_mut_ptr(), name.len()) != 0 {
            return Err(errno());
        }
        // Retry on the classic devpts race: the slave node may not exist yet
        // right after unlockpt under heavy parallel allocation.
        for attempt in 0..5 {
            let slave = libc::open(name.as_ptr(), O_RDWR | O_NOCTTY);
            if slave >= 0 {
                return Ok(Pty { master, slave });
            }
            if attempt == 4 {
                return Err(errno());
            }
            std::thread::sleep(std::time::Duration::from_millis(2 * (attempt as u64 + 1)));
        }
        Err(errno())
    }
}

pub fn set_winsize(fd: c_int, rows: u16, cols: u16) -> Result<(), c_int> {
    let mut ws = libc::winsize {
        ws_row: rows,
        ws_col: cols,
        ws_xpixel: 0,
        ws_ypixel: 0,
    };
    let r = unsafe {
        libc::ioctl(fd, libc::TIOCSWINSZ, &mut ws as *mut libc::winsize)
    };
    if r == 0 {
        Ok(())
    } else {
        Err(errno())
    }
}

pub fn set_raw_mode(fd: c_int) -> Result<(), c_int> {
    unsafe {
        let mut termios: libc::termios = std::mem::zeroed();
        if libc::tcgetattr(fd, &mut termios) != 0 {
            return Err(errno());
        }
        libc::cfmakeraw(&mut termios);
        if libc::tcsetattr(fd, libc::TCSANOW, &termios) != 0 {
            return Err(errno());
        }
        Ok(())
    }
}

fn errno() -> c_int {
    std::io::Error::last_os_error()
        .raw_os_error()
        .unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use libc::c_void;

    #[test]
    fn pty_echo_roundtrip() {
        let pty = open_pty().expect("open pty");
        let msg = b"hello-pty\n";
        let n = unsafe { libc::write(pty.master, msg.as_ptr() as *const c_void, msg.len()) };
        assert_eq!(n, msg.len() as isize);
        let mut buf = [0u8; 64];
        let n = unsafe { libc::read(pty.slave, buf.as_mut_ptr() as *mut c_void, buf.len()) };
        assert!(n > 0);
        assert_eq!(&buf[..n as usize], msg);
    }

    #[test]
    fn pty_echo_reverse() {
        let pty = open_pty().expect("open pty");
        set_raw_mode(pty.slave).expect("raw mode");
        let msg = b"from-slave\n";
        let n = unsafe { libc::write(pty.slave, msg.as_ptr() as *const c_void, msg.len()) };
        assert_eq!(n, msg.len() as isize);
        let mut buf = [0u8; 64];
        let n = unsafe { libc::read(pty.master, buf.as_mut_ptr() as *mut c_void, buf.len()) };
        assert!(n > 0);
        assert_eq!(&buf[..n as usize], msg);
    }

    #[test]
    fn winsize_roundtrip() {
        let pty = open_pty().expect("open pty");
        set_winsize(pty.master, 40, 120).expect("set winsize");
        let mut ws = libc::winsize {
            ws_row: 0,
            ws_col: 0,
            ws_xpixel: 0,
            ws_ypixel: 0,
        };
        let r = unsafe { libc::ioctl(pty.master, libc::TIOCGWINSZ, &mut ws as *mut libc::winsize) };
        assert_eq!(r, 0);
        assert_eq!(ws.ws_row, 40);
        assert_eq!(ws.ws_col, 120);
    }
}
