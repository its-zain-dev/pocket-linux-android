#!/bin/sh
set -eu

SMOKE_ONLY=0
if [ "${1:-}" = "--smoke" ]; then
    SMOKE_ONLY=1
fi

PASS=0
FAIL=0

ok() {
    PASS=$((PASS + 1))
    printf "[OK]   %s\n" "$1"
}

warn() {
    printf "[WARN] %s\n" "$1"
}

fail() {
    FAIL=$((FAIL + 1))
    printf "[FAIL] %s\n" "$1"
}

ALPINE_VER="$(cat /etc/alpine-release 2>/dev/null || echo unknown)"
ARCH="$(uname -m 2>/dev/null || echo unknown)"

echo "=========================================="
echo "  PocketLinux Full System & ELF Check"
echo "=========================================="
echo "OS: Alpine Linux ${ALPINE_VER} (${ARCH})"
echo "Kernel: $(uname -sr 2>/dev/null || echo unknown)"
echo ""

# 1. Core tools
for tool in /bin/busybox /bin/bash /usr/bin/python3 /sbin/apk /usr/bin/file /usr/bin/nano; do
    if [ -x "$tool" ]; then
        ok "Core binary present: $tool"
    else
        fail "Missing core binary: $tool"
    fi
done

# 2. ELF execution & gcompat glibc compatibility
if /bin/busybox true >/dev/null 2>&1; then
    ELF_INFO="$(file -L /bin/busybox 2>/dev/null || echo 'ELF binary')"
    ok "Native ELF execution OK ($ELF_INFO)"
else
    fail "Native ELF execution failed for /bin/busybox"
fi

if [ -e /lib/libgcompat.so.0 ] || [ -e /usr/lib/libgcompat.so.0 ]; then
    ok "glibc ELF compatibility layer OK (gcompat present)"
else
    fail "gcompat missing (/lib/libgcompat.so.0)"
fi

if python3 -c 'import ctypes; print("ok")' >/dev/null 2>&1; then
    ok "Python 3 + ctypes dynamic ELF loading OK"
else
    fail "Python 3 ctypes dynamic loading failed"
fi

# 3. /proc readability
if [ -r /proc/meminfo ] && [ -r /proc/uptime ]; then
    ok "/proc filesystem readable"
else
    warn "/proc partially restricted by Android SELinux"
fi

# 4. Home directories & File Manager setup
for d in /root/Desktop /root/Documents /root/Downloads /root/Projects; do
    if [ -d "$d" ]; then
        ok "Directory exists: $d"
    else
        fail "Missing directory: $d"
    fi
done

if [ -s /root/.config/gtk-3.0/bookmarks ] && [ -s /root/.config/gtk-3.0/settings.ini ]; then
    ok "GTK-3 dark theme & PCManFM bookmarks configured"
else
    fail "GTK-3 config or bookmarks missing"
fi

# 5. Storage writability (skip strict write test in chroot --smoke mode)
if [ "$SMOKE_ONLY" -eq 0 ]; then
    if [ -d /mnt/sdcard ] && touch /mnt/sdcard/.pocketlinux_health_probe 2>/dev/null; then
        rm -f /mnt/sdcard/.pocketlinux_health_probe
        ok "Shared storage writable (/mnt/sdcard)"
    else
        warn "Shared storage /mnt/sdcard not writable"
    fi

    if [ -d /mnt/phone ] && ls /mnt/phone >/dev/null 2>&1; then
        ok "Phone storage accessible (/mnt/phone)"
    else
        warn "Phone storage /mnt/phone not mounted (use /mnt/sdcard)"
    fi

    if DISPLAY="${DISPLAY:-:1}" xdpyinfo >/dev/null 2>&1; then
        ok "X11 Display ${DISPLAY:-:1} active"
    else
        warn "X11 Display ${DISPLAY:-:1} not currently running"
    fi
fi

echo ""
echo "Summary: PASS=${PASS}, FAIL=${FAIL}"
if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
echo "STATUS: ELF OK | ALPINE ${ALPINE_VER} OK"
exit 0
