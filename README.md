# PocketLinux for Android

**PocketLinux** is a self-contained, rootless Linux desktop and terminal environment for Android. It bundles **Alpine Linux 3.20**, **JWM (Joe's Window Manager)**, and **TigerVNC (`Xvnc`)** running inside **PRoot**, paired with a custom pure-Kotlin **RFB 3.8** VNC client, a native **C/JNI PTY bridge**, and a built-in **Jetpack Compose** VT100 terminal—no root access or external Termux installation required.

## Features

- **Rootless Alpine Linux 3.20 Userspace**: Pre-built, per-ABI Alpine Linux 3.20 rootfs tarballs extracted into app-private storage on first boot and executed via `termux/proot` (statically linked against `libtalloc.a` with an unbundled loader).
- **X11 Desktop (`Xvnc` + `JWM`)**: Runs TigerVNC's `Xvnc` on `127.0.0.1:5901` (`DISPLAY=:1`, default `1024x600`, 24-bit color) with a themed dark JWM window manager and bottom panel.
- **Custom Pure-Kotlin RFB 3.8 Client**: Zero third-party VNC SDKs—implements RFC 6143 RFB 3.8 handshaking, Raw/CopyRect/Hextile/Zlib (persistent `Inflater`) encodings, DesktopSize/ExtendedDesktopSize pseudo-encodings, X11 KeySym translation, and an aspect-locked `SurfaceView` renderer with **Direct Tap** and **Trackpad** modes.
- **Native C/JNI PTY Bridge & Built-in Terminal**: Spawns interactive `/bin/bash -l` sessions inside PRoot over a real Linux pseudo-terminal (`forkpty` in `pty_bridge.c`) with window-size (`TIOCSWINSZ`) updates, VT100/ANSI parsing, and an extra-keys toolbar.
- **Foreground Runtime Supervisor**: `LinuxRuntimeService` manages rootfs extraction, desktop lifecycle, RFB connection, PTY session, and live Linux system metrics (`sysinfo.sh`) inside an Android `specialUse` foreground service.

## Architecture Overview

```text
+---------------------------------------------------------------------------+
|                     Android App (Jetpack Compose UI)                      |
|  MainActivity (configChanges-safe)                                        |
|  +-- DesktopTab        (LinuxDesktopSurface + touch/key toolbar)          |
|  +-- TerminalTab       (TerminalEmulator + VT100 output + extra keys)     |
|  +-- AppsSystemTab     (GUI app launchers, ad-hoc exec, system metrics)   |
+-------------------------------+-------------------------------------------+
                                | LocalBinder / StateFlow
                                v
+---------------------------------------------------------------------------+
|                LinuxRuntimeService (Foreground Service)                   |
|  +-- LinuxRuntimeEngine  (state machine: Idle -> Extracting -> Ready)     |
|  +-- RootfsManager       (extracts assets/rootfs-<abi>.tar.gz)            |
|  +-- ProotCommandBuilder (wires libproot.so + libproot-loader.so)         |
|  +-- RfbClient           (pure-Kotlin RFB 3.8 TCP client -> 127.0.0.1:5901|
|  +-- PtyBridge           (JNI -> libptybridge.so / forkpty())             |
+-------------------+-----------------------------------+-------------------+
                    | ProcessBuilder / JNI fork         | TCP (RFB 3.8)
                    v                                   v
+---------------------------------------------------------------------------+
|               PRoot Userspace (Alpine Linux 3.20 Rootfs)                  |
|  libproot.so + libproot-loader.so  (no root required)                     |
|  +-- /usr/local/bin/start-desktop.sh                                      |
|  |     +-- Xvnc :1 (-geometry 1024x600 -depth 24 -localhost -rfbport 5901)|
|  |     +-- jwm     (/root/.jwmrc, 2 virtual desktops, bottom tray)        |
|  +-- /bin/bash -l  <--------> PtyBridge (master/slave PTY fd)             |
|  +-- /usr/local/bin/sysinfo.sh (JSON/text kernel, RAM, proc, X11 stats)   |
+---------------------------------------------------------------------------+
```

For a milestone-by-milestone breakdown of the codebase, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Supported ABIs & Android Requirements

| Requirement | Value |
| :--- | :--- |
| **Minimum SDK** | Android 8.0 Oreo (API 26) |
| **Target / Compile SDK** | Target API 34 (Android 14) / Compile API 35 |
| **Supported ABIs** | `arm64-v8a` (`arm64` flavor), `x86_64` (`x86_64` flavor) |
| **Toolchain** | JDK 17, Android NDK (LLVM Clang), CMake |

### Android 12+ Phantom Process Killer Note

Starting with Android 12 (API 31), the system limits apps to 32 background child ("phantom") processes and may send `SIGKILL` (`Signal 9`) to Linux processes spawned under PRoot when the app is backgrounded or spawns multiple GUI tools.

- PocketLinux runs `LinuxRuntimeService` as a `specialUse` foreground service to keep the runtime alive while active.
- If you encounter unexpected `Signal 9` terminations on Android 12–14:
  - **Android 12L / 13+**: Enable **Developer options → Disable child process restrictions**.
  - **Android 12 (via ADB)**:
    ```bash
    adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent"
    adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
    ```

## Building Locally

### Prerequisites

- **Linux x86_64 host** (recommended for building both ABIs)
- **JDK 17** and **Android SDK** (`ANDROID_HOME`) + **Android NDK** (`ANDROID_NDK_LATEST_HOME` or `ANDROID_NDK_HOME`)
- **Build tools**: `gawk`, `binutils`, `make`, `python3`, `curl`, `tar`, `unzip`, `gzip`
- **QEMU user-mode emulation** (for building the `arm64-v8a` Alpine rootfs on an `x86_64` host): `qemu-user-static` + `binfmt-support` (or `qemu-user` + `qemu-user-binfmt`)

### 1. Build PRoot & Unbundled Loader

```bash
bash scripts/ci/build-proot.sh --skip-existing
# Or build a single ABI:
bash scripts/ci/build-proot.sh --abi arm64-v8a
```

### 2. Build Alpine Linux 3.20 Rootfs Tarballs

```bash
bash scripts/ci/build-rootfs.sh --skip-existing
# Or build a single ABI:
bash scripts/ci/build-rootfs.sh --abi arm64-v8a
```

### 3. Assemble APKs & Verify Packaging

```bash
./gradlew --no-daemon assembleArm64Debug assembleX86_64Debug

python3 scripts/ci/verify-apk.py \
  --apk app/build/outputs/apk/arm64/debug/app-arm64-debug.apk \
  --abi arm64-v8a

python3 scripts/ci/verify-apk.py \
  --apk app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk \
  --abi x86_64
```

## GitHub Actions CI

1. **`PocketLinux CI` (`.github/workflows/ci.yml`)**: Builds and caches NDK PRoot binaries and Alpine v3.20 rootfs tarballs, chroot-smoke-tests both rootfs images, runs unit tests and Android Lint, assembles `arm64` and `x86_64` debug APKs, verifies them with `scripts/ci/verify-apk.py`, and uploads both APKs as workflow artifacts.
2. **`PocketLinux E2E Verification` (`.github/workflows/e2e.yml`)**: Builds multi-ABI APKs and `androidTest` APK, runs dual-ABI verification (`scripts/e2e/verify_apks.py`), and runs the 4-tier ADB E2E harness (`scripts/e2e/run-e2e.sh`) + instrumentation tests on API 30 and API 34 emulators.

## Testing

```bash
# JVM Unit Tests & Lint
./gradlew --no-daemon \
  testArm64DebugUnitTest \
  testX86_64DebugUnitTest \
  lintArm64Debug \
  lintX86_64Debug
```

## Included Desktop Apps & Shortcuts

| Category | Packages / Tools |
| :--- | :--- |
| **Desktop & X11** | `tigervnc` (`Xvnc`), `jwm`, `xwininfo`, `xdpyinfo`, `xrandr`, `xsetroot`, `xrdb`, `dbus`, `dbus-x11`, `adwaita-icon-theme`, DejaVu & misc X11 fonts |
| **GUI Apps** | `xterm`, `mousepad`, `pcmanfm`, `galculator`, `xcalc`, `xclock`, `feh` |
| **CLI & Scripting** | `bash`, `busybox`, `coreutils`, `apk-tools`, `procps-ng`, `curl`, `python3`, `ca-certificates`, `/usr/local/bin/sysinfo.sh` |

- **JWM Shortcuts**: `Alt+F1` (Apps menu), `Alt+Tab` (Cycle windows), `Alt+F4` (Close window).
- **Desktop Tab Toolbar**: `Mode: Tap` / `Mode: Trackpad`, `Esc`, `Tab`, `Ctrl`, `Alt`, `Super`, arrow keys, and `Reconnect`.
- **Terminal Tab Toolbar**: Latchable `Ctrl`, `Tab`, `Esc`, `Up`, `Down`, `Ctrl+C`, `Ctrl+D`.
