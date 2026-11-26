#!/usr/bin/env python3
"""PocketLinux Windows-11-Inspired Settings & Personalization Center (GTK-3 for Alpine 3.20)."""

import json
import os
import signal
import socket
import subprocess
import sys
import threading
import urllib.request

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk  # noqa: E402

CONF_DIR = "/root/.config/pocketlinux"
GPU_ENV_PATH = os.path.join(CONF_DIR, "gpu.env")
DESKTOP_PREFS_PATH = os.path.join(CONF_DIR, "desktop.json")
DESKTOP_LAYOUT_PATH = os.path.join(CONF_DIR, "desktop_layout.json")
DOWNLOADS_CONF_PATH = os.path.join(CONF_DIR, "downloads.json")
INSTALLED_MANIFEST_PATH = os.path.join(CONF_DIR, "installed_apps.json")
NET_MODE_PATH = os.path.join(CONF_DIR, "network_mode")
NET_ENV_PATH = os.path.join(CONF_DIR, "network.env")
PRIVACY_FLAG_PATH = os.path.join(CONF_DIR, "privacy_hide_phone")
JWMRC_THEME_PATH = "/root/.jwmrc-theme"
DESKTOP_PID_FILE = "/tmp/.pocketlinux_desktop.pid"
VIRGL_LOG_PATH = "/tmp/.virgl_ipc/virgl_server.log"
PKGD_BASE = "http://127.0.0.1:18765"

ACCENT_PRESETS = [
    ("Windows Sky", "#0EA5E9", "#0284C7", "#38BDF8"),
    ("Win11 Cobalt", "#2563EB", "#1D4ED8", "#60A5FA"),
    ("Emerald", "#10B981", "#059669", "#34D399"),
    ("Royal Violet", "#8B5CF6", "#7C3AED", "#A78BFA"),
    ("Solar Amber", "#F59E0B", "#D97706", "#FBBF24"),
    ("Crimson", "#EF4444", "#DC2626", "#F87171"),
]

WALLPAPER_PRESETS = [
    ("Obsidian Blue (Default)", "/usr/share/backgrounds/pocketlinux-wallpaper.png"),
    ("Midnight Aurora", "/usr/share/backgrounds/wallpaper-aurora.png"),
    ("Emerald Dusk", "/usr/share/backgrounds/wallpaper-emerald.png"),
    ("Warm Sunset", "/usr/share/backgrounds/wallpaper-sunset.png"),
]

DEFAULT_DESKTOP_PREFS = {
    "show_icons": True,
    "icon_size": 48,
    "single_click": True,
    "snap_grid": True,
    "auto_arrange": False,
    "wallpaper": "/usr/share/backgrounds/pocketlinux-wallpaper.png",
    "accent_color": "#0EA5E9",
    "accent_dark": "#0284C7",
    "accent_light": "#38BDF8",
    "taskbar_position": "bottom",
    "taskbar_height": 42,
    "taskbar_autohide": False,
    "clock_format": "%H:%M",
    "workspaces": 2,
    "move_mode": "opaque",
    "resize_mode": "outline",
    "snap_distance": 12,
    "double_click_delta": 16,
    "dpi_scale": 96,
    "auto_desktop_shortcuts": True,
}

CSS = b"""
window, .background {
    background-color: #090D16;
    color: #F1F5F9;
}
headerbar {
    background-color: #0F172A;
    border-bottom: 1px solid #1E293B;
    padding: 6px 12px;
}
.sidebar-box {
    background-color: #0D1322;
    border-right: 1px solid #1E293B;
    padding: 8px 6px;
}
list.nav-list {
    background-color: transparent;
}
list.nav-list row {
    border-radius: 8px;
    padding: 9px 10px;
    margin: 2px 0;
    color: #CBD5E1;
}
list.nav-list row:hover {
    background-color: #162036;
}
list.nav-list row:selected {
    background-color: #0EA5E9;
    color: #FFFFFF;
    font-weight: bold;
}
.panel-card {
    background-color: #111827;
    border: 1px solid #1E293B;
    border-radius: 12px;
    padding: 13px 15px;
}
.section-title {
    font-size: 16px;
    font-weight: bold;
    color: #F8FAFC;
}
.card-title {
    font-size: 12px;
    font-weight: bold;
    color: #38BDF8;
}
.muted-text {
    color: #94A3B8;
    font-size: 11px;
}
button.primary-btn {
    background-image: none;
    background-color: #0EA5E9;
    color: #FFFFFF;
    border-radius: 8px;
    font-weight: bold;
    padding: 7px 14px;
    border: none;
}
button.primary-btn:hover {
    background-color: #0284C7;
}
button.secondary-btn {
    background-image: none;
    background-color: #1E293B;
    color: #E2E8F0;
    border-radius: 8px;
    border: 1px solid #334155;
    padding: 6px 12px;
}
button.secondary-btn:hover {
    background-color: #273549;
}
button.danger-btn {
    background-image: none;
    background-color: #7F1D1D;
    color: #FEE2E2;
    border-radius: 8px;
    border: 1px solid #B91C1C;
    padding: 6px 12px;
}
progressbar trough {
    background-color: #1E293B;
    border-radius: 6px;
    min-height: 8px;
}
progressbar progress {
    background-color: #0EA5E9;
    border-radius: 6px;
    min-height: 8px;
}
textview, textview text {
    background-color: #050811;
    color: #38BDF8;
    font-family: "DejaVu Sans Mono", monospace;
    font-size: 11px;
}
"""


def read_file_safe(path: str, default: str = "") -> str:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            val = f.read().strip()
            return val if val else default
    except OSError:
        return default


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


def signal_desktop_reload() -> None:
    if os.path.isfile(DESKTOP_PID_FILE):
        try:
            pid = int(open(DESKTOP_PID_FILE, encoding="utf-8").read().strip())
            os.kill(pid, signal.SIGUSR1)
        except Exception:
            pass


def write_jwmrc_theme(prefs: dict) -> None:
    """Regenerate /root/.jwmrc-theme matching Windows-style personalization settings."""
    accent = str(prefs.get("accent_color", "#0EA5E9"))
    accent_dark = str(prefs.get("accent_dark", "#0284C7"))
    accent_light = str(prefs.get("accent_light", "#38BDF8"))
    tb_pos = str(prefs.get("taskbar_position", "bottom"))
    tb_h = max(32, min(60, int(prefs.get("taskbar_height", 42))))
    autohide = "bottom" if (prefs.get("taskbar_autohide") and tb_pos == "bottom") else (
        "top" if prefs.get("taskbar_autohide") else "off"
    )
    y_coord = "0" if tb_pos == "top" else "-1"
    valign = "top" if tb_pos == "top" else "bottom"
    clock_fmt = str(prefs.get("clock_format", "%H:%M"))
    workspaces = max(1, min(4, int(prefs.get("workspaces", 2))))
    move_mode = "outline" if prefs.get("move_mode") == "outline" else "opaque"
    resize_mode = "opaque" if prefs.get("resize_mode") == "opaque" else "outline"
    snap_dist = max(4, min(30, int(prefs.get("snap_distance", 12))))
    dbl_delta = max(6, min(32, int(prefs.get("double_click_delta", 16))))

    xml = f"""<?xml version="1.0"?>
<JWM>
    <!-- Generated live by PocketLinux Settings -->
    <Tray x="0" y="{y_coord}" height="{tb_h}" autohide="{autohide}" halign="fixed" valign="{valign}">
        <TrayButton label=" Apps " icon="start-here">root:1</TrayButton>
        <Spacer width="4"/>
        <TrayButton label=" Store " icon="system-software-install">exec:pocketlinux-store.py</TrayButton>
        <TrayButton label=" DL " icon="folder-download">exec:pocketlinux-downloads.py</TrayButton>
        <TrayButton label=" Settings " icon="preferences-system">exec:pocketlinux-settings.py</TrayButton>
        <TrayButton label=" Term " icon="utilities-terminal">exec:xterm -fa "DejaVu Sans Mono" -fs 12 -bg "#0F172A" -fg "#E2E8F0"</TrayButton>
        <TrayButton label=" Files " icon="system-file-manager">exec:pcmanfm /root</TrayButton>
        <Spacer width="6"/>
        <Pager labeled="true"/>
        <TaskList maxwidth="220"/>
        <Dock/>
        <Clock format="{clock_fmt}"/>
    </Tray>

    <WindowStyle>
        <Font>DejaVu Sans-10:bold</Font>
        <Width>4</Width>
        <Height>28</Height>
        <Corner>6</Corner>
        <Foreground>#CBD5E1</Foreground>
        <Background>#1E293B</Background>
        <Outline>#334155</Outline>
        <Opacity>1.0</Opacity>
        <Active>
            <Foreground>#FFFFFF</Foreground>
            <Background>{accent}</Background>
            <Outline>{accent_light}</Outline>
            <Opacity>1.0</Opacity>
        </Active>
    </WindowStyle>

    <TrayStyle group="true" list="all">
        <Font>DejaVu Sans-10:bold</Font>
        <Background>#0F172A</Background>
        <Foreground>#F1F5F9</Foreground>
        <Outline>#1E293B</Outline>
        <Active>
            <Foreground>#FFFFFF</Foreground>
            <Background>{accent_dark}</Background>
        </Active>
    </TrayStyle>

    <TaskListStyle>
        <Font>DejaVu Sans-10</Font>
        <Active>
            <Foreground>#FFFFFF</Foreground>
            <Background>{accent}</Background>
        </Active>
        <Foreground>#CBD5E1</Foreground>
        <Background>#1E293B</Background>
    </TaskListStyle>

    <PagerStyle>
        <Outline>#334155</Outline>
        <Foreground>{accent_light}</Foreground>
        <Background>#0F172A</Background>
        <Text>#E2E8F0</Text>
        <Active>
            <Foreground>{accent}</Foreground>
            <Background>#1E293B</Background>
        </Active>
    </PagerStyle>

    <MenuStyle>
        <Font>DejaVu Sans-11</Font>
        <Foreground>#F1F5F9</Foreground>
        <Background>#0F172A</Background>
        <Outline>#334155</Outline>
        <Active>
            <Foreground>#FFFFFF</Foreground>
            <Background>{accent}</Background>
        </Active>
    </MenuStyle>

    <ClockStyle>
        <Font>DejaVu Sans-11:bold</Font>
        <Foreground>{accent_light}</Foreground>
        <Background>#0F172A</Background>
    </ClockStyle>

    <Desktops width="{workspaces}" height="1">
        <Background type="solid">#0F172A</Background>
    </Desktops>

    <DoubleClickSpeed>500</DoubleClickSpeed>
    <DoubleClickDelta>{dbl_delta}</DoubleClickDelta>
    <SnapMode distance="{snap_dist}">border</SnapMode>
    <MoveMode>{move_mode}</MoveMode>
    <ResizeMode>{resize_mode}</ResizeMode>
</JWM>
"""
    with open(JWMRC_THEME_PATH, "w", encoding="utf-8") as f:
        f.write(xml)


class PocketSettingsWindow(Gtk.Window):
    def __init__(self, initial_page: str = "system") -> None:
        super().__init__(title="PocketLinux Settings")
        self.set_default_size(940, 560)
        self.set_position(Gtk.WindowPosition.CENTER)

        self.prefs = dict(DEFAULT_DESKTOP_PREFS)
        self.prefs.update(load_json_safe(DESKTOP_PREFS_PATH, {}))
        self._suppress_signals = True
        self._nav_rows: dict[str, Gtk.ListBoxRow] = {}

        self._apply_css()
        self._build_ui()
        self._load_saved_gpu_config()
        self._load_personalization_widgets()
        self._load_network_widgets()
        self._load_apps_widgets()
        self._load_privacy_widgets()
        self._refresh_hw_probe()
        self._refresh_system_meters()
        self._suppress_signals = False

        if initial_page in self._nav_rows:
            self.nav_list.select_row(self._nav_rows[initial_page])
        elif "system" in self._nav_rows:
            self.nav_list.select_row(self._nav_rows["system"])

    def _apply_css(self) -> None:
        provider = Gtk.CssProvider()
        provider.load_from_data(CSS)
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(),
            provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
        )

    def _make_card(self, title: str, subtitle: str = "") -> Gtk.Box:
        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        card.get_style_context().add_class("panel-card")
        t = Gtk.Label(label=title, xalign=0.0)
        t.get_style_context().add_class("card-title")
        card.pack_start(t, False, False, 0)
        if subtitle:
            s = Gtk.Label(label=subtitle, xalign=0.0)
            s.get_style_context().add_class("muted-text")
            s.set_line_wrap(True)
            card.pack_start(s, False, False, 0)
        return card

    def _make_setting_row(self, title: str, widget: Gtk.Widget, desc: str = "") -> Gtk.Box:
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        left = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        lbl = Gtk.Label(label=title, xalign=0.0)
        left.pack_start(lbl, False, False, 0)
        if desc:
            dlbl = Gtk.Label(label=desc, xalign=0.0)
            dlbl.get_style_context().add_class("muted-text")
            dlbl.set_line_wrap(True)
            left.pack_start(dlbl, False, False, 0)
        row.pack_start(left, True, True, 0)
        widget.set_valign(Gtk.Align.CENTER)
        row.pack_start(widget, False, False, 0)
        return row

    def _make_page_scroll(self, title: str, subtitle: str) -> tuple[Gtk.ScrolledWindow, Gtk.Box]:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        box.set_border_width(12)
        hdr = Gtk.Label(label=title, xalign=0.0)
        hdr.get_style_context().add_class("section-title")
        box.pack_start(hdr, False, False, 0)
        sub = Gtk.Label(label=subtitle, xalign=0.0)
        sub.get_style_context().add_class("muted-text")
        box.pack_start(sub, False, False, 0)

        sc = Gtk.ScrolledWindow()
        sc.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        sc.add(box)
        return sc, box

    def _build_ui(self) -> None:
        header = Gtk.HeaderBar()
        header.set_show_close_button(True)
        header.set_title("PocketLinux Settings")
        header.set_subtitle("Windows-Inspired Personalization · GPU · Network · Privacy")
        self.set_titlebar(header)

        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        self.add(outer)

        body = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=0)
        outer.pack_start(body, True, True, 0)

        # Left Navigation Sidebar (Windows 11 style)
        side_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        side_box.set_size_request(210, -1)
        side_box.get_style_context().add_class("sidebar-box")

        brand = Gtk.Label(xalign=0.0)
        brand.set_markup("<b>PocketLinux 2.1</b>\n<span foreground='#94A3B8' size='small'>Control Center</span>")
        brand.set_margin_start(8)
        brand.set_margin_top(4)
        brand.set_margin_bottom(6)
        side_box.pack_start(brand, False, False, 0)

        self.nav_list = Gtk.ListBox()
        self.nav_list.get_style_context().add_class("nav-list")
        self.nav_list.set_selection_mode(Gtk.SelectionMode.SINGLE)
        self.nav_list.connect("row-selected", self._on_nav_selected)

        nav_scroll = Gtk.ScrolledWindow()
        nav_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        nav_scroll.add(self.nav_list)
        side_box.pack_start(nav_scroll, True, True, 0)
        body.pack_start(side_box, False, False, 0)

        # Right Stack
        self.stack = Gtk.Stack()
        self.stack.set_transition_type(Gtk.StackTransitionType.CROSSFADE)
        self.stack.set_transition_duration(120)
        body.pack_start(self.stack, True, True, 0)

        pages = [
            ("system", "⚡  System & Speed", self._build_system_page()),
            ("gpu", "🎮  Graphics & GPU", self._build_gpu_page()),
            ("display", "🖥  Display & Windows", self._build_display_page()),
            ("personalization", "🎨  Personalization", self._build_personalization_page()),
            ("apps", "📦  Apps & Downloads", self._build_apps_page()),
            ("network", "🌐  Network & Browser", self._build_network_page()),
            ("privacy", "🔒  Privacy & Storage", self._build_privacy_page()),
            ("repair", "🛠  Repair & About", self._build_repair_page()),
        ]
        for pid, label, widget in pages:
            self.stack.add_named(widget, pid)
            row = Gtk.ListBoxRow()
            row.page_id = pid  # type: ignore[attr-defined]
            lbl = Gtk.Label(label=label, xalign=0.0)
            row.add(lbl)
            self.nav_list.add(row)
            self._nav_rows[pid] = row

        # Compact bottom console with collapsible toggle
        console_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        console_bar.set_border_width(6)
        self.status_lbl = Gtk.Label(label="Ready — Changes apply live across Desktop & JWM.", xalign=0.0)
        self.status_lbl.get_style_context().add_class("muted-text")
        console_bar.pack_start(self.status_lbl, True, True, 0)

        self.toggle_log_btn = Gtk.ToggleButton(label="Output Log")
        self.toggle_log_btn.get_style_context().add_class("secondary-btn")
        self.toggle_log_btn.set_active(False)
        self.toggle_log_btn.connect("toggled", self._on_toggle_console)
        console_bar.pack_start(self.toggle_log_btn, False, False, 0)

        clear_btn = Gtk.Button(label="Clear")
        clear_btn.get_style_context().add_class("secondary-btn")
        clear_btn.connect("clicked", lambda *_: self.console.get_buffer().set_text(""))
        console_bar.pack_start(clear_btn, False, False, 0)
        outer.pack_start(console_bar, False, False, 0)

        self.console = Gtk.TextView()
        self.console.set_editable(False)
        self.console.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        self.console_scroll = Gtk.ScrolledWindow()
        self.console_scroll.set_size_request(-1, 125)
        self.console_scroll.add(self.console)
        outer.pack_start(self.console_scroll, False, False, 0)

    def _on_nav_selected(self, _lb: Gtk.ListBox, row: Gtk.ListBoxRow | None) -> None:
        if row is not None and hasattr(row, "page_id"):
            self.stack.set_visible_child_name(row.page_id)  # type: ignore[attr-defined]

    def _on_toggle_console(self, btn: Gtk.ToggleButton) -> None:
        if btn.get_active():
            self.console_scroll.show()
        else:
            self.console_scroll.hide()

    def _show_console(self) -> None:
        if not self.toggle_log_btn.get_active():
            self.toggle_log_btn.set_active(True)
        self.console_scroll.show()

    # ---------- Page 1: System & Speed ----------

    def _build_system_page(self) -> Gtk.Widget:
        sc, box = self._make_page_scroll(
            "System & Performance",
            "Live CPU, RAM, Shared Memory & Disk telemetry with quick optimization tools.",
        )

        meters_card = self._make_card("Live Resource Telemetry")
        self.sys_summary_lbl = Gtk.Label(label="Loading system metrics...", xalign=0.0)
        self.sys_summary_lbl.set_line_wrap(True)
        meters_card.pack_start(self.sys_summary_lbl, False, False, 0)

        self.ram_bar = Gtk.ProgressBar()
        self.ram_bar.set_show_text(True)
        meters_card.pack_start(self.ram_bar, False, False, 0)

        self.disk_bar = Gtk.ProgressBar()
        self.disk_bar.set_show_text(True)
        meters_card.pack_start(self.disk_bar, False, False, 0)

        m_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        ref_btn = Gtk.Button(label="Refresh Metrics")
        ref_btn.get_style_context().add_class("secondary-btn")
        ref_btn.connect("clicked", lambda *_: self._refresh_system_meters())
        m_row.pack_start(ref_btn, False, False, 0)

        htop_btn = Gtk.Button(label="Open Task Manager (htop)")
        htop_btn.get_style_context().add_class("secondary-btn")
        htop_btn.connect("clicked", lambda *_: self._spawn("xterm -T 'htop' -fa 'DejaVu Sans Mono' -fs 11 -bg '#0F172A' -fg '#E2E8F0' -e htop"))
        m_row.pack_start(htop_btn, False, False, 0)

        clean_btn = Gtk.Button(label="Clean APK & Thumbnail Cache")
        clean_btn.get_style_context().add_class("primary-btn")
        clean_btn.connect(
            "clicked",
            lambda *_: self._run_command(
                "Clean System Cache",
                "rm -rf /var/cache/apk/*.apk /root/.cache/thumbnails/* /tmp/mesa_shader_cache/* 2>/dev/null || true; df -h /root",
            ),
        )
        m_row.pack_start(clean_btn, False, False, 0)
        meters_card.pack_start(m_row, False, False, 2)
        box.pack_start(meters_card, False, False, 0)

        speed_card = self._make_card(
            "Multi-Core Rendering & Speed Preset",
            "Tune CPU thread allocation for software rasterization and UI responsiveness.",
        )
        self.threads_combo = Gtk.ComboBoxText()
        for val, lbl in (("4", "4 Threads (Balanced & Fast)"), ("6", "6 Threads (High Performance)"), ("2", "2 Threads (Battery / Low RAM)"), ("auto", "Auto (All Cores)")):
            self.threads_combo.append(val, lbl)
        self.threads_combo.set_active_id("4")
        speed_card.pack_start(
            self._make_setting_row("Software Render Threads (LP_NUM_THREADS)", self.threads_combo),
            False,
            False,
            0,
        )
        apply_spd = Gtk.Button(label="Apply Thread Setting")
        apply_spd.get_style_context().add_class("secondary-btn")
        apply_spd.connect("clicked", lambda *_: self._save_gpu_config())
        apply_spd.set_halign(Gtk.Align.START)
        speed_card.pack_start(apply_spd, False, False, 2)
        box.pack_start(speed_card, False, False, 0)

        return sc

    # ---------- Page 2: Graphics & GPU ----------

    def _build_gpu_page(self) -> Gtk.Widget:
        sc, box = self._make_page_scroll(
            "Graphics & GPU Acceleration (VirGL)",
            "Control Android Hardware GPU bridging, Mesa Gallium drivers, and live 3D diagnostics.",
        )

        hw_card = self._make_card("Detected Hardware GPU & VirGL Bridge")
        self.hw_label = Gtk.Label(label="Probing Android GPU & VirGL server...", xalign=0.0)
        self.hw_label.set_line_wrap(True)
        hw_card.pack_start(self.hw_label, False, False, 0)
        box.pack_start(hw_card, False, False, 0)

        cfg_card = self._make_card("OpenGL & Mesa Driver Configuration")
        self.driver_combo = Gtk.ComboBoxText()
        self.driver_combo.append("auto", "Auto (Hardware VirGL if active, else LLVMpipe)")
        self.driver_combo.append("virpipe", "Force VirGL Hardware GPU (virpipe)")
        self.driver_combo.append("llvmpipe", "Force Fast CPU Software (llvmpipe)")
        self.driver_combo.append("zink", "Vulkan Zink (Adreno 6xx/7xx)")
        self.driver_combo.set_active_id("auto")
        cfg_card.pack_start(
            self._make_setting_row("GPU Driver Mode", self.driver_combo, "Auto works best on Adreno 5xx/6xx/7xx & Mali."),
            False,
            False,
            0,
        )

        self.gl_combo = Gtk.ComboBoxText()
        for val, lbl in (("default", "Default (Native GPU / Recommended)"), ("3.3", "OpenGL 3.3 Override"), ("4.0", "OpenGL 4.0 Override"), ("4.5", "OpenGL 4.5 Override")):
            self.gl_combo.append(val, lbl)
        self.gl_combo.set_active_id("default")
        cfg_card.pack_start(
            self._make_setting_row("OpenGL Version Override", self.gl_combo, "Leave on Default for Adreno 5xx stability."),
            False,
            False,
            0,
        )

        self.no_error_switch = Gtk.Switch()
        self.no_error_switch.set_active(True)
        cfg_card.pack_start(
            self._make_setting_row("Skip GL Error Checks (MESA_NO_ERROR=1)", self.no_error_switch, "Boosts 3D frame rate slightly."),
            False,
            False,
            0,
        )

        btn_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        save_btn = Gtk.Button(label="Apply & Save GPU Settings")
        save_btn.get_style_context().add_class("primary-btn")
        save_btn.connect("clicked", lambda *_: self._save_gpu_config())
        btn_row.pack_start(save_btn, False, False, 0)

        glx_btn = Gtk.Button(label="Test 3D (glxgears)")
        glx_btn.get_style_context().add_class("secondary-btn")
        glx_btn.connect(
            "clicked",
            lambda *_: self._run_command(
                "3D Hardware Test (glxgears)",
                ". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; "
                "echo \"GALLIUM_DRIVER=${GALLIUM_DRIVER:-unset} VTEST_SOCKET_NAME=${VTEST_SOCKET_NAME:-unset}\"; "
                "glxgears -info 2>&1",
            ),
        )
        btn_row.pack_start(glx_btn, False, False, 0)

        info_btn = Gtk.Button(label="OpenGL Info")
        info_btn.get_style_context().add_class("secondary-btn")
        info_btn.connect(
            "clicked",
            lambda *_: self._run_command(
                "OpenGL Renderer Info",
                ". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; glxinfo -B 2>&1",
            ),
        )
        btn_row.pack_start(info_btn, False, False, 0)

        vlog_btn = Gtk.Button(label="VirGL Server Log")
        vlog_btn.get_style_context().add_class("secondary-btn")
        vlog_btn.connect("clicked", lambda *_: self._show_virgl_log())
        btn_row.pack_start(vlog_btn, False, False, 0)

        cfg_card.pack_start(btn_row, False, False, 4)
        box.pack_start(cfg_card, False, False, 0)

        return sc

    # ---------- Page 3: Display & Windows ----------

    def _build_display_page(self) -> Gtk.Widget:
        sc, box = self._make_page_scroll(
            "Display & Window Management",
            "Live screen resolution presets, DPI scaling, workspaces, and snappy window behavior.",
        )

        res_card = self._make_card("Screen Resolution Presets (Live xrandr)")
        res_row = Gtk.FlowBox()
        res_row.set_selection_mode(Gtk.SelectionMode.NONE)
        res_row.set_column_spacing(8)
        res_row.set_row_spacing(8)
        for label, res in (
            ("960x540 (Turbo 3D)", "960x540"),
            ("1024x600 (Balanced)", "1024x600"),
            ("1280x720 (HD)", "1280x720"),
            ("1440x810 (Crisp)", "1440x810"),
            ("1600x900 (Wide)", "1600x900"),
        ):
            btn = Gtk.Button(label=label)
            btn.get_style_context().add_class("secondary-btn")
            btn.connect("clicked", lambda _b, r=res: self._apply_resolution(r))
            res_row.add(btn)
        res_card.pack_start(res_row, False, False, 0)
        box.pack_start(res_card, False, False, 0)

        wm_card = self._make_card("Windows-Style Multitasking & Touch Controls")
        self.dpi_combo = Gtk.ComboBoxText()
        for val, lbl in (("96", "100% — 96 DPI (Standard)"), ("110", "115% — 110 DPI (Comfortable)"), ("120", "125% — 120 DPI (Touch Friendly)"), ("144", "150% — 144 DPI (Large)")):
            self.dpi_combo.append(val, lbl)
        self.dpi_combo.connect("changed", lambda *_: self._apply_personalization(update_dpi=True))
        wm_card.pack_start(self._make_setting_row("Display Scaling (DPI)", self.dpi_combo), False, False, 0)

        self.ws_combo = Gtk.ComboBoxText()
        for val, lbl in (("1", "1 Virtual Desktop"), ("2", "2 Virtual Desktops (Default)"), ("4", "4 Virtual Desktops")):
            self.ws_combo.append(val, lbl)
        self.ws_combo.connect("changed", lambda *_: self._apply_personalization())
        wm_card.pack_start(self._make_setting_row("Virtual Workspaces", self.ws_combo), False, False, 0)

        self.move_combo = Gtk.ComboBoxText()
        self.move_combo.append("opaque", "Smooth Live Preview (Opaque)")
        self.move_combo.append("outline", "Ultra-Fast Frame Outline (Zero Lag)")
        self.move_combo.connect("changed", lambda *_: self._apply_personalization())
        wm_card.pack_start(self._make_setting_row("Window Drag Mode", self.move_combo), False, False, 0)

        self.snap_combo = Gtk.ComboBoxText()
        for val, lbl in (("6", "Light (6 px)"), ("12", "Windows Standard (12 px)"), ("20", "Strong Magnetic (20 px)")):
            self.snap_combo.append(val, lbl)
        self.snap_combo.connect("changed", lambda *_: self._apply_personalization())
        wm_card.pack_start(self._make_setting_row("Window Edge Snap Distance", self.snap_combo), False, False, 0)

        self.dbl_combo = Gtk.ComboBoxText()
        for val, lbl in (("10", "Precision Mouse (10 px)"), ("16", "Touch Friendly (16 px)"), ("24", "Extra Forgiving Touch (24 px)")):
            self.dbl_combo.append(val, lbl)
        self.dbl_combo.connect("changed", lambda *_: self._apply_personalization())
        wm_card.pack_start(self._make_setting_row("Double-Click Touch Tolerance", self.dbl_combo), False, False, 0)

        box.pack_start(wm_card, False, False, 0)
        return sc

    # ---------- Page 4: Personalization (Windows 11 Style) ----------

    def _build_personalization_page(self) -> Gtk.Widget:
        sc, box = self._make_page_scroll(
            "Personalization (Windows Style)",
            "Customize accent colors, wallpapers, taskbar layout, and movable Desktop icon arrangement.",
        )

        # Accent Colors
        accent_card = self._make_card("Accent Color & Theme", "Applies live to Window titlebars, Taskbar, Menus & Desktop icon selection.")
        acc_flow = Gtk.FlowBox()
        acc_flow.set_selection_mode(Gtk.SelectionMode.NONE)
        acc_flow.set_column_spacing(8)
        acc_flow.set_row_spacing(8)
        for name, c_main, c_dark, c_light in ACCENT_PRESETS:
            btn = Gtk.Button(label=f"●  {name}")
            btn.get_style_context().add_class("secondary-btn")
            prov = Gtk.CssProvider()
            prov.load_from_data(f"button {{ border-left: 5px solid {c_main}; }}".encode())
            btn.get_style_context().add_provider(prov, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1)
            btn.connect("clicked", lambda _b, n=name, m=c_main, d=c_dark, l=c_light: self._set_accent(n, m, d, l))
            acc_flow.add(btn)
        accent_card.pack_start(acc_flow, False, False, 0)
        box.pack_start(accent_card, False, False, 0)

        # Wallpapers
        wp_card = self._make_card("Desktop Background Wallpaper")
        wp_flow = Gtk.FlowBox()
        wp_flow.set_selection_mode(Gtk.SelectionMode.NONE)
        wp_flow.set_column_spacing(8)
        wp_flow.set_row_spacing(8)
        for name, path in WALLPAPER_PRESETS:
            btn = Gtk.Button(label=name)
            btn.get_style_context().add_class("secondary-btn")
            btn.connect("clicked", lambda _b, p=path, n=name: self._set_wallpaper(p, n))
            wp_flow.add(btn)
        choose_wp_btn = Gtk.Button(label="Browse Custom Image...")
        choose_wp_btn.get_style_context().add_class("primary-btn")
        choose_wp_btn.connect("clicked", lambda *_: self._choose_custom_wallpaper())
        wp_flow.add(choose_wp_btn)
        wp_card.pack_start(wp_flow, False, False, 0)
        box.pack_start(wp_card, False, False, 0)

        # Taskbar Customization
        tb_card = self._make_card("Taskbar & Clock")
        self.tb_pos_combo = Gtk.ComboBoxText()
        self.tb_pos_combo.append("bottom", "Bottom (Windows Default)")
        self.tb_pos_combo.append("top", "Top Bar")
        self.tb_pos_combo.connect("changed", lambda *_: self._apply_personalization())
        tb_card.pack_start(self._make_setting_row("Taskbar Position", self.tb_pos_combo), False, False, 0)

        self.tb_height_combo = Gtk.ComboBoxText()
        self.tb_height_combo.append("36", "Compact (36 px)")
        self.tb_height_combo.append("42", "Standard (42 px)")
        self.tb_height_combo.append("50", "Large Touch (50 px)")
        self.tb_height_combo.connect("changed", lambda *_: self._apply_personalization())
        tb_card.pack_start(self._make_setting_row("Taskbar Size", self.tb_height_combo), False, False, 0)

        self.tb_clock_combo = Gtk.ComboBoxText()
        self.tb_clock_combo.append("%H:%M", "24-Hour (14:30)")
        self.tb_clock_combo.append("%I:%M %p", "12-Hour (02:30 PM)")
        self.tb_clock_combo.append("%b %d  %H:%M", "Date & Time")
        self.tb_clock_combo.connect("changed", lambda *_: self._apply_personalization())
        tb_card.pack_start(self._make_setting_row("Taskbar Clock Format", self.tb_clock_combo), False, False, 0)

        self.tb_autohide_sw = Gtk.Switch()
        self.tb_autohide_sw.connect("notify::active", lambda *_: self._apply_personalization())
        tb_card.pack_start(self._make_setting_row("Auto-Hide Taskbar", self.tb_autohide_sw, "Maximizes screen space for full-screen apps."), False, False, 0)
        box.pack_start(tb_card, False, False, 0)

        # Desktop Icons & Arrangement
        ico_card = self._make_card(
            "Desktop Icons & Arrangement",
            "Drag any app icon directly on the Desktop to move it anywhere — just like Windows!",
        )
        self.show_icons_sw = Gtk.Switch()
        self.show_icons_sw.connect("notify::active", lambda *_: self._apply_personalization())
        ico_card.pack_start(self._make_setting_row("Show Desktop Icons", self.show_icons_sw), False, False, 0)

        self.icon_size_combo = Gtk.ComboBoxText()
        self.icon_size_combo.append("36", "Small Icons (36 px)")
        self.icon_size_combo.append("48", "Medium Icons (48 px)")
        self.icon_size_combo.append("64", "Large Icons (64 px)")
        self.icon_size_combo.connect("changed", lambda *_: self._apply_personalization())
        ico_card.pack_start(self._make_setting_row("Desktop Icon Size", self.icon_size_combo), False, False, 0)

        self.single_click_sw = Gtk.Switch()
        self.single_click_sw.connect("notify::active", lambda *_: self._apply_personalization())
        ico_card.pack_start(
            self._make_setting_row("Single-Tap to Open Desktop Icons", self.single_click_sw, "Turn off to require double-click like classic desktop."),
            False,
            False,
            0,
        )

        self.snap_grid_sw = Gtk.Switch()
        self.snap_grid_sw.connect("notify::active", lambda *_: self._apply_personalization())
        ico_card.pack_start(
            self._make_setting_row("Align Icons to Grid", self.snap_grid_sw, "Turn off for pixel-free placement anywhere on screen."),
            False,
            False,
            0,
        )

        self.auto_arrange_sw = Gtk.Switch()
        self.auto_arrange_sw.connect("notify::active", lambda *_: self._apply_personalization())
        ico_card.pack_start(
            self._make_setting_row("Auto-Arrange Icons Automatically", self.auto_arrange_sw),
            False,
            False,
            0,
        )

        act_row = Gtk.FlowBox()
        act_row.set_selection_mode(Gtk.SelectionMode.NONE)
        act_row.set_column_spacing(8)
        act_row.set_row_spacing(8)
        for lbl, mode in (
            ("Auto-Arrange Grid", "auto"),
            ("Sort by Name (A-Z)", "name"),
            ("Sort by Date", "date"),
            ("Reset Layout", "reset"),
        ):
            b = Gtk.Button(label=lbl)
            b.get_style_context().add_class("secondary-btn")
            b.connect("clicked", lambda _b, m=mode: self._sort_or_reset_desktop(m))
            act_row.add(b)
        ico_card.pack_start(act_row, False, False, 4)
        box.pack_start(ico_card, False, False, 0)

        return sc

    # ---------- Page 5: Apps & Downloads ----------

    def _build_apps_page(self) -> Gtk.Widget:
        sc, box = self._make_page_scroll(
            "Apps & Background Download Manager",
            "Configure multi-stream download acceleration and automatic installed-app synchronization.",
        )

        dl_card = self._make_card("Download Acceleration (pocketlinux-pkgd)")
        self.streams_combo = Gtk.ComboBoxText()
        self.streams_combo.append("2", "2 Streams (Low Bandwidth)")
        self.streams_combo.append("4", "4 Streams (Fast)")
        self.streams_combo.append("6", "6 Streams (Turbo — Recommended)")
        self.streams_combo.connect("changed", lambda *_: self._save_download_streams())
        dl_card.pack_start(
            self._make_setting_row("Parallel Download Streams", self.streams_combo, "Downloads multiple package chunks simultaneously."),
            False,
            False,
            0,
        )

        self.auto_shortcut_sw = Gtk.Switch()
        self.auto_shortcut_sw.connect("notify::active", lambda *_: self._apply_personalization())
        dl_card.pack_start(
            self._make_setting_row(
                "Auto-Add Installed Apps to Desktop",
                self.auto_shortcut_sw,
                "Automatically places shortcuts on Desktop, Applications folder, Start Menu & Android tab.",
            ),
            False,
            False,
            0,
        )

        self.installed_count_lbl = Gtk.Label(label="Scanning installed apps...", xalign=0.0)
        self.installed_count_lbl.get_style_context().add_class("muted-text")
        dl_card.pack_start(self.installed_count_lbl, False, False, 0)

        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        sync_btn = Gtk.Button(label="Sync Installed Apps Now")
        sync_btn.get_style_context().add_class("primary-btn")
        sync_btn.connect("clicked", lambda *_: self._sync_apps_now())
        row.pack_start(sync_btn, False, False, 0)

        store_btn = Gtk.Button(label="Open App Store")
        store_btn.get_style_context().add_class("secondary-btn")
        store_btn.connect("clicked", lambda *_: self._spawn("/usr/local/bin/pocketlinux-store.py"))
        row.pack_start(store_btn, False, False, 0)

        dl_btn = Gtk.Button(label="Open Download Manager")
        dl_btn.get_style_context().add_class("secondary-btn")
        dl_btn.connect("clicked", lambda *_: self._spawn("/usr/local/bin/pocketlinux-downloads.py"))
        row.pack_start(dl_btn, False, False, 0)

        apps_dir_btn = Gtk.Button(label="Open Applications Folder")
        apps_dir_btn.get_style_context().add_class("secondary-btn")
        apps_dir_btn.connect("clicked", lambda *_: self._spawn("pcmanfm /root/Applications"))
        row.pack_start(apps_dir_btn, False, False, 0)

        dl_card.pack_start(row, False, False, 4)
        box.pack_start(dl_card, False, False, 0)
        return sc

    # ---------- Page 6: Network & Browser ----------

    def _build_network_page(self) -> Gtk.Widget:
        sc, box = self._make_page_scroll(
            "Network, DNS & Web Browsers",
            "Control the Android Native Network Bridge so Firefox, Chromium, Luakit & APK always resolve domains.",
        )

        net_card = self._make_card("Android Native Network & DNS Bridge (127.0.0.1:18080)")
        self.net_status_lbl = Gtk.Label(label="Checking network status...", xalign=0.0)
        self.net_status_lbl.set_line_wrap(True)
        net_card.pack_start(self.net_status_lbl, False, False, 0)

        self.net_mode_combo = Gtk.ComboBoxText()
        self.net_mode_combo.append("auto", "Auto — Activate bridge automatically if UDP DNS fails (Recommended)")
        self.net_mode_combo.append("always", "Always ON — Always route browsers & APK via Android DNS")
        self.net_mode_combo.append("off", "OFF — Direct Linux networking only")
        self.net_mode_combo.connect("changed", lambda *_: self._save_network_mode())
        net_card.pack_start(self._make_setting_row("Network Bridge Mode", self.net_mode_combo), False, False, 0)

        btn_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        fix_btn = Gtk.Button(label="Fix DNS & Sync Proxy Now")
        fix_btn.get_style_context().add_class("primary-btn")
        fix_btn.connect("clicked", lambda *_: self._run_dns_fix())
        btn_row.pack_start(fix_btn, False, False, 0)

        test_btn = Gtk.Button(label="Test Internet & Browser DNS")
        test_btn.get_style_context().add_class("secondary-btn")
        test_btn.connect(
            "clicked",
            lambda *_: self._run_command(
                "Network & DNS Test",
                ". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; "
                "echo \"http_proxy=${http_proxy:-direct}\"; "
                "cat /etc/resolv.conf 2>/dev/null; echo ''; "
                "curl -sI --max-time 6 https://dl-cdn.alpinelinux.org/alpine/v3.20/main/aarch64/APKINDEX.tar.gz | head -n 4; "
                "curl -sI --max-time 6 https://example.com | head -n 4",
            ),
        )
        btn_row.pack_start(test_btn, False, False, 0)

        cf_btn = Gtk.Button(label="Use Cloudflare DNS (1.1.1.1)")
        cf_btn.get_style_context().add_class("secondary-btn")
        cf_btn.connect("clicked", lambda *_: self._set_dns_servers("1.1.1.1", "8.8.8.8"))
        btn_row.pack_start(cf_btn, False, False, 0)

        net_card.pack_start(btn_row, False, False, 4)
        box.pack_start(net_card, False, False, 0)
        return sc

    # ---------- Page 7: Privacy & Phone Storage ----------

    def _build_privacy_page(self) -> Gtk.Widget:
        sc, box = self._make_page_scroll(
            "Privacy & Phone Storage",
            "Control whether Linux apps can see your Android phone files (/mnt/phone) or appear completely empty.",
        )

        priv_card = self._make_card(
            "Phone Files Visibility (Privacy Mode)",
            "When Privacy Mode is ON, /mnt/phone and PhoneStorage appear completely empty to all Linux apps.",
        )
        self.privacy_sw = Gtk.Switch()
        self.privacy_sw.connect("notify::active", lambda *_: self._on_privacy_toggled())
        priv_card.pack_start(
            self._make_setting_row(
                "Hide Phone Files (Show /mnt/phone as Empty)",
                self.privacy_sw,
                "Restart Linux runtime (or toggle from Android top-right menu) to apply mount isolation immediately.",
            ),
            False,
            False,
            0,
        )

        self.phone_status_lbl = Gtk.Label(label="Inspecting /mnt/phone...", xalign=0.0)
        self.phone_status_lbl.set_line_wrap(True)
        priv_card.pack_start(self.phone_status_lbl, False, False, 0)

        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        for lbl, path in (
            ("Open Phone Storage", "/mnt/phone"),
            ("Open Shared Storage", "/mnt/sdcard"),
            ("Open Downloads", "/root/Downloads"),
        ):
            b = Gtk.Button(label=lbl)
            b.get_style_context().add_class("secondary-btn")
            b.connect("clicked", lambda _b, p=path: self._spawn(f"pcmanfm {p}"))
            row.pack_start(b, False, False, 0)
        priv_card.pack_start(row, False, False, 4)
        box.pack_start(priv_card, False, False, 0)
        return sc

    # ---------- Page 8: Repair & About ----------

    def _build_repair_page(self) -> Gtk.Widget:
        sc, box = self._make_page_scroll(
            "Repair, Maintenance & About",
            "One-click recovery for APK locks, broken libraries, window manager, and background services.",
        )

        rep_card = self._make_card("System Repair Tools")
        flow = Gtk.FlowBox()
        flow.set_selection_mode(Gtk.SelectionMode.NONE)
        flow.set_column_spacing(8)
        flow.set_row_spacing(8)
        actions = [
            (
                "Unlock DB & Repair Libraries",
                "primary-btn",
                lambda: self._run_command(
                    "Repair All Libraries",
                    "rm -f /lib/apk/db/lock 2>/dev/null || true; /usr/local/bin/fix-dns.sh; /usr/local/sbin/apk update && /usr/local/sbin/apk upgrade && /usr/local/sbin/apk fix",
                ),
            ),
            (
                "Run ELF & Linux Health Check",
                "secondary-btn",
                lambda: self._run_command("Health Check", "/usr/local/bin/linux-health.sh"),
            ),
            (
                "Restart JWM Window Manager",
                "secondary-btn",
                lambda: self._run_command("Restart JWM", "jwm -restart && echo 'JWM restarted cleanly.'"),
            ),
            (
                "Restart Desktop Canvas",
                "secondary-btn",
                lambda: self._spawn("/usr/local/bin/pocketlinux-desktop.py"),
            ),
            (
                "Restart Download Daemon",
                "secondary-btn",
                lambda: self._run_command(
                    "Restart Download Daemon",
                    "pkill -f pocketlinux-pkgd.py 2>/dev/null || true; sleep 0.3; /usr/local/bin/pocketlinux-pkgd.py >/tmp/pkgd.log 2>&1 & sleep 0.5; curl -s http://127.0.0.1:18765/api/ping",
                ),
            ),
        ]
        for lbl, cls, fn in actions:
            b = Gtk.Button(label=lbl)
            b.get_style_context().add_class(cls)
            b.connect("clicked", lambda _b, f=fn: f())
            flow.add(b)
        rep_card.pack_start(flow, False, False, 0)
        box.pack_start(rep_card, False, False, 0)

        about_card = self._make_card("About PocketLinux")
        un = os.uname()
        alpine_ver = read_file_safe("/etc/alpine-release", "3.20")
        about_lbl = Gtk.Label(xalign=0.0)
        about_lbl.set_markup(
            f"<b>PocketLinux Desktop v2.1.0</b>\n"
            f"Distribution: Alpine Linux {GLib.markup_escape_text(alpine_ver)} ({GLib.markup_escape_text(un.machine)})\n"
            f"Kernel: {GLib.markup_escape_text(un.sysname)} {GLib.markup_escape_text(un.release)}\n"
            f"Desktop: JWM + PocketLinux Interactive Desktop + Background Download Daemon"
        )
        about_lbl.set_line_wrap(True)
        about_card.pack_start(about_lbl, False, False, 0)
        box.pack_start(about_card, False, False, 0)
        return sc

    # ---------- Data Loading & Actions ----------

    def _refresh_system_meters(self) -> None:
        cores = os.cpu_count() or 1
        mem_total = mem_avail = 0
        try:
            with open("/proc/meminfo", "r", encoding="utf-8") as f:
                for line in f:
                    if line.startswith("MemTotal:"):
                        mem_total = int(line.split()[1])
                    elif line.startswith("MemAvailable:"):
                        mem_avail = int(line.split()[1])
        except Exception:
            pass
        if mem_total > 0:
            used_mb = (mem_total - mem_avail) / 1024.0
            tot_mb = mem_total / 1024.0
            frac = max(0.0, min(1.0, (mem_total - mem_avail) / float(mem_total)))
            self.ram_bar.set_fraction(frac)
            self.ram_bar.set_text(f"RAM: {used_mb:.0f} MB / {tot_mb:.0f} MB ({frac * 100:.0f}%)")
        else:
            self.ram_bar.set_text("RAM info unavailable")

        try:
            st = os.statvfs("/root")
            total_b = st.f_blocks * st.f_frsize
            free_b = st.f_bavail * st.f_frsize
            used_b = max(0, total_b - free_b)
            frac = (used_b / total_b) if total_b > 0 else 0.0
            self.disk_bar.set_fraction(max(0.0, min(1.0, frac)))
            self.disk_bar.set_text(
                f"Storage (/root): {used_b / (1024**3):.2f} GB used / {total_b / (1024**3):.2f} GB total"
            )
        except Exception:
            self.disk_bar.set_text("Disk info unavailable")

        shm_ok = "Active (/dev/shm)" if os.path.isdir("/dev/shm") else "Fallback (/tmp)"
        self.sys_summary_lbl.set_markup(
            f"<b>CPU Governor:</b> {cores} Cores Active   |   <b>Shared Memory:</b> {shm_ok}   |   <b>Arch:</b> {os.uname().machine}"
        )

    def _refresh_hw_probe(self) -> None:
        gpu_model = read_file_safe("/sys/class/kgsl/kgsl-3d0/gpu_model", "Android EGL/GLES GPU")
        kgsl_ok = "Accessible" if os.path.exists("/dev/kgsl-3d0") else "EGL Surfaceless"
        has_virgl = os.path.exists("/tmp/.virgl_ipc/.virgl_test") or os.path.exists("/tmp/.virgl_test")
        virgl_ok = (
            "<span foreground='#34D399'><b>Active — Hardware GPU Accelerated (VirGL)</b></span>"
            if has_virgl
            else "<span foreground='#FBBF24'>Standby (LLVMpipe Multi-Core)</span>"
        )
        self.hw_label.set_markup(
            f"<b>Detected GPU:</b> {GLib.markup_escape_text(gpu_model)}\n"
            f"<b>VirGL Server:</b> {virgl_ok}   |   <b>GPU Node:</b> {kgsl_ok}"
        )

    def _load_saved_gpu_config(self) -> None:
        if not os.path.isfile(GPU_ENV_PATH):
            return
        content = read_file_safe(GPU_ENV_PATH, "")
        for line in content.splitlines():
            if line.startswith("# MODE="):
                self.driver_combo.set_active_id(line.split("=", 1)[1].strip())
            elif "MESA_GL_VERSION_OVERRIDE=" in line and not line.strip().startswith("#"):
                ver = line.split("=", 1)[1].strip().strip('"')
                self.gl_combo.set_active_id(ver)
            elif "LP_NUM_THREADS=" in line:
                th = line.split("=", 1)[1].strip().strip('"')
                self.threads_combo.set_active_id(th)
            elif "MESA_NO_ERROR=0" in line:
                self.no_error_switch.set_active(False)

    def _save_gpu_config(self) -> None:
        os.makedirs(CONF_DIR, exist_ok=True)
        mode = self.driver_combo.get_active_id() or "auto"
        gl_ver = self.gl_combo.get_active_id() or "default"
        threads = self.threads_combo.get_active_id() or "4"
        no_err = "1" if self.no_error_switch.get_active() else "0"

        lines = [
            "# Generated by PocketLinux Settings",
            f"# MODE={mode}",
            "export GDK_BACKEND=x11",
            'if [ -S /tmp/.virgl_ipc/.virgl_test ]; then export VTEST_SOCKET_NAME=/tmp/.virgl_ipc/.virgl_test; else export VTEST_SOCKET_NAME=/tmp/.virgl_test; fi',
            f"export MESA_NO_ERROR={no_err}",
            "export GALLIUM_THREAD=true",
            "export LIBGL_ALWAYS_SOFTWARE=1",
        ]
        if mode == "virpipe":
            lines.append("export GALLIUM_DRIVER=virpipe")
        elif mode == "llvmpipe":
            lines.append("export GALLIUM_DRIVER=llvmpipe")
        elif mode == "zink":
            lines.append("export GALLIUM_DRIVER=zink")
        else:
            lines.append('if [ -S "$VTEST_SOCKET_NAME" ]; then export GALLIUM_DRIVER=virpipe; else export GALLIUM_DRIVER=llvmpipe; fi')

        if gl_ver != "default":
            lines.append(f"export MESA_GL_VERSION_OVERRIDE={gl_ver}")
        if threads != "auto":
            lines.append(f"export LP_NUM_THREADS={threads}")

        with open(GPU_ENV_PATH, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")

        self._refresh_hw_probe()
        self._set_status(f"Saved GPU profile (mode={mode}, GL={gl_ver}, threads={threads}).")
        self._log(f"\nSaved {GPU_ENV_PATH} (mode={mode}, GL={gl_ver}, threads={threads}).\n")

    def _show_virgl_log(self) -> None:
        self._show_console()
        if os.path.isfile(VIRGL_LOG_PATH):
            txt = read_file_safe(VIRGL_LOG_PATH, "(log is empty)")
            self._log(f"\n=== VirGL Server Log ({VIRGL_LOG_PATH}) ===\n{txt[-4000:]}\n")
        else:
            self._log(f"\nVirGL server log not found at {VIRGL_LOG_PATH}.\n")

    def _load_personalization_widgets(self) -> None:
        self.tb_pos_combo.set_active_id(str(self.prefs.get("taskbar_position", "bottom")))
        self.tb_height_combo.set_active_id(str(self.prefs.get("taskbar_height", 42)))
        self.tb_clock_combo.set_active_id(str(self.prefs.get("clock_format", "%H:%M")))
        self.tb_autohide_sw.set_active(bool(self.prefs.get("taskbar_autohide", False)))
        self.show_icons_sw.set_active(bool(self.prefs.get("show_icons", True)))
        self.icon_size_combo.set_active_id(str(self.prefs.get("icon_size", 48)))
        self.single_click_sw.set_active(bool(self.prefs.get("single_click", True)))
        self.snap_grid_sw.set_active(bool(self.prefs.get("snap_grid", True)))
        self.auto_arrange_sw.set_active(bool(self.prefs.get("auto_arrange", False)))
        self.auto_shortcut_sw.set_active(bool(self.prefs.get("auto_desktop_shortcuts", True)))
        self.dpi_combo.set_active_id(str(self.prefs.get("dpi_scale", 96)))
        self.ws_combo.set_active_id(str(self.prefs.get("workspaces", 2)))
        self.move_combo.set_active_id(str(self.prefs.get("move_mode", "opaque")))
        self.snap_combo.set_active_id(str(self.prefs.get("snap_distance", 12)))
        self.dbl_combo.set_active_id(str(self.prefs.get("double_click_delta", 16)))

    def _set_accent(self, name: str, main_c: str, dark_c: str, light_c: str) -> None:
        self.prefs["accent_color"] = main_c
        self.prefs["accent_dark"] = dark_c
        self.prefs["accent_light"] = light_c
        self._apply_personalization()
        self._set_status(f"Applied accent theme: {name} ({main_c})")

    def _set_wallpaper(self, path: str, label: str = "") -> None:
        if not os.path.isfile(path):
            self._set_status(f"Wallpaper file not found: {path}")
            return
        self.prefs["wallpaper"] = path
        save_json_atomic(DESKTOP_PREFS_PATH, self.prefs)
        subprocess.run(["feh", "--no-fehbg", "--bg-scale", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        signal_desktop_reload()
        self._set_status(f"Wallpaper updated: {label or os.path.basename(path)}")

    def _choose_custom_wallpaper(self) -> None:
        dlg = Gtk.FileChooserDialog(
            title="Choose Desktop Wallpaper Image",
            parent=self,
            action=Gtk.FileChooserAction.OPEN,
        )
        dlg.add_buttons(Gtk.STOCK_CANCEL, Gtk.ResponseType.CANCEL, Gtk.STOCK_OPEN, Gtk.ResponseType.OK)
        flt = Gtk.FileFilter()
        flt.set_name("Images (PNG / JPG / WebP)")
        for pat in ("*.png", "*.jpg", "*.jpeg", "*.bmp", "*.webp"):
            flt.add_pattern(pat)
        dlg.add_filter(flt)
        if dlg.run() == Gtk.ResponseType.OK:
            chosen = dlg.get_filename()
            if chosen:
                self._set_wallpaper(chosen)
        dlg.destroy()

    def _apply_personalization(self, update_dpi: bool = False) -> None:
        if self._suppress_signals:
            return
        self.prefs["taskbar_position"] = self.tb_pos_combo.get_active_id() or "bottom"
        self.prefs["taskbar_height"] = int(self.tb_height_combo.get_active_id() or "42")
        self.prefs["clock_format"] = self.tb_clock_combo.get_active_id() or "%H:%M"
        self.prefs["taskbar_autohide"] = self.tb_autohide_sw.get_active()
        self.prefs["show_icons"] = self.show_icons_sw.get_active()
        self.prefs["icon_size"] = int(self.icon_size_combo.get_active_id() or "48")
        self.prefs["single_click"] = self.single_click_sw.get_active()
        self.prefs["snap_grid"] = self.snap_grid_sw.get_active()
        self.prefs["auto_arrange"] = self.auto_arrange_sw.get_active()
        self.prefs["auto_desktop_shortcuts"] = self.auto_shortcut_sw.get_active()
        self.prefs["dpi_scale"] = int(self.dpi_combo.get_active_id() or "96")
        self.prefs["workspaces"] = int(self.ws_combo.get_active_id() or "2")
        self.prefs["move_mode"] = self.move_combo.get_active_id() or "opaque"
        self.prefs["snap_distance"] = int(self.snap_combo.get_active_id() or "12")
        self.prefs["double_click_delta"] = int(self.dbl_combo.get_active_id() or "16")

        save_json_atomic(DESKTOP_PREFS_PATH, self.prefs)
        write_jwmrc_theme(self.prefs)
        if update_dpi:
            dpi = self.prefs["dpi_scale"]
            font_pt = 10 if dpi <= 96 else (11 if dpi <= 110 else (12 if dpi <= 120 else 13))
            try:
                subprocess.run(["xrdb", "-merge"], input=f"Xft.dpi: {dpi}\n", text=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                ini_path = "/root/.config/gtk-3.0/settings.ini"
                if os.path.isfile(ini_path):
                    lines = open(ini_path, encoding="utf-8").read().splitlines()
                    out = [l for l in lines if not l.startswith("gtk-font-name=")]
                    out.append(f"gtk-font-name=DejaVu Sans {font_pt}")
                    with open(ini_path, "w", encoding="utf-8") as f:
                        f.write("\n".join(out) + "\n")
            except Exception:
                pass
        subprocess.run(["jwm", "-reload"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        signal_desktop_reload()
        self._set_status("Applied personalization live to JWM & Desktop.")

    def _sort_or_reset_desktop(self, mode: str) -> None:
        if mode == "reset":
            try:
                os.remove(DESKTOP_LAYOUT_PATH)
            except OSError:
                pass
            signal_desktop_reload()
            self._set_status("Reset Desktop icon positions to clean Windows grid.")
            return

        # Reorder layout file directly so Desktop canvas snaps immediately
        desk = "/root/Desktop"
        if not os.path.isdir(desk):
            return
        files = [f for f in os.listdir(desk) if not f.startswith(".")]
        if mode == "name":
            files.sort(key=lambda s: s.lower())
        elif mode == "date":
            files.sort(key=lambda s: os.path.getmtime(os.path.join(desk, s)), reverse=True)
        else:
            files.sort()

        icon_sz = int(self.prefs.get("icon_size", 48))
        step_x = icon_sz + 54
        step_y = icon_sz + 56
        tb_pos = str(self.prefs.get("taskbar_position", "bottom"))
        tb_h = int(self.prefs.get("taskbar_height", 42))
        top = (tb_h + 10) if tb_pos == "top" else 12
        screen = Gdk.Screen.get_default()
        sh = screen.get_height() if screen else 600
        rows = max(1, (sh - tb_h - 24) // step_y)

        layout = {}
        for idx, fn in enumerate(files):
            col = idx // rows
            row = idx % rows
            layout[fn] = {"x": 14 + col * step_x, "y": top + row * step_y}
        save_json_atomic(DESKTOP_LAYOUT_PATH, layout)
        signal_desktop_reload()
        self._set_status(f"Arranged {len(files)} desktop icons ({mode}).")

    def _apply_resolution(self, res: str) -> None:
        w, h = res.split("x")
        wp = str(self.prefs.get("wallpaper", "/usr/share/backgrounds/pocketlinux-wallpaper.png"))
        cmd = (
            f"OUT=$(xrandr 2>/dev/null | awk '/ connected/{{print $1; exit}}'); "
            f"xrandr --fb {res} 2>/dev/null || "
            f"(xrandr --newmode {res}_60 60 {w} {w} {w} {w} {h} {h} {h} {h} 2>/dev/null || true; "
            f"[ -n \"$OUT\" ] && xrandr --addmode \"$OUT\" {res}_60 2>/dev/null || true; "
            f"[ -n \"$OUT\" ] && xrandr --output \"$OUT\" --mode {res}_60 2>/dev/null || xrandr -s {res} 2>&1); "
            f"feh --no-fehbg --bg-scale '{wp}' 2>/dev/null || true; "
            f"xdpyinfo | grep dimensions"
        )
        self._run_command(f"Set Resolution {res}", cmd)
        GLib.timeout_add(700, lambda: (signal_desktop_reload(), False)[1])

    def _load_apps_widgets(self) -> None:
        dl_cfg = load_json_safe(DOWNLOADS_CONF_PATH, {"max_streams": 6})
        self.streams_combo.set_active_id(str(dl_cfg.get("max_streams", 6)))
        try:
            with open(INSTALLED_MANIFEST_PATH, "r", encoding="utf-8") as f:
                arr = json.load(f)
                cnt = len(arr) if isinstance(arr, list) else 0
        except Exception:
            cnt = 0
        self.installed_count_lbl.set_text(
            f"Tracked third-party installed apps: {cnt} (visible on Desktop, Applications folder, Start Menu & Android Apps tab)."
        )

    def _save_download_streams(self) -> None:
        if self._suppress_signals:
            return
        streams = int(self.streams_combo.get_active_id() or "6")
        save_json_atomic(DOWNLOADS_CONF_PATH, {"max_streams": streams})

        def post() -> None:
            try:
                req = urllib.request.Request(
                    f"{PKGD_BASE}/api/config",
                    data=json.dumps({"max_streams": streams}).encode("utf-8"),
                    headers={"Content-Type": "application/json"},
                    method="POST",
                )
                urllib.request.urlopen(req, timeout=2.0).read()
            except Exception:
                pass

        threading.Thread(target=post, daemon=True).start()
        self._set_status(f"Download accelerator set to {streams} parallel streams.")

    def _sync_apps_now(self) -> None:
        def done() -> None:
            self._load_apps_widgets()
            self._set_status("Synced all installed apps across Desktop, Applications & Start Menu.")

        self._run_command("Sync Installed Apps", "/usr/local/bin/pocketlinux-sync-apps.py", on_done=done)

    def _load_network_widgets(self) -> None:
        mode = read_file_safe(NET_MODE_PATH, "auto")
        if mode not in ("auto", "always", "off"):
            mode = "auto"
        self.net_mode_combo.set_active_id(mode)

        bridge_up = False
        try:
            with socket.create_connection(("127.0.0.1", 18080), timeout=0.4):
                bridge_up = True
        except OSError:
            bridge_up = False

        proxy_active = "http_proxy=" in read_file_safe(NET_ENV_PATH, "")
        dns_lines = [l.strip() for l in read_file_safe("/etc/resolv.conf", "").splitlines() if l.startswith("nameserver")]
        dns_txt = ", ".join(l.split()[1] for l in dns_lines[:3] if len(l.split()) > 1) or "None"
        bridge_str = (
            "<span foreground='#34D399'><b>Running (127.0.0.1:18080)</b></span>"
            if bridge_up
            else "<span foreground='#FBBF24'>Not reachable</span>"
        )
        proxy_str = "Active (Browsers & APK routed via Android)" if proxy_active else "Direct Linux UDP DNS"
        self.net_status_lbl.set_markup(
            f"<b>Android Network Bridge:</b> {bridge_str}\n"
            f"<b>Routing Mode:</b> {proxy_str}   |   <b>DNS:</b> {GLib.markup_escape_text(dns_txt)}"
        )

    def _save_network_mode(self) -> None:
        if self._suppress_signals:
            return
        mode = self.net_mode_combo.get_active_id() or "auto"
        os.makedirs(CONF_DIR, exist_ok=True)
        with open(NET_MODE_PATH, "w", encoding="utf-8") as f:
            f.write(mode + "\n")
        self._run_dns_fix()

    def _run_dns_fix(self) -> None:
        self._run_command(
            "Sync DNS & Network Bridge",
            "/usr/local/bin/fix-dns.sh && cat /root/.config/pocketlinux/network.env 2>/dev/null || true",
            on_done=self._load_network_widgets,
        )

    def _set_dns_servers(self, dns1: str, dns2: str) -> None:
        try:
            with open("/etc/resolv.conf", "w", encoding="utf-8") as f:
                f.write(f"nameserver {dns1}\nnameserver {dns2}\n")
            self._load_network_widgets()
            self._set_status(f"Updated /etc/resolv.conf to {dns1}, {dns2}.")
        except OSError as exc:
            self._set_status(f"Could not update resolv.conf: {exc}")

    def _load_privacy_widgets(self) -> None:
        hidden = os.path.exists(PRIVACY_FLAG_PATH)
        self.privacy_sw.set_active(hidden)
        try:
            entries = os.listdir("/mnt/phone") if os.path.isdir("/mnt/phone") else []
            count = len(entries)
        except OSError:
            count = 0
        if hidden:
            self.phone_status_lbl.set_markup(
                f"<b>Status:</b> <span foreground='#FBBF24'>Privacy Mode Enabled</span> (currently {count} item(s) in /mnt/phone)."
            )
        elif count > 0:
            self.phone_status_lbl.set_markup(
                f"<b>Status:</b> <span foreground='#34D399'>Phone Storage Accessible</span> ({count} top-level item(s) in /mnt/phone)."
            )
        else:
            self.phone_status_lbl.set_markup(
                "<b>Status:</b> <span foreground='#94A3B8'>/mnt/phone is currently empty.</span> "
                "Tap <b>Grant Phone Files</b> in PocketLinux's top-right Android menu if you want to browse your phone's storage."
            )

    def _on_privacy_toggled(self) -> None:
        if self._suppress_signals:
            return
        os.makedirs(CONF_DIR, exist_ok=True)
        if self.privacy_sw.get_active():
            with open(PRIVACY_FLAG_PATH, "w", encoding="utf-8") as f:
                f.write("1\n")
            self._set_status("Privacy Mode ON — restart Linux (or toggle from Android menu) to mount /mnt/phone empty.")
        else:
            try:
                os.remove(PRIVACY_FLAG_PATH)
            except OSError:
                pass
            self._set_status("Privacy Mode OFF — restart Linux to show Phone Storage in /mnt/phone.")
        self._load_privacy_widgets()

    # ---------- Process & Console Helpers ----------

    def _set_status(self, msg: str) -> None:
        self.status_lbl.set_text(msg)

    def _spawn(self, cmd: str) -> None:
        env = os.environ.copy()
        env.setdefault("DISPLAY", ":1")
        subprocess.Popen(
            ["/bin/sh", "-c", f". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; {cmd}"],
            env=env,
            start_new_session=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def _log(self, text: str) -> None:
        buf = self.console.get_buffer()
        buf.insert(buf.get_end_iter(), text)
        mark = buf.create_mark(None, buf.get_end_iter(), False)
        self.console.scroll_to_mark(mark, 0.0, False, 0.0, 0.0)

    def _run_command(self, title: str, cmd: str, on_done=None) -> None:
        self._show_console()
        self._set_status(f"Running: {title}...")
        self._log(f"\n=== {title} ===\n")

        def worker() -> None:
            proc = subprocess.Popen(
                ["/bin/sh", "-c", cmd],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            assert proc.stdout is not None
            for line in proc.stdout:
                GLib.idle_add(self._log, line)
            code = proc.wait()
            GLib.idle_add(self._log, f"[Finished exitCode={code}]\n")
            GLib.idle_add(self._set_status, f"{title} finished (exit={code}).")
            if on_done is not None:
                GLib.idle_add(on_done)

        threading.Thread(target=worker, daemon=True).start()


def main() -> None:
    initial_page = "system"
    if "--page" in sys.argv:
        idx = sys.argv.index("--page")
        if idx + 1 < len(sys.argv):
            initial_page = sys.argv[idx + 1]

    win = PocketSettingsWindow(initial_page=initial_page)
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    win.console_scroll.hide()
    Gtk.main()


if __name__ == "__main__":
    main()
