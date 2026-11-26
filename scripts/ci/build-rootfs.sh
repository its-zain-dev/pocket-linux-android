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
    # PocketStore, Download Manager, Interactive Desktop & Settings (GTK-3 Python + Cairo) + VirGL/Mesa GPU
    py3-gobject3
    py3-cairo
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

# Generate 4 crisp built-in dark-gradient PNG wallpapers once via Python stdlib (zero external deps)
WALLPAPER_DIR="$WORK_DIR/wallpapers"
mkdir -p "$WALLPAPER_DIR"
python3 - "$WALLPAPER_DIR" <<'PYEOF'
import math
import os
import struct
import sys
import zlib

out_dir = sys.argv[1]
W, H = 1280, 720


def png_chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


THEMES = [
    # filename, base_top, base_bot, glow1_rgb, glow2_rgb, grid
    ("pocketlinux-wallpaper.png", (11, 17, 32), (30, 41, 59), (14, 165, 233), (99, 102, 241), True),
    ("wallpaper-aurora.png", (10, 12, 30), (24, 18, 52), (139, 92, 246), (20, 184, 166), False),
    ("wallpaper-emerald.png", (8, 22, 24), (15, 42, 40), (16, 185, 129), (14, 165, 233), False),
    ("wallpaper-sunset.png", (20, 12, 24), (42, 20, 30), (245, 158, 11), (239, 68, 68), False),
]

cx1, cy1 = W * 0.72, H * 0.28
inv_r1 = 1.0 / (520.0 * 520.0)
cx2, cy2 = W * 0.20, H * 0.82
inv_r2 = 1.0 / (480.0 * 480.0)

for fname, bt, bb_c, g1_c, g2_c, has_grid in THEMES:
    rows = bytearray()
    for y in range(H):
        rows.append(0)
        ty = y / (H - 1)
        dy1 = (y - cy1) * (y - cy1)
        dy2 = (y - cy2) * (y - cy2)
        grid_y = has_grid and (y % 64 == 0)

        br = bt[0] + (bb_c[0] - bt[0]) * ty
        bg = bt[1] + (bb_c[1] - bt[1]) * ty
        bb = bt[2] + (bb_c[2] - bt[2]) * ty

        row = bytearray(W * 3)
        idx = 0
        for x in range(W):
            d1 = (x - cx1) * (x - cx1) + dy1
            g1 = math.exp(-d1 * inv_r1) if d1 < 900000 else 0.0
            d2 = (x - cx2) * (x - cx2) + dy2
            g2 = math.exp(-d2 * inv_r2) if d2 < 800000 else 0.0

            r = br + g1_c[0] * g1 * 0.34 + g2_c[0] * g2 * 0.26
            g = bg + g1_c[1] * g1 * 0.30 + g2_c[1] * g2 * 0.22
            b = bb + g1_c[2] * g1 * 0.34 + g2_c[2] * g2 * 0.28

            if grid_y or (has_grid and x % 64 == 0):
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
    with open(os.path.join(out_dir, fname), "wb") as f:
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
        "$rootfs/usr/share/applications" \
        "$rootfs/etc/xdg/menus" \
        "$rootfs/root/Desktop" \
        "$rootfs/root/Applications" \
        "$rootfs/root/Documents" \
        "$rootfs/root/Downloads" \
        "$rootfs/root/Projects" \
        "$rootfs/root/.config/gtk-3.0" \
        "$rootfs/root/.config/libfm" \
        "$rootfs/root/.config/pcmanfm/default" \
        "$rootfs/usr/local/bin" \
        "$rootfs/usr/local/sbin" \
        "$rootfs/usr/local/lib" \
        "$rootfs/etc/profile.d" \
        "$rootfs/root/.config/pocketlinux"

    sudo cp "$WALLPAPER_DIR"/*.png "$rootfs/usr/share/backgrounds/"
    sudo chmod 0644 "$rootfs/usr/share/backgrounds/"*.png

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
gtk-double-click-distance=16
gtk-double-click-time=500
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.config/gtk-3.0/bookmarks" >/dev/null
file:///root Home
file:///root/Desktop Desktop
file:///root/Applications Applications
file:///root/Documents Documents
file:///root/Downloads Downloads
file:///root/Projects Projects
file:///mnt/sdcard Shared Storage
file:///mnt/phone Phone Storage
file:/// Root Filesystem
EOF

    cat <<'EOF' | sudo tee "$rootfs/etc/xdg/menus/lxde-applications.menu" >/dev/null
<!DOCTYPE Menu PUBLIC "-//freedesktop//DTD Menu 1.0//EN"
 "http://www.freedesktop.org/standards/menu-spec/1.0/menu.dtd">
<Menu>
  <Name>Applications</Name>
  <DefaultAppDirs/>
  <DefaultDirectoryDirs/>
  <DefaultMergeDirs/>
  <Menu>
    <Name>Internet</Name>
    <Include><Category>Network</Category><Category>WebBrowser</Category></Include>
  </Menu>
  <Menu>
    <Name>Development</Name>
    <Include><Category>Development</Category></Include>
  </Menu>
  <Menu>
    <Name>Graphics</Name>
    <Include><Category>Graphics</Category></Include>
  </Menu>
  <Menu>
    <Name>Office</Name>
    <Include><Category>Office</Category></Include>
  </Menu>
  <Menu>
    <Name>Multimedia</Name>
    <Include><Category>AudioVideo</Category><Category>Audio</Category><Category>Video</Category></Include>
  </Menu>
  <Menu>
    <Name>Games</Name>
    <Include><Category>Game</Category></Include>
  </Menu>
  <Menu>
    <Name>Accessories</Name>
    <Include><Category>Utility</Category></Include>
  </Menu>
  <Menu>
    <Name>System</Name>
    <Include><Category>System</Category><Category>Settings</Category></Include>
  </Menu>
  <Menu>
    <Name>Other</Name>
    <OnlyUnallocated/>
    <Include><All/></Include>
  </Menu>
</Menu>
EOF
    sudo ln -sfn lxde-applications.menu "$rootfs/etc/xdg/menus/applications.menu"
    sudo chmod 0644 "$rootfs/etc/xdg/menus/lxde-applications.menu"

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
XTerm*loginShell: true
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.bashrc" >/dev/null
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
[ -r /etc/pocketlinux-hardware.env ] && . /etc/pocketlinux-hardware.env
[ -r /etc/profile.d/pocketlinux-gpu.sh ] && . /etc/profile.d/pocketlinux-gpu.sh
EOF


    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/store.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=App Store
Comment=Install Linux Apps with Parallel Background Downloads
Exec=/usr/local/bin/pocketlinux-store.py
Icon=system-software-install
Terminal=false
Categories=System;PackageManager;
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/downloads.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=Downloads
Comment=Parallel Background Download Manager & Live Logs
Exec=/usr/local/bin/pocketlinux-downloads.py
Icon=folder-download
Terminal=false
Categories=Network;System;
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Desktop/settings.desktop" >/dev/null
[Desktop Entry]
Type=Application
Name=Settings
Comment=Windows-Style Personalization, GPU, Network & Privacy
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

    # Also expose core PocketLinux apps in /usr/share/applications for PCManFM Applications view
    sudo cp "$rootfs/root/Desktop/store.desktop" "$rootfs/usr/share/applications/pocketlinux-store.desktop"
    sudo cp "$rootfs/root/Desktop/downloads.desktop" "$rootfs/usr/share/applications/pocketlinux-downloads.desktop"
    sudo cp "$rootfs/root/Desktop/settings.desktop" "$rootfs/usr/share/applications/pocketlinux-settings.desktop"

    # Default JWM dynamic include fragments & Desktop preferences
    cat <<'EOF' | sudo tee "$rootfs/root/.jwmrc-apps" >/dev/null
<?xml version="1.0"?>
<JWM>
    <Program label="Install Apps via PocketStore..." icon="system-software-install">pocketlinux-store.py</Program>
</JWM>
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.jwmrc-theme" >/dev/null
<?xml version="1.0"?>
<JWM>
    <Tray x="0" y="-1" height="42" autohide="off" halign="fixed" valign="bottom">
        <TrayButton label=" Apps " icon="start-here">root:1</TrayButton>
        <Spacer width="4"/>
        <TrayButton label=" Store " icon="system-software-install">exec:pocketlinux-store.py</TrayButton>
        <TrayButton label=" DL " icon="folder-download">exec:pocketlinux-downloads.py</TrayButton>
        <TrayButton label=" Settings " icon="preferences-system">exec:pocketlinux-settings.py</TrayButton>
        <TrayButton label=" Term " icon="utilities-terminal">exec:xterm -fa "DejaVu Sans Mono" -fs 12 -bg "#0F172A" -fg "#E2E8F0"</TrayButton>
        <TrayButton label=" Files " icon="system-file-manager">exec:pcmanfm /root</TrayButton>
        <Spacer width="6"/>
        <Pager labeled="true"/>
        <TaskList maxwidth="220"/>
        <Dock/>
        <Clock format="%H:%M"/>
    </Tray>
    <WindowStyle>
        <Font>DejaVu Sans-10:bold</Font>
        <Width>4</Width>
        <Height>28</Height>
        <Corner>6</Corner>
        <Foreground>#CBD5E1</Foreground>
        <Background>#1E293B</Background>
        <Outline>#334155</Outline>
        <Opacity>1.0</Opacity>
        <Active>
            <Foreground>#FFFFFF</Foreground>
            <Background>#0EA5E9</Background>
            <Outline>#38BDF8</Outline>
            <Opacity>1.0</Opacity>
        </Active>
    </WindowStyle>
    <TrayStyle group="true" list="all">
        <Font>DejaVu Sans-10:bold</Font>
        <Background>#0F172A</Background>
        <Foreground>#F1F5F9</Foreground>
        <Outline>#1E293B</Outline>
        <Active>
            <Foreground>#FFFFFF</Foreground>
            <Background>#0284C7</Background>
        </Active>
    </TrayStyle>
    <TaskListStyle>
        <Font>DejaVu Sans-10</Font>
        <Active>
            <Foreground>#FFFFFF</Foreground>
            <Background>#0EA5E9</Background>
        </Active>
        <Foreground>#CBD5E1</Foreground>
        <Background>#1E293B</Background>
    </TaskListStyle>
    <PagerStyle>
        <Outline>#334155</Outline>
        <Foreground>#38BDF8</Foreground>
        <Background>#0F172A</Background>
        <Text>#E2E8F0</Text>
        <Active>
            <Foreground>#0EA5E9</Foreground>
            <Background>#1E293B</Background>
        </Active>
    </PagerStyle>
    <MenuStyle>
        <Font>DejaVu Sans-11</Font>
        <Foreground>#F1F5F9</Foreground>
        <Background>#0F172A</Background>
        <Outline>#334155</Outline>
        <Active>
            <Foreground>#FFFFFF</Foreground>
            <Background>#0EA5E9</Background>
        </Active>
    </MenuStyle>
    <ClockStyle>
        <Font>DejaVu Sans-11:bold</Font>
        <Foreground>#38BDF8</Foreground>
        <Background>#0F172A</Background>
    </ClockStyle>
    <Desktops width="2" height="1">
        <Background type="solid">#0F172A</Background>
    </Desktops>
    <DoubleClickSpeed>500</DoubleClickSpeed>
    <DoubleClickDelta>16</DoubleClickDelta>
    <SnapMode distance="12">border</SnapMode>
    <MoveMode>opaque</MoveMode>
    <ResizeMode>outline</ResizeMode>
</JWM>
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/.config/pocketlinux/desktop.json" >/dev/null
{
  "show_icons": true,
  "icon_size": 48,
  "single_click": true,
  "snap_grid": true,
  "auto_arrange": false,
  "wallpaper": "/usr/share/backgrounds/pocketlinux-wallpaper.png",
  "accent_color": "#0EA5E9",
  "accent_dark": "#0284C7",
  "accent_light": "#38BDF8",
  "taskbar_position": "bottom",
  "taskbar_height": 42,
  "taskbar_autohide": false,
  "clock_format": "%H:%M",
  "workspaces": 2,
  "move_mode": "opaque",
  "resize_mode": "outline",
  "snap_distance": 12,
  "double_click_delta": 16,
  "dpi_scale": 96,
  "auto_desktop_shortcuts": true
}
EOF

    cat <<'EOF' | sudo tee "$rootfs/root/Documents/Welcome-to-PocketLinux.txt" >/dev/null
============================================================
                 Welcome to PocketLinux 2.1!
============================================================

You are running a full Alpine Linux desktop inside Android.

Quick Folder Guide
------------------
- /root/Desktop         Movable Desktop app shortcuts (drag anywhere!)
- /root/Applications    All installed Linux applications
- /root/Documents       Notes and guides (including this file)
- /root/Downloads       Downloaded files (from Download Manager)
- /root/Projects        Sample Python & ELF test scripts
- /root/SharedStorage   Linked to Android app shared storage (/mnt/sdcard)
- /root/PhoneStorage    Linked to phone storage (/mnt/phone; hideable via Privacy Mode)

New in PocketLinux 2.1
----------------------
- Standalone Download Manager with parallel multi-stream acceleration & live logs
- Background installs that continue even after closing PocketStore
- Movable Windows-style Desktop icons with auto-placement for installed apps
- Windows-11-inspired Settings with live themes, wallpapers, taskbar & Privacy Mode
- Android Native Network Bridge so Firefox, Chromium & Luakit resolve DNS cleanly
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
    sudo cp "$OVERLAY_DIR/usr/local/bin/pocketlinux-downloads.py" "$rootfs/usr/local/bin/pocketlinux-downloads.py"
    sudo cp "$OVERLAY_DIR/usr/local/bin/pocketlinux-pkgd.py" "$rootfs/usr/local/bin/pocketlinux-pkgd.py"
    sudo cp "$OVERLAY_DIR/usr/local/bin/pocketlinux-desktop.py" "$rootfs/usr/local/bin/pocketlinux-desktop.py"
    sudo cp "$OVERLAY_DIR/usr/local/bin/pocketlinux-sync-apps.py" "$rootfs/usr/local/bin/pocketlinux-sync-apps.py"
    sudo cp "$OVERLAY_DIR/usr/local/bin/pocketlinux-settings.py" "$rootfs/usr/local/bin/pocketlinux-settings.py"
    sudo cp "$OVERLAY_DIR/usr/local/sbin/apk" "$rootfs/usr/local/sbin/apk"
    sudo cp "$OVERLAY_DIR/etc/profile.d/pocketlinux-gpu.sh" "$rootfs/etc/profile.d/pocketlinux-gpu.sh"

    sudo chown -R 0:0 "$rootfs/root" "$rootfs/usr/local" "$rootfs/usr/share/backgrounds"
    sudo chmod 0644 \
        "$rootfs/root/.jwmrc" \
        "$rootfs/root/.jwmrc-apps" \
        "$rootfs/root/.jwmrc-theme" \
        "$rootfs/root/.bashrc" \
        "$rootfs/etc/profile.d/pocketlinux-gpu.sh" \
        "$rootfs/root/.Xresources" \
        "$rootfs/root/.config/user-dirs.dirs" \
        "$rootfs/root/.config/gtk-3.0/settings.ini" \
        "$rootfs/root/.config/gtk-3.0/bookmarks" \
        "$rootfs/root/.config/libfm/libfm.conf" \
        "$rootfs/root/.config/pcmanfm/default/pcmanfm.conf" \
        "$rootfs/root/.config/pcmanfm/default/desktop-items-0.conf" \
        "$rootfs/root/.config/pocketlinux/desktop.json" \
        "$rootfs/usr/share/applications/pocketlinux-store.desktop" \
        "$rootfs/usr/share/applications/pocketlinux-downloads.desktop" \
        "$rootfs/usr/share/applications/pocketlinux-settings.desktop" \
        "$rootfs/root/Documents/Welcome-to-PocketLinux.txt"
    sudo chmod 0755 \
        "$rootfs/root/Desktop/store.desktop" \
        "$rootfs/root/Desktop/downloads.desktop" \
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
        "$rootfs/usr/local/bin/pocketlinux-downloads.py" \
        "$rootfs/usr/local/bin/pocketlinux-pkgd.py" \
        "$rootfs/usr/local/bin/pocketlinux-desktop.py" \
        "$rootfs/usr/local/bin/pocketlinux-sync-apps.py" \
        "$rootfs/usr/local/bin/pocketlinux-settings.py" \
        "$rootfs/usr/local/bin/fix-dns.sh" \
        "$rootfs/usr/local/sbin/apk"
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

    printf '%s/main\n%s/community\n' "$ALPINE_MIRROR" "$ALPINE_MIRROR" | sudo tee "$rootfs/etc/apk/repositories" >/dev/null
    sudo chmod 0644 "$rootfs/etc/apk/repositories"
    sudo cp /etc/resolv.conf "$rootfs/etc/resolv.conf" 2>/dev/null || true

    sudo mount -t proc proc "$rootfs/proc"
    sudo mount --bind /sys "$rootfs/sys"
    sudo mount --bind /dev "$rootfs/dev"

    sudo "$APK_STATIC" \
        --root "$rootfs" \
        --arch "$alpine_arch" \
        --no-cache \
        add "${PACKAGES[@]}"

    install_desktop_and_home "$rootfs"

    sudo chroot "$rootfs" /bin/sh -c '
        set -eu
        /sbin/apk add --no-cache gcc musl-dev
        printf "int flock(int fd, int op) { (void)fd; (void)op; return 0; }\n" \
            | gcc -shared -fPIC -O2 -s -xc - -o /usr/local/lib/libnoflock.so
        chmod 0755 /usr/local/lib/libnoflock.so
        /sbin/apk del --no-cache gcc musl-dev

        /sbin/apk info -q -L mesa-demos 2>/dev/null | while IFS= read -r rel; do
            case "$rel" in
                usr/bin/glxgears|usr/bin/glxinfo|usr/bin/eglinfo|usr/bin/es2_info|usr/bin/es2gears_x11|usr/bin/gears) ;;
                usr/bin/*) rm -f "/$rel" ;;
            esac
        done

        fc-cache -f || true
        gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true
        gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
        gdk-pixbuf-query-loaders --update-cache 2>/dev/null || true
        glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true
        update-desktop-database /usr/share/applications 2>/dev/null || true
        update-mime-database /usr/share/mime 2>/dev/null || true
        python3 -m py_compile \
            /usr/local/bin/pocketlinux-store.py \
            /usr/local/bin/pocketlinux-downloads.py \
            /usr/local/bin/pocketlinux-pkgd.py \
            /usr/local/bin/pocketlinux-desktop.py \
            /usr/local/bin/pocketlinux-sync-apps.py \
            /usr/local/bin/pocketlinux-settings.py
        python3 -c "import cairo, gi; gi.require_version(\"Gtk\", \"3.0\"); gi.require_version(\"PangoCairo\", \"1.0\"); from gi.repository import Gtk, Gdk, GdkPixbuf, Pango, PangoCairo"
        HOME=/root /usr/local/bin/pocketlinux-sync-apps.py
        HOME=/root jwm -p
    '

    unmount_rootfs "$rootfs"

    printf 'nameserver 8.8.8.8\nnameserver 1.1.1.1\n' | sudo tee "$rootfs/etc/resolv.conf" >/dev/null
    sudo chmod 0644 "$rootfs/etc/resolv.conf"

    sudo rm -f "$rootfs/usr/bin/qemu-aarch64-static" "$rootfs/usr/bin/qemu-aarch64" "$rootfs/lib/apk/db/lock"
    sudo rm -rf "$rootfs/var/cache/apk/"*
    sudo mkdir -p "$rootfs/tmp/.X11-unix" "$rootfs/dev/shm" "$rootfs/run/shm"
    sudo chmod 1777 "$rootfs/tmp" "$rootfs/tmp/.X11-unix" "$rootfs/dev/shm" "$rootfs/run/shm"

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
