# PocketLinux GPU & rendering environment profile
# Sourced by /etc/profile and /usr/local/bin/start-desktop.sh (POSIX sh compatible)

export GDK_BACKEND="${GDK_BACKEND:-x11}"
export VTEST_SOCKET_NAME="${VTEST_SOCKET_NAME:-/tmp/.virgl_test}"

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
        export LP_NUM_THREADS="4"
    fi
fi

# Xvnc has no real GPU: use Mesa's software GLX path unless VirGL is active
if [ "${GALLIUM_DRIVER:-}" != "virpipe" ]; then
    export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"
fi

unset _pocketlinux_gpu_env
