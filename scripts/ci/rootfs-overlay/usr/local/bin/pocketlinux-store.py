#!/usr/bin/env python3
"""PocketStore — Multi-Window, Background-Daemon App Store for PocketLinux (Alpine 3.20).

All installs/removals run through pocketlinux-pkgd.py (127.0.0.1:18765), so:
- Closing PocketStore NEVER stops downloads (they finish in background & appear on Desktop).
- Multiple PocketStore windows share the exact same live 0-100% progress & queue.
- Multiple apps can be queued and downloaded in parallel simultaneously.
- Live Log drawer toggles reliably and displays real-time daemon output.
"""

import json
import os
import shutil
import subprocess
import threading
import time
import urllib.request

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk, Pango  # noqa: E402

DAEMON_URL = "http://127.0.0.1:18765"

CSS = b"""
window, .background {
    background-color: #090D16;
    color: #F1F5F9;
}
headerbar {
    background-color: #0F172A;
    border-bottom: 1px solid #1E293B;
    padding: 6px 10px;
}
.store-card {
    background-color: #111827;
    border: 1px solid #1E293B;
    border-radius: 12px;
    padding: 12px;
}
.store-card:hover {
    border-color: #38BDF8;
}
.cat-badge {
    background-color: #0C2D48;
    color: #38BDF8;
    border-radius: 8px;
    padding: 2px 8px;
    font-size: 11px;
    font-weight: bold;
}
.installed-badge {
    background-color: #064E3B;
    color: #34D399;
    border-radius: 8px;
    padding: 2px 8px;
    font-size: 11px;
    font-weight: bold;
}
.dl-badge {
    background-color: #1E3A8A;
    color: #93C5FD;
    border-radius: 8px;
    padding: 2px 8px;
    font-size: 11px;
    font-weight: bold;
}
button.install-btn {
    background-image: none;
    background-color: #0EA5E9;
    color: #FFFFFF;
    border-radius: 8px;
    font-weight: bold;
    padding: 6px 14px;
    border: none;
}
button.open-btn {
    background-image: none;
    background-color: #10B981;
    color: #FFFFFF;
    border-radius: 8px;
    font-weight: bold;
    padding: 6px 14px;
    border: none;
}
button.remove-btn {
    background-image: none;
    background-color: #334155;
    color: #FCA5A5;
    border-radius: 8px;
    font-weight: bold;
    padding: 6px 12px;
    border: 1px solid #EF4444;
}
button.repair-btn {
    background-image: none;
    background-color: #1E293B;
    color: #38BDF8;
    border-radius: 8px;
    border: 1px solid #38BDF8;
    padding: 5px 10px;
}
entry {
    background-color: #050811;
    color: #F1F5F9;
    border: 1px solid #334155;
    border-radius: 8px;
    padding: 6px 10px;
}
textview, textview text {
    background-color: #050811;
    color: #38BDF8;
    font-family: "DejaVu Sans Mono", monospace;
    font-size: 11px;
}
"""

# Curated popular Linux apps verified across BOTH aarch64 and x86_64 in Alpine 3.20
FEATURED_APPS = [
    {
        "name": "Firefox ESR Browser",
        "category": "Internet",
        "desc": "Full desktop Mozilla Firefox browser with HTML5, DevTools & Android DNS Bridge.",
        "packages": ["firefox-esr"],
        "extra_deps": ["ttf-dejavu"],
        "launch": "firefox --no-remote",
        "binary": "firefox",
    },
    {
        "name": "Chromium Web Browser",
        "category": "Internet",
        "desc": "Fast open-source Chrome-engine desktop browser (pre-configured for PRoot).",
        "packages": ["chromium"],
        "extra_deps": ["ttf-dejavu"],
        "launch": "chromium --no-sandbox --disable-gpu-sandbox --disable-dev-shm-usage",
        "binary": "chromium",
    },
    {
        "name": "Luakit Fast Browser",
        "category": "Internet",
        "desc": "Ultra-fast, lightweight WebKitGTK browser with minimal RAM footprint.",
        "packages": ["luakit"],
        "extra_deps": [],
        "launch": "luakit",
        "binary": "luakit",
    },
    {
        "name": "Geany IDE",
        "category": "Development",
        "desc": "Fast, lightweight code editor and IDE with syntax highlighting and terminal.",
        "packages": ["geany"],
        "extra_deps": [],
        "launch": "geany",
        "binary": "geany",
    },
    {
        "name": "GIMP Photo Editor",
        "category": "Graphics",
        "desc": "Professional image editor (Photoshop alternative). Auto-bundles lcms2.",
        "packages": ["gimp"],
        "extra_deps": ["lcms2"],
        "launch": "gimp",
        "binary": "gimp",
    },
    {
        "name": "KolourPaint",
        "category": "Graphics",
        "desc": "Easy-to-use paint and pixel drawing program (classic Windows Paint style).",
        "packages": ["kolourpaint"],
        "extra_deps": [],
        "launch": "kolourpaint",
        "binary": "kolourpaint",
    },
    {
        "name": "MPV Media Player",
        "category": "Graphics",
        "desc": "Hardware-capable video and audio player supporting MP4, MKV, WebM, and streams.",
        "packages": ["mpv"],
        "extra_deps": ["ffmpeg"],
        "launch": "mpv --player-operation-mode=pseudo-gui",
        "binary": "mpv",
    },
    {
        "name": "AbiWord",
        "category": "Office",
        "desc": "Fast word processor compatible with DOCX, RTF, and ODT documents.",
        "packages": ["abiword"],
        "extra_deps": [],
        "launch": "abiword",
        "binary": "abiword",
    },
    {
        "name": "Gnumeric",
        "category": "Office",
        "desc": "Lightweight spreadsheet editor compatible with Excel (.xlsx / .csv).",
        "packages": ["gnumeric"],
        "extra_deps": [],
        "launch": "gnumeric",
        "binary": "gnumeric",
    },
    {
        "name": "MuPDF Viewer",
        "category": "Office",
        "desc": "Blazing-fast PDF, XPS, and CBZ document reader with tiny RAM usage.",
        "packages": ["mupdf"],
        "extra_deps": [],
        "launch": "mupdf",
        "binary": "mupdf",
    },
    {
        "name": "Node.js & npm",
        "category": "Development",
        "desc": "JavaScript runtime for CLI AI agents, full-stack tools, and web servers.",
        "packages": ["nodejs", "npm"],
        "extra_deps": ["git"],
        "launch": "xterm -fa 'DejaVu Sans Mono' -fs 12 -bg '#0F172A' -fg '#E2E8F0' -e sh -c 'node -v; npm -v; exec bash -l'",
        "binary": "node",
    },
    {
        "name": "C/C++ Dev Suite",
        "category": "Development",
        "desc": "GCC compiler, musl-dev headers, make, and Git for building native ELF binaries.",
        "packages": ["gcc", "g++", "musl-dev", "make", "git"],
        "extra_deps": [],
        "launch": "xterm -fa 'DejaVu Sans Mono' -fs 12 -bg '#0F172A' -fg '#E2E8F0' -e sh -c 'gcc --version | head -n1; git --version; exec bash -l'",
        "binary": "gcc",
    },
    {
        "name": "SuperTux",
        "category": "Games",
        "desc": "Classic 2D jump-and-run platformer game (great VirGL GPU test).",
        "packages": ["supertux"],
        "extra_deps": [],
        "launch": "supertux2",
        "binary": "supertux2",
    },
    {
        "name": "DOSBox Staging",
        "category": "Games",
        "desc": "Modernized x86 DOS emulator for classic retro PC games and software.",
        "packages": ["dosbox-staging"],
        "extra_deps": [],
        "launch": "dosbox",
        "binary": "dosbox",
    },
    {
        "name": "Fastfetch System Info",
        "category": "System",
        "desc": "Blazing-fast C system & hardware neofetch dashboard in terminal.",
        "packages": ["fastfetch"],
        "extra_deps": [],
        "launch": "xterm -fa 'DejaVu Sans Mono' -fs 11 -bg '#0F172A' -fg '#E2E8F0' -hold -e fastfetch",
        "binary": "fastfetch",
    },
    {
        "name": "3D GPU Demos (glxgears)",
        "category": "System",
        "desc": "OpenGL glxgears & glxinfo benchmark to test VirGL / Mesa GPU speed.",
        "packages": ["mesa-demos"],
        "extra_deps": [],
        "launch": "glxgears",
        "binary": "glxgears",
    },
]


def ensure_daemon_running() -> None:
    try:
        with urllib.request.urlopen(f"{DAEMON_URL}/api/ping", timeout=0.6):
            return
    except Exception:
        pass
    subprocess.Popen(
        ["/usr/local/bin/pocketlinux-pkgd.py"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    for _ in range(25):
        time.sleep(0.1)
        try:
            with urllib.request.urlopen(f"{DAEMON_URL}/api/ping", timeout=0.5):
                return
        except Exception:
            pass


def api_get(path: str) -> dict:
    with urllib.request.urlopen(f"{DAEMON_URL}{path}", timeout=2.0) as r:
        return json.loads(r.read().decode("utf-8"))


def api_post(path: str, payload: dict | None = None) -> dict:
    raw = json.dumps(payload or {}).encode("utf-8")
    req = urllib.request.Request(
        f"{DAEMON_URL}{path}",
        data=raw,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=3.0) as r:
        return json.loads(r.read().decode("utf-8"))


class PocketStoreWindow(Gtk.Window):
    def __init__(self) -> None:
        super().__init__(title="PocketStore — Linux App Store")
        self.set_default_size(920, 540)
        self.set_position(Gtk.WindowPosition.CENTER)

        self.active_category = "All"
        self.daemon_tasks: list[dict] = []
        self.last_view_sig = ""
        self.last_log_text = ""
        self.log_visible = False

        self._apply_css()
        self._build_ui()
        self.refresh_all_views()

        threading.Thread(target=ensure_daemon_running, daemon=True).start()
        GLib.timeout_add(500, self._poll_daemon)

    def _apply_css(self) -> None:
        provider = Gtk.CssProvider()
        provider.load_from_data(CSS)
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(),
            provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION,
        )

    def _build_ui(self) -> None:
        header = Gtk.HeaderBar()
        header.set_show_close_button(True)
        header.set_title("PocketStore")
        header.set_subtitle("Parallel Background Downloads · Auto-Desktop Integration")
        self.set_titlebar(header)

        self.search_entry = Gtk.SearchEntry()
        self.search_entry.set_placeholder_text("Search all Alpine packages...")
        self.search_entry.set_width_chars(22)
        self.search_entry.connect("activate", self._on_search_triggered)
        header.pack_end(self.search_entry)

        self.dl_mgr_btn = Gtk.Button(label="⬇ Downloads")
        self.dl_mgr_btn.get_style_context().add_class("install-btn")
        self.dl_mgr_btn.connect("clicked", lambda *_: subprocess.Popen(["/usr/local/bin/pocketlinux-downloads.py"]))
        header.pack_start(self.dl_mgr_btn)

        repair_btn = Gtk.Button(label="Fix & Update")
        repair_btn.get_style_context().add_class("repair-btn")
        repair_btn.connect("clicked", lambda *_: self._enqueue_repair())
        header.pack_start(repair_btn)

        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        root.set_border_width(8)
        self.add(root)

        self.stack = Gtk.Stack()
        self.stack.set_transition_type(Gtk.StackTransitionType.CROSSFADE)

        switcher = Gtk.StackSwitcher()
        switcher.set_stack(self.stack)
        switcher.set_halign(Gtk.Align.CENTER)
        root.pack_start(switcher, False, False, 2)

        # Tab 1: Featured / Popular Home
        home_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        cat_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        for cat in ("All", "Internet", "Development", "Graphics", "Office", "Games", "System"):
            btn = Gtk.Button(label=cat)
            btn.connect("clicked", self._on_category_clicked, cat)
            cat_row.pack_start(btn, False, False, 0)
        cat_scroll = Gtk.ScrolledWindow()
        cat_scroll.set_policy(Gtk.PolicyType.AUTOMATIC, Gtk.PolicyType.NEVER)
        cat_scroll.add(cat_row)
        home_box.pack_start(cat_scroll, False, False, 0)

        self.featured_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        feat_scroll = Gtk.ScrolledWindow()
        feat_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        feat_scroll.add(self.featured_box)
        home_box.pack_start(feat_scroll, True, True, 0)
        self.stack.add_titled(home_box, "featured", "Popular & Featured")

        # Tab 2: Live Alpine Package Search
        search_tab = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        search_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.tab_search_entry = Gtk.Entry()
        self.tab_search_entry.set_placeholder_text("Type package name (e.g. python, vim, firefox, chromium, vlc)...")
        self.tab_search_entry.connect("activate", self._on_tab_search_clicked)
        search_btn = Gtk.Button(label="Search")
        search_btn.get_style_context().add_class("install-btn")
        search_btn.connect("clicked", self._on_tab_search_clicked)
        search_bar.pack_start(self.tab_search_entry, True, True, 0)
        search_bar.pack_start(search_btn, False, False, 0)
        search_tab.pack_start(search_bar, False, False, 0)

        self.search_results_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        search_scroll = Gtk.ScrolledWindow()
        search_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        search_scroll.add(self.search_results_box)
        search_tab.pack_start(search_scroll, True, True, 0)
        self.stack.add_titled(search_tab, "search", "Search All Packages")

        # Tab 3: Installed
        self.installed_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        inst_scroll = Gtk.ScrolledWindow()
        inst_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        inst_scroll.add(self.installed_box)
        self.stack.add_titled(inst_scroll, "installed", "Installed")

        root.pack_start(self.stack, True, True, 0)

        # Bottom live daemon status & FIXED Log toggle drawer
        self.status_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.status_label = Gtk.Label(label="Ready · Downloads continue in background even if you close this window")
        self.status_label.set_xalign(0.0)
        self.status_label.set_ellipsize(Pango.EllipsizeMode.END)

        self.progress = Gtk.ProgressBar()
        self.progress.set_size_request(150, -1)
        self.progress.set_show_text(True)

        self.log_toggle = Gtk.ToggleButton(label="Show Log")
        self.log_toggle.connect("toggled", self._on_toggle_log)
        self.status_bar.pack_start(self.status_label, True, True, 0)
        self.status_bar.pack_start(self.progress, False, False, 0)
        self.status_bar.pack_start(self.log_toggle, False, False, 0)
        root.pack_start(self.status_bar, False, False, 0)

        self.log_view = Gtk.TextView()
        self.log_view.set_editable(False)
        self.log_view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        self.log_scroll = Gtk.ScrolledWindow()
        self.log_scroll.set_size_request(-1, 130)
        self.log_scroll.add(self.log_view)
        root.pack_start(self.log_scroll, False, False, 0)

    def show_initial(self) -> None:
        self.show_all()
        # Hide optional widgets AFTER show_all (never use set_no_show_all(True) + show_all()!)
        self.progress.hide()
        self.log_scroll.hide()

    def _on_toggle_log(self, btn: Gtk.ToggleButton) -> None:
        self.log_visible = btn.get_active()
        if self.log_visible:
            btn.set_label("Hide Log")
            self.log_scroll.show()
            self.log_view.show()
            self._fetch_log_now()
        else:
            btn.set_label("Show Log")
            self.log_scroll.hide()

    def _fetch_log_now(self) -> None:
        def worker() -> None:
            try:
                lg = api_get("/api/logs")
                GLib.idle_add(self._set_log_text, lg.get("log", ""))
            except Exception:
                pass
        threading.Thread(target=worker, daemon=True).start()

    def _set_log_text(self, text: str) -> None:
        if text == self.last_log_text:
            return
        self.last_log_text = text
        buf = self.log_view.get_buffer()
        buf.set_text(text)
        adj = self.log_scroll.get_vadjustment()
        GLib.idle_add(lambda: adj.set_value(adj.get_upper()))

    def _on_category_clicked(self, _btn: Gtk.Button, cat: str) -> None:
        self.active_category = cat
        self._populate_featured()

    def _is_app_installed(self, app: dict) -> bool:
        return shutil.which(app["binary"]) is not None

    def _active_task_for_packages(self, pkgs: list[str]) -> dict | None:
        pkg_set = set(pkgs)
        for t in self.daemon_tasks:
            if t.get("status") in ("queued", "resolving", "downloading", "installing"):
                if set(t.get("packages", [])) & pkg_set:
                    return t
        return None

    def _clear_box(self, box: Gtk.Box) -> None:
        for child in box.get_children():
            box.remove(child)

    def refresh_all_views(self) -> None:
        self._populate_featured()
        self._populate_installed()

    def _populate_featured(self) -> None:
        self._clear_box(self.featured_box)
        for app in FEATURED_APPS:
            if self.active_category != "All" and app["category"] != self.active_category:
                continue
            self.featured_box.pack_start(self._build_app_card(app), False, False, 0)
        self.featured_box.show_all()

    def _populate_installed(self) -> None:
        self._clear_box(self.installed_box)
        installed = [a for a in FEATURED_APPS if self._is_app_installed(a)]
        if not installed:
            lbl = Gtk.Label(label="No featured apps installed yet. Install multiple apps in parallel from Popular & Featured!")
            lbl.set_margin_top(24)
            self.installed_box.pack_start(lbl, False, False, 0)
        else:
            for app in installed:
                self.installed_box.pack_start(self._build_app_card(app), False, False, 0)
        self.installed_box.show_all()

    def _build_app_card(self, app: dict) -> Gtk.Widget:
        installed = self._is_app_installed(app)
        active_task = self._active_task_for_packages(app["packages"])

        card = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        card.get_style_context().add_class("store-card")

        info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        top = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)

        title = Gtk.Label()
        title.set_markup(f"<b>{GLib.markup_escape_text(app['name'])}</b>")
        title.set_xalign(0.0)
        top.pack_start(title, False, False, 0)

        cat_lbl = Gtk.Label(label=app["category"])
        cat_lbl.get_style_context().add_class("cat-badge")
        top.pack_start(cat_lbl, False, False, 0)

        if active_task:
            pct = float(active_task.get("progress", 0.0))
            dl_lbl = Gtk.Label(label=f"{active_task['status'].capitalize()} {pct:.0f}%")
            dl_lbl.get_style_context().add_class("dl-badge")
            top.pack_start(dl_lbl, False, False, 0)
        elif installed:
            inst_lbl = Gtk.Label(label="Installed")
            inst_lbl.get_style_context().add_class("installed-badge")
            top.pack_start(inst_lbl, False, False, 0)

        desc = Gtk.Label(label=app["desc"])
        desc.set_xalign(0.0)
        desc.set_line_wrap(True)
        info.pack_start(top, False, False, 0)
        info.pack_start(desc, False, False, 0)
        card.pack_start(info, True, True, 0)

        actions = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        actions.set_valign(Gtk.Align.CENTER)
        if active_task:
            tid = active_task["id"]
            prog_btn = Gtk.Button(label=f"{float(active_task.get('progress', 0)):.0f}% · View")
            prog_btn.get_style_context().add_class("repair-btn")
            prog_btn.connect("clicked", lambda *_: subprocess.Popen(["/usr/local/bin/pocketlinux-downloads.py"]))
            actions.pack_start(prog_btn, False, False, 0)

            cancel_btn = Gtk.Button(label="Cancel")
            cancel_btn.get_style_context().add_class("remove-btn")
            cancel_btn.connect("clicked", lambda _b, i=tid: self._async_post("/api/cancel", {"id": i}))
            actions.pack_start(cancel_btn, False, False, 0)
        elif installed:
            open_btn = Gtk.Button(label="Open")
            open_btn.get_style_context().add_class("open-btn")
            open_btn.connect("clicked", lambda *_: self._launch_command(app["launch"], app["name"]))
            actions.pack_start(open_btn, False, False, 0)

            rm_btn = Gtk.Button(label="Remove")
            rm_btn.get_style_context().add_class("remove-btn")
            rm_btn.connect("clicked", lambda *_: self._enqueue_remove(app["packages"], app["name"]))
            actions.pack_start(rm_btn, False, False, 0)
        else:
            inst_btn = Gtk.Button(label="Install")
            inst_btn.get_style_context().add_class("install-btn")
            pkgs = app["packages"] + app.get("extra_deps", [])
            inst_btn.connect("clicked", lambda *_: self._enqueue_install(pkgs, app["name"], app["launch"]))
            actions.pack_start(inst_btn, False, False, 0)

        card.pack_start(actions, False, False, 0)
        return card

    def _on_search_triggered(self, entry: Gtk.SearchEntry) -> None:
        query = entry.get_text().strip()
        if not query:
            return
        self.tab_search_entry.set_text(query)
        self.stack.set_visible_child_name("search")
        self._perform_search(query)

    def _on_tab_search_clicked(self, *_args) -> None:
        query = self.tab_search_entry.get_text().strip()
        if query:
            self._perform_search(query)

    def _perform_search(self, query: str) -> None:
        self._clear_box(self.search_results_box)
        loading = Gtk.Label(label=f"Searching Alpine repositories for '{query}'...")
        loading.set_margin_top(16)
        self.search_results_box.pack_start(loading, False, False, 0)
        self.search_results_box.show_all()

        def worker() -> None:
            subprocess.run(["/usr/local/bin/fix-dns.sh"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                res = subprocess.run(
                    ["/bin/sh", "-c", f". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; apk search -v -- {query!r}"],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    timeout=30,
                )
                out_lines = res.stdout.splitlines()
            except Exception:
                out_lines = []
            items = []
            seen = set()
            for line in out_lines:
                if " - " not in line:
                    continue
                pkg_ver, desc = line.split(" - ", 1)
                parts = pkg_ver.strip().split("-")
                pkg_name = pkg_ver.strip()
                for idx in range(len(parts) - 1, 0, -1):
                    if parts[idx] and parts[idx][0].isdigit():
                        pkg_name = "-".join(parts[:idx])
                        break
                if pkg_name not in seen:
                    seen.add(pkg_name)
                    items.append((pkg_name, desc.strip()))
                if len(items) >= 40:
                    break
            GLib.idle_add(self._render_search_results, query, items)

        threading.Thread(target=worker, daemon=True).start()

    def _render_search_results(self, query: str, items: list) -> None:
        self._clear_box(self.search_results_box)
        if not items:
            lbl = Gtk.Label(label=f"No Alpine packages found for '{query}'.")
            lbl.set_margin_top(16)
            self.search_results_box.pack_start(lbl, False, False, 0)
        else:
            for pkg_name, desc in items:
                card = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
                card.get_style_context().add_class("store-card")
                info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
                lbl = Gtk.Label()
                lbl.set_markup(f"<b>{GLib.markup_escape_text(pkg_name)}</b>")
                lbl.set_xalign(0.0)
                d_lbl = Gtk.Label(label=desc)
                d_lbl.set_xalign(0.0)
                d_lbl.set_line_wrap(True)
                info.pack_start(lbl, False, False, 0)
                info.pack_start(d_lbl, False, False, 0)
                card.pack_start(info, True, True, 0)

                btn = Gtk.Button(label="Install")
                btn.get_style_context().add_class("install-btn")
                btn.set_valign(Gtk.Align.CENTER)
                btn.connect("clicked", lambda _b, p=pkg_name: self._enqueue_install([p], p, p))
                card.pack_start(btn, False, False, 0)
                self.search_results_box.pack_start(card, False, False, 0)
        self.search_results_box.show_all()

    def _async_post(self, path: str, payload: dict) -> None:
        def worker() -> None:
            try:
                ensure_daemon_running()
                api_post(path, payload)
            except Exception:
                pass
        threading.Thread(target=worker, daemon=True).start()

    def _enqueue_install(self, packages: list, display_name: str, launch: str = "") -> None:
        self.status_label.set_text(f"Queued {display_name} in background Download Manager...")
        self._async_post("/api/install", {"name": display_name, "packages": packages, "launch": launch})

    def _enqueue_remove(self, packages: list, display_name: str) -> None:
        self.status_label.set_text(f"Queued removal of {display_name}...")
        self._async_post("/api/remove", {"name": display_name, "packages": packages})

    def _enqueue_repair(self) -> None:
        self.status_label.set_text("Queued System Update & Library Repair in background...")
        self._async_post("/api/repair", {})

    def _poll_daemon(self) -> bool:
        def worker() -> None:
            try:
                st = api_get("/api/status")
                lg = api_get("/api/logs") if self.log_visible else None
                GLib.idle_add(self._apply_daemon_state, st, lg)
            except Exception:
                ensure_daemon_running()
        threading.Thread(target=worker, daemon=True).start()
        return True

    def _apply_daemon_state(self, st: dict, lg: dict | None) -> None:
        self.daemon_tasks = st.get("tasks", [])
        active = [t for t in self.daemon_tasks if t["status"] in ("queued", "resolving", "downloading", "installing")]
        if active:
            self.dl_mgr_btn.set_label(f"⬇ Downloads ({len(active)})")
            avg_pct = sum(float(t.get("progress", 0.0)) for t in active) / len(active)
            spd_bps = st.get("total_speed_bps", 0)
            spd_str = f"{spd_bps / (1024*1024):.2f} MB/s" if spd_bps >= 1024 * 1024 else f"{spd_bps / 1024:.0f} KB/s"
            names = ", ".join(t["name"] for t in active[:2])
            if len(active) > 2:
                names += f" +{len(active)-2}"
            self.status_label.set_text(f"Background: {names} ({spd_str})")
            self.progress.set_fraction(max(0.0, min(1.0, avg_pct / 100.0)))
            self.progress.set_text(f"{avg_pct:.0f}%")
            self.progress.show()
        else:
            self.dl_mgr_btn.set_label("⬇ Downloads")
            self.progress.hide()
            if self.daemon_tasks:
                latest = self.daemon_tasks[0]
                self.status_label.set_text(f"Last task: {latest['name']} — {latest['stage']}")

        installed_sig = tuple(self._is_app_installed(a) for a in FEATURED_APPS)
        task_sig = tuple((t["id"], t["status"], int(float(t.get("progress", 0.0)))) for t in active)
        sig = f"{self.active_category}|{installed_sig}|{task_sig}"
        if sig != self.last_view_sig:
            self.last_view_sig = sig
            self.refresh_all_views()

        if lg and self.log_visible:
            self._set_log_text(lg.get("log", ""))

    def _launch_command(self, cmd: str, name: str) -> None:
        self.status_label.set_text(f"Launching {name}...")
        env = os.environ.copy()
        env.setdefault("DISPLAY", ":1")
        subprocess.Popen(["/bin/sh", "-c", f". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; exec {cmd}"], env=env)


def main() -> None:
    win = PocketStoreWindow()
    win.connect("destroy", Gtk.main_quit)
    win.show_initial()
    Gtk.main()


if __name__ == "__main__":
    main()
