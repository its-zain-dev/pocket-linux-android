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

patch_virgl_for_android_ndk() {
    local src_dir="$1"
    python3 - "$src_dir" <<'PYEOF'
import os
import sys

root = sys.argv[1]

# 1. meson.build: enable EGL without GBM on Android NDK (uses EGL_DEFAULT_DISPLAY + surfaceless/pbuffer)
p = os.path.join(root, "meson.build")
s = open(p, encoding="utf-8").read()
s = s.replace(
    "if with_host_windows\n         have_egl = true",
    "if with_host_windows or host_machine.system() == 'android'\n         have_egl = true",
    1,
)
open(p, "w", encoding="utf-8").write(s)

# 2. u_thread.h: avoid initial-exec TLS on Android Bionic (__ANDROID__)
p = os.path.join(root, "src/mesa/util/u_thread.h")
s = open(p, encoding="utf-8").read()
s = s.replace("#elif defined(ANDROID)", "#elif defined(ANDROID) || defined(__ANDROID__)")
open(p, "w", encoding="utf-8").write(s)

# 3. anon_file.c: use SYS_memfd_create syscall directly on Android API 26+
p = os.path.join(root, "src/mesa/util/anon_file.c")
s = open(p, encoding="utf-8").read()
s = s.replace("#elif defined(ANDROID)", "#elif defined(ANDROID) || defined(__ANDROID__)")
s = s.replace(
    "defined(HAVE_MEMFD_CREATE) || defined(ANDROID)",
    "defined(HAVE_MEMFD_CREATE) || defined(ANDROID) || defined(__ANDROID__)",
)
s = s.replace(
    "defined(HAVE_MKOSTEMP) || defined(ANDROID)",
    "defined(HAVE_MKOSTEMP) || defined(ANDROID) || defined(__ANDROID__)",
)
open(p, "w", encoding="utf-8").write(s)

# 4. vrend_renderer.c: replace C11 timespec_get (API 29+) with POSIX clock_gettime (API 1+)
p = os.path.join(root, "src/vrend_renderer.c")
s = open(p, encoding="utf-8").read()
s = s.replace(
    "ret = timespec_get(&ts, TIME_UTC);",
    "ret = (clock_gettime(CLOCK_REALTIME, &ts) == 0 ? TIME_UTC : 0);",
)
open(p, "w", encoding="utf-8").write(s)

# 5. vrend_winsys.c: wire HAVE_EPOXY_EGL_H when ENABLE_GBM is absent (Android surfaceless/pbuffer EGL)
p = os.path.join(root, "src/vrend_winsys.c")
s = open(p, encoding="utf-8").read()
s = s.replace(
    """      use_context = CONTEXT_EGL;
#else
      (void)preferred_fd;
      virgl_error("EGL is not supported on this platform\\n");
      return -1;
#endif""",
    """      use_context = CONTEXT_EGL;
#elif defined(HAVE_EPOXY_EGL_H)
      (void)preferred_fd;
      egl = virgl_egl_init(EGL_DEFAULT_DISPLAY, flags & VIRGL_RENDERER_USE_SURFACELESS,
                           flags & VIRGL_RENDERER_USE_GLES);
      if (!egl)
         return -1;
      use_context = CONTEXT_EGL;
#else
      (void)preferred_fd;
      virgl_error("EGL is not supported on this platform\\n");
      return -1;
#endif""",
    1,
)
s = s.replace(
    """void vrend_winsys_cleanup(void)
{
#ifdef ENABLE_GBM""",
    """void vrend_winsys_cleanup(void)
{
#if defined(ENABLE_GBM)""",
    1,
)
s = s.replace(
    """   } else if (use_context == CONTEXT_EGL_EXTERNAL) {
      free(egl);
      egl = NULL;
      use_context = CONTEXT_NONE;
   }
#endif""",
    """   } else if (use_context == CONTEXT_EGL_EXTERNAL) {
      free(egl);
      egl = NULL;
      use_context = CONTEXT_NONE;
   }
#elif defined(HAVE_EPOXY_EGL_H)
   if (use_context == CONTEXT_EGL) {
      virgl_egl_destroy(egl);
      egl = NULL;
      use_context = CONTEXT_NONE;
   } else if (use_context == CONTEXT_EGL_EXTERNAL) {
      free(egl);
      egl = NULL;
      use_context = CONTEXT_NONE;
   }
#endif""",
    1,
)
open(p, "w", encoding="utf-8").write(s)

# 6. vrend_winsys_egl.c: native Android EGL_DEFAULT_DISPLAY + 1x1 Pbuffer fallback for Adreno 5xx & Mali GPUs
p = os.path.join(root, "src/vrend_winsys_egl.c")
s = open(p, encoding="utf-8").read()
s = s.replace(
    "   EGLDisplay egl_display;\n   EGLConfig egl_conf;\n   EGLContext egl_ctx;",
    "   EGLDisplay egl_display;\n   EGLConfig egl_conf;\n   EGLContext egl_ctx;\n   EGLSurface dummy_surface;",
    1,
)
s = s.replace(
    """static bool virgl_egl_check_extensions(struct virgl_egl *egl)
{
   if (!has_bits(egl->extension_bits, EGL_KHR_SURFACELESS_CONTEXT | EGL_KHR_CREATE_CONTEXT)) {
      virgl_error("Missing EGL_KHR_surfaceless_context or EGL_KHR_create_context\\n");
      return false;
   }

   return true;
}""",
    """static bool virgl_egl_check_extensions(struct virgl_egl *egl)
{
#ifdef __ANDROID__
   (void)egl;
   return true;
#else
   if (!has_bits(egl->extension_bits, EGL_KHR_SURFACELESS_CONTEXT | EGL_KHR_CREATE_CONTEXT)) {
      virgl_error("Missing EGL_KHR_surfaceless_context or EGL_KHR_create_context\\n");
      return false;
   }

   return true;
#endif
}""",
    1,
)
s = s.replace(
    """   if (has_egl_base) {
      if (!has_bit(egl->extension_bits, EGL_EXT_PLATFORM_BASE))
         goto fail;
      if (surfaceless) {
         egl->egl_display = egl->funcs.eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA,
                                                             EGL_DEFAULT_DISPLAY, NULL);
      }""",
    """#ifdef __ANDROID__
   (void)has_egl_base;
   if (true) {
      egl->egl_display = eglGetDisplay(display_id);
#else
   if (has_egl_base) {
      if (!has_bit(egl->extension_bits, EGL_EXT_PLATFORM_BASE))
         goto fail;
      if (surfaceless) {
         egl->egl_display = egl->funcs.eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA,
                                                             EGL_DEFAULT_DISPLAY, NULL);
      }
#endif""",
    1,
)
s = s.replace(
    """   eglMakeCurrent(egl->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                  egl->egl_ctx);""",
    """   egl->dummy_surface = EGL_NO_SURFACE;
   if (!has_bit(egl->extension_bits, EGL_KHR_SURFACELESS_CONTEXT)) {
      static const EGLint pbuf_att[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
      egl->dummy_surface = eglCreatePbufferSurface(egl->egl_display, egl->egl_conf, pbuf_att);
   }
   eglMakeCurrent(egl->egl_display, egl->dummy_surface, egl->dummy_surface,
                  egl->egl_ctx);""",
    1,
)
s = s.replace(
    """   eglDestroyContext(egl->egl_display, egl->egl_ctx);
   eglTerminate(egl->egl_display);""",
    """   if (egl->dummy_surface != EGL_NO_SURFACE) {
      eglDestroySurface(egl->egl_display, egl->dummy_surface);
   }
   eglDestroyContext(egl->egl_display, egl->egl_ctx);
   eglTerminate(egl->egl_display);""",
    1,
)
s = s.replace(
    """   egl_ctx = eglCreateContext(egl->egl_display,
                             egl->egl_conf,
                             vparams->shared ? eglGetCurrentContext() : EGL_NO_CONTEXT,
                             ctx_att);
   return (virgl_renderer_gl_context)egl_ctx;""",
    """   egl_ctx = eglCreateContext(egl->egl_display,
                             egl->egl_conf,
                             vparams->shared ? eglGetCurrentContext() : EGL_NO_CONTEXT,
                             ctx_att);
   if (!egl_ctx) {
      EGLint fallback_att[] = {
         EGL_CONTEXT_CLIENT_VERSION, vparams->major_ver >= 3 ? 3 : 2,
         EGL_NONE
      };
      egl_ctx = eglCreateContext(egl->egl_display,
                                 egl->egl_conf,
                                 vparams->shared ? eglGetCurrentContext() : EGL_NO_CONTEXT,
                                 fallback_att);
   }
   return (virgl_renderer_gl_context)egl_ctx;""",
    1,
)
s = s.replace(
    """int virgl_egl_make_context_current(struct virgl_egl *egl, virgl_renderer_gl_context virglctx)
{
   EGLContext egl_ctx = (EGLContext)virglctx;

   return eglMakeCurrent(egl->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE,
                         egl_ctx) ? 0 : -1;
}""",
    """int virgl_egl_make_context_current(struct virgl_egl *egl, virgl_renderer_gl_context virglctx)
{
   EGLContext egl_ctx = (EGLContext)virglctx;
   EGLSurface surf = egl_ctx ? egl->dummy_surface : EGL_NO_SURFACE;

   return eglMakeCurrent(egl->egl_display, surf, surf,
                         egl_ctx) ? 0 : -1;
}""",
    1,
)
open(p, "w", encoding="utf-8").write(s)

# 7. vtest/vtest_shm.c: avoid NDK memfd_create symbol conflict & add TMPDIR zero-copy SHM fallback for Android SELinux
p = os.path.join(root, "vtest/vtest_shm.c")
open(p, "w", encoding="utf-8").write("""#include "util.h"
#include "vtest_shm.h"

#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

static int vtest_open_anon_shm(const char *name, unsigned int flags)
{
   int fd = -1;
#ifdef __NR_memfd_create
   fd = (int)syscall(__NR_memfd_create, name, flags);
   if (fd >= 0)
      return fd;
#else
   (void)name;
   (void)flags;
#endif
   const char *tmpdir = getenv("TMPDIR");
   if (!tmpdir || !tmpdir[0])
      tmpdir = "/data/local/tmp";
   char tmpl[512];
   snprintf(tmpl, sizeof(tmpl), "%s/.vtest-shm-XXXXXX", tmpdir);
   fd = mkstemp(tmpl);
   if (fd >= 0) {
      unlink(tmpl);
      fcntl(fd, F_SETFD, FD_CLOEXEC);
   }
   return fd;
}

int vtest_new_shm(uint32_t handle, size_t size)
{
   int fd, ret;
   char name[64];
   snprintf(name, sizeof(name), "vtest-res-%u", handle);

   fd = vtest_open_anon_shm(name, MFD_ALLOW_SEALING | MFD_CLOEXEC);
   if (fd < 0)
      return report_failed_call("vtest_open_anon_shm", -errno);

   ret = ftruncate(fd, (off_t)size);
   if (ret < 0) {
      close(fd);
      return report_failed_call("ftruncate", -errno);
   }

   return fd;
}

int vtest_shm_check(void)
{
   int mfd = vtest_open_anon_shm("vtest-check", MFD_ALLOW_SEALING | MFD_CLOEXEC);
   if (mfd >= 0) {
      close(mfd);
      return 1;
   }
   return 0;
}
""")
PYEOF
}

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
c_args = ['-fPIC', '-O3', '-D_GNU_SOURCE', '-I${prefix}/include']
c_link_args = ['-L${prefix}/lib', '-Wl,--gc-sections', '-ldl', '-lm']
cpp_args = ['-fPIC', '-O3', '-D_GNU_SOURCE', '-I${prefix}/include']
cpp_link_args = ['-L${prefix}/lib', '-Wl,--gc-sections', '-ldl', '-lm']

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
    patch_virgl_for_android_ndk "$abi_work/virglrenderer-${VIRGLRENDERER_VERSION}"

    PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" \
    meson setup "$abi_work/build-virgl" "$abi_work/virglrenderer-${VIRGLRENDERER_VERSION}" \
        --cross-file "$cross_file" --prefix "$prefix" --libdir lib --buildtype release \
        -Ddefault_library=static -Dplatforms=egl -Dminigbm_allocation=false -Dvenus=false -Dtests=false -Drender-server=false
    ninja -C "$abi_work/build-virgl" -j"$(nproc)" vtest/virgl_test_server

    cp "$abi_work/build-virgl/vtest/virgl_test_server" "$out_bin"
    "$NDK_BIN/llvm-strip" "$out_bin"
    chmod 0755 "$out_bin"
    readelf -h "$out_bin" | grep -q "$expected_machine"
    echo "[$abi] Successfully built $out_bin ($(du -h "$out_bin" | cut -f1))"
}

for abi in "${REQUESTED_ABIS[@]}"; do
    build_for_abi "$abi"
done
