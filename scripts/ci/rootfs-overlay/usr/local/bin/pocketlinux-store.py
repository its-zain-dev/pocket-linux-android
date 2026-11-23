#!/usr/bin/env python3
"""PocketStore - Google Play-style GTK-3 App Store for PocketLinux (Alpine 3.20)."""

import os
import shlex
import shutil
import subprocess
import threading
import time

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk, Pango  # noqa: E402

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
    color: #94A3B8;
    font-family: "DejaVu Sans Mono", monospace;
    font-size: 11px;
}
"""

# Curated popular Linux apps with automatic companion libraries so nothing breaks
FEATURED_APPS = [
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
        "name": "NetSurf Browser",
        "category": "Internet",
        "desc": "Ultra-fast, low-RAM web browser designed for lightweight Linux desktops.",
        "packages": ["netsurf"],
        "extra_deps": [],
        "launch": "netsurf",
        "binary": "netsurf",
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
        "name": "mtPaint",
        "category": "Graphics",
        "desc": "Super lightweight pixel-art and photo painting tool.",
        "packages": ["mtpaint"],
        "extra_deps": [],
        "launch": "mtpaint",
        "binary": "mtpaint",
    },
    {
        "name": "Node.js & npm",
        "category": "Development",
        "desc": "JavaScript runtime for OpenClaw, CLI AI agents, and web servers.",
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
        "desc": "Classic 2D jump-and-run platformer game (great graphics & audio test).",
        "packages": ["supertux"],
        "extra_deps": [],
        "launch": "supertux2",
        "binary": "supertux2",
    },
    {
        "name": "DOSBox",
        "category": "Games",
        "desc": "x86 DOS emulator for running classic retro games and programs.",
        "packages": ["dosbox"],
        "extra_deps": [],
        "launch": "dosbox",
        "binary": "dosbox",
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


class PocketStoreWindow(Gtk.Window):
    def __init__(self) -> None:
        super().__init__(title="PocketStore — Linux App Store")
        self.set_default_size(900, 520)
        self.set_position(Gtk.WindowPosition.CENTER)

        self.busy = False
        self.last_apk_update = 0.0
        self.active_category = "All"

        self._apply_css()
        self._build_ui()
        self.refresh_all_views()

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
        header.set_subtitle("Alpine Linux 3.20 · Auto-Dependency Installer")
        self.set_titlebar(header)

        self.search_entry = Gtk.SearchEntry()
        self.search_entry.set_placeholder_text("Search all Alpine packages...")
        self.search_entry.set_width_chars(24)
        self.search_entry.connect("activate", self._on_search_triggered)
        header.pack_end(self.search_entry)

        repair_btn = Gtk.Button(label="Fix & Update")
        repair_btn.get_style_context().add_class("repair-btn")
        repair_btn.set_tooltip_text("Run apk update && apk upgrade && apk fix")
        repair_btn.connect("clicked", lambda *_: self._run_repair_all())
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
        for cat in ("All", "Development", "Graphics", "Office", "Internet", "Games", "System"):
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
        self.tab_search_entry.set_placeholder_text("Type package name (e.g. python, vim, firefox, sdl2)...")
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

        # Bottom live progress & log drawer
        self.status_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.status_label = Gtk.Label(label="Ready · Browse apps or search Alpine repositories")
        self.status_label.set_xalign(0.0)
        self.status_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.progress = Gtk.ProgressBar()
        self.progress.set_size_request(140, -1)
        self.progress.set_no_show_all(True)

        self.log_toggle = Gtk.ToggleButton(label="Log")
        self.log_toggle.connect("toggled", self._on_toggle_log)
        self.status_bar.pack_start(self.status_label, True, True, 0)
        self.status_bar.pack_start(self.progress, False, False, 0)
        self.status_bar.pack_start(self.log_toggle, False, False, 0)
        root.pack_start(self.status_bar, False, False, 0)

        self.log_view = Gtk.TextView()
        self.log_view.set_editable(False)
        self.log_view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        self.log_scroll = Gtk.ScrolledWindow()
        self.log_scroll.set_size_request(-1, 120)
        self.log_scroll.add(self.log_view)
        self.log_scroll.set_no_show_all(True)
        root.pack_start(self.log_scroll, False, False, 0)

    def _on_toggle_log(self, btn: Gtk.ToggleButton) -> None:
        if btn.get_active():
            self.log_scroll.show_all()
        else:
            self.log_scroll.hide()

    def _on_category_clicked(self, _btn: Gtk.Button, cat: str) -> None:
        self.active_category = cat
        self._populate_featured()

    def _is_app_installed(self, app: dict) -> bool:
        return shutil.which(app["binary"]) is not None

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
            lbl = Gtk.Label(label="No featured apps installed yet. Install any app from Popular & Featured!")
            lbl.set_margin_top(24)
            self.installed_box.pack_start(lbl, False, False, 0)
        else:
            for app in installed:
                self.installed_box.pack_start(self._build_app_card(app), False, False, 0)
        self.installed_box.show_all()

    def _build_app_card(self, app: dict) -> Gtk.Widget:
        installed = self._is_app_installed(app)
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

        if installed:
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
        if installed:
            open_btn = Gtk.Button(label="Open")
            open_btn.get_style_context().add_class("open-btn")
            open_btn.connect("clicked", lambda *_: self._launch_command(app["launch"], app["name"]))
            actions.pack_start(open_btn, False, False, 0)

            rm_btn = Gtk.Button(label="Remove")
            rm_btn.get_style_context().add_class("remove-btn")
            rm_btn.connect("clicked", lambda *_: self._remove_packages(app["packages"], app["name"]))
            actions.pack_start(rm_btn, False, False, 0)
        else:
            inst_btn = Gtk.Button(label="Install")
            inst_btn.get_style_context().add_class("install-btn")
            pkgs = app["packages"] + app.get("extra_deps", [])
            inst_btn.connect("clicked", lambda *_: self._install_packages(pkgs, app["name"]))
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
            if time.time() - self.last_apk_update > 3600:
                subprocess.run(["apk", "update"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                self.last_apk_update = time.time()
            res = subprocess.run(
                ["apk", "search", "-v", query],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                timeout=30,
            )
            items = []
            seen = set()
            for line in res.stdout.splitlines():
                if " - " not in line:
                    continue
                pkg_ver, desc = line.split(" - ", 1)
                # Strip -version-rX from package name
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
                btn.connect("clicked", lambda _b, p=pkg_name: self._install_packages([p], p))
                card.pack_start(btn, False, False, 0)
                self.search_results_box.pack_start(card, False, False, 0)
        self.search_results_box.show_all()

    def _append_log(self, text: str) -> None:
        buf = self.log_view.get_buffer()
        buf.insert(buf.get_end_iter(), text)
        adj = self.log_scroll.get_vadjustment()
        adj.set_value(adj.get_upper())

    def _set_busy(self, busy: bool, status: str) -> None:
        self.busy = busy
        self.status_label.set_text(status)
        if busy:
            self.progress.show()
            self.log_toggle.set_active(True)
            GLib.timeout_add(120, self._pulse_progress)
        else:
            self.progress.hide()

    def _pulse_progress(self) -> bool:
        if self.busy:
            self.progress.pulse()
            return True
        return False

    def _run_apk_pipeline(self, title: str, shell_cmd: str) -> None:
        if self.busy:
            self.status_label.set_text("Another installation is already running — please wait...")
            return
        self._set_busy(True, f"{title}...")
        self._append_log(f"\n=== {title} ===\n$ {shell_cmd}\n")

        def worker() -> None:
            proc = subprocess.Popen(
                ["/bin/sh", "-c", shell_cmd],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            assert proc.stdout is not None
            for line in proc.stdout:
                GLib.idle_add(self._append_log, line)
            code = proc.wait()
            if code == 0:
                msg = f"Completed: {title}"
            else:
                msg = f"Failed ({code}): {title} — check Log"
            GLib.idle_add(self._on_pipeline_done, msg)

        threading.Thread(target=worker, daemon=True).start()

    def _on_pipeline_done(self, status_msg: str) -> None:
        self._set_busy(False, status_msg)
        self.refresh_all_views()

    def _install_packages(self, packages: list, display_name: str) -> None:
        quoted = " ".join(shlex.quote(p) for p in packages)
        # Auto-update index + upgrade shared libs + fix any missing dependencies automatically
        cmd = f"/usr/local/bin/fix-dns.sh 2>/dev/null || true; apk update && apk add --upgrade {quoted} && apk fix"
        self.last_apk_update = time.time()
        self._run_apk_pipeline(f"Installing {display_name}", cmd)

    def _remove_packages(self, packages: list, display_name: str) -> None:
        quoted = " ".join(shlex.quote(p) for p in packages)
        cmd = f"apk del {quoted}"
        self._run_apk_pipeline(f"Removing {display_name}", cmd)

    def _run_repair_all(self) -> None:
        self.last_apk_update = time.time()
        self._run_apk_pipeline("Updating & Repairing All Libraries", "/usr/local/bin/fix-dns.sh 2>/dev/null || true; apk update && apk upgrade && apk fix")

    def _launch_command(self, cmd: str, name: str) -> None:
        self.status_label.set_text(f"Launching {name}...")
        env = os.environ.copy()
        env.setdefault("DISPLAY", ":1")
        subprocess.Popen(["/bin/sh", "-c", f". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; {cmd}"], env=env)


def main() -> None:
    win = PocketStoreWindow()
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
