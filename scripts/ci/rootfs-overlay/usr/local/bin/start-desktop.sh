#!/bin/sh
set -eu

export DISPLAY="${DISPLAY:-:1}"
export HOME="${HOME:-/root}"
export USER="${USER:-root}"
export XDG_CONFIG_HOME="${XDG_CONFIG_HOME:-/root/.config}"
export XDG_DATA_HOME="${XDG_DATA_HOME:-/root/.local/share}"
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

# Ensure Alpine DNS resolves even if Android Private DNS / ISP blocks UDP 53
if [ -x /usr/local/bin/fix-dns.sh ]; then
    /usr/local/bin/fix-dns.sh >/dev/null 2>&1 &
fi

# Load GPU acceleration / software rendering profile before starting Xvnc/jwm/pcmanfm
if [ -r /etc/profile.d/pocketlinux-gpu.sh ]; then
    # shellcheck disable=SC1091
    . /etc/profile.d/pocketlinux-gpu.sh
fi

GEOM="${GEOM:-1024x600}"
RFB_PORT="${RFB_PORT:-5901}"
READY_FILE="/tmp/.pocketlinux_desktop_ready"
WALLPAPER="/usr/share/backgrounds/pocketlinux-wallpaper.png"

rm -f "$READY_FILE" "${READY_FILE}.tmp" /tmp/.X1-lock /tmp/.X11-unix/X1
chmod 1777 /tmp 2>/dev/null || true
mkdir -p /tmp/.X11-unix
chmod 1777 /tmp/.X11-unix 2>/dev/null || true

XVNC_PID=""
JWM_PID=""
PCMANFM_PID=""
DBUS_PID=""

cleanup() {
    rm -f "$READY_FILE" "${READY_FILE}.tmp"
    for p in "$PCMANFM_PID" "$JWM_PID" "$DBUS_PID" "$XVNC_PID"; do
        if [ -n "$p" ]; then
            kill "$p" 2>/dev/null || true
        fi
    done
}
trap cleanup TERM INT EXIT

Xvnc "$DISPLAY" \
    -geometry "$GEOM" \
    -depth 24 \
    -rfbport "$RFB_PORT" \
    -localhost \
    -SecurityTypes None \
    +extension GLX \
    -ac \
    -AlwaysShared \
    -ZlibLevel 1 \
    -FrameRate 60 >/tmp/xvnc.log 2>&1 &
XVNC_PID=$!

# Wait for Xvnc display to accept connections (up to 10s)
i=0
while [ "$i" -lt 100 ]; do
    if ! kill -0 "$XVNC_PID" 2>/dev/null; then
        echo "Xvnc exited prematurely" >&2
        cat /tmp/xvnc.log >&2 || true
        exit 1
    fi
    if DISPLAY="$DISPLAY" xdpyinfo >/dev/null 2>&1; then
        break
    fi
    i=$((i + 1))
    sleep 0.1
done

if ! DISPLAY="$DISPLAY" xdpyinfo >/dev/null 2>&1; then
    echo "Timed out waiting for Xvnc on $DISPLAY" >&2
    cat /tmp/xvnc.log >&2 || true
    exit 1
fi

if command -v dbus-launch >/dev/null 2>&1; then
    eval "$(dbus-launch --sh-syntax 2>/dev/null)" || true
    DBUS_PID="${DBUS_SESSION_BUS_PID:-}"
fi

if [ -f "$HOME/.Xresources" ] && command -v xrdb >/dev/null 2>&1; then
    DISPLAY="$DISPLAY" xrdb -merge "$HOME/.Xresources" 2>/dev/null || true
fi

if [ -f "$WALLPAPER" ] && command -v feh >/dev/null 2>&1; then
    DISPLAY="$DISPLAY" feh --no-fehbg --bg-scale "$WALLPAPER" 2>/dev/null || true
else
    DISPLAY="$DISPLAY" xsetroot -solid "#0F172A" -cursor_name left_ptr 2>/dev/null || true
fi

DISPLAY="$DISPLAY" jwm >/tmp/jwm.log 2>&1 &
JWM_PID=$!

if command -v pcmanfm >/dev/null 2>&1; then
    DISPLAY="$DISPLAY" pcmanfm --desktop >/tmp/pcmanfm-desktop.log 2>&1 &
    PCMANFM_PID=$!
fi

j=0
while [ "$j" -lt 50 ]; do
    if DISPLAY="$DISPLAY" xwininfo -root >/dev/null 2>&1; then
        break
    fi
    j=$((j + 1))
    sleep 0.1
done

printf 'ready %s %s\n' "$DISPLAY" "$RFB_PORT" > "${READY_FILE}.tmp"
mv "${READY_FILE}.tmp" "$READY_FILE"

wait "$XVNC_PID"
