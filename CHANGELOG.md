# Changelog

All notable changes to **PocketLinux for Android** are documented in this file.

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
