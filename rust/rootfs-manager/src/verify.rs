use std::path::Path;

use sha2::{Digest, Sha256};

pub fn sha256_hex(path: &Path) -> Result<String, String> {
    let file = std::fs::File::open(path).map_err(|e| format!("open: {e}"))?;
    let mut reader = std::io::BufReader::with_capacity(256 * 1024, file);
    let mut hasher = Sha256::new();
    std::io::copy(&mut reader, &mut hasher).map_err(|e| format!("read: {e}"))?;
    Ok(format!("{:x}", hasher.finalize()))
}
