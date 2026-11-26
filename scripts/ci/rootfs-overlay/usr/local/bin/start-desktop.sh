#!/bin/sh
set -eu

export DISPLAY="${DISPLAY:-:1}"
export HOME="${HOME:-/root}"
export USER="${USER:-root}"
export XDG_CONFIG_HOME="${XDG_CONFIG_HOME:-/root/.config}"
export XDG_DATA_HOME="${XDG_DATA_HOME:-/root/.local/share}"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-root}"
export NO_AT_BRIDGE=1
export GTK_A11Y=none
export QT_ACCESSIBILITY=0
export GIO_USE_VFS=local
export GSETTINGS_BACKEND=memory
export GDK_RENDERING=image
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# Ensure runtime & temp directories exist with safe permissions
mkdir -p "$XDG_RUNTIME_DIR" /tmp/.X11-unix /tmp/mesa_shader_cache /run/shm /root/Desktop /root/Applications /root/Downloads /root/.config/pocketlinux
chmod 0700 "$XDG_RUNTIME_DIR" 2>/dev/null || true
chmod 1777 /tmp /tmp/.X11-unix 2>/dev/null || true

# Ensure Alpine DNS & Android Native Network Bridge resolve all domains cleanly
if [ -x /usr/local/bin/fix-dns.sh ]; then
    /usr/local/bin/fix-dns.sh >/dev/null 2>&1 &
fi

GEOM="${GEOM:-1024x600}"
RFB_PORT="${RFB_PORT:-5901}"
READY_FILE="/tmp/.pocketlinux_desktop_ready"
DBUS_ADDR_FILE="/tmp/.pocketlinux_dbus_address"
WALLPAPER="/usr/share/backgrounds/pocketlinux-wallpaper.png"

rm -f "$READY_FILE" "${READY_FILE}.tmp" "$DBUS_ADDR_FILE" /tmp/.X1-lock /tmp/.X11-unix/X1 /lib/apk/db/lock 2>/dev/null || true
if [ -d /dev/shm ]; then
    chmod 1777 /dev/shm 2>/dev/null || true
    ln -sfn /dev/shm /run/shm 2>/dev/null || true
fi

# Bridge directory-mounted VirGL hardware GPU socket (/tmp/.virgl_ipc/.virgl_test -> /tmp/.virgl_test)
if [ -d /tmp/.virgl_ipc ]; then
    v=0
    while [ "$v" -lt 25 ] && [ ! -S /tmp/.virgl_ipc/.virgl_test ]; do
        v=$((v + 1))
        sleep 0.04
    done
    if [ -S /tmp/.virgl_ipc/.virgl_test ]; then
        ln -sfn /tmp/.virgl_ipc/.virgl_test /tmp/.virgl_test 2>/dev/null || true
    fi
fi

# Ensure default JWM dynamic include fragments exist before JWM starts
if [ ! -f /root/.jwmrc-apps ]; then
    cat > /root/.jwmrc-apps <<'EOF'
<?xml version="1.0"?>
<JWM>
    <Program label="Install Apps via PocketStore..." icon="system-software-install">pocketlinux-store.py</Program>
</JWM>
EOF
fi

if [ ! -f /root/.jwmrc-theme ]; then
    cat > /root/.jwmrc-theme <<'EOF'
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
fi

# Load GPU acceleration / hardware profile before starting Xvnc/jwm/desktop
if [ -r /etc/profile.d/pocketlinux-gpu.sh ]; then
    # shellcheck disable=SC1091
    . /etc/profile.d/pocketlinux-gpu.sh
fi

XVNC_PID=""
JWM_PID=""
DESKTOP_PID=""
DBUS_PID=""
PKGD_PID=""

cleanup() {
    rm -f "$READY_FILE" "${READY_FILE}.tmp" "$DBUS_ADDR_FILE"
    for p in "$DESKTOP_PID" "$JWM_PID" "$PKGD_PID" "$DBUS_PID" "$XVNC_PID"; do
        if [ -n "$p" ]; then
            kill "$p" 2>/dev/null || true
        fi
    done
}
trap cleanup TERM INT EXIT

# Loopback-tuned TigerVNC 1.13.1: zero compression/comparison CPU overhead, RichCursor client cursor, GLX enabled
Xvnc "$DISPLAY" \
    -geometry "$GEOM" \
    -depth 24 \
    -rfbport "$RFB_PORT" \
    -localhost \
    -SecurityTypes None \
    +extension GLX \
    -ac \
    -AlwaysShared \
    -ZlibLevel 0 \
    -CompareFB 0 \
    -ImprovedHextile 0 \
    -dpi 96 \
    -FrameRate 60 >/tmp/xvnc.log 2>&1 &
XVNC_PID=$!

# Boost Xvnc scheduling priority when permitted
renice -n -4 -p "$XVNC_PID" >/dev/null 2>&1 || true

# Wait for X11 UNIX socket first
X_NUM="${DISPLAY#:}"
X_NUM="${X_NUM%%.*}"
X_SOCK="/tmp/.X11-unix/X${X_NUM}"
i=0
while [ "$i" -lt 200 ]; do
    if ! kill -0 "$XVNC_PID" 2>/dev/null; then
        echo "Xvnc exited prematurely" >&2
        cat /tmp/xvnc.log >&2 || true
        exit 1
    fi
    if [ -S "$X_SOCK" ] && DISPLAY="$DISPLAY" xdpyinfo >/dev/null 2>&1; then
        break
    fi
    i=$((i + 1))
    sleep 0.05
done

if ! DISPLAY="$DISPLAY" xdpyinfo >/dev/null 2>&1; then
    echo "Timed out waiting for Xvnc on $DISPLAY" >&2
    cat /tmp/xvnc.log >&2 || true
    exit 1
fi

if command -v dbus-launch >/dev/null 2>&1; then
    eval "$(dbus-launch --sh-syntax 2>/dev/null)" || true
    DBUS_PID="${DBUS_SESSION_BUS_PID:-}"
    if [ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then
        printf '%s\n' "$DBUS_SESSION_BUS_ADDRESS" > "$DBUS_ADDR_FILE" 2>/dev/null || true
    fi
fi

if [ -f "$HOME/.Xresources" ] && command -v xrdb >/dev/null 2>&1; then
    DISPLAY="$DISPLAY" xrdb -merge "$HOME/.Xresources" 2>/dev/null || true
fi

if [ -f "$WALLPAPER" ] && command -v feh >/dev/null 2>&1; then
    DISPLAY="$DISPLAY" feh --no-fehbg --bg-scale "$WALLPAPER" 2>/dev/null || true
else
    DISPLAY="$DISPLAY" xsetroot -solid "#0F172A" -cursor_name left_ptr 2>/dev/null || true
fi

# Start persistent background Download & Package Manager daemon
if [ -x /usr/local/bin/pocketlinux-pkgd.py ]; then
    /usr/local/bin/pocketlinux-pkgd.py >/tmp/pkgd.log 2>&1 &
    PKGD_PID=$!
fi

DISPLAY="$DISPLAY" jwm >/tmp/jwm.log 2>&1 &
JWM_PID=$!
renice -n -2 -p "$JWM_PID" >/dev/null 2>&1 || true

# Wait briefly for JWM to initialize before launching the below-layer desktop canvas
j=0
while [ "$j" -lt 40 ]; do
    if DISPLAY="$DISPLAY" xwininfo -root >/dev/null 2>&1 && kill -0 "$JWM_PID" 2>/dev/null; then
        sleep 0.25
        break
    fi
    j=$((j + 1))
    sleep 0.05
done

# Launch Windows-style interactive movable Desktop icon canvas (or fallback to pcmanfm --desktop)
if [ -x /usr/local/bin/pocketlinux-desktop.py ]; then
    DISPLAY="$DISPLAY" /usr/local/bin/pocketlinux-desktop.py >/tmp/pocketlinux-desktop.log 2>&1 &
    DESKTOP_PID=$!
elif command -v pcmanfm >/dev/null 2>&1; then
    DISPLAY="$DISPLAY" pcmanfm --desktop >/tmp/pcmanfm-desktop.log 2>&1 &
    DESKTOP_PID=$!
fi

# Synchronize installed Linux apps to Desktop, Applications folder, JWM menu & Android tab
if [ -x /usr/local/bin/pocketlinux-sync-apps.py ]; then
    DISPLAY="$DISPLAY" /usr/local/bin/pocketlinux-sync-apps.py >/tmp/sync-apps.log 2>&1 &
fi

printf 'ready %s %s\n' "$DISPLAY" "$RFB_PORT" > "${READY_FILE}.tmp"
mv "${READY_FILE}.tmp" "$READY_FILE"

wait "$XVNC_PID"
