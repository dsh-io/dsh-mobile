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
    Ok(())
}

pub fn install_rootfs(tarball: &Path, dest: &Path, expected_sha256: &str) -> Result<(), String> {
    let actual = crate::verify::sha256_hex(tarball)?;
    if actual != expected_sha256.to_lowercase() {
        return Err(format!("sha256 mismatch: got {actual}, want {expected_sha256}"));
    }
    let tmp = dest.with_extension("tmp");
    if tmp.exists() {
        std::fs::remove_dir_all(&tmp).map_err(|e| format!("clean tmp: {e}"))?;
    }
    extract_archive(tarball, &tmp)?;
    if dest.exists() {
        std::fs::remove_dir_all(dest).map_err(|e| format!("clean dest: {e}"))?;
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

    fn make_tar_gz(path: &Path, entries: &[(&str, &[u8])]) {
        let f = std::fs::File::create(path).unwrap();
        let mut gz = GzEncoder::new(f, Compression::default());
        {
            let mut a = tar::Builder::new(&mut gz);
            for (name, data) in entries {
                let mut h = tar::Header::new_gnu();
                h.set_size(data.len() as u64);
                h.set_mode(0o755);
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

    #[test]
    fn extract_flat_gz() {
        let dir = tmp_dir("flat");
        let tgz = dir.join("root.tar.gz");
        make_tar_gz(&tgz, &[("usr/bin/hello", b"#!/bin/sh\necho hi\n")]);
        let dest = dir.join("dest");
        extract_archive(&tgz, &dest).unwrap();
        assert!(dest.join("usr/bin/hello").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn install_verifies_hash_then_renames() {
        let dir = tmp_dir("install");
        let tgz = dir.join("root.tar.gz");
        make_tar_gz(&tgz, &[("etc/os-release", b"NAME=Test\n")]);
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
        make_tar_gz(&tgz, &[("a", b"b")]);
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
        assert!(err.contains("extract"), "expected extract error, got: {err}");
        assert!(!dir.join("evil").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
