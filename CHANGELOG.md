# Changelog

All notable changes to **PocketLinux for Android** are documented in this file.

## [1.1.0] - 2026-11-26

### Performance
- **Zero-Lag Pointer & Touch Engine**: Added FIFO-exact pointer motion coalescing in `RfbClient`, velocity-based trackpad acceleration (`1.6x` base + smooth flick boost), 120Hz hardware-canvas drag cursor feedback, and zero-allocation gesture loops.
- **Dirty-Rect & Loopback VNC Pipeline**: Prioritized `CopyRect` + `Raw` over `127.0.0.1` loopback (`-CompareFB 0 -ZlibLevel 0 -ImprovedHextile 0 -FrameRate 90`), added dirty-region bounding box tracking in `RfbFramebuffer` so `SurfaceView` uploads only modified rectangles instead of full-screen textures, batched multi-row RFB decoding, and pipelined `FramebufferUpdateRequest`s.
- **Instant App Launches & PRoot Acceleration**: Enabled PRoot `seccomp-BPF` syscall acceleration by default, eliminated 3–10s GTK/GIO/D-Bus/GVFS/AT-SPI timeouts (`GIO_USE_VFS=local`, `GSETTINGS_BACKEND=memory`, `NO_AT_BRIDGE=1`, shared `/tmp/.pocketlinux_dbus_address`), disabled GTK3 software animations, and pre-built `Adwaita`/`hicolor` `mmap` icon caches, `gdk-pixbuf` loaders, GLib schemas, and `.pyc` bytecode at rootfs build time.
- **Eliminated Background CPU Stalls**: Rewrote `sysinfo.sh` in pure POSIX `sh + awk` (removing 1.5s Python startup under PRoot), stopped 4-second background `proot` telemetry polling while using the fullscreen desktop, and switched Android memory sampling to fast `/proc/self/statm` reads.
- **Crisp Mobile Resolution & Window Dragging**: Capped auto-desktop sizing to `1280x800` (2.3x fewer software X11 pixels to rasterize, GPU-scaled via `HardwareCanvas`), switched JWM window move/resize to instant `outline` mode, and enabled `GALLIUM_THREAD=true` + dynamic `nproc` threads for Mesa LLVMpipe.

### Fixed
- Fixed `glxgears` failing with `Error: couldn't get an RGB, Double-buffered visual` by bundling `mesa-gl`, `mesa-dri-gallium`, and trimmed `mesa-demos` (`glxgears`/`glxinfo`), starting `Xvnc` with `+extension GLX`, and setting `LIBGL_ALWAYS_SOFTWARE=1` when VirGL is inactive.
- Fixed E2E emulator workflow script multiline expansion (`scripts/e2e/ci-emulator-run.sh`) and aligned `release.yml` APK size verification bound (`--max-size-mb 180`).

## [1.0.1] - 2026-11-26

### Fixed
- `glxgears` failed with `Error: couldn't get an RGB, Double-buffered visual`. The rootfs now bundles `mesa-gl`, `mesa-dri-gallium` and `mesa-demos`, `Xvnc` starts with `+extension GLX`, and `LIBGL_ALWAYS_SOFTWARE=1` is set when VirGL is not active.

## [1.0.0] - 2026-11-23

Major v1.0 release transforming PocketLinux into a polished, mobile-first, rootless Alpine Linux 3.20 desktop and terminal environment for Android.

### Added
- **Full Mobile Keyboard & IME Support**:
  - Added dedicated `⌨ Keyboard` toggle button on the Desktop tab wired to `InputMethodManager.showSoftInput()` + `DesktopImeInputBridge` (fixing Gboard/Samsung Keyboard typing, Enter, Backspace, and composing text inside `xterm`, `mousepad`, and all X11 apps).
  - Upgraded `TerminalTab` with tap-anywhere-to-open-keyboard focus, `imePadding()`, bottom command bar, and expanded extra-keys bar (`Ctrl`, `Tab`, `Esc`, arrows, `Ctrl+C`, `Ctrl+D`, `/`, `-`, `|`).
- **Modern Material 3 UI & Real Linux Desktop**:
  - Redesigned `Theme.kt`, `MainActivity.kt`, `DesktopTab.kt`, `TerminalTab.kt`, and `AppsSystemTab.kt` with sleek obsidian dark cards, glowing telemetry pills, animated boot/extract overlay, and a 1-tap **Quick App Dock** right on the Desktop tab (no right-click required).
  - Added a generated dark-gradient wallpaper, clickable `.desktop` shortcuts via `pcmanfm --desktop`, and a touch-friendly 42px JWM bottom taskbar.
  - Added automatic debounced desktop resolution resizing (`computeAutoDesktopSize`) and `android:configChanges` rotation preservation.
- **Working File Manager & Storage Integration**:
  - Pre-populated `/root/{Desktop,Documents,Downloads,Projects}` with sample files and scripts, configured GTK-3 dark theme (`settings.ini`) and sidebar bookmarks (`bookmarks`), and linked `/root/SharedStorage` (`/mnt/sdcard`) and `/root/PhoneStorage` (`/mnt/phone`).
- **Full Linux & ELF Binary Compatibility**:
  - Bundled `gcompat` (glibc compatibility layer), `patchelf`, `file`, `nano`, `htop`, `xdg-utils`, `desktop-file-utils`, `shared-mime-info`, and `font-noto`.
  - Added SELinux-safe fake `/proc` files (`stat`, `uptime`, `version`, `loadavg`, `vmstat`) in `ProotCommandBuilder.kt`.
  - Added `/usr/local/bin/linux-health.sh` and `/usr/local/bin/run-elf.sh` plus `ElfAndLinuxFullVerificationTest.kt` E2E verification.
