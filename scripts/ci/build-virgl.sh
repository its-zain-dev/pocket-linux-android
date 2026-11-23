#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

LIBEPOXY_VERSION="${LIBEPOXY_VERSION:-1.5.10}"
LIBEPOXY_URL="https://github.com/anholt/libepoxy/archive/refs/tags/${LIBEPOXY_VERSION}.tar.gz"

VIRGLRENDERER_VERSION="${VIRGLRENDERER_VERSION:-1.0.1}"
VIRGLRENDERER_URL="https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/${VIRGLRENDERER_VERSION}/virglrenderer-${VIRGLRENDERER_VERSION}.tar.gz"

ANDROID_API="${ANDROID_API:-26}"
SKIP_EXISTING=false
REQUESTED_ABIS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-existing) SKIP_EXISTING=true; shift ;;
        --abi) REQUESTED_ABIS+=("$2"); shift 2 ;;
        *) echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

if [[ ${#REQUESTED_ABIS[@]} -eq 0 ]]; then
    REQUESTED_ABIS=("arm64-v8a" "x86_64")
fi

need_build=false
for abi in "${REQUESTED_ABIS[@]}"; do
    out_dir="$REPO_ROOT/app/src/main/jniLibs/$abi"
    if [[ "$SKIP_EXISTING" == true && -s "$out_dir/libvirgl_test_server.so" ]]; then
        echo "[$abi] Existing libvirgl_test_server.so found; skipping."
    else
        need_build=true
    fi
done
[[ "$need_build" == false ]] && exit 0

for tool in meson ninja pkg-config python3 curl tar readelf; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: Required build tool '$tool' not found." >&2
        exit 1
    fi
done

NDK_HOME="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK_HOME" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK_HOME="$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -n1)"
fi
if [[ -z "$NDK_HOME" || ! -d "$NDK_HOME" ]]; then
    echo "ERROR: Android NDK not found." >&2
    exit 1
fi
NDK_BIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"

WORK_DIR="${RUNNER_TEMP:-$REPO_ROOT/tmp}/virgl-build-$$"
trap 'rm -rf "$WORK_DIR"' EXIT
mkdir -p "$WORK_DIR/downloads"

LIBEPOXY_TAR="$WORK_DIR/downloads/libepoxy-${LIBEPOXY_VERSION}.tar.gz"
VIRGL_TAR="$WORK_DIR/downloads/virglrenderer-${VIRGLRENDERER_VERSION}.tar.gz"
curl -fsSL --retry 3 --retry-delay 2 "$LIBEPOXY_URL" -o "$LIBEPOXY_TAR"
curl -fsSL --retry 3 --retry-delay 2 "$VIRGLRENDERER_URL" -o "$VIRGL_TAR"

build_for_abi() {
    local abi="$1"
    local target_triple="" cpu_family="" expected_machine=""
    case "$abi" in
        arm64-v8a) target_triple="aarch64-linux-android${ANDROID_API}"; cpu_family="aarch64"; expected_machine="AArch64" ;;
        x86_64)    target_triple="x86_64-linux-android${ANDROID_API}";  cpu_family="x86_64";  expected_machine="Advanced Micro Devices X86-64" ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
    esac

    local out_dir="$REPO_ROOT/app/src/main/jniLibs/$abi"
    local out_bin="$out_dir/libvirgl_test_server.so"
    [[ "$SKIP_EXISTING" == true && -s "$out_bin" ]] && return 0

    local abi_work="$WORK_DIR/$abi"
    local prefix="$abi_work/prefix"
    local cross_file="$abi_work/android-${abi}.cross"
    mkdir -p "$abi_work" "$prefix" "$out_dir"

    cat <<CROSS_EOF > "$cross_file"
[binaries]
c = '${NDK_BIN}/${target_triple}-clang'
cpp = '${NDK_BIN}/${target_triple}-clang++'
ar = '${NDK_BIN}/llvm-ar'
strip = '${NDK_BIN}/llvm-strip'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-fPIC', '-O2', '-DANDROID', '-D__ANDROID_API__=${ANDROID_API}', '-I${prefix}/include']
c_link_args = ['-L${prefix}/lib', '-Wl,--gc-sections']
cpp_args = ['-fPIC', '-O2', '-DANDROID', '-D__ANDROID_API__=${ANDROID_API}', '-I${prefix}/include']
cpp_link_args = ['-L${prefix}/lib', '-Wl,--gc-sections']

[properties]
pkg_config_libdir = '${prefix}/lib/pkgconfig'
needs_exe_wrapper = true

[host_machine]
system = 'android'
cpu_family = '${cpu_family}'
cpu = '${cpu_family}'
endian = 'little'
CROSS_EOF

    tar -xzf "$LIBEPOXY_TAR" -C "$abi_work"
    PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" \
    meson setup "$abi_work/build-epoxy" "$abi_work/libepoxy-${LIBEPOXY_VERSION}" \
        --cross-file "$cross_file" --prefix "$prefix" --libdir lib --buildtype release \
        -Ddefault_library=static -Degl=yes -Dglx=no -Dx11=false -Dtests=false -Ddocs=false
    ninja -C "$abi_work/build-epoxy" -j"$(nproc)" install

    tar -xzf "$VIRGL_TAR" -C "$abi_work"
    PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" \
    meson setup "$abi_work/build-virgl" "$abi_work/virglrenderer-${VIRGLRENDERER_VERSION}" \
        --cross-file "$cross_file" --prefix "$prefix" --libdir lib --buildtype release \
        -Ddefault_library=static -Dplatforms=egl -Dminigbm_allocation=false -Dvenus=false -Dtests=false -Drender-server=false
    ninja -C "$abi_work/build-virgl" -j"$(nproc)" vtest/virgl_test_server

    cp "$abi_work/build-virgl/vtest/virgl_test_server" "$out_bin"
    "$NDK_BIN/llvm-strip" "$out_bin"
    chmod 0755 "$out_bin"
    readelf -h "$out_bin" | grep -q "$expected_machine"
    echo "[$abi] Successfully built $out_bin"
}

for abi in "${REQUESTED_ABIS[@]}"; do
    build_for_abi "$abi"
done
