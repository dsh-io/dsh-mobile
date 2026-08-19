//! CI + local reproduction harness for the device extraction path.
//!
//! Runs install_rootfs (hash check → extract → W^X strip → rename) against a
//! real rootfs tarball as an unprivileged user — the exact path the app runs
//! on device. The CI smoke test uses `sudo tar` + chroot, which never
//! exercises this code, so a regression here would only surface on a phone.
//!
//! Usage: verify_extract <tar.xz> <dest> [expected-sha256]

use std::path::PathBuf;

fn main() {
    let mut args = std::env::args().skip(1);
    let tar = PathBuf::from(args.next().expect("tar path"));
    let dest = PathBuf::from(args.next().expect("dest path"));
    let expected = args.next();
    match rootfs_manager::extract::install_rootfs(&tar, &dest, expected.as_deref().unwrap_or("")) {
        Ok(()) => println!("EXTRACT_OK"),
        Err(e) => {
            println!("EXTRACT_FAIL: {e}");
            std::process::exit(1);
        }
    }
}