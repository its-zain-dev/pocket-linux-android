# PocketLinux for Android

[![PocketLinux CI](https://github.com/its-zain-dev/pocket-linux-android/actions/workflows/ci.yml/badge.svg)](https://github.com/its-zain-dev/pocket-linux-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/its-zain-dev/pocket-linux-android?label=Release&color=0EA5E9)](https://github.com/its-zain-dev/pocket-linux-android/releases/latest)
![Android API](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-10B981)
![ABIs](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-38BDF8)
![Rootless](https://img.shields.io/badge/Root-Not%20Required-6366F1)

**PocketLinux** is a self-contained, rootless **Alpine Linux 3.20** fullscreen desktop and terminal environment for Android — packed into a single standalone APK with **no root access**, **no Termux**, and **no external VNC viewer** required.

---

## Key Features

- **Real Hardware GPU Acceleration (`VirGL` + Zero-Copy SHM, v2.0.0)**: Bundles a native Android NDK **`libvirgl_test_server.so`** (`virglrenderer 1.0.1` + `libepoxy`) supporting Qualcomm **Adreno 5xx/6xx/7xx**, ARM **Mali**, PowerVR, and Xclipse GPUs via surfaceless + `1x1` Pbuffer EGL contexts and zero-copy shared-memory (`mmap`) frame transfer, unlocking smooth OpenGL 4.0 / `glxgears` even when maximized fullscreen.
- **Zero-Flicker 120Hz Hardware Cursor & Turbo VNC Engine**: Implements **`RFC 6143 ENCODING_CURSOR (-239)` RichCursor** so X11 cursor bitmaps render directly on the Android `HardwareCanvas` at 120Hz with zero server-side cursor erase/redraw flickering, paired with `CopyRect + Raw` loopback streaming, dirty-rectangle `Bitmap` uploads, coalesced 120Hz touch/trackpad motion, and smooth opaque window dragging.
- **Smart Hardware Resource Governor & Full Linux Compatibility**: Auto-probes big.LITTLE CPU core frequencies, RAM, and GPU (`LinuxResourceGovernor`), synthesizes multi-core SELinux-safe `/proc` (`stat`, `uptime`, `loadavg`, `version`, `vmstat`, `filesystems`), mounts writable **`/dev/shm` POSIX shared memory** (required by Firefox, Chromium, MPV, Electron), executes musl & glibc (`gcompat`) ELF64 binaries, and auto-upgrades existing rootfs installations while preserving `/root/{Documents,Downloads,Projects}`.
- **Bulletproof `PocketStore` & Lock-Free `apk` Package Manager**: Browse verified dual-ABI (`arm64` + `x86_64`) Linux apps (`Firefox ESR`, `Luakit`, `Geany`, `GIMP`, `KolourPaint`, `AbiWord`, `Gnumeric`, `MPV`, `DOSBox Staging`, `SuperTux`, `Node.js`, `GCC`, `Fastfetch`), live-search Alpine 3.20 `main` + `community` repos, and install reliably via a resilient `/usr/local/sbin/apk` wrapper (bounded timeouts, serialized store queue, orphaned lock recovery, `libnoflock.so` fallback, and Cloudflare + Google DoH DNS).
- **PocketLinux Settings & Sticky `xrandr` Display Presets**: Switch OpenGL renderers (`Auto VirGL`, `VirGL Forced`, `LLVMpipe`, `Zink`), pick sticky display resolutions (`960x540 Turbo 3D`, `1280x720`, `1366x768`, `1600x900`), inspect live CPU/RAM/GPU/`/dev/shm` health, and run 1-click library & database repair.
- **Smart Auto-Keyboard Control Bar & Native C/JNI PTY Terminal**: Automatically reveals `Esc`, `Tab`, `Ctrl`, `Alt`, `Super`, arrow keys, and `Tap/Trackpad` above the Android keyboard when typing, plus a built-in `/bin/bash -l` VT100 terminal backed by `libptybridge.so` (`forkpty`).

---

## Download & Install

1. Open the [**Latest Release**](https://github.com/its-zain-dev/pocket-linux-android/releases/latest) page.
2. Download the APK matching your device:
   - **Android Phones & Tablets (64-bit ARM)**: `PocketLinux-*-arm64-v8a.apk`
   - **x86_64 Emulators / Chromebooks**: `PocketLinux-*-x86_64.apk`
3. Install the APK and open **PocketLinux**. First launch extracts (or auto-upgrades) the offline Alpine 3.20 rootfs while keeping your files in `/root/{Documents,Downloads,Projects}` intact.

> **Note on Android 12+ (Phantom Process Killer):** If heavy desktop sessions exit with `Signal 9`, enable **Developer options → Disable child process restrictions** (Android 12L/13+) or run:
> ```bash
> adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
> adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
> ```

---

## Architecture Overview

```text
+-------------------------------------------------------------------------+
|                Android App (Kotlin + Jetpack Compose)                   |
|  120Hz Hardware RichCursor Desktop · Auto-IME Bar · VT100 TerminalTab   |
+------------------------------------+------------------------------------+
                                     | LocalBinder / StateFlow
                                     v
+-------------------------------------------------------------------------+
|            LinuxRuntimeService (specialUse Foreground Service)          |
|  LinuxResourceGovernor · RootfsManager (v2 Auto-Upgrade) · RfbClient    |
|  VirglServerManager (libvirgl_test_server.so) · PtyBridge (forkpty)     |
+-------------------+----------------------+------------------------------+
                    | PRoot + seccomp-BPF  | Local TCP :5901 + VirGL IPC
                    v                      v
+-------------------------------------------------------------------------+
|            Rootless Alpine Linux 3.20 Userspace (arm64 / x86_64)        |
|  Xvnc (:1 +GLX) · JWM · PCManFM · PocketStore · Settings & GPU          |
|  Mesa Virpipe/LLVMpipe · /dev/shm · Multi-Core /proc · Lock-Free apk    |
+-------------------------------------------------------------------------+
```

For a detailed milestone-by-milestone breakdown, see [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Building from Source

### Prerequisites (Linux x86_64 host)
- **JDK 17**, **Android SDK** (API 35), **Android NDK** (`ANDROID_NDK_HOME`)
- `gawk`, `binutils`, `make`, `meson`, `ninja-build`, `pkg-config`, `python3`, `curl`, `tar`, `unzip`, `gzip`, `qemu-user-static` + `binfmt-support` (or `qemu-user` + `qemu-user-binfmt` on Ubuntu 26.04+)

### Build Steps
```bash
git clone https://github.com/its-zain-dev/pocket-linux-android.git
cd pocket-linux-android

# 1. Cross-compile PRoot, unbundled loader, and VirGL hardware GPU server
bash scripts/ci/build-proot.sh --skip-existing
bash scripts/ci/build-virgl.sh --skip-existing

# 2. Build offline Alpine Linux 3.20 rootfs tarballs
bash scripts/ci/build-rootfs.sh --skip-existing

# 3. Run unit tests, lint, and assemble multi-ABI APKs
./gradlew --no-daemon testArm64DebugUnitTest testX86_64DebugUnitTest assembleArm64Debug assembleX86_64Debug

# 4. Verify APK integrity, VirGL + PRoot ELF64 headers, and rootfs
python3 scripts/ci/verify-apk.py --apk app/build/outputs/apk/arm64/debug/app-arm64-debug.apk --abi arm64-v8a
```

---

## Creating a New Release

Push a version tag and GitHub Actions (`.github/workflows/release.yml`) automatically builds, verifies, checksums, and publishes the APKs to **GitHub Releases**:

```bash
git tag v2.0.0
git push origin v2.0.0
```
