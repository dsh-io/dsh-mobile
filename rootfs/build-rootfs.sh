#!/usr/bin/env bash
set -euo pipefail

# Builds a minimal Debian bookworm arm64 rootfs with Node 22 (glibc),
# pruned for embedding in the dsh-mobile APK. Run on an arm64 host (or
# under qemu). Must be run as root (debootstrap).
#
# Usage: sudo ./build-rootfs.sh [output-dir]   (default: ./out)

OUT="${1:-out}"
NODE_VER="$(cat "$(dirname "$0")/node-version.txt")"
ROOT="$(mktemp -d)/rootfs"

if [ "$(id -u)" != "0" ]; then
  echo "!! run as root (debootstrap requires it)" >&2
  exit 2
fi
if [ "$(uname -m)" != "aarch64" ] && [ "$(uname -m)" != "arm64" ]; then
  echo "!! must run on arm64 (or qemu-user under binfmt)" >&2
  exit 2
fi

echo "==> debootstrap bookworm arm64"
debootstrap --arch=arm64 --variant=minbase --include=ca-certificates \
  bookworm "${ROOT}" http://deb.debian.org/debian

echo "==> install Node ${NODE_VER} (glibc)"
curl -fsSL "https://nodejs.org/dist/${NODE_VER}/node-${NODE_VER}-linux-arm64.tar.xz" -o /tmp/node.tar.xz
tar xJf /tmp/node.tar.xz -C "${ROOT}/usr" --strip-components=1 --exclude="*/share/doc" \
  --exclude="*/share/man" --exclude="*/include"
rm -f /tmp/node.tar.xz

echo "==> prune"
rm -rf "${ROOT}/var/lib/apt/lists" "${ROOT}/var/cache/apt" \
       "${ROOT}/usr/share/doc" "${ROOT}/usr/share/man" "${ROOT}/usr/share/locale" \
       "${ROOT}/usr/share/info" "${ROOT}/usr/share/common-licenses" \
       "${ROOT}/root/.bashrc"
find "${ROOT}/usr/lib" -name '*.a' -delete
find "${ROOT}" -type f -name '*.pyc' -delete

echo "==> tar.xz"
mkdir -p "${OUT}"
tar cJf "${OUT}/debian.tar.xz" -C "${ROOT}" .
# bare 64-hex hash only (no filename): install_rootfs compares the raw string
sha256sum "${OUT}/debian.tar.xz" | cut -d' ' -f1 > "${OUT}/debian.tar.xz.sha256"
ls -la "${OUT}/debian.tar.xz"
echo "==> rootfs done"
