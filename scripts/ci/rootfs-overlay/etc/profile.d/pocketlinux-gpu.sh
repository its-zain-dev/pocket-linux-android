# PocketLinux GPU, hardware topology & desktop performance environment profile
# Sourced by /etc/profile and /usr/local/bin/start-desktop.sh (POSIX sh compatible)

if [ -r /etc/pocketlinux-hardware.env ]; then
    # shellcheck disable=SC1091
    . /etc/pocketlinux-hardware.env
fi

export GDK_BACKEND="${GDK_BACKEND:-x11}"
export GDK_RENDERING="${GDK_RENDERING:-image}"
export NO_AT_BRIDGE=1
export GTK_A11Y=none
export QT_ACCESSIBILITY=0
export GIO_USE_VFS="${GIO_USE_VFS:-local}"
export GSETTINGS_BACKEND="${GSETTINGS_BACKEND:-memory}"
export MESA_SHADER_CACHE_DIR="${MESA_SHADER_CACHE_DIR:-/tmp/mesa_shader_cache}"
export MESA_GLSL_CACHE_MAX_SIZE="${MESA_GLSL_CACHE_MAX_SIZE:-128M}"

# Resolve live VirGL UNIX socket from directory bind-mount (/tmp/.virgl_ipc/.virgl_test) or /tmp/.virgl_test
if [ -S /tmp/.virgl_ipc/.virgl_test ]; then
    export VTEST_SOCKET_NAME="/tmp/.virgl_ipc/.virgl_test"
else
    export VTEST_SOCKET_NAME="${VTEST_SOCKET_NAME:-/tmp/.virgl_test}"
fi

if [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ] && [ -r /tmp/.pocketlinux_dbus_address ]; then
    DBUS_SESSION_BUS_ADDRESS="$(cat /tmp/.pocketlinux_dbus_address 2>/dev/null || true)"
    if [ -n "$DBUS_SESSION_BUS_ADDRESS" ]; then
        export DBUS_SESSION_BUS_ADDRESS
    fi
fi

_pocketlinux_gpu_env="/root/.config/pocketlinux/gpu.env"

if [ -r "$_pocketlinux_gpu_env" ]; then
    # shellcheck disable=SC1090
    . "$_pocketlinux_gpu_env"
    export GDK_BACKEND="${GDK_BACKEND:-x11}"
    if [ -S /tmp/.virgl_ipc/.virgl_test ]; then
        export VTEST_SOCKET_NAME="/tmp/.virgl_ipc/.virgl_test"
    fi
else
    if [ -S "$VTEST_SOCKET_NAME" ]; then
        export GALLIUM_DRIVER="virpipe"
        export MESA_NO_ERROR="1"
        export MESA_GL_VERSION_OVERRIDE="${MESA_GL_VERSION_OVERRIDE:-4.0}"
        export MESA_GLSL_VERSION_OVERRIDE="${MESA_GLSL_VERSION_OVERRIDE:-400}"
    else
        export GALLIUM_DRIVER="llvmpipe"
        export MESA_NO_ERROR="1"
        _pl_threads="${PL_RECOMMENDED_THREADS:-}"
        if [ -z "$_pl_threads" ]; then
            _pl_threads="$(nproc 2>/dev/null || echo 4)"
            case "$_pl_threads" in
                ''|*[!0-9]*) _pl_threads=4 ;;
            esac
            if [ "$_pl_threads" -gt 6 ]; then
                _pl_threads=6
            fi
        fi
        export LP_NUM_THREADS="$_pl_threads"
        unset _pl_threads
    fi
fi

# Enable Mesa asynchronous Gallium command-stream thread
export GALLIUM_THREAD="${GALLIUM_THREAD:-true}"

# CRITICAL: Under Xvnc (no physical DRM render node), Mesa GLX loads BOTH 'virpipe' (real
# Android hardware GPU via virgl_test_server) AND 'llvmpipe' via the drisw loader, which
# requires LIBGL_ALWAYS_SOFTWARE=1.
export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"

unset _pocketlinux_gpu_env
