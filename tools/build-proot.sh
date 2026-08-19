#!/usr/bin/env bash
set -euo pipefail
# Builds the proot binary for Android (Termux fork v5.1.107.90, the proven
# Android path) using a static libtalloc cross-compiled with the NDK.
#
# Usage: ./tools/build-proot.sh <target-triple>   (aarch64-linux-android | x86_64-linux-android)
# Requires: ANDROID_NDK_HOME (NDK r26+), python3 (for talloc's waf build)
TARGET="$1"
API=26
case "$TARGET" in
  aarch64-linux-android) ARCH=aarch64 ;;
  x86_64-linux-android)  ARCH=x86_64 ;;
  *) echo "unsupported target: $TARGET"; exit 1 ;;
esac

NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$NDK_BIN/${TARGET}${API}-clang"
AR="$NDK_BIN/llvm-ar"
STRIP="$NDK_BIN/llvm-strip"
OBJCOPY="$NDK_BIN/llvm-objcopy"
OBJDUMP="$NDK_BIN/llvm-objdump"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/talloc-lib"

# ---- 1. talloc 2.4.3 (GPL-3.0) static archive ----
curl -sL --retry 3 --retry-delay 2 --retry-all-errors \
  -o "$WORK/talloc.tar.gz" https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
tar -xzf "$WORK/talloc.tar.gz" -C "$WORK"
cd "$WORK/talloc-2.4.3"
cat > cross-answers.txt <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF
CC="$CC" ./configure --prefix="$WORK/prefix" \
  --disable-rpath \
  --disable-python \
  --cross-compile \
  --cross-answers=cross-answers.txt >/dev/null
make -j"$(nproc)" >/dev/null
(cd bin/default && "$AR" rcu libtalloc.a talloc*.o)
cp bin/default/libtalloc.a "$WORK/talloc-lib/libtalloc.a"

# ---- 2. proot (termux fork, GPL-2.0) ----
git clone --depth 1 --branch v5.1.107.90 https://github.com/termux/proot.git "$WORK/proot" >/dev/null 2>&1
cd "$WORK/proot/src"
make CC="$CC" LD="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
  CFLAGS="-O2 -g -Wall -Wextra -Wno-implicit-function-declaration" \
  LDFLAGS="-L$WORK/talloc-lib -ltalloc -Wl,-z,noexecstack" \
  CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\"5.1.107.90\" -I. -I$WORK/talloc-2.4.3" \
  proot

OUT_DIR="${PROOT_OUT_DIR:-$GITHUB_WORKSPACE/app/src/main/assets/proot}"
mkdir -p "$OUT_DIR"
cp ./proot "$OUT_DIR/proot-$ARCH"
echo "built $OUT_DIR/proot-$ARCH"
