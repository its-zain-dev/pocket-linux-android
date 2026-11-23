# PocketLinux for Android

[![PocketLinux CI](https://github.com/its-zain-dev/pocket-linux-android/actions/workflows/ci.yml/badge.svg)](https://github.com/its-zain-dev/pocket-linux-android/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/its-zain-dev/pocket-linux-android?label=Release&color=0EA5E9)](https://github.com/its-zain-dev/pocket-linux-android/releases/latest)
![Android API](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-10B981)
![ABIs](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-38BDF8)
![Rootless](https://img.shields.io/badge/Root-Not%20Required-6366F1)

**PocketLinux** is a self-contained, rootless **Alpine Linux 3.20** fullscreen desktop and terminal environment for Android — packed into a single standalone APK with **no root access**, **no Termux**, and **no external VNC viewer** required.

---

## Key Features

- **Immersive Fullscreen Linux Desktop**: Runs **TigerVNC (`Xvnc`)**, **JWM**, and **PCManFM** desktop icons with a dark gradient wallpaper and touch-friendly 42px taskbar. A tiny semi-transparent floating menu pill (`☰`) keeps 100% of the screen dedicated to Linux.
- **Smart Auto-Keyboard Control Bar**: Uses a custom pure-Kotlin **RFB 3.8** VNC client (`SurfaceView` + Gboard/Samsung IME bridge). Whenever the Android keyboard opens, a sleek control bar (`Esc`, `Tab`, `Ctrl`, `Alt`, `Super`, arrow keys, `Tap/Trackpad`, `Right Click`) automatically appears above it and hides when you're done typing.
- **PocketStore (Built-in GTK-3 App Store)**: Browse featured Linux apps (`Geany`, `GIMP`, `NetSurf`, `AbiWord`, `Gnumeric`, `MuPDF`, `Node.js`, `GCC`, `SuperTux`, `DOSBox`), search all Alpine 3.20 repositories live, and install with automatic dependency & library resolution (`apk update && apk add --upgrade && apk fix`).
- **PocketLinux Settings & GPU Control Center**: Configure GPU rendering modes (**VirGL** hardware acceleration when bundled, fast multi-threaded **LLVMpipe**, or **Zink**), switch display resolutions live via `xrandr`, clean caches, and run 1-click library repair.
- **Native C/JNI PTY Terminal**: Built-in `/bin/bash -l` VT100 terminal backed by `libptybridge.so` (`forkpty`) with tap-anywhere-to-type focus and extra keys.
- **Full Linux & ELF Compatibility**: Executes native `aarch64` / `x86_64` musl & static ELF64 binaries plus **`gcompat`** for glibc ELF binaries, **SELinux-safe `/proc` emulation**, pre-populated `/root` folders + GTK-3 dark bookmarks (`/mnt/sdcard` & `/mnt/phone`), and **automatic DNS-over-HTTPS fallback** (`fix-dns.sh`) when UDP port 53 is restricted.

---

## Download & Install

1. Open the [**Latest Release**](https://github.com/its-zain-dev/pocket-linux-android/releases/latest) page.
2. Download the APK matching your device:
   - **Android Phones & Tablets (64-bit ARM)**: `PocketLinux-*-arm64-v8a.apk`
   - **x86_64 Emulators / Chromebooks**: `PocketLinux-*-x86_64.apk`
3. Install the APK and open **PocketLinux**. First launch extracts the offline Alpine 3.20 rootfs (~10–25 seconds); subsequent launches boot in seconds.

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
|  Immersive Fullscreen DesktopTab · Auto-IME Bar · VT100 TerminalTab     |
+------------------------------------+------------------------------------+
                                     | LocalBinder / StateFlow
                                     v
+-------------------------------------------------------------------------+
|            LinuxRuntimeService (specialUse Foreground Service)          |
|  LinuxRuntimeEngine · RootfsManager · ProotCommandBuilder · RfbClient   |
|  PtyBridge (C/JNI libptybridge.so) · VirglServerManager (optional)      |
+-------------------+---------------------------------+-------------------+
                    | PRoot (NDK libproot.so)         | Local TCP :5901
                    v                                 v
+-------------------------------------------------------------------------+
|            Rootless Alpine Linux 3.20 Userspace (arm64 / x86_64)        |
|  Xvnc (:1) + JWM + PCManFM Desktop + PocketStore + Settings & GPU       |
|  gcompat (glibc ELF) · SELinux fake /proc · Auto DoH DNS (fix-dns.sh)   |
+-------------------------------------------------------------------------+
```

For a detailed milestone-by-milestone breakdown, see [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Building from Source

### Prerequisites (Linux x86_64 host)
- **JDK 17**, **Android SDK** (API 35), **Android NDK** (`ANDROID_NDK_HOME`)
- `gawk`, `binutils`, `make`, `python3`, `curl`, `tar`, `unzip`, `gzip`, `qemu-user-static` + `binfmt-support` (or `qemu-user` + `qemu-user-binfmt` on Ubuntu 26.04+)

### Build Steps
```bash
git clone https://github.com/its-zain-dev/pocket-linux-android.git
cd pocket-linux-android

# 1. Cross-compile PRoot & unbundled loader
bash scripts/ci/build-proot.sh --skip-existing

# 2. Build offline Alpine Linux 3.20 rootfs tarballs
bash scripts/ci/build-rootfs.sh --skip-existing

# 3. Run unit tests, lint, and assemble multi-ABI APKs
./gradlew --no-daemon testArm64DebugUnitTest testX86_64DebugUnitTest assembleArm64Debug assembleX86_64Debug

# 4. Verify APK integrity & ELF64 headers
python3 scripts/ci/verify-apk.py --apk app/build/outputs/apk/arm64/debug/app-arm64-debug.apk --abi arm64-v8a
```

---

## Creating a New Release

Push a version tag and GitHub Actions (`.github/workflows/release.yml`) automatically builds, verifies, checksums, and publishes the APKs to **GitHub Releases**:

```bash
git tag v1.0.0
git push origin v1.0.0
```
