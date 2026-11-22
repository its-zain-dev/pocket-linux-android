#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OVERLAY_DIR="$SCRIPT_DIR/rootfs-overlay"

ALPINE_BRANCH="v3.20"
ALPINE_MIRROR="https://dl-cdn.alpinelinux.org/alpine/${ALPINE_BRANCH}"

PACKAGES=(
    busybox
    bash
    coreutils
    apk-tools
    procps-ng
    curl
    python3
    tigervnc
    jwm
    xterm
    xcalc
    xclock
    feh
    mousepad
    galculator
    pcmanfm
    adwaita-icon-theme
    xwininfo
    xdpyinfo
    xrandr
    xsetroot
    xrdb
    font-dejavu
    font-misc-misc
    font-cursor-misc
    dbus
    dbus-x11
    ca-certificates
)

SKIP_EXISTING=false
REQUESTED_ABIS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-existing)
            SKIP_EXISTING=true
            shift
            ;;
        --abi)
            REQUESTED_ABIS+=("$2")
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [[ ${#REQUESTED_ABIS[@]} -eq 0 ]]; then
    REQUESTED_ABIS=("arm64-v8a" "x86_64")
fi

WORK_DIR="${RUNNER_TEMP:-$REPO_ROOT/tmp}/rootfs-build-$$"
ACTIVE_ROOTFS=""

unmount_rootfs() {
    local target="$1"
    if [[ -n "$target" && -d "$target" ]]; then
        for mp in "$target/dev" "$target/sys" "$target/proc"; do
            if mountpoint -q "$mp" 2>/dev/null; then
                sudo umount -l "$mp" || true
            fi
        done
    fi
}

cleanup() {
    unmount_rootfs "$ACTIVE_ROOTFS"
    if [[ -n "$ACTIVE_ROOTFS" && -d "$ACTIVE_ROOTFS" ]]; then
        sudo rm -rf "$ACTIVE_ROOTFS" || true
    fi
    rm -rf "$WORK_DIR" || true
}
trap cleanup EXIT

mkdir -p "$WORK_DIR/downloads" "$WORK_DIR/apk-tools"

# Ensure binfmt_misc has qemu-aarch64 enabled if arm64-v8a is requested
if [[ " ${REQUESTED_ABIS[*]} " == *" arm64-v8a "* ]]; then
    if [[ ! -x /usr/bin/qemu-aarch64-static ]]; then
        echo "ERROR: /usr/bin/qemu-aarch64-static not found. Install qemu-user-static." >&2
        exit 1
    fi
    if command -v update-binfmts >/dev/null 2>&1; then
        sudo update-binfmts --enable qemu-aarch64 >/dev/null 2>&1 || true
    fi
fi

# Fetch host (x86_64) apk-tools-static from Alpine v3.20 main
APKINDEX_HOST="$WORK_DIR/downloads/APKINDEX-x86_64.tar.gz"
curl -fsSL --retry 3 --retry-delay 2 "${ALPINE_MIRROR}/main/x86_64/APKINDEX.tar.gz" -o "$APKINDEX_HOST"
APK_STATIC_PKG="$(tar -xOzf "$APKINDEX_HOST" APKINDEX | awk -F: '/^P:apk-tools-static$/{f=1} f&&/^V:/{print "apk-tools-static-"$2".apk"; exit}')"
if [[ -z "$APK_STATIC_PKG" ]]; then
    echo "ERROR: Failed to resolve apk-tools-static version from APKINDEX" >&2
    exit 1
fi
echo "Resolved host apk-tools-static: $APK_STATIC_PKG"

curl -fsSL --retry 3 --retry-delay 2 "${ALPINE_MIRROR}/main/x86_64/${APK_STATIC_PKG}" -o "$WORK_DIR/downloads/${APK_STATIC_PKG}"
tar -xzf "$WORK_DIR/downloads/${APK_STATIC_PKG}" -C "$WORK_DIR/apk-tools" sbin/apk.static
APK_STATIC="$WORK_DIR/apk-tools/sbin/apk.static"
chmod +x "$APK_STATIC"

build_rootfs_for_abi() {
    local abi="$1"
    local alpine_arch=""
    local flavor_dir=""

    case "$abi" in
        arm64-v8a)
            alpine_arch="aarch64"
            flavor_dir="arm64"
            ;;
        x86_64)
            alpine_arch="x86_64"
            flavor_dir="x86_64"
            ;;
        *)
            echo "Unsupported ABI: $abi" >&2
            exit 1
            ;;
    esac

    local out_dir="$REPO_ROOT/app/src/${flavor_dir}/assets"
    local out_tar="$out_dir/rootfs-${abi}.tar.gz"
    if [[ "$SKIP_EXISTING" == true && -s "$out_tar" ]]; then
        echo "[$abi] Existing $out_tar found; skipping."
        return 0
    fi

    mkdir -p "$out_dir"

    local releases_yaml="$WORK_DIR/downloads/latest-releases-${alpine_arch}.yaml"
    curl -fsSL --retry 3 --retry-delay 2 "${ALPINE_MIRROR}/releases/${alpine_arch}/latest-releases.yaml" -o "$releases_yaml"

    local minirootfs_info
    minirootfs_info="$(python3 - "$releases_yaml" "$alpine_arch" <<'PYEOF'
import sys
yaml_path, arch = sys.argv[1], sys.argv[2]
prefix = f"alpine-minirootfs-3.20."
suffix = f"-{arch}.tar.gz"
cur_file = None
cur_sha = None
with open(yaml_path, "r", encoding="utf-8") as f:
    for raw in f:
        line = raw.strip()
        if line.startswith("file:"):
            val = line.split(":", 1)[1].strip().strip("\"'")
            if val.startswith(prefix) and val.endswith(suffix):
                cur_file = val
                cur_sha = None
            elif cur_file and cur_sha:
                break
            else:
                cur_file = None
                cur_sha = None
        elif cur_file and line.startswith("sha256:"):
            cur_sha = line.split(":", 1)[1].strip().strip("\"'")
            print(f"{cur_file} {cur_sha}")
            sys.exit(0)
sys.exit(1)
PYEOF
)"
    local mini_file mini_sha
    read -r mini_file mini_sha <<< "$minirootfs_info"
    if [[ -z "$mini_file" || -z "$mini_sha" ]]; then
        echo "ERROR: Could not resolve alpine-minirootfs for $alpine_arch" >&2
        exit 1
    fi
    echo "[$abi] Resolved minirootfs: $mini_file ($mini_sha)"

    local mini_tar="$WORK_DIR/downloads/$mini_file"
    curl -fsSL --retry 3 --retry-delay 2 "${ALPINE_MIRROR}/releases/${alpine_arch}/${mini_file}" -o "$mini_tar"
    echo "${mini_sha}  ${mini_tar}" | sha256sum -c -

    local rootfs="$WORK_DIR/rootfs-${abi}"
    ACTIVE_ROOTFS="$rootfs"
    sudo rm -rf "$rootfs"
    mkdir -p "$rootfs"
    sudo tar -xzf "$mini_tar" -C "$rootfs"

    if [[ "$alpine_arch" == "aarch64" ]]; then
        sudo cp /usr/bin/qemu-aarch64-static "$rootfs/usr/bin/qemu-aarch64-static"
        sudo chmod 0755 "$rootfs/usr/bin/qemu-aarch64-static"
    fi

    # Provide temporary DNS for apk/chroot if needed
    sudo cp /etc/resolv.conf "$rootfs/etc/resolv.conf" 2>/dev/null || true

    sudo mount -t proc proc "$rootfs/proc"
    sudo mount --bind /sys "$rootfs/sys"
    sudo mount --bind /dev "$rootfs/dev"

    sudo "$APK_STATIC" \
        --root "$rootfs" \
        --arch "$alpine_arch" \
        --no-cache \
        add "${PACKAGES[@]}"

    sudo chroot "$rootfs" /bin/sh -c "fc-cache -f || true"

    unmount_rootfs "$rootfs"

    # Replace host resolv.conf with clean public DNS defaults
    printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' | sudo tee "$rootfs/etc/resolv.conf" >/dev/null
    sudo chmod 0644 "$rootfs/etc/resolv.conf"

    # Strip emulator helper and apk caches; ensure tmp permissions
    sudo rm -f "$rootfs/usr/bin/qemu-aarch64-static"
    sudo rm -rf "$rootfs/var/cache/apk/"*
    sudo mkdir -p "$rootfs/tmp/.X11-unix"
    sudo chmod 1777 "$rootfs/tmp" "$rootfs/tmp/.X11-unix"

    # Install PocketLinux rootfs overlay files
    sudo mkdir -p "$rootfs/root" "$rootfs/usr/local/bin"
    sudo cp "$OVERLAY_DIR/root/.jwmrc" "$rootfs/root/.jwmrc"
    sudo cp "$OVERLAY_DIR/usr/local/bin/start-desktop.sh" "$rootfs/usr/local/bin/start-desktop.sh"
    sudo cp "$OVERLAY_DIR/usr/local/bin/sysinfo.sh" "$rootfs/usr/local/bin/sysinfo.sh"
    sudo chown -R 0:0 "$rootfs/root" "$rootfs/usr/local"
    sudo chmod 0644 "$rootfs/root/.jwmrc"
    sudo chmod 0755 "$rootfs/usr/local/bin/start-desktop.sh" "$rootfs/usr/local/bin/sysinfo.sh"

    local tmp_out="${out_tar}.tmp"
    sudo tar --numeric-owner -czf "$tmp_out" -C "$rootfs" .
    sudo chown "$(id -u):$(id -g)" "$tmp_out"
    mv "$tmp_out" "$out_tar"

    sudo rm -rf "$rootfs"
    ACTIVE_ROOTFS=""

    echo "[$abi] Built rootfs archive: $out_tar ($(du -h "$out_tar" | cut -f1))"
}

for abi in "${REQUESTED_ABIS[@]}"; do
    build_rootfs_for_abi "$abi"
done
