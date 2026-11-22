#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

TALLOC_VERSION="2.5.0"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"

PROOT_VERSION="5.1.107.102"
PROOT_URL="https://github.com/termux/proot/archive/v${PROOT_VERSION}.zip"
PROOT_SHA256="30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852"

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

# Resolve Android NDK: prefer ANDROID_NDK_LATEST_HOME, fallback to ANDROID_NDK_HOME or newest under ANDROID_HOME/ndk
NDK_HOME="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK_HOME" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
    NDK_HOME="$(ls -d "$ANDROID_HOME/ndk/"* 2>/dev/null | sort -V | tail -n1)"
fi
if [[ -z "$NDK_HOME" || ! -d "$NDK_HOME" ]]; then
    echo "ERROR: Android NDK not found. Set ANDROID_NDK_LATEST_HOME or ANDROID_NDK_HOME." >&2
    exit 1
fi

NDK_BIN="$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -d "$NDK_BIN" ]]; then
    echo "ERROR: NDK LLVM bin directory not found: $NDK_BIN" >&2
    exit 1
fi
echo "Using Android NDK: $NDK_HOME"

WORK_DIR="${RUNNER_TEMP:-$REPO_ROOT/tmp}/proot-build-$$"
cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT
mkdir -p "$WORK_DIR/downloads"

TALLOC_TAR="$WORK_DIR/downloads/talloc-${TALLOC_VERSION}.tar.gz"
PROOT_ZIP="$WORK_DIR/downloads/proot-${PROOT_VERSION}.zip"

need_build=false
for abi in "${REQUESTED_ABIS[@]}"; do
    out_dir="$REPO_ROOT/app/src/main/jniLibs/$abi"
    if [[ "$SKIP_EXISTING" == true && -s "$out_dir/libproot.so" && -s "$out_dir/libproot-loader.so" ]]; then
        echo "[$abi] Existing libproot.so and libproot-loader.so found; skipping."
    else
        need_build=true
    fi
done

if [[ "$need_build" == false ]]; then
    exit 0
fi

echo "Downloading talloc ${TALLOC_VERSION}..."
curl -fsSL --retry 3 --retry-delay 2 "$TALLOC_URL" -o "$TALLOC_TAR"
echo "${TALLOC_SHA256}  ${TALLOC_TAR}" | sha256sum -c -

echo "Downloading termux/proot v${PROOT_VERSION}..."
curl -fsSL --retry 3 --retry-delay 2 "$PROOT_URL" -o "$PROOT_ZIP"
echo "${PROOT_SHA256}  ${PROOT_ZIP}" | sha256sum -c -

build_for_abi() {
    local abi="$1"
    local target_triple=""
    local expected_machine=""

    case "$abi" in
        arm64-v8a)
            target_triple="aarch64-linux-android26"
            expected_machine="AArch64"
            ;;
        x86_64)
            target_triple="x86_64-linux-android26"
            expected_machine="Advanced Micro Devices X86-64"
            ;;
        *)
            echo "Unsupported ABI: $abi" >&2
            exit 1
            ;;
    esac

    local out_dir="$REPO_ROOT/app/src/main/jniLibs/$abi"
    if [[ "$SKIP_EXISTING" == true && -s "$out_dir/libproot.so" && -s "$out_dir/libproot-loader.so" ]]; then
        return 0
    fi

    echo "=== Building libtalloc.a + proot for $abi ($target_triple) ==="
    local abi_work="$WORK_DIR/$abi"
    local talloc_prefix="$abi_work/talloc-prefix"
    mkdir -p "$abi_work" "$talloc_prefix" "$out_dir"

    # 1. Build static libtalloc.a
    tar -xzf "$TALLOC_TAR" -C "$abi_work"
    (
        cd "$abi_work/talloc-${TALLOC_VERSION}"
        export CC="$NDK_BIN/${target_triple}-clang"
        export AR="$NDK_BIN/llvm-ar"
        export RANLIB="$NDK_BIN/llvm-ranlib"
        export CFLAGS="-fPIC -O2"
        export LDFLAGS=""

        cat <<'CROSS_EOF' > cross-answers.txt
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
CROSS_EOF

        ./configure \
            --prefix="$talloc_prefix" \
            --disable-rpath \
            --disable-python \
            --cross-compile \
            --cross-answers=cross-answers.txt

        make -j"$(nproc)"
        mkdir -p "$talloc_prefix/lib" "$talloc_prefix/include"
        "$AR" rcu "$talloc_prefix/lib/libtalloc.a" bin/default/talloc*.o
        "$RANLIB" "$talloc_prefix/lib/libtalloc.a"
        cp talloc.h "$talloc_prefix/include/talloc.h"
    )

    # 2. Build termux/proot with PROOT_UNBUNDLE_LOADER
    unzip -q "$PROOT_ZIP" -d "$abi_work"
    (
        cd "$abi_work/proot-${PROOT_VERSION}"
        unset LD
        export CC="$NDK_BIN/${target_triple}-clang"
        export AR="$NDK_BIN/llvm-ar"
        export RANLIB="$NDK_BIN/llvm-ranlib"
        export STRIP="$NDK_BIN/llvm-strip"
        export OBJCOPY="$NDK_BIN/llvm-objcopy"
        export OBJDUMP="$NDK_BIN/llvm-objdump"
        export CPPFLAGS="-DARG_MAX=131072 -DVERSION=\\\"${PROOT_VERSION}\\\" -I${talloc_prefix}/include"
        export CFLAGS="-O2"
        export LDFLAGS="-L${talloc_prefix}/lib"

        make -C src -j"$(nproc)" PROOT_UNBUNDLE_LOADER="/data/data/com.pocketlinux/files/proot"

        cp src/proot "$out_dir/libproot.so"
        cp src/loader/loader "$out_dir/libproot-loader.so"
        "$STRIP" "$out_dir/libproot.so" "$out_dir/libproot-loader.so"
        chmod 0755 "$out_dir/libproot.so" "$out_dir/libproot-loader.so"
    )

    # 3. Verify ELF architecture & static talloc linkage
    for bin in "$out_dir/libproot.so" "$out_dir/libproot-loader.so"; do
        echo "Verifying $bin:"
        readelf -h "$bin" | grep -E "Class:|Machine:"
        if ! readelf -h "$bin" | grep -q "$expected_machine"; then
            echo "ERROR: $bin does not match expected ELF machine '$expected_machine'" >&2
            exit 1
        fi
    done

    if readelf -d "$out_dir/libproot.so" | grep -qi "talloc"; then
        echo "ERROR: libproot.so dynamically links libtalloc instead of static libtalloc.a!" >&2
        readelf -d "$out_dir/libproot.so" >&2
        exit 1
    fi
    echo "[$abi] Successfully built and verified libproot.so and libproot-loader.so"
}

for abi in "${REQUESTED_ABIS[@]}"; do
    build_for_abi "$abi"
done
