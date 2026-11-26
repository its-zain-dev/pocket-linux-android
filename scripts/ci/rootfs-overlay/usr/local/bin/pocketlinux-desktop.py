#!/usr/bin/env python3
"""PocketLinux Windows-Style Interactive Desktop Canvas (GTK-3 + Cairo).

Features:
- Draws crisp desktop wallpaper (auto-reloads on xrandr / theme / wallpaper changes).
- Displays all Desktop shortcuts (/root/Desktop/*.desktop) plus files & folders.
- Smooth drag-and-drop repositioning of icons anywhere on the screen:
  * Snap-to-grid mode (default, swaps or snaps cleanly into slots) OR pixel-free placement.
  * Positions persisted to /root/.config/pocketlinux/desktop_layout.json.
  * Newly installed apps automatically claim the next open slot and appear immediately.
- Touch-friendly: single-tap launch (configurable to double-click), plus right-click
  Windows-style context menu (View size, Sort by Name/Date/Type, Auto-Arrange,
  New Folder, New File, Terminal, App Store, Download Manager, Personalize Settings).
- Instant live refresh via SIGUSR1 (sent by pocketlinux-sync-apps.py & pocketlinux-settings.py)
  plus lightweight 1.5s directory polling.
"""

import json
import math
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time

import cairo
import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("GdkPixbuf", "2.0")
gi.require_version("Pango", "1.0")
gi.require_version("PangoCairo", "1.0")
from gi.repository import Gdk, GdkPixbuf, GLib, Gtk, Pango, PangoCairo  # noqa: E402

DESKTOP_DIR = "/root/Desktop"
CONF_DIR = "/root/.config/pocketlinux"
PREFS_PATH = os.path.join(CONF_DIR, "desktop.json")
LAYOUT_PATH = os.path.join(CONF_DIR, "desktop_layout.json")
PID_FILE = "/tmp/.pocketlinux_desktop.pid"
DEFAULT_WALLPAPER = "/usr/share/backgrounds/pocketlinux-wallpaper.png"

DEFAULT_PREFS = {
    "show_icons": True,
    "icon_size": 48,
    "single_click": True,
    "snap_grid": True,
    "auto_arrange": False,
    "wallpaper": DEFAULT_WALLPAPER,
    "accent_color": "#0EA5E9",
    "taskbar_position": "bottom",
    "taskbar_height": 42,
    "auto_desktop_shortcuts": True,
}

# Preferred initial ordering for built-in shortcuts
BUILTIN_ORDER = [
    "store.desktop",
    "downloads.desktop",
    "settings.desktop",
    "terminal.desktop",
    "files.desktop",
    "editor.desktop",
    "calculator.desktop",
    "sysinfo.desktop",
]

DRAG_THRESHOLD = 10.0


def hex_to_rgb(color_hex: str) -> tuple[float, float, float]:
    s = (color_hex or "#0EA5E9").strip().lstrip("#")
    if len(s) != 6:
        return (0.055, 0.647, 0.914)
    try:
        return (
            int(s[0:2], 16) / 255.0,
            int(s[2:4], 16) / 255.0,
            int(s[4:6], 16) / 255.0,
        )
    except ValueError:
        return (0.055, 0.647, 0.914)


def parse_desktop_entry(path: str) -> dict | None:
    in_entry = False
    fields = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            for raw in f:
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                if line.startswith("["):
                    in_entry = (line == "[Desktop Entry]")
                    continue
                if not in_entry or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip()
                if k not in fields:
                    fields[k] = v.strip()
    except OSError:
        return None
    if fields.get("NoDisplay", "").lower() == "true" or fields.get("Hidden", "").lower() == "true":
        return None
    name = fields.get("Name", "").strip()
    exec_cmd = fields.get("Exec", "").strip()
    if not name:
        name = os.path.splitext(os.path.basename(path))[0]
    return {
        "name": name,
        "exec": exec_cmd,
        "icon": fields.get("Icon", "application-x-executable").strip() or "application-x-executable",
        "terminal": fields.get("Terminal", "false").lower() == "true",
        "comment": fields.get("Comment", "").strip(),
    }


def load_json_safe(path: str, default: dict) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
            if isinstance(data, dict):
                return data
    except Exception:
        pass
    return dict(default)


def save_json_atomic(path: str, data: dict) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)


class DesktopItem:
    def __init__(
        self,
        filename: str,
        full_path: str,
        name: str,
        icon_name: str,
        kind: str,
        exec_cmd: str = "",
        terminal: bool = False,
        mtime: float = 0.0,
    ) -> None:
        self.filename = filename
        self.full_path = full_path
        self.name = name
        self.icon_name = icon_name
        self.kind = kind  # "desktop" | "dir" | "file"
        self.exec_cmd = exec_cmd
        self.terminal = terminal
        self.mtime = mtime
        self.x = 16.0
        self.y = 16.0
        self.pixbuf: GdkPixbuf.Pixbuf | None = None


class PocketLinuxDesktopWindow(Gtk.Window):
    def __init__(self) -> None:
        super().__init__(type=Gtk.WindowType.TOPLEVEL)
        self.set_title("PocketLinux Desktop")
        self.set_wmclass("PocketLinuxDesktop", "PocketLinuxDesktop")
        self.set_type_hint(Gdk.WindowTypeHint.DESKTOP)
        self.set_decorated(False)
        self.set_resizable(False)
        self.set_skip_taskbar_hint(True)
        self.set_skip_pager_hint(True)
        self.set_keep_below(True)
        self.stick()

        self.prefs = dict(DEFAULT_PREFS)
        self.layout_positions: dict[str, dict] = {}
        self.items: list[DesktopItem] = []

        self.screen_w = 1024
        self.screen_h = 600
        self._bg_surface: cairo.ImageSurface | None = None
        self._bg_cache_key: tuple[str, int, int] | None = None
        self._icon_cache: dict[tuple[str, int], GdkPixbuf.Pixbuf | None] = {}

        # Interaction state
        self.selected_name: str | None = None
        self.hovered_name: str | None = None
        self.dragging_item: DesktopItem | None = None
        self.drag_started = False
        self.press_x = 0.0
        self.press_y = 0.0
        self.drag_offset_x = 0.0
        self.drag_offset_y = 0.0
        self.drag_cur_x = 0.0
        self.drag_cur_y = 0.0
        self._last_click_name: str | None = None
        self._last_click_time = 0.0

        # Change detection state for lightweight polling
        self._last_dir_sig: tuple | None = None
        self._last_prefs_mtime: float = -1.0
        self._last_layout_mtime: float = -1.0

        self.area = Gtk.DrawingArea()
        self.area.set_can_focus(True)
        self.area.add_events(
            Gdk.EventMask.BUTTON_PRESS_MASK
            | Gdk.EventMask.BUTTON_RELEASE_MASK
            | Gdk.EventMask.POINTER_MOTION_MASK
            | Gdk.EventMask.LEAVE_NOTIFY_MASK
            | Gdk.EventMask.KEY_PRESS_MASK
        )
        self.area.connect("draw", self._on_draw)
        self.area.connect("button-press-event", self._on_button_press)
        self.area.connect("button-release-event", self._on_button_release)
        self.area.connect("motion-notify-event", self._on_motion)
        self.area.connect("leave-notify-event", self._on_leave)
        self.area.connect("key-press-event", self._on_key_press)
        self.add(self.area)

        screen = Gdk.Screen.get_default()
        if screen is not None:
            screen.connect("size-changed", lambda *_: self._sync_screen_size())
            screen.connect("monitors-changed", lambda *_: self._sync_screen_size())

        self._load_prefs_and_layout()
        self._sync_screen_size()
        self._scan_desktop_items(force=True)

        # Lightweight background poll every 1.5s
        GLib.timeout_add(1500, self._poll_changes)

    # ---------- Geometry & Grid Metrics ----------

    def _cell_metrics(self) -> tuple[int, int, int]:
        icon_size = int(self.prefs.get("icon_size", 48))
        icon_size = max(32, min(72, icon_size))
        cell_w = icon_size + 46
        cell_h = icon_size + 48
        return icon_size, cell_w, cell_h

    def _workarea(self) -> tuple[int, int, int, int]:
        tb_h = int(self.prefs.get("taskbar_height", 42))
        tb_pos = str(self.prefs.get("taskbar_position", "bottom"))
        top = (tb_h + 10) if tb_pos == "top" else 12
        bottom = self.screen_h - (12 if tb_pos == "top" else (tb_h + 10))
        left = 14
        right = max(left + 120, self.screen_w - 14)
        bottom = max(top + 120, bottom)
        return left, top, right, bottom

    def _sync_screen_size(self) -> None:
        screen = Gdk.Screen.get_default()
        if screen is not None:
            w = max(320, screen.get_width())
            h = max(240, screen.get_height())
        else:
            w, h = 1024, 600
        self.screen_w = w
        self.screen_h = h
        self.move(0, 0)
        self.set_size_request(w, h)
        self.resize(w, h)
        self._bg_cache_key = None
        self._clamp_all_positions()
        self.area.queue_draw()

    def _grid_origin_and_step(self) -> tuple[int, int, int, int]:
        left, top, _, _ = self._workarea()
        _, cell_w, cell_h = self._cell_metrics()
        return left, top, cell_w + 8, cell_h + 8

    def _grid_slots(self) -> list[tuple[float, float]]:
        left, top, right, bottom = self._workarea()
        _, cell_w, cell_h = self._cell_metrics()
        step_x = cell_w + 8
        step_y = cell_h + 8
        cols = max(1, (right - left) // step_x)
        rows = max(1, (bottom - top) // step_y)
        slots: list[tuple[float, float]] = []
        # Windows-style: fill vertically top-to-bottom, then next column to the right
        for c in range(cols):
            for r in range(rows):
                slots.append((float(left + c * step_x), float(top + r * step_y)))
        return slots

    def _snap_to_grid(self, x: float, y: float) -> tuple[float, float]:
        left, top, right, bottom = self._workarea()
        _, cell_w, cell_h = self._cell_metrics()
        step_x = cell_w + 8
        step_y = cell_h + 8
        col = max(0, round((x - left) / step_x))
        row = max(0, round((y - top) / step_y))
        max_col = max(0, (right - left - cell_w) // step_x)
        max_row = max(0, (bottom - top - cell_h) // step_y)
        col = min(col, max_col)
        row = min(row, max_row)
        return float(left + col * step_x), float(top + row * step_y)

    def _clamp_pos(self, x: float, y: float) -> tuple[float, float]:
        left, top, right, bottom = self._workarea()
        _, cell_w, cell_h = self._cell_metrics()
        cx = max(float(left), min(float(max(left, right - cell_w)), x))
        cy = max(float(top), min(float(max(top, bottom - cell_h)), y))
        return cx, cy

    def _clamp_all_positions(self) -> None:
        for it in self.items:
            it.x, it.y = self._clamp_pos(it.x, it.y)

    # ---------- Config, Layout & Icon Loading ----------

    def _load_prefs_and_layout(self) -> None:
        merged = dict(DEFAULT_PREFS)
        merged.update(load_json_safe(PREFS_PATH, {}))
        self.prefs = merged
        self.layout_positions = load_json_safe(LAYOUT_PATH, {})
        try:
            self._last_prefs_mtime = os.path.getmtime(PREFS_PATH)
        except OSError:
            self._last_prefs_mtime = -1.0
        try:
            self._last_layout_mtime = os.path.getmtime(LAYOUT_PATH)
        except OSError:
            self._last_layout_mtime = -1.0

    def _save_prefs(self) -> None:
        save_json_atomic(PREFS_PATH, self.prefs)
        try:
            self._last_prefs_mtime = os.path.getmtime(PREFS_PATH)
        except OSError:
            pass

    def _save_layout(self) -> None:
        data = {it.filename: {"x": int(round(it.x)), "y": int(round(it.y))} for it in self.items}
        self.layout_positions = data
        save_json_atomic(LAYOUT_PATH, data)
        try:
            self._last_layout_mtime = os.path.getmtime(LAYOUT_PATH)
        except OSError:
            pass

    def _load_icon_pixbuf(self, icon_name: str, size: int) -> GdkPixbuf.Pixbuf | None:
        key = (icon_name, size)
        if key in self._icon_cache:
            return self._icon_cache[key]

        pb: GdkPixbuf.Pixbuf | None = None
        theme = Gtk.IconTheme.get_default()

        # Direct path to image file
        if icon_name and os.path.isfile(icon_name):
            try:
                pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(icon_name, size, size, True)
            except Exception:
                pb = None

        # Named icon lookup in GTK icon theme (with sensible fallbacks)
        if pb is None and theme is not None:
            candidates = [
                icon_name,
                os.path.splitext(os.path.basename(icon_name))[0] if icon_name else "",
                "application-x-executable",
                "system-run",
                "text-x-generic",
            ]
            for cand in candidates:
                if not cand:
                    continue
                try:
                    if theme.has_icon(cand):
                        pb = theme.load_icon(cand, size, Gtk.IconLookupFlags.FORCE_SIZE)
                        if pb is not None:
                            break
                except Exception:
                    continue

        self._icon_cache[key] = pb
        return pb

    def _ensure_wallpaper_surface(self) -> cairo.ImageSurface:
        wp_path = str(self.prefs.get("wallpaper") or DEFAULT_WALLPAPER)
        key = (wp_path, self.screen_w, self.screen_h)
        if self._bg_surface is not None and self._bg_cache_key == key:
            return self._bg_surface

        surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, self.screen_w, self.screen_h)
        cr = cairo.Context(surf)

        drawn = False
        if wp_path and os.path.isfile(wp_path):
            try:
                pb = GdkPixbuf.Pixbuf.new_from_file_at_scale(
                    wp_path, self.screen_w, self.screen_h, False
                )
                Gdk.cairo_set_source_pixbuf(cr, pb, 0, 0)
                cr.paint()
                drawn = True
            except Exception:
                drawn = False

        if not drawn:
            pat = cairo.LinearGradient(0, 0, self.screen_w, self.screen_h)
            pat.add_color_stop_rgb(0.0, 0.043, 0.067, 0.125)
            pat.add_color_stop_rgb(0.55, 0.075, 0.114, 0.212)
            pat.add_color_stop_rgb(1.0, 0.039, 0.098, 0.176)
            cr.set_source(pat)
            cr.paint()

        self._bg_surface = surf
        self._bg_cache_key = key
        return surf

    def _dir_signature(self) -> tuple:
        if not os.path.isdir(DESKTOP_DIR):
            return ()
        entries = []
        try:
            for fn in sorted(os.listdir(DESKTOP_DIR)):
                if fn.startswith("."):
                    continue
                p = os.path.join(DESKTOP_DIR, fn)
                try:
                    st = os.stat(p)
                    entries.append((fn, int(st.st_mtime), st.st_size))
                except OSError:
                    pass
        except OSError:
            pass
        return tuple(entries)

    def _scan_desktop_items(self, force: bool = False) -> None:
        os.makedirs(DESKTOP_DIR, exist_ok=True)
        sig = self._dir_signature()
        if not force and sig == self._last_dir_sig:
            return
        self._last_dir_sig = sig

        icon_size, cell_w, cell_h = self._cell_metrics()
        discovered: list[DesktopItem] = []

        for fn in sorted(os.listdir(DESKTOP_DIR)):
            if fn.startswith("."):
                continue
            full_path = os.path.join(DESKTOP_DIR, fn)
            try:
                st = os.stat(full_path)
                mtime = st.st_mtime
            except OSError:
                continue

            if fn.endswith(".desktop") and os.path.isfile(full_path):
                parsed = parse_desktop_entry(full_path)
                if not parsed:
                    continue
                item = DesktopItem(
                    filename=fn,
                    full_path=full_path,
                    name=parsed["name"],
                    icon_name=parsed["icon"],
                    kind="desktop",
                    exec_cmd=parsed["exec"],
                    terminal=parsed["terminal"],
                    mtime=mtime,
                )
            elif os.path.isdir(full_path):
                item = DesktopItem(
                    filename=fn,
                    full_path=full_path,
                    name=fn,
                    icon_name="folder",
                    kind="dir",
                    mtime=mtime,
                )
            else:
                icon = "text-x-generic"
                low = fn.lower()
                if low.endswith((".png", ".jpg", ".jpeg", ".webp", ".gif")):
                    icon = "image-x-generic"
                elif low.endswith((".mp4", ".mkv", ".webm", ".mp3", ".wav")):
                    icon = "video-x-generic"
                elif low.endswith((".zip", ".tar", ".gz", ".xz", ".7z")):
                    icon = "package-x-generic"
                elif low.endswith((".sh", ".py", ".c", ".cpp", ".js")):
                    icon = "text-x-script"
                item = DesktopItem(
                    filename=fn,
                    full_path=full_path,
                    name=fn,
                    icon_name=icon,
                    kind="file",
                    mtime=mtime,
                )

            item.pixbuf = self._load_icon_pixbuf(item.icon_name, icon_size)
            discovered.append(item)

        # Sort initially so built-in apps stay in a clean predictable column, followed by newly installed apps
        def initial_sort_key(it: DesktopItem) -> tuple:
            if it.filename in BUILTIN_ORDER:
                return (0, BUILTIN_ORDER.index(it.filename), it.name.lower())
            if it.filename.startswith("pl-app-"):
                return (1, 0, it.name.lower())
            if it.kind == "dir":
                return (2, 0, it.name.lower())
            return (3, 0, it.name.lower())

        discovered.sort(key=initial_sort_key)

        if self.prefs.get("auto_arrange", False):
            slots = self._grid_slots()
            for idx, it in enumerate(discovered):
                if idx < len(slots):
                    it.x, it.y = slots[idx]
                else:
                    it.x, it.y = self._clamp_pos(16.0 + idx * 12, 16.0)
            self.items = discovered
            self._save_layout()
        else:
            occupied: list[tuple[float, float]] = []
            unplaced: list[DesktopItem] = []

            for it in discovered:
                saved = self.layout_positions.get(it.filename)
                if isinstance(saved, dict) and "x" in saved and "y" in saved:
                    x, y = self._clamp_pos(float(saved["x"]), float(saved["y"]))
                    it.x, it.y = x, y
                    occupied.append((x, y))
                else:
                    unplaced.append(it)

            if unplaced:
                slots = self._grid_slots()

                def is_free(sx: float, sy: float) -> bool:
                    for ox, oy in occupied:
                        if abs(ox - sx) < cell_w * 0.65 and abs(oy - sy) < cell_h * 0.65:
                            return False
                    return True

                for it in unplaced:
                    chosen = None
                    for sx, sy in slots:
                        if is_free(sx, sy):
                            chosen = (sx, sy)
                            break
                    if chosen is None:
                        left, top, _, _ = self._workarea()
                        chosen = (float(left + 20), float(top + 20))
                    it.x, it.y = chosen
                    occupied.append(chosen)
                self.items = discovered
                self._save_layout()
            else:
                self.items = discovered

        self.area.queue_draw()

    def reload_all(self) -> bool:
        self._load_prefs_and_layout()
        self._icon_cache.clear()
        self._bg_cache_key = None
        self._scan_desktop_items(force=True)
        self.area.queue_draw()
        return False

    def _poll_changes(self) -> bool:
        prefs_changed = False
        layout_changed = False
        try:
            mt = os.path.getmtime(PREFS_PATH)
            if mt != self._last_prefs_mtime:
                prefs_changed = True
        except OSError:
            pass
        try:
            lmt = os.path.getmtime(LAYOUT_PATH)
            if lmt != self._last_layout_mtime:
                layout_changed = True
        except OSError:
            pass

        if prefs_changed or layout_changed:
            self.reload_all()
        else:
            self._scan_desktop_items(force=False)
        return True

    # ---------- Sorting & Auto-Arrange ----------

    def _arrange_items(self, mode: str = "auto") -> None:
        if mode == "name":
            self.items.sort(key=lambda it: it.name.lower())
        elif mode == "date":
            self.items.sort(key=lambda it: it.mtime, reverse=True)
        elif mode == "type":
            order = {"desktop": 0, "dir": 1, "file": 2}
            self.items.sort(key=lambda it: (order.get(it.kind, 3), it.name.lower()))
        else:
            def auto_key(it: DesktopItem) -> tuple:
                if it.filename in BUILTIN_ORDER:
                    return (0, BUILTIN_ORDER.index(it.filename), it.name.lower())
                if it.filename.startswith("pl-app-"):
                    return (1, 0, it.name.lower())
                return (2, 0, it.name.lower())
            self.items.sort(key=auto_key)

        slots = self._grid_slots()
        for idx, it in enumerate(self.items):
            if idx < len(slots):
                it.x, it.y = slots[idx]
            else:
                it.x, it.y = self._clamp_pos(16.0 + idx * 10, 16.0)
        self._save_layout()
        self.area.queue_draw()

    # ---------- Drawing ----------

    def _draw_rounded_rect(self, cr: cairo.Context, x: float, y: float, w: float, h: float, r: float) -> None:
        cr.new_sub_path()
        cr.arc(x + w - r, y + r, r, -math.pi / 2, 0)
        cr.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
        cr.arc(x + r, y + h - r, r, math.pi / 2, math.pi)
        cr.arc(x + r, y + r, r, math.pi, 3 * math.pi / 2)
        cr.close_path()

    def _draw_item(
        self,
        cr: cairo.Context,
        item: DesktopItem,
        x: float,
        y: float,
        icon_size: int,
        cell_w: int,
        cell_h: int,
        selected: bool,
        hovered: bool,
        dragging: bool,
    ) -> None:
        ar, ag, ab = hex_to_rgb(str(self.prefs.get("accent_color", "#0EA5E9")))

        if dragging:
            self._draw_rounded_rect(cr, x, y, cell_w, cell_h, 12)
            cr.set_source_rgba(ar, ag, ab, 0.34)
            cr.fill_preserve()
            cr.set_source_rgba(ar, ag, ab, 0.95)
            cr.set_line_width(1.8)
            cr.stroke()
        elif selected:
            self._draw_rounded_rect(cr, x, y, cell_w, cell_h, 10)
            cr.set_source_rgba(ar, ag, ab, 0.28)
            cr.fill_preserve()
            cr.set_source_rgba(ar, ag, ab, 0.85)
            cr.set_line_width(1.4)
            cr.stroke()
        elif hovered:
            self._draw_rounded_rect(cr, x, y, cell_w, cell_h, 10)
            cr.set_source_rgba(1.0, 1.0, 1.0, 0.11)
            cr.fill_preserve()
            cr.set_source_rgba(1.0, 1.0, 1.0, 0.22)
            cr.set_line_width(1.0)
            cr.stroke()

        # Icon pixbuf centered horizontally
        ix = x + (cell_w - icon_size) / 2.0
        iy = y + 8.0
        if item.pixbuf is not None:
            cr.save()
            pw = item.pixbuf.get_width()
            ph = item.pixbuf.get_height()
            dx = ix + (icon_size - pw) / 2.0
            dy = iy + (icon_size - ph) / 2.0
            Gdk.cairo_set_source_pixbuf(cr, item.pixbuf, dx, dy)
            cr.paint_with_alpha(0.92 if dragging else 1.0)
            cr.restore()
        else:
            # Crisp vector fallback badge
            self._draw_rounded_rect(cr, ix, iy, icon_size, icon_size, 10)
            cr.set_source_rgba(ar, ag, ab, 0.85)
            cr.fill()

        # Wrapped 2-line centered label with drop shadow
        layout = PangoCairo.create_layout(cr)
        font_size = 9 if icon_size <= 40 else (10 if icon_size <= 54 else 11)
        layout.set_font_description(Pango.FontDescription(f"DejaVu Sans {font_size}"))
        layout.set_alignment(Pango.Alignment.CENTER)
        layout.set_width(int((cell_w - 8) * Pango.SCALE))
        layout.set_wrap(Pango.WrapMode.WORD_CHAR)
        layout.set_ellipsize(Pango.EllipsizeMode.END)
        layout.set_height(-2)  # max 2 lines
        layout.set_text(item.name, -1)

        tx = x + 4.0
        ty = iy + icon_size + 5.0

        # Shadow
        cr.set_source_rgba(0.0, 0.0, 0.0, 0.88)
        cr.move_to(tx + 1.0, ty + 1.0)
        PangoCairo.show_layout(cr, layout)

        # Foreground label
        cr.set_source_rgba(0.97, 0.98, 1.0, 1.0)
        cr.move_to(tx, ty)
        PangoCairo.show_layout(cr, layout)

    def _on_draw(self, _widget: Gtk.Widget, cr: cairo.Context) -> bool:
        bg = self._ensure_wallpaper_surface()
        cr.set_source_surface(bg, 0, 0)
        cr.paint()

        if not self.prefs.get("show_icons", True):
            return False

        icon_size, cell_w, cell_h = self._cell_metrics()
        ar, ag, ab = hex_to_rgb(str(self.prefs.get("accent_color", "#0EA5E9")))

        # Draw ghost grid target while dragging
        if self.dragging_item is not None and self.drag_started:
            gx, gy = self.drag_cur_x, self.drag_cur_y
            if self.prefs.get("snap_grid", True):
                gx, gy = self._snap_to_grid(gx, gy)
            else:
                gx, gy = self._clamp_pos(gx, gy)
            self._draw_rounded_rect(cr, gx, gy, cell_w, cell_h, 10)
            cr.set_source_rgba(ar, ag, ab, 0.14)
            cr.fill_preserve()
            cr.set_dash([5.0, 4.0])
            cr.set_source_rgba(ar, ag, ab, 0.72)
            cr.set_line_width(1.5)
            cr.stroke()
            cr.set_dash([])

        # Draw all non-dragged items
        for it in self.items:
            if it is self.dragging_item and self.drag_started:
                continue
            self._draw_item(
                cr,
                it,
                it.x,
                it.y,
                icon_size,
                cell_w,
                cell_h,
                selected=(it.filename == self.selected_name),
                hovered=(it.filename == self.hovered_name),
                dragging=False,
            )

        # Draw actively dragged item on top
        if self.dragging_item is not None and self.drag_started:
            self._draw_item(
                cr,
                self.dragging_item,
                self.drag_cur_x,
                self.drag_cur_y,
                icon_size,
                cell_w,
                cell_h,
                selected=True,
                hovered=False,
                dragging=True,
            )

        return False

    # ---------- Input Handling ----------

    def _hit_test(self, px: float, py: float) -> DesktopItem | None:
        if not self.prefs.get("show_icons", True):
            return None
        _, cell_w, cell_h = self._cell_metrics()
        for it in reversed(self.items):
            if it.x <= px <= (it.x + cell_w) and it.y <= py <= (it.y + cell_h):
                return it
        return None

    def _on_button_press(self, _widget: Gtk.Widget, event: Gdk.EventButton) -> bool:
        self.area.grab_focus()
        hit = self._hit_test(event.x, event.y)

        if event.button == 3:
            self.selected_name = hit.filename if hit else None
            self.area.queue_draw()
            self._show_context_menu(event, hit)
            return True

        if event.button == 1:
            self.press_x = event.x
            self.press_y = event.y
            self.drag_started = False
            if hit is not None:
                self.selected_name = hit.filename
                self.dragging_item = hit
                self.drag_offset_x = event.x - hit.x
                self.drag_offset_y = event.y - hit.y
                self.drag_cur_x = hit.x
                self.drag_cur_y = hit.y
            else:
                self.selected_name = None
                self.dragging_item = None
            self.area.queue_draw()
            return True

        return False

    def _on_motion(self, _widget: Gtk.Widget, event: Gdk.EventMotion) -> bool:
        if self.dragging_item is not None:
            dist = math.hypot(event.x - self.press_x, event.y - self.press_y)
            if not self.drag_started and dist >= DRAG_THRESHOLD:
                self.drag_started = True
            if self.drag_started:
                nx, ny = self._clamp_pos(
                    event.x - self.drag_offset_x,
                    event.y - self.drag_offset_y,
                )
                self.drag_cur_x = nx
                self.drag_cur_y = ny
                self.area.queue_draw()
            return True

        hit = self._hit_test(event.x, event.y)
        new_hover = hit.filename if hit else None
        if new_hover != self.hovered_name:
            self.hovered_name = new_hover
            self.area.queue_draw()
        return False

    def _on_leave(self, _widget: Gtk.Widget, _event: Gdk.EventCrossing) -> bool:
        if self.hovered_name is not None:
            self.hovered_name = None
            self.area.queue_draw()
        return False

    def _on_button_release(self, _widget: Gtk.Widget, event: Gdk.EventButton) -> bool:
        if event.button != 1:
            return False

        item = self.dragging_item
        was_drag = self.drag_started
        self.dragging_item = None
        self.drag_started = False

        if item is None:
            return False

        if was_drag:
            old_x, old_y = item.x, item.y
            if self.prefs.get("snap_grid", True):
                nx, ny = self._snap_to_grid(self.drag_cur_x, self.drag_cur_y)
                # Swap cleanly if dropped directly on another icon's snapped slot
                for other in self.items:
                    if other is not item and abs(other.x - nx) < 6 and abs(other.y - ny) < 6:
                        other.x, other.y = old_x, old_y
                        break
                item.x, item.y = nx, ny
            else:
                item.x, item.y = self._clamp_pos(self.drag_cur_x, self.drag_cur_y)
            self._save_layout()
            self.area.queue_draw()
            return True

        # Click / Tap (not drag)
        now = time.monotonic()
        if self.prefs.get("single_click", True):
            self._launch_item(item)
        else:
            if self._last_click_name == item.filename and (now - self._last_click_time) <= 0.65:
                self._last_click_name = None
                self._launch_item(item)
            else:
                self._last_click_name = item.filename
                self._last_click_time = now
        self.area.queue_draw()
        return True

    def _on_key_press(self, _widget: Gtk.Widget, event: Gdk.EventKey) -> bool:
        if event.keyval in (Gdk.KEY_Return, Gdk.KEY_KP_Enter) and self.selected_name:
            for it in self.items:
                if it.filename == self.selected_name:
                    self._launch_item(it)
                    return True
        elif event.keyval == Gdk.KEY_F5:
            self.reload_all()
            return True
        return False

    # ---------- App Launching ----------

    def _spawn(self, shell_cmd: str) -> None:
        env = os.environ.copy()
        env.setdefault("DISPLAY", ":1")
        subprocess.Popen(
            ["/bin/sh", "-c", f". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; {shell_cmd}"],
            env=env,
            start_new_session=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def _launch_item(self, item: DesktopItem) -> None:
        if item.kind == "desktop" and item.exec_cmd:
            clean = re.sub(r"\s+%[fFuUdDnNickvm]", "", item.exec_cmd).strip()
            if item.terminal:
                cmd = f"xterm -fa 'DejaVu Sans Mono' -fs 12 -bg '#0F172A' -fg '#E2E8F0' -e {clean}"
            else:
                cmd = clean
            self._spawn(cmd)
        elif item.kind == "dir":
            self._spawn(f"pcmanfm {shlex.quote(item.full_path)}")
        else:
            low = item.filename.lower()
            if low.endswith((".txt", ".md", ".py", ".sh", ".json", ".conf", ".log", ".c", ".h")):
                self._spawn(f"mousepad {shlex.quote(item.full_path)}")
            elif low.endswith((".png", ".jpg", ".jpeg", ".bmp", ".gif")):
                self._spawn(f"feh {shlex.quote(item.full_path)}")
            else:
                self._spawn(f"xdg-open {shlex.quote(item.full_path)}")

    # ---------- Context Menu (Windows Style) ----------

    def _add_menu_item(self, menu: Gtk.Menu, label: str, callback) -> Gtk.MenuItem:
        mi = Gtk.MenuItem(label=label)
        mi.connect("activate", lambda *_: callback())
        menu.append(mi)
        return mi

    def _add_check_item(self, menu: Gtk.Menu, label: str, active: bool, callback) -> Gtk.CheckMenuItem:
        ci = Gtk.CheckMenuItem(label=label)
        ci.set_active(active)
        ci.connect("toggled", lambda w: callback(w.get_active()))
        menu.append(ci)
        return ci

    def _show_context_menu(self, event: Gdk.EventButton, hit: DesktopItem | None) -> None:
        menu = Gtk.Menu()

        if hit is not None:
            self._add_menu_item(menu, f"Open  {hit.name}", lambda: self._launch_item(hit))
            self._add_menu_item(menu, "Open File Manager (Desktop)", lambda: self._spawn("pcmanfm /root/Desktop"))
            menu.append(Gtk.SeparatorMenuItem())
            if hit.filename not in BUILTIN_ORDER:
                self._add_menu_item(menu, "Remove from Desktop", lambda: self._remove_item(hit))
                menu.append(Gtk.SeparatorMenuItem())

        # View submenu
        view_sub = Gtk.Menu()
        cur_sz = int(self.prefs.get("icon_size", 48))
        self._add_check_item(view_sub, "Large icons (64px)", cur_sz >= 60, lambda v: v and self._set_pref("icon_size", 64))
        self._add_check_item(view_sub, "Medium icons (48px)", 42 <= cur_sz < 60, lambda v: v and self._set_pref("icon_size", 48))
        self._add_check_item(view_sub, "Small icons (36px)", cur_sz < 42, lambda v: v and self._set_pref("icon_size", 36))
        view_sub.append(Gtk.SeparatorMenuItem())
        self._add_check_item(
            view_sub,
            "Auto arrange icons",
            bool(self.prefs.get("auto_arrange", False)),
            lambda v: self._set_pref("auto_arrange", v),
        )
        self._add_check_item(
            view_sub,
            "Align icons to grid",
            bool(self.prefs.get("snap_grid", True)),
            lambda v: self._set_pref("snap_grid", v),
        )
        self._add_check_item(
            view_sub,
            "Show desktop icons",
            bool(self.prefs.get("show_icons", True)),
            lambda v: self._set_pref("show_icons", v),
        )
        self._add_check_item(
            view_sub,
            "Single-tap to open",
            bool(self.prefs.get("single_click", True)),
            lambda v: self._set_pref("single_click", v),
        )
        view_mi = Gtk.MenuItem(label="View")
        view_mi.set_submenu(view_sub)
        menu.append(view_mi)

        # Sort by submenu
        sort_sub = Gtk.Menu()
        self._add_menu_item(sort_sub, "Name (A - Z)", lambda: self._arrange_items("name"))
        self._add_menu_item(sort_sub, "Date modified", lambda: self._arrange_items("date"))
        self._add_menu_item(sort_sub, "Type (Apps first)", lambda: self._arrange_items("type"))
        self._add_menu_item(sort_sub, "Default Windows Grid", lambda: self._arrange_items("auto"))
        sort_mi = Gtk.MenuItem(label="Sort by")
        sort_mi.set_submenu(sort_sub)
        menu.append(sort_mi)

        self._add_menu_item(menu, "Refresh", self.reload_all)
        menu.append(Gtk.SeparatorMenuItem())

        # New submenu
        new_sub = Gtk.Menu()
        self._add_menu_item(new_sub, "Folder", lambda: self._create_new_entry(is_dir=True))
        self._add_menu_item(new_sub, "Text Document", lambda: self._create_new_entry(is_dir=False))
        new_mi = Gtk.MenuItem(label="New")
        new_mi.set_submenu(new_sub)
        menu.append(new_mi)

        menu.append(Gtk.SeparatorMenuItem())
        self._add_menu_item(menu, "Open Terminal Here", lambda: self._spawn("cd /root/Desktop && xterm -fa 'DejaVu Sans Mono' -fs 12 -bg '#0F172A' -fg '#E2E8F0'"))
        self._add_menu_item(menu, "Open App Store", lambda: self._spawn("/usr/local/bin/pocketlinux-store.py"))
        self._add_menu_item(menu, "Open Download Manager", lambda: self._spawn("/usr/local/bin/pocketlinux-downloads.py"))
        self._add_menu_item(menu, "Personalize & Settings", lambda: self._spawn("/usr/local/bin/pocketlinux-settings.py --page personalization"))

        menu.show_all()
        menu.popup_at_pointer(event)

    def _set_pref(self, key: str, value) -> None:
        self.prefs[key] = value
        self._save_prefs()
        if key == "icon_size":
            self._icon_cache.clear()
            self._scan_desktop_items(force=True)
        elif key == "auto_arrange" and value:
            self._arrange_items("auto")
        else:
            self.area.queue_draw()

    def _remove_item(self, item: DesktopItem) -> None:
        try:
            if os.path.isdir(item.full_path) and not os.path.islink(item.full_path):
                shutil.rmtree(item.full_path)
            else:
                os.remove(item.full_path)
        except OSError:
            pass
        self._scan_desktop_items(force=True)

    def _create_new_entry(self, is_dir: bool) -> None:
        base = "New Folder" if is_dir else "New Note.txt"
        target = os.path.join(DESKTOP_DIR, base)
        idx = 2
        while os.path.exists(target):
            if is_dir:
                target = os.path.join(DESKTOP_DIR, f"New Folder {idx}")
            else:
                target = os.path.join(DESKTOP_DIR, f"New Note {idx}.txt")
            idx += 1
        try:
            if is_dir:
                os.makedirs(target, exist_ok=True)
            else:
                with open(target, "w", encoding="utf-8") as f:
                    f.write("")
        except OSError:
            pass
        self._scan_desktop_items(force=True)


def main() -> None:
    # Replace any stale desktop canvas instance cleanly
    if os.path.isfile(PID_FILE):
        try:
            old_pid = int(open(PID_FILE, encoding="utf-8").read().strip())
            if old_pid != os.getpid():
                os.kill(old_pid, signal.SIGTERM)
                time.sleep(0.15)
        except Exception:
            pass

    with open(PID_FILE, "w", encoding="utf-8") as f:
        f.write(str(os.getpid()) + "\n")

    win = PocketLinuxDesktopWindow()
    win.connect("destroy", Gtk.main_quit)

    def on_sigusr1(_signum, _frame) -> None:
        GLib.idle_add(win.reload_all)

    signal.signal(signal.SIGUSR1, on_sigusr1)
    signal.signal(signal.SIGTERM, lambda *_: GLib.idle_add(Gtk.main_quit))

    win.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
