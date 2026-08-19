use std::path::PathBuf;

pub struct ProotSpec {
    pub rootfs: PathBuf,
    pub shell: String,
    pub extra_env: Vec<(String, String)>,
}

pub fn build_proot_args(spec: &ProotSpec) -> Vec<String> {
    let mut args = vec![
        "-0".to_string(),
        "-r".to_string(),
        spec.rootfs.to_string_lossy().to_string(),
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
    for (k, v) in &spec.extra_env {
        args.push(format!("{k}={v}"));
    }
    args.push(spec.shell.clone());
    args.push("-l".to_string());
    args
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spec() -> ProotSpec {
        ProotSpec {
            rootfs: PathBuf::from("/data/files/rootfs/alpine"),
            shell: "/bin/sh".to_string(),
            extra_env: vec![("LANG".to_string(), "C.UTF-8".to_string())],
        }
    }

    #[test]
    fn builds_proot_flags_in_order() {
        let args = build_proot_args(&spec());
        assert_eq!(&args[0..2], &["-0", "-r"]);
        assert_eq!(args[2], "/data/files/rootfs/alpine");
        assert_eq!(&args[3..5], &["-b", "/dev"]);
        assert_eq!(&args[5..7], &["-b", "/proc"]);
        assert_eq!(&args[7..9], &["-b", "/sys"]);
        assert_eq!(&args[9..11], &["-w", "/root"]);
        assert!(args.contains(&"--kill-on-exit".to_string()));
    }

    #[test]
    fn env_i_clean_environment() {
        let args = build_proot_args(&spec());
        let i_pos = args.iter().position(|a| a == "-i").unwrap();
        assert_eq!(args[i_pos + 1], "HOME=/root");
        assert!(args
            .contains(&"PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin".to_string()));
        assert!(args.contains(&"TERM=xterm-256color".to_string()));
    }

    #[test]
    fn extra_env_before_shell() {
        let args = build_proot_args(&spec());
        let lang_pos = args.iter().position(|a| a == "LANG=C.UTF-8").unwrap();
        let shell_pos = args.iter().position(|a| a == "/bin/sh").unwrap();
        let dashl_pos = args.iter().position(|a| a == "-l").unwrap();
        assert!(lang_pos < shell_pos && shell_pos < dashl_pos);
    }
}
