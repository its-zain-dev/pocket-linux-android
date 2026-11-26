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
    # ELF / glibc compatibility & Linux power tools
    # gcompat provides libc6-compat + glibc dynamic loader on Alpine v3.20
    gcompat
    patchelf
    file
    nano
    htop
    # Desktop MIME, launcher & typography polish
    xdg-utils
    desktop-file-utils
    shared-mime-info
    font-noto
    # PocketStore & PocketLinux Settings (GTK-3 Python) + VirGL/Mesa GPU
    py3-gobject3
    mesa-egl
    mesa-gles
    # GLX software rasterizer (fixes "couldn't get an RGB, Double-buffered visual")
    mesa-gl
    mesa-dri-gallium
    mesa-demos
    gtk-update-icon-cache
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
QEMU_AARCH64_BIN=""
if [[ " ${REQUESTED_ABIS[*]} " == *" arm64-v8a "* ]]; then
    if [[ -x /usr/bin/qemu-aarch64-static ]]; then
        QEMU_AARCH64_BIN="/usr/bin/qemu-aarch64-static"
    elif [[ -x /usr/bin/qemu-aarch64 ]]; then
        QEMU_AARCH64_BIN="/usr/bin/qemu-aarch64"
    else
        echo "ERROR: Neither /usr/bin/qemu-aarch64-static nor /usr/bin/qemu-aarch64 found." >&2
        exit 1
    fi
    if command -v update-binfmts >/dev/null 2>&1; then
        sudo update-binfmts --enable qemu-aarch64 >/dev/null 2>&1 || true
    fi
fi

# Generate crisp dark-gradient PNG wallpaper once via Python stdlib (zero external deps)
WALLPAPER_PNG="$WORK_DIR/pocketlinux-wallpaper.png"
python3 - "$WALLPAPER_PNG" <<'PYEOF'
import math
import struct
import sys
import zlib

out_path = sys.argv[1]
W, H = 1280, 720


def png_chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


rows = bytearray()
cx1, cy1 = W * 0.72, H * 0.28
inv_r1 = 1.0 / (520.0 * 520.0)
cx2, cy2 = W * 0.20, H * 0.82
inv_r2 = 1.0 / (480.0 * 480.0)

for y in range(H):
    rows.append(0)
    ty = y / (H - 1)
    dy1 = (y - cy1) * (y - cy1)
    dy2 = (y - cy2) * (y - cy2)
    grid_y = (y % 64 == 0)

    br = 11.0 + 19.0 * ty
    bg = 17.0 + 24.0 * ty
    bb = 32.0 + 27.0 * ty

    row = bytearray(W * 3)
    idx = 0
    for x in range(W):
        d1 = (x - cx1) * (x - cx1) + dy1
        g1 = math.exp(-d1 * inv_r1) if d1 < 900000 else 0.0
        d2 = (x - cx2) * (x - cx2) + dy2
        g2 = math.exp(-d2 * inv_r2) if d2 < 800000 else 0.0

        r = br + 14.0 * g1 * 0.35 + 60.0 * g2 * 0.22
        g = bg + 165.0 * g1 * 0.28 + 65.0 * g2 * 0.16
        b = bb + 233.0 * g1 * 0.34 + 190.0 * g2 * 0.26

        if grid_y or (x % 64 == 0):
            r += 5.0
            g += 8.0
            b += 12.0

        row[idx] = 255 if r > 255 else int(r)
        row[idx + 1] = 255 if g > 255 else int(g)
        row[idx + 2] = 255 if b > 255 else int(b)
        idx += 3
    rows.extend(row)

ihdr = struct.pack(">IIBBBBB", W, H, 8, 2, 0, 0, 0)
idat = zlib.compress(bytes(rows), level=6)
png_bytes = (
    b"\x89PNG\r\n\x1a\n"
    + png_chunk(b"IHDR", ihdr)
    + png_chunk(b"IDAT", idat)
    + png_chunk(b"IEND", b"")
)
with open(out_path, "wb") as f:
    f.write(png_bytes)
PYEOF

# Fetch host (x86_64) apk-tools-static from Alpine v3.20 main
APKINDEX_HOST="$WORK_DIR/downloads/APKINDEX-x86_64.tar.gz"
curl -fsSL --retry 3 --retry-delay 2 "${ALPINE_MIRROR}/main/x86_64/APKINDEX.tar.gz" -o "$APKINDEX_HOST"
APK_STATIC_PKG="$(tar -ixOzf "$APKINDEX_HOST" APKINDEX | awk -F: '/^P:apk-tools-static$/{f=1; next} f&&/^V:/{res="apk-tools-static-"$2".apk"; f=0} END{if(res) print res}')"
if [[ -z "$APK_STATIC_PKG" ]]; then
    echo "ERROR: Failed to resolve apk-tools-static version from APKINDEX" >&2
    exit 1
fi
echo "Resolved host apk-tools-static: $APK_STATIC_PKG"

curl -fsSL --retry 3 --retry-delay 2 "${ALPINE_MIRROR}/main/x86_64/${APK_STATIC_PKG}" -o "$WORK_DIR/downloads/${APK_STATIC_PKG}"
tar -ixzf "$WORK_DIR/downloads/${APK_STATIC_PKG}" -C "$WORK_DIR/apk-tools" sbin/apk.static
APK_STATIC="$WORK_DIR/apk-tools/sbin/apk.static"
chmod +x "$APK_STATIC"

install_desktop_and_home() {
    local rootfs="$1"

    sudo mkdir -p \
        "$rootfs/mnt/sdcard" \
        "$rootfs/mnt/phone" \
        "$rootfs/usr/share/backgrounds" \
        "$rootfs/root/Desktop" \
        "$rootfs/root/Documents" \
        "$rootfs/root/Downloads" \
        "$rootfs/root/Projects" \
        "$rootfs/root/.config/gtk-3.0" \
        "$rootfs/root/.config/libfm" \
        "$rootfs/root/.config/pcmanfm/default" \
        "$rootfs/usr/local/bin" \
        "$rootfs/etc/profile.d" \
        "$rootfs/root/.config/pocketlinux"

    sudo cp "$WALLPAPER_PNG" "$rootfs/usr/share/backgrounds/pocketlinux-wallpaper.png"
    sudo chmod 0644 "$rootfs/usr/share/backgrounds/pocketlinux-wallpaper.png"

    sudo ln -sfn /mnt/sdcard "$rootfs/root/SharedStorage"
    sudo ln -sfn /mnt/phone "$rootfs/root/PhoneStorage"

    cat <<'EOF' | sudo tee "$rootfs/root/.config/user-dirs.dirs" >/dev/null
XDG_DESKTOP_DIR="$HOME/Desktop"
XDG_DOCUMENTS_DIR="$HOME/Documents"
XDG_DOWNLOAD_DIR="$HOME/Downloads"
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.config/gtk-3.0/settings.ini" >/dev/null
[Settings]
gtk-application-prefer-dark-theme=1
gtk-theme-name=Adwaita
gtk-icon-theme-name=Adwaita
gtk-font-name=DejaVu Sans 10
gtk-cursor-theme-name=Adwaita
gtk-toolbar-style=GTK_TOOLBAR_ICONS
gtk-enable-animations=0
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.config/gtk-3.0/bookmarks" >/dev/null
file:///root Home
file:///root/Desktop Desktop
file:///root/Documents Documents
file:///root/Downloads Downloads
file:///root/Projects Projects
file:///mnt/sdcard Shared Storage
file:///mnt/phone Phone Storage
file:/// Root Filesystem
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.config/libfm/libfm.conf" >/dev/null
[config]
single_click=0
use_trash=0
confirm_del=1
confirm_trash=0
quick_exec=1
terminal=xterm -fa "DejaVu Sans Mono" -fs 12 -bg "#0F172A" -fg "#E2E8F0" -e %s
thumbnail_local=0
thumbnail_max=512

[ui]
big_icon_size=48
small_icon_size=24
pane_icon_size=24
thumbnail_size=96
show_thumbnail=0
shadow_hidden=1
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.config/pcmanfm/default/pcmanfm.conf" >/dev/null
[config]
bm_open_method=0

[volume]
mount_on_startup=0
mount_removable=0
autorun=0

[ui]
always_show_tabs=0
max_tab_chars=32
win_width=860
win_height=520
splitter_pos=190
media_in_new_tab=0
desktop_folder_new_win=0
change_tab_on_drop=1
close_on_unmount=1
focus_previous=0
side_pane_mode=places
view_mode=icon
show_hidden=0
sort=name;ascending;
toolbar=newtab;navigation;home;
show_statusbar=1
pathbar_mode_buttons=1
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.config/pcmanfm/default/desktop-items-0.conf" >/dev/null
[*]
wallpaper_mode=stretch
wallpaper_common=1
wallpaper=/usr/share/backgrounds/pocketlinux-wallpaper.png
desktop_bg=#0f172a
desktop_fg=#f8fafc
desktop_shadow=#020617
desktop_font=DejaVu Sans 10
show_wm_menu=1
sort=mtime;ascending;
show_documents=0
show_trash=0
show_mounts=0
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.Xresources" >/dev/null
XTerm*faceName: DejaVu Sans Mono
XTerm*faceSize: 12
XTerm*background: #0F172A
XTerm*foreground: #E2E8F0
XTerm*cursorColor: #38BDF8
XTerm*scrollBar: false
XTerm*saveLines: 4096
XTerm*selectToClipboard: true
EOF


    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/store.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=App Store
Comment=Install Linux Apps with Auto-Dependencies
Exec=/usr/local/bin/pocketlinux-store.py
Icon=system-software-install
Terminal=false
Categories=System;PackageManager;
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/settings.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=Settings & GPU
Comment=GPU Acceleration, Display & System Repair
Exec=/usr/local/bin/pocketlinux-settings.py
Icon=preferences-system
Terminal=false
Categories=Settings;System;
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/terminal.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=Terminal
Comment=Open PocketLinux Shell
Exec=xterm -fa "DejaVu Sans Mono" -fs 12 -bg "#0F172A" -fg "#E2E8F0"
Icon=utilities-terminal
Terminal=false
Categories=System;TerminalEmulator;
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/files.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=File Manager
Comment=Browse Linux & Phone Storage
Exec=pcmanfm /root
Icon=system-file-manager
Terminal=false
Categories=System;FileManager;
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/editor.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=Text Editor
Comment=Edit code and text files
Exec=mousepad
Icon=accessories-text-editor
Terminal=false
Categories=Utility;TextEditor;
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/calculator.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=Calculator
Comment=Scientific & Basic Calculator
Exec=galculator
Icon=accessories-calculator
Terminal=false
Categories=Utility;Calculator;
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/sysinfo.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=System Info
Comment=Run PocketLinux Health & ELF Self-Check
Exec=xterm -T "PocketLinux Health Check" -fa "DejaVu Sans Mono" -fs 11 -bg "#0F172A" -fg "#E2E8F0" -hold -e /usr/local/bin/linux-health.sh
Icon=utilities-system-monitor
Terminal=false
Categories=System;Monitor;
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Documents/Welcome-to-PocketLinux.txt" >/dev/null
============================================================
                 Welcome to PocketLinux!
============================================================

You are running a full Alpine Linux desktop inside Android.

Quick Folder Guide
------------------
- /root/Desktop         Desktop app shortcuts
- /root/Documents       Notes and guides (including this file)
- /root/Downloads       Downloaded files
- /root/Projects        Sample Python & ELF test scripts
- /root/SharedStorage   Linked to Android app shared storage (/mnt/sdcard)
- /root/PhoneStorage    Linked to phone storage (/mnt/phone)

Running Linux & ELF Binaries
----------------------------
PocketLinux includes gcompat (glibc compatibility layer), file,
and patchelf so both musl and standard glibc Linux ELF binaries
can be inspected and executed:

  linux-health.sh                 Run full system & ELF self-check
  run-elf.sh --info /bin/busybox  Inspect ELF headers & libraries
  run-elf.sh ./my-linux-binary    Execute a Linux ELF binary

Installing More Packages
------------------------
  apk update
  apk add git vim gcc musl-dev    # Install extra dev tools anytime
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Projects/hello.py" >/dev/null
#!/usr/bin/env python3
"""Quick PocketLinux Python 3 verification script."""

import os
import platform


def main() -> None:
    uname = os.uname()
    print("Hello from PocketLinux!")
    print(f"Python:   {platform.python_version()}")
    print(f"System:   {uname.sysname} {uname.release} ({uname.machine})")
    print(f"Workdir:  {os.getcwd()}")


if __name__ == "__main__":
    main()
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Projects/test-elf.sh" >/dev/null
#!/bin/sh
set -eu

echo "=== PocketLinux ELF & gcompat Quick Test ==="
echo "Architecture: $(uname -m)"
echo ""
echo "[1] Inspecting /bin/busybox:"
/usr/local/bin/run-elf.sh --info /bin/busybox
echo ""
echo "[2] Executing /bin/busybox via run-elf.sh:"
/usr/local/bin/run-elf.sh /bin/busybox uname -a
echo ""
echo "ELF execution test succeeded."
EOF

    sudo cp "$OVERLAY_DIR/root/.jwmrc" "$rootfs/root/.jwmrc"
    sudo cp "$OVERLAY_DIR/usr/local/bin/start-desktop.sh" "$rootfs/usr/local/bin/start-desktop.sh"
    sudo cp "$OVERLAY_DIR/usr/local/bin/sysinfo.sh" "$rootfs/usr/local/bin/sysinfo.sh"
    sudo cp "$OVERLAY_DIR/usr/local/bin/linux-health.sh" "$rootfs/usr/local/bin/linux-health.sh"
    sudo cp "$OVERLAY_DIR/usr/local/bin/run-elf.sh" "$rootfs/usr/local/bin/run-elf.sh"
    sudo cp "$OVERLAY_DIR/usr/local/bin/fix-dns.sh" "$rootfs/usr/local/bin/fix-dns.sh"
    sudo cp "$OVERLAY_DIR/usr/local/bin/pocketlinux-store.py" "$rootfs/usr/local/bin/pocketlinux-store.py"
    sudo cp "$OVERLAY_DIR/usr/local/bin/pocketlinux-settings.py" "$rootfs/usr/local/bin/pocketlinux-settings.py"
    sudo cp "$OVERLAY_DIR/etc/profile.d/pocketlinux-gpu.sh" "$rootfs/etc/profile.d/pocketlinux-gpu.sh"

    sudo chown -R 0:0 "$rootfs/root" "$rootfs/usr/local" "$rootfs/usr/share/backgrounds"
    sudo chmod 0644 \
        "$rootfs/root/.jwmrc" \
        "$rootfs/etc/profile.d/pocketlinux-gpu.sh" \
        "$rootfs/root/.Xresources" \
        "$rootfs/root/.config/user-dirs.dirs" \
        "$rootfs/root/.config/gtk-3.0/settings.ini" \
        "$rootfs/root/.config/gtk-3.0/bookmarks" \
        "$rootfs/root/.config/libfm/libfm.conf" \
        "$rootfs/root/.config/pcmanfm/default/pcmanfm.conf" \
        "$rootfs/root/.config/pcmanfm/default/desktop-items-0.conf" \
        "$rootfs/root/Documents/Welcome-to-PocketLinux.txt"
    sudo chmod 0755 \
        "$rootfs/root/Desktop/store.desktop" \
        "$rootfs/root/Desktop/settings.desktop" \
        "$rootfs/root/Desktop/terminal.desktop" \
        "$rootfs/root/Desktop/files.desktop" \
        "$rootfs/root/Desktop/editor.desktop" \
        "$rootfs/root/Desktop/calculator.desktop" \
        "$rootfs/root/Desktop/sysinfo.desktop" \
        "$rootfs/root/Projects/hello.py" \
        "$rootfs/root/Projects/test-elf.sh" \
        "$rootfs/usr/local/bin/start-desktop.sh" \
        "$rootfs/usr/local/bin/sysinfo.sh" \
        "$rootfs/usr/local/bin/linux-health.sh" \
        "$rootfs/usr/local/bin/run-elf.sh" \
        "$rootfs/usr/local/bin/pocketlinux-store.py" \
        "$rootfs/usr/local/bin/pocketlinux-settings.py" \
        "$rootfs/usr/local/bin/fix-dns.sh"
}

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
prefix = "alpine-minirootfs-3.20."
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
        if ! grep -qsE 'flags:.*F' /proc/sys/fs/binfmt_misc/qemu-aarch64 2>/dev/null; then
            sudo cp "$QEMU_AARCH64_BIN" "$rootfs${QEMU_AARCH64_BIN}"
            sudo chmod 0755 "$rootfs${QEMU_AARCH64_BIN}"
        fi
    fi

    sudo cp /etc/resolv.conf "$rootfs/etc/resolv.conf" 2>/dev/null || true

    sudo mount -t proc proc "$rootfs/proc"
    sudo mount --bind /sys "$rootfs/sys"
    sudo mount --bind /dev "$rootfs/dev"

    sudo "$APK_STATIC" \
        --root "$rootfs" \
        --arch "$alpine_arch" \
        --no-cache \
        add "${PACKAGES[@]}"

    sudo chroot "$rootfs" /bin/sh -c "fc-cache -f || true; update-desktop-database /usr/share/applications 2>/dev/null || true; update-mime-database /usr/share/mime 2>/dev/null || true"

    unmount_rootfs "$rootfs"

    printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' | sudo tee "$rootfs/etc/resolv.conf" >/dev/null
    sudo chmod 0644 "$rootfs/etc/resolv.conf"

    sudo rm -f "$rootfs/usr/bin/qemu-aarch64-static" "$rootfs/usr/bin/qemu-aarch64"
    sudo rm -rf "$rootfs/var/cache/apk/"*
    sudo mkdir -p "$rootfs/tmp/.X11-unix"
    sudo chmod 1777 "$rootfs/tmp" "$rootfs/tmp/.X11-unix"

    install_desktop_and_home "$rootfs"

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
