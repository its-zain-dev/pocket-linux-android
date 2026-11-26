# PocketLinux GPU & desktop performance environment profile
# Sourced by /etc/profile and /usr/local/bin/start-desktop.sh (POSIX sh compatible)

export GDK_BACKEND="${GDK_BACKEND:-x11}"
export GDK_RENDERING="${GDK_RENDERING:-image}"
export NO_AT_BRIDGE=1
export GTK_A11Y=none
export QT_ACCESSIBILITY=0
export GIO_USE_VFS="${GIO_USE_VFS:-local}"
export GSETTINGS_BACKEND="${GSETTINGS_BACKEND:-memory}"
export MESA_SHADER_CACHE_DIR="${MESA_SHADER_CACHE_DIR:-/tmp/mesa_shader_cache}"
export VTEST_SOCKET_NAME="${VTEST_SOCKET_NAME:-/tmp/.virgl_test}"

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
    export VTEST_SOCKET_NAME="${VTEST_SOCKET_NAME:-/tmp/.virgl_test}"
else
    if [ -S "$VTEST_SOCKET_NAME" ]; then
        export GALLIUM_DRIVER="virpipe"
        export MESA_NO_ERROR="1"
    else
        export GALLIUM_DRIVER="llvmpipe"
        export MESA_NO_ERROR="1"
        _pl_cores="$(nproc 2>/dev/null || echo 4)"
        case "$_pl_cores" in
            ''|*[!0-9]*) _pl_cores=4 ;;
        esac
        if [ "$_pl_cores" -gt 8 ]; then
            _pl_cores=8
        fi
        export LP_NUM_THREADS="$_pl_cores"
        unset _pl_cores
    fi
fi

# Enable Mesa asynchronous Gallium command-stream thread for smoother GL rendering
export GALLIUM_THREAD="${GALLIUM_THREAD:-true}"

# Xvnc has no physical DRM GPU: use Mesa's software GLX path unless VirGL is active
if [ "${GALLIUM_DRIVER:-}" != "virpipe" ]; then
    export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
fi

unset _pocketlinux_gpu_env
