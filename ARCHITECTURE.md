# PocketLinux Architecture

PocketLinux is organized into four architectural milestones that layer rootless Linux packaging, native process supervision, custom RFB rendering, and Jetpack Compose UX with end-to-end verification.

## Milestone 1 (M1): CI Pipeline, PRoot Cross-Compilation & Alpine 3.20 Rootfs

- **PRoot Cross-Compilation (`scripts/ci/build-proot.sh`)**: Downloads and SHA-256 verifies `talloc` (`2.5.0`) and `termux/proot` (`v5.1.107.102`), cross-compiles static `libtalloc.a` and `proot` + unbundled `loader` with Android NDK LLVM Clang targeting API 26 (`arm64-v8a` and `x86_64`), and installs stripped binaries as `app/src/main/jniLibs/<abi>/libproot.so` and `libproot-loader.so`.
- **Alpine 3.20 Rootfs Builder (`scripts/ci/build-rootfs.sh` & `scripts/ci/rootfs-overlay/`)**: Resolves and SHA-256 verifies Alpine 3.20 minirootfs, installs `tigervnc`, `jwm`, GUI apps, fonts, and CLI tools via `apk.static` (with QEMU `binfmt_misc` for `aarch64`), applies `/root/.jwmrc`, `/usr/local/bin/start-desktop.sh`, and `/usr/local/bin/sysinfo.sh`, and packages `app/src/<flavor>/assets/rootfs-<abi>.tar.gz`.
- **CI & Static Verification (`.github/workflows/ci.yml`, `scripts/ci/verify-apk.py`)**: Caches PRoot and rootfs artifacts, chroot-smoke-tests both rootfs images, runs unit tests/lint, assembles multi-ABI APKs, and validates ELF64 headers and raw rootfs assets.

## Milestone 2 (M2): Runtime Engine, Foreground Service & Native PTY Bridge

- **Rootfs Extraction (`RootfsManager.kt`)**: Streams `rootfs-<abi>.tar.gz` via Apache Commons Compress into a staging directory with zip-slip/symlink protections, bottom-up `chmod`, and atomic commit to `filesDir/rootfs`.
- **PRoot Invocation (`ProotCommandBuilder.kt`)**: Builds PRoot `argv` and environment variables (`PROOT_LOADER`, `PROOT_TMP_DIR`, `PROOT_NO_SECCOMP=1`, `--link2symlink`, `--sysvipc`, `--ashmem-memfd`, `--kill-on-exit`).
- **Runtime State Machine & Service (`LinuxRuntimeEngine.kt`, `LinuxRuntimeService.kt`)**: Coordinates `Idle → Extracting → Booting → Ready` transitions via `StateFlow<RuntimeState>`, launches `start-desktop.sh` (`Xvnc :1` + `jwm`), polls readiness, executes commands/GUI apps, and parses `sysinfo.sh` JSON inside an Android `specialUse` foreground service.
- **Native C/JNI PTY Bridge (`app/src/main/cpp/pty_bridge.c`, `PtyBridge.kt`)**: Uses `forkpty()`, `O_NONBLOCK`, `poll()`, `TIOCSWINSZ`, and `waitpid()` to run an interactive `/bin/bash -l` shell inside PRoot.

## Milestone 3 (M3): Pure-Kotlin RFB 3.8 Client & SurfaceView Renderer

- **Wire Protocol (`RfbProtocol.kt`, `RfbModels.kt`)**: Implements RFC 6143 RFB 3.8 handshaking, `SecurityType.None`, Raw, CopyRect, Hextile, Zlib (persistent `Inflater`), DesktopSize, and ExtendedDesktopSize.
- **Framebuffer & Client Loop (`RfbFramebuffer.kt`, `RfbClient.kt`)**: Manages ARGB pixel storage with reusable `Bitmap` rendering and exposes `StateFlow<RfbConnectionState>` over `127.0.0.1:5901`.
- **Surface & Input Translation (`LinuxDesktopSurface.kt`, `DesktopInput.kt`, `KeySymMapper.kt`)**: Hosts a dedicated-thread `SurfaceView` renderer with Direct Tap and Trackpad modes plus Android `KeyEvent`/IME to X11 KeySym translation.

## Milestone 4 (M4): Jetpack Compose UI, Built-in Terminal, Automation & E2E

- **Jetpack Compose UI (`MainActivity.kt`, `Theme.kt`, `DesktopTab.kt`, `TerminalTab.kt`, `TerminalEmulator.kt`, `AppsSystemTab.kt`)**: Three-tab workspace (`Desktop`, `Terminal`, `Apps & System`) with `configChanges`-safe `MainActivity`, live telemetry header, VT100 terminal bound to `PtyBridge`, GUI app launchers, and ad-hoc Linux command execution.
- **Diagnostics & Automation (`AutomationReceiver.kt`, `DiagnosticsStore.kt`)**: Broadcast hooks for deterministic headless E2E verification of runtime status, command execution, GUI app launches, and non-blank RFB frames.
- **Automated E2E & Instrumentation Suite (`.github/workflows/e2e.yml`, `scripts/e2e/`, `app/src/androidTest/`)**: Dual-ABI static verifier, 4-tier ADB test harness, and on-device instrumentation tests across API 30 and API 34 emulators.
