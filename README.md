# PocketLinux for Android

[![PocketLinux CI](https://github.com/its-zain-dev/pocket-linux-android/actions/workflows/ci.yml/badge.svg)](https://github.com/its-zain-dev/pocket-linux-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/its-zain-dev/pocket-linux-android?label=Release&color=0EA5E9)](https://github.com/its-zain-dev/pocket-linux-android/releases/latest)
![Android API](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-10B981)
![ABIs](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-38BDF8)
![Rootless](https://img.shields.io/badge/Root-Not%20Required-6366F1)

**PocketLinux** is a self-contained, rootless **Alpine Linux 3.20** fullscreen desktop and terminal environment for Android — packed into a single standalone APK with **no root access**, **no Termux**, and **no external VNC viewer** required.

---

## Key Features

- **Real Hardware GPU Acceleration (`VirGL` + Adreno/Mali EGL Fixes, v2.1.0)**: Bundles a native Android NDK **`libvirgl_test_server.so`** (`virglrenderer 1.0.1` + `libepoxy`) tuned for Qualcomm **Adreno 5xx/6xx/7xx**, ARM **Mali**, PowerVR, and Xclipse GPUs (disables `feat_dual_src_blend` on Android, uses GLES 3 + `1x1` Pbuffer EGL context, safe `memfd`/`ashmem`/v0 SHM, and live `/tmp/.virgl_ipc/virgl_server.log` diagnostics).
- **Android Native Network & DNS Bridge (`127.0.0.1:18080`)**: Routes browser and package manager traffic seamlessly through Android's native DNS resolver whenever Private DNS/VPN/carrier blocks raw UDP 53, paired with PRoot-safe sandbox flags for **Firefox ESR**, **Chromium**, and **Luakit**.
- **Standalone Parallel Download Manager & Persistent `PocketStore`**: Backed by a detached **`pocketlinux-pkgd`** background daemon so downloads survive closing Store windows, stay synced across multiple windows, accelerate via multi-stream fetching (0%–100% progress + live speed), support parallel multi-app installs, and provide a live Log Viewer.
- **Movable Windows-Style Desktop & Automatic App Sync**: Interactive GTK-3 + Cairo Desktop canvas lets you freely drag and arrange icons anywhere on screen (snap-to-grid or pixel-free) and automatically adds newly installed apps to your **Desktop**, **Start Menu → Installed Apps**, **File Manager → Applications**, and Android **Apps & System** tab.
- **Windows-11-Inspired Settings & Phone Storage Privacy Mode**: 8-section sidebar Control Center with live Accent Colors, 4 built-in Wallpapers + custom image picker, customizable Taskbar, DPI scaling, GPU diagnostics, Network Bridge controls, and a **Privacy Mode (`Hide Phone Files`)** toggle that lets you browse `/mnt/phone` or isolate it as completely empty on demand.
- **Zero-Flicker 120Hz Hardware Cursor & Upgraded Touch Controls**: Implements **`RFC 6143 ENCODING_CURSOR (-239)` RichCursor** on Android `HardwareCanvas`, plus touch double-tap snapping, **Drag Lock**, optional floating **Quick Touch Bar**, auto-IME keyboard bar, and native C/JNI VT100 terminal (`libptybridge.so`).

---

## Download & Install

1. Open the [**Latest Release**](https://github.com/its-zain-dev/pocket-linux-android/releases/latest) page.
2. Download the APK matching your device:
   - **Android Phones & Tablets (64-bit ARM)**: `PocketLinux-*-arm64-v8a.apk`
   - **x86_64 Emulators / Chromebooks**: `PocketLinux-*-x86_64.apk`
3. Install the APK and open **PocketLinux**. First launch extracts (or auto-upgrades) the offline Alpine 3.20 rootfs while keeping your files in `/root/{Documents,Downloads,Projects}` and `/root/.config/pocketlinux` intact.

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
|  120Hz RichCursor Desktop · Drag Lock · Quick Touch Bar · TerminalTab   |
+------------------------------------+------------------------------------+
                                     | LocalBinder / StateFlow
                                     v
+-------------------------------------------------------------------------+
|            LinuxRuntimeService (specialUse Foreground Service)          |
|  LinuxResourceGovernor · RootfsManager (v3 Auto-Upgrade) · RfbClient    |
|  VirglServerManager · AndroidNetworkBridge (:18080) · PtyBridge         |
+-------------------+----------------------+------------------------------+
                    | PRoot + seccomp-BPF  | Local TCP :5901 + VirGL IPC
                    v                      v
+-------------------------------------------------------------------------+
|            Rootless Alpine Linux 3.20 Userspace (arm64 / x86_64)        |
|  Xvnc (:1 +GLX) · JWM · Interactive Desktop · Settings · PocketStore    |
|  Parallel Download Daemon (:18765) · App Sync · Mesa Virpipe/LLVMpipe   |
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
