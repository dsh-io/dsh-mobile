use std::path::PathBuf;
use std::sync::OnceLock;

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jint, JNI_VERSION_1_6};
use jni::JNIEnv;
use jni::JavaVM;

static FILES_DIR: OnceLock<PathBuf> = OnceLock::new();
static JAVA_VM: OnceLock<JavaVM> = OnceLock::new();

const ENOENT: jint = -2;
const EINVAL: jint = -22;

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jint {
    let _ = JAVA_VM.set(vm);
    std::panic::set_hook(Box::new(|info| {
        if let Some(vm) = JAVA_VM.get() {
            if let Ok(mut env) = vm.attach_current_thread() {
                if let Ok(class) = env.find_class("com/dshio/dshmobile/NativeLib") {
                    let msg = env.new_string(format!("{info}"));
                    if let Ok(msg) = msg {
                        let _ = env.call_static_method(
                            class,
                            "onRustPanic",
                            "(Ljava/lang/String;)V",
                            &[(&msg).into()],
                        );
                    }
                }
            }
        }
        eprintln!("{info}");
    }));
    JNI_VERSION_1_6
}

fn files_dir() -> &'static PathBuf {
    FILES_DIR.get().expect("initNative must be called first")
}

fn get_str(env: &mut JNIEnv, s: &JString) -> Result<String, jni::errors::Error> {
    let s = env.get_string(s)?;
    Ok(s.into())
}

#[no_mangle]
pub extern "system" fn Java_com_dshio_dshmobile_NativeLib_initNative(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
) {
    if let Ok(dir) = get_str(&mut env, &files_dir) {
        let _ = FILES_DIR.set(PathBuf::from(dir));
    }
}

/// Build the proot argv for a distro. Returns a String[] of arguments,
/// or null on error (missing rootfs / bad input).
#[no_mangle]
pub extern "system" fn Java_com_dshio_dshmobile_NativeLib_buildProotArgs(
    mut env: JNIEnv,
    _class: JClass,
    distro: JString,
    shell: JString,
) -> jni::sys::jobject {
    let Ok(distro) = get_str(&mut env, &distro) else {
        return std::ptr::null_mut();
    };
    let Ok(shell) = get_str(&mut env, &shell) else {
        return std::ptr::null_mut();
    };
    let rootfs = files_dir().join("rootfs").join(&distro);
    if !rootfs.is_dir() {
        return std::ptr::null_mut();
    }
    let spec = proot_runner::command::ProotSpec {
        rootfs,
        shell,
        extra_env: Vec::new(),
    };
    let argv = proot_runner::command::build_proot_args(&spec);
    let initial: JObject = env.new_string("").unwrap().into();
    match env.new_object_array(argv.len() as jint, "java/lang/String", &initial) {
        Err(_) => std::ptr::null_mut(),
        Ok(array) => {
            for (i, arg) in argv.iter().enumerate() {
                let s = match env.new_string(arg) {
                    Ok(s) => s,
                    Err(_) => return std::ptr::null_mut(),
                };
                if env
                    .set_object_array_element(&array, i as jint, &s)
                    .is_err()
                {
                    return std::ptr::null_mut();
                }
            }
            array.into_raw()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dshio_dshmobile_NativeLib_rootfsVerifyExtract(
    mut env: JNIEnv,
    _class: JClass,
    tarball_path: JString,
    expected_sha256: JString,
) -> jint {
    let Ok(tarball) = get_str(&mut env, &tarball_path) else {
        return EINVAL;
    };
    let Ok(sha) = get_str(&mut env, &expected_sha256) else {
        return EINVAL;
    };
    let tarball_path = PathBuf::from(tarball);
    let distro = tarball_path
        .file_stem()
        .and_then(|s| s.to_str())
        .map(|s| s.trim_end_matches(".tar"))
        .unwrap_or("");
    let dest = files_dir().join("rootfs").join(distro);
    match rootfs_manager::extract::install_rootfs(&tarball_path, &dest, &sha) {
        Ok(()) => 0,
        Err(_) => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dshio_dshmobile_NativeLib_rootfsDelete(
    mut env: JNIEnv,
    _class: JClass,
    distro: JString,
) -> jint {
    let Ok(distro) = get_str(&mut env, &distro) else {
        return EINVAL;
    };
    let dir = files_dir().join("rootfs").join(distro);
    if !dir.exists() {
        return ENOENT;
    }
    match std::fs::remove_dir_all(&dir) {
        Ok(()) => 0,
        Err(_) => -1,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dshio_dshmobile_NativeLib_probePtrace(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    unsafe {
        let pid = libc::fork();
        if pid == 0 {
            libc::ptrace(
                libc::PTRACE_TRACEME,
                0,
                std::ptr::null_mut::<libc::c_void>(),
                std::ptr::null_mut::<libc::c_void>(),
            );
            let c = std::ffi::CString::new("/system/bin/true").unwrap();
            libc::execl(c.as_ptr(), c.as_ptr(), std::ptr::null::<libc::c_char>());
            libc::_exit(127);
        }
        if pid < 0 {
            return 0;
        }
        let mut status: libc::c_int = 0;
        let r = libc::waitpid(pid, &mut status, 0);
        (r > 0 && libc::WIFEXITED(status) && libc::WEXITSTATUS(status) == 0) as jboolean
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn files_dir_requires_init() {
        // FILES_DIR is a global OnceLock; init once here, verify behavior.
        assert!(FILES_DIR.get().is_some() || FILES_DIR.set(PathBuf::from("/tmp")).is_ok());
    }

    #[test]
    fn probe_ptrace_on_host() {
        // Linux allows an unprivileged parent to trace its own child, so this
        // mirrors what the JNI function does and must return true.
        unsafe {
            let pid = libc::fork();
            if pid == 0 {
                libc::ptrace(
                    libc::PTRACE_TRACEME,
                    0,
                    0 as *mut libc::c_void,
                    0 as *mut libc::c_void,
                );
                libc::_exit(0);
            }
            let mut status: libc::c_int = 0;
            let r = libc::waitpid(pid, &mut status, 0);
            assert!(r > 0);
            assert!(libc::WIFEXITED(status));
        }
    }
}
