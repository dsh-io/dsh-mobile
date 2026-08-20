use std::path::Path;

pub fn extract_archive(tarball: &Path, dest: &Path) -> Result<(), String> {
    std::fs::create_dir_all(dest).map_err(|e| format!("mkdir: {e}"))?;
    let f = std::fs::File::open(tarball).map_err(|e| format!("open: {e}"))?;
    let buffered = std::io::BufReader::with_capacity(256 * 1024, f);
    let ext = tarball.extension().and_then(|e| e.to_str()).unwrap_or("");
    if ext == "xz" {
        let xz = xz2::read::XzDecoder::new(buffered);
        let mut a = tar::Archive::new(xz);
        a.unpack(dest).map_err(|e| format!("extract: {e}"))?;
    } else if ext == "gz" {
        let gz = flate2::read::GzDecoder::new(buffered);
        let mut a = tar::Archive::new(gz);
        a.unpack(dest).map_err(|e| format!("extract: {e}"))?;
    } else {
        return Err(format!("unsupported archive extension: {ext}"));
    }
    strip_write_bits(dest)?;
    Ok(())
}

/// Vendor W^X compatibility (device-verified in the deprecated harness-mobile
/// project, SnapshotExtractor): Huawei/EMUI (and Android 10 hardening) refuse
/// to exec a writable file, and refuse mmap(PROT_EXEC) of a writable file —
/// so a writable shared library fails dlopen even when the module itself is
/// r-x. After extraction, strip write bits from every executable file and
/// from every `.so`/`.node` file (dlopen'd modules like node-pty's pty.node,
/// including DT_NEEDED libs that ship mode 0600 in the archive). Engine
/// binaries and libs never write themselves at runtime.
///
/// Uses libc::stat/chmod directly: std::fs::set_permissions relies on statx,
/// which is unreliable in sandboxed/emulated environments, and libc calls
/// behave identically on bionic (Android).
///
/// Symlinks are skipped entirely (lstat): following one could escape the
/// tree — Debian's /usr/lib/ssl -> /etc/ssl and /lib -> usr/lib mean a
/// follow-based walk reaches host directories outside the rootfs (EACCES on
/// root-owned 0700 dirs, e.g. /etc/ssl/private), failing the whole extract.
/// The symlink target is handled by its own archive entry if any.
fn strip_write_bits(root: &Path) -> Result<(), String> {
    fn visit(dir: &Path) -> Result<(), String> {
        use std::os::unix::ffi::OsStrExt;
        use std::os::unix::fs::MetadataExt;
        for entry in
            std::fs::read_dir(dir).map_err(|e| format!("read_dir {}: {e}", dir.display()))?
        {
            let p = entry.map_err(|e| format!("read_dir entry: {e}"))?.path();
            let md = match std::fs::symlink_metadata(&p) {
                Ok(md) => md,
                Err(_) => continue,
            };
            if md.file_type().is_symlink() {
                continue;
            }
            if md.is_dir() {
                visit(&p)?;
                continue;
            }
            let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
            let mode = md.mode();
            let is_exec = mode & 0o111 != 0;
            let is_lib = name.ends_with(".so") || name.ends_with(".node");
            if is_exec || is_lib {
                let c = std::ffi::CString::new(p.as_os_str().as_bytes())
                    .map_err(|_| format!("cstring: {}", p.display()))?;
                unsafe {
                    if libc::chmod(c.as_ptr(), mode & !0o222) != 0 {
                        return Err(format!(
                            "chmod {}: {}",
                            p.display(),
                            std::io::Error::last_os_error()
                        ));
                    }
                }
            }
        }
        Ok(())
    }
    visit(root)
}

/// Recursively restore write bits (undo W^X stripping) so a stale tree can
/// be removed or overwritten — an interrupted previous run must never leave
/// a permanently unremovable/EACCES'd extraction. Symlinks are skipped
/// (lstat) for the same reason as strip_write_bits.
fn make_writable_recursive(root: &Path) -> Result<(), String> {
    use std::os::unix::ffi::OsStrExt;
    use std::os::unix::fs::MetadataExt;
    fn visit(dir: &Path) -> Result<(), String> {
        for entry in
            std::fs::read_dir(dir).map_err(|e| format!("read_dir {}: {e}", dir.display()))?
        {
            let p = entry.map_err(|e| format!("read_dir entry: {e}"))?.path();
            let md = match std::fs::symlink_metadata(&p) {
                Ok(md) => md,
                Err(_) => continue,
            };
            if md.file_type().is_symlink() {
                continue;
            }
            if md.is_dir() {
                visit(&p)?;
            }
            let c = std::ffi::CString::new(p.as_os_str().as_bytes())
                .map_err(|_| format!("cstring: {}", p.display()))?;
            unsafe {
                if libc::chmod(c.as_ptr(), md.mode() | 0o200) != 0 {
                    return Err(format!(
                        "chmod {}: {}",
                        p.display(),
                        std::io::Error::last_os_error()
                    ));
                }
            }
        }
        Ok(())
    }
    visit(root)
}

fn remove_dir_all_force(p: &Path, what: &str) -> Result<(), String> {
    match std::fs::remove_dir_all(p) {
        Ok(()) => Ok(()),
        Err(_) => {
            // Read-only files left by a W^X-stripped previous run can make
            // removal fail on some filesystems; restore write bits and retry
            // once before giving up (idempotent reinstall).
            let _ = make_writable_recursive(p);
            std::fs::remove_dir_all(p).map_err(|e| format!("clean {what}: {e}"))
        }
    }
}

pub fn install_rootfs(tarball: &Path, dest: &Path, expected_sha256: &str) -> Result<(), String> {
    let actual = crate::verify::sha256_hex(tarball)?;
    if actual != expected_sha256.to_lowercase() {
        return Err(format!(
            "sha256 mismatch: got {actual}, want {expected_sha256}"
        ));
    }
    let tmp = dest.with_extension("tmp");
    if tmp.exists() {
        remove_dir_all_force(&tmp, "tmp")?;
    }
    extract_archive(tarball, &tmp)?;
    if dest.exists() {
        remove_dir_all_force(dest, "dest")?;
    }
    std::fs::rename(&tmp, dest).map_err(|e| format!("rename: {e}"))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::verify::sha256_hex;
    use flate2::write::GzEncoder;
    use flate2::Compression;
    fn make_tar_gz(path: &Path, entries: &[(&str, &[u8], u32)]) {
        let f = std::fs::File::create(path).unwrap();
        let mut gz = GzEncoder::new(f, Compression::default());
        {
            let mut a = tar::Builder::new(&mut gz);
            for (name, data, mode) in entries {
                let mut h = tar::Header::new_gnu();
                h.set_size(data.len() as u64);
                h.set_mode(*mode);
                h.set_cksum();
                a.append_data(&mut h, name, data.as_ref()).unwrap();
            }
        }
        gz.finish().unwrap();
    }

    fn tmp_dir(tag: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("rm-test-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn mode_of(p: &Path) -> u32 {
        use std::os::unix::ffi::OsStrExt;
        let c = std::ffi::CString::new(p.as_os_str().as_bytes()).unwrap();
        unsafe {
            let mut st: libc::stat = std::mem::zeroed();
            assert_eq!(libc::stat(c.as_ptr(), &mut st), 0);
            st.st_mode
        }
    }

    #[test]
    fn extract_flat_gz() {
        let dir = tmp_dir("flat");
        let tgz = dir.join("root.tar.gz");
        make_tar_gz(&tgz, &[("usr/bin/hello", b"#!/bin/sh\necho hi\n", 0o755)]);
        let dest = dir.join("dest");
        extract_archive(&tgz, &dest).unwrap();
        assert!(dest.join("usr/bin/hello").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn install_verifies_hash_then_renames() {
        let dir = tmp_dir("install");
        let tgz = dir.join("root.tar.gz");
        make_tar_gz(&tgz, &[("etc/os-release", b"NAME=Test\n", 0o644)]);
        let good = sha256_hex(&tgz).unwrap();
        let dest = dir.join("rootfs/alpine");
        install_rootfs(&tgz, &dest, &good).unwrap();
        assert!(dest.join("etc/os-release").exists());
        assert!(!dest.with_extension("tmp").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn install_rejects_bad_hash() {
        let dir = tmp_dir("badhash");
        let tgz = dir.join("root.tar.gz");
        make_tar_gz(&tgz, &[("a", b"b", 0o644)]);
        let dest = dir.join("rootfs/alpine");
        let err = install_rootfs(&tgz, &dest, &"0".repeat(64)).unwrap_err();
        assert!(err.contains("sha256 mismatch"));
        assert!(!dest.exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn install_rejects_traversal_entry() {
        let dir = tmp_dir("traversal");
        let tgz = dir.join("root.tar.gz");
        // Build a raw tar entry with a ".." path (the tar builder refuses to
        // create such entries, so craft the header manually).
        let mut header = [0u8; 512];
        header[..8].copy_from_slice(b"../evil\0");
        header[100..108].copy_from_slice(b"0000755\0");
        header[124..136].copy_from_slice(b"00000000001\0");
        header[156] = b'0';
        let checksum: u32 = header.iter().map(|&b| b as u32).sum();
        let cs = format!("{:06o}\0 ", checksum);
        header[148..156].copy_from_slice(cs.as_bytes());
        let mut tar_bytes = header.to_vec();
        tar_bytes.extend_from_slice(b"x");
        tar_bytes.resize((tar_bytes.len() + 511) & !511, 0);
        tar_bytes.extend_from_slice(&[0u8; 1024]);
        let f = std::fs::File::create(&tgz).unwrap();
        let mut gz = GzEncoder::new(f, Compression::default());
        std::io::Write::write_all(&mut gz, &tar_bytes).unwrap();
        gz.finish().unwrap();

        let dest = dir.join("dest");
        let err = extract_archive(&tgz, &dest).unwrap_err();
        assert!(
            err.contains("extract"),
            "expected extract error, got: {err}"
        );
        assert!(!dir.join("evil").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn extract_skips_symlinks_and_does_not_escape_tree() {
        // Debian rootfs carries absolute symlinks (/lib -> usr/lib,
        // /usr/lib/ssl -> /etc/ssl). strip_write_bits must never follow
        // them: a follow-based walk reaches host/Android system dirs
        // outside the rootfs (root-owned 0700 /etc/ssl/private -> EACCES),
        // which failed extraction on device (rc=-1).
        let dir = tmp_dir("symlink");
        let tgz = dir.join("root.tar.gz");
        let f = std::fs::File::create(&tgz).unwrap();
        let mut gz = GzEncoder::new(f, Compression::default());
        {
            let mut a = tar::Builder::new(&mut gz);
            let mut h = tar::Header::new_gnu();
            h.set_entry_type(tar::EntryType::Directory);
            h.set_mode(0o700);
            h.set_size(0);
            h.set_cksum();
            a.append_data(&mut h, "etc/ssl/private", &[][..]).unwrap();
            let mut h = tar::Header::new_gnu();
            h.set_entry_type(tar::EntryType::Symlink);
            h.set_mode(0o777);
            h.set_size(0);
            h.set_link_name("/etc/ssl").unwrap();
            h.set_cksum();
            a.append_data(&mut h, "usr/lib/ssl", &[][..]).unwrap();
            let mut h = tar::Header::new_gnu();
            h.set_entry_type(tar::EntryType::Directory);
            h.set_mode(0o755);
            h.set_size(0);
            h.set_cksum();
            a.append_data(&mut h, "usr/lib", &[][..]).unwrap();
        }
        gz.finish().unwrap();

        let dest = dir.join("dest");
        extract_archive(&tgz, &dest).unwrap();
        assert!(dest.join("etc/ssl/private").is_dir());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn executable_files_lose_write_bits() {
        let dir = tmp_dir("wxe");
        let tgz = dir.join("root.tar.gz");
        make_tar_gz(
            &tgz,
            &[
                ("usr/bin/tool", b"#!/bin/sh\necho t\n", 0o755),
                ("usr/bin/plain", b"data\n", 0o644),
            ],
        );
        let dest = dir.join("dest");
        extract_archive(&tgz, &dest).unwrap();
        assert_eq!(
            mode_of(&dest.join("usr/bin/tool")) & 0o222,
            0,
            "executable must be non-writable (W^X)"
        );
        assert_eq!(
            mode_of(&dest.join("usr/bin/tool")) & 0o111,
            0o111,
            "exec bit must survive"
        );
        assert_ne!(
            mode_of(&dest.join("usr/bin/plain")) & 0o222,
            0,
            "plain file keeps write bits"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn shared_libs_lose_write_bits_even_when_not_executable() {
        let dir = tmp_dir("wxl");
        let tgz = dir.join("root.tar.gz");
        make_tar_gz(
            &tgz,
            &[
                ("usr/lib/libc++.so", b"ELF-LIKE", 0o600),
                ("usr/lib/pty.node", b"ELF-LIKE", 0o755),
                ("usr/lib/libfoo.a", b"AR", 0o644),
            ],
        );
        let dest = dir.join("dest");
        extract_archive(&tgz, &dest).unwrap();
        assert_eq!(
            mode_of(&dest.join("usr/lib/libc++.so")) & 0o222,
            0,
            ".so must be non-writable"
        );
        assert_eq!(
            mode_of(&dest.join("usr/lib/pty.node")) & 0o222,
            0,
            ".node must be non-writable"
        );
        assert_ne!(
            mode_of(&dest.join("usr/lib/libfoo.a")) & 0o222,
            0,
            "non-dlopen lib keeps write bits"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn reinstall_over_existing_tree_succeeds() {
        let dir = tmp_dir("reinstall");
        let tgz = dir.join("root.tar.gz");
        make_tar_gz(
            &tgz,
            &[
                ("usr/bin/tool", b"#!/bin/sh\necho t\n", 0o755),
                ("etc/os-release", b"NAME=Test\n", 0o644),
            ],
        );
        let good = sha256_hex(&tgz).unwrap();
        let dest = dir.join("rootfs/debian");
        install_rootfs(&tgz, &dest, &good).unwrap();
        // Second install over the W^X-stripped tree must succeed (idempotent
        // retry — a failed first run never leaves a permanent EACCES).
        install_rootfs(&tgz, &dest, &good).unwrap();
        assert_eq!(mode_of(&dest.join("usr/bin/tool")) & 0o222, 0);
        assert!(dest.join("etc/os-release").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
