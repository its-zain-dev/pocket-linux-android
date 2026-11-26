#!/usr/bin/env python3
"""PocketLinux Download Manager — Standalone GTK-3 App with 0-100% Progress, Speed & Live Logs."""

import json
import os
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
.dl-card {
    background-color: #111827;
    border: 1px solid #1E293B;
    border-radius: 12px;
    padding: 12px;
}
.dl-card-active {
    background-color: #111827;
    border: 1px solid #0EA5E9;
    border-radius: 12px;
    padding: 12px;
}
.stats-banner {
    background-color: #0F172A;
    border: 1px solid #1E293B;
    border-radius: 10px;
    padding: 10px 12px;
}
.badge-active { background-color: #0C2D48; color: #38BDF8; border-radius: 8px; padding: 2px 8px; font-size: 11px; font-weight: bold; }
.badge-done { background-color: #064E3B; color: #34D399; border-radius: 8px; padding: 2px 8px; font-size: 11px; font-weight: bold; }
.badge-fail { background-color: #450A0A; color: #FCA5A5; border-radius: 8px; padding: 2px 8px; font-size: 11px; font-weight: bold; }
.badge-queued { background-color: #1E293B; color: #CBD5E1; border-radius: 8px; padding: 2px 8px; font-size: 11px; font-weight: bold; }
button.primary-btn {
    background-image: none;
    background-color: #0EA5E9;
    color: #FFFFFF;
    border-radius: 8px;
    font-weight: bold;
    padding: 6px 12px;
    border: none;
}
button.secondary-btn {
    background-image: none;
    background-color: #1E293B;
    color: #E2E8F0;
    border-radius: 8px;
    border: 1px solid #334155;
    padding: 5px 10px;
}
button.danger-btn {
    background-image: none;
    background-color: #334155;
    color: #FCA5A5;
    border-radius: 8px;
    border: 1px solid #EF4444;
    padding: 5px 10px;
}
progressbar trough {
    background-color: #1E293B;
    border-radius: 6px;
    min-height: 14px;
}
progressbar progress {
    background-color: #0EA5E9;
    border-radius: 6px;
    min-height: 14px;
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


def fmt_bytes(n: int) -> str:
    if n <= 0:
        return "0 B"
    if n < 1024:
        return f"{n} B"
    if n < 1024 * 1024:
        return f"{n / 1024:.1f} KB"
    return f"{n / (1024 * 1024):.2f} MB"


def fmt_speed(bps: int) -> str:
    if bps <= 0:
        return "0 KB/s"
    if bps < 1024 * 1024:
        return f"{bps / 1024:.0f} KB/s"
    return f"{bps / (1024 * 1024):.2f} MB/s"


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


class DownloadManagerWindow(Gtk.Window):
    def __init__(self) -> None:
        super().__init__(title="PocketLinux Download Manager")
        self.set_default_size(900, 540)
        self.set_position(Gtk.WindowPosition.CENTER)

        self.selected_log_task_id: str | None = None
        self.log_visible = True
        self.last_tasks_sig = ""
        self.last_log_text = ""

        self._apply_css()
        self._build_ui()
        threading.Thread(target=ensure_daemon_running, daemon=True).start()
        GLib.timeout_add(450, self._poll_daemon)

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
        header.set_title("Download Manager")
        header.set_subtitle("Background Multi-Stream Accelerator · Persists When Closed")
        self.set_titlebar(header)

        store_btn = Gtk.Button(label="Open App Store")
        store_btn.get_style_context().add_class("secondary-btn")
        store_btn.connect("clicked", lambda *_: subprocess.Popen(["/usr/local/bin/pocketlinux-store.py"]))
        header.pack_start(store_btn)

        clear_btn = Gtk.Button(label="Clear Completed")
        clear_btn.get_style_context().add_class("secondary-btn")
        clear_btn.connect("clicked", lambda *_: self._async_post("/api/clear-done", {}))
        header.pack_end(clear_btn)

        self.streams_combo = Gtk.ComboBoxText()
        for n in ("2", "4", "6"):
            self.streams_combo.append(n, f"{n} Streams")
        self.streams_combo.set_active_id("6")
        self.streams_combo.connect("changed", self._on_streams_changed)
        header.pack_end(self.streams_combo)

        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        root.set_border_width(10)
        self.add(root)

        # Stats summary bar
        stats_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        stats_box.get_style_context().add_class("stats-banner")
        self.stats_label = Gtk.Label(label="Connecting to background download daemon...")
        self.stats_label.set_xalign(0.0)
        stats_box.pack_start(self.stats_label, True, True, 0)

        folder_btn = Gtk.Button(label="Open Downloads Folder")
        folder_btn.get_style_context().add_class("secondary-btn")
        folder_btn.connect("clicked", lambda *_: subprocess.Popen(["pcmanfm", "/root/Downloads"]))
        stats_box.pack_end(folder_btn, False, False, 0)
        root.pack_start(stats_box, False, False, 0)

        # Direct URL downloader bar
        url_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        self.url_entry = Gtk.Entry()
        self.url_entry.set_placeholder_text("Paste direct file URL (https://...) to accelerate download into /root/Downloads...")
        self.url_entry.connect("activate", self._on_add_url)
        add_btn = Gtk.Button(label="+ Download URL")
        add_btn.get_style_context().add_class("primary-btn")
        add_btn.connect("clicked", self._on_add_url)
        url_row.pack_start(self.url_entry, True, True, 0)
        url_row.pack_start(add_btn, False, False, 0)
        root.pack_start(url_row, False, False, 0)

        # Scrollable active + history task list
        self.tasks_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        tasks_scroll = Gtk.ScrolledWindow()
        tasks_scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        tasks_scroll.add(self.tasks_box)
        root.pack_start(tasks_scroll, True, True, 0)

        # Working Live Log Drawer Header
        log_bar = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.log_title = Gtk.Label()
        self.log_title.set_markup("<b>Live Log Viewer:</b> All Daemon Activity")
        self.log_title.set_xalign(0.0)
        log_bar.pack_start(self.log_title, True, True, 0)

        all_log_btn = Gtk.Button(label="All Logs")
        all_log_btn.get_style_context().add_class("secondary-btn")
        all_log_btn.connect("clicked", lambda *_: self._select_log(None, "All Daemon Activity"))
        log_bar.pack_end(all_log_btn, False, False, 0)

        self.log_toggle = Gtk.ToggleButton(label="Hide Log")
        self.log_toggle.set_active(True)
        self.log_toggle.connect("toggled", self._on_toggle_log)
        log_bar.pack_end(self.log_toggle, False, False, 0)
        root.pack_start(log_bar, False, False, 0)

        self.log_view = Gtk.TextView()
        self.log_view.set_editable(False)
        self.log_view.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        self.log_scroll = Gtk.ScrolledWindow()
        self.log_scroll.set_size_request(-1, 145)
        self.log_scroll.add(self.log_view)
        root.pack_start(self.log_scroll, False, False, 0)

    def _on_toggle_log(self, btn: Gtk.ToggleButton) -> None:
        self.log_visible = btn.get_active()
        if self.log_visible:
            btn.set_label("Hide Log")
            self.log_scroll.show()
            self.log_view.show()
        else:
            btn.set_label("Show Log")
            self.log_scroll.hide()

    def _select_log(self, tid: str | None, title: str) -> None:
        self.selected_log_task_id = tid
        self.last_log_text = ""
        self.log_title.set_markup(f"<b>Live Log Viewer:</b> {GLib.markup_escape_text(title)}")
        if not self.log_toggle.get_active():
            self.log_toggle.set_active(True)

    def _on_streams_changed(self, combo: Gtk.ComboBoxText) -> None:
        val = combo.get_active_id()
        if val and val.isdigit():
            self._async_post("/api/config", {"max_streams": int(val)})

    def _on_add_url(self, *_args) -> None:
        url = self.url_entry.get_text().strip()
        if not url.startswith(("http://", "https://")):
            self.stats_label.set_text("Enter a valid http:// or https:// URL to download.")
            return
        self.url_entry.set_text("")
        self._async_post("/api/download-url", {"url": url})

    def _async_post(self, path: str, payload: dict) -> None:
        def worker() -> None:
            try:
                ensure_daemon_running()
                api_post(path, payload)
            except Exception:
                pass
        threading.Thread(target=worker, daemon=True).start()

    def _poll_daemon(self) -> bool:
        def worker() -> None:
            try:
                st = api_get("/api/status")
                log_q = f"/api/logs?id={self.selected_log_task_id}" if self.selected_log_task_id else "/api/logs"
                lg = api_get(log_q) if self.log_visible else None
                GLib.idle_add(self._apply_snapshot, st, lg)
            except Exception:
                ensure_daemon_running()
        threading.Thread(target=worker, daemon=True).start()
        return True

    def _apply_snapshot(self, st: dict, lg: dict | None) -> None:
        tasks = st.get("tasks", [])
        active_n = st.get("active_count", 0)
        speed = fmt_speed(st.get("total_speed_bps", 0))
        done_n = sum(1 for t in tasks if t.get("status") == "completed")
        streams = st.get("max_streams", 6)

        self.stats_label.set_markup(
            f"<b>Active:</b> {active_n}   |   <b>Speed:</b> <span foreground='#38BDF8'>{speed}</span>   |   "
            f"<b>Completed:</b> <span foreground='#34D399'>{done_n}</span>   |   <b>Streams:</b> {streams}x"
        )

        # Avoid unnecessary widget rebuild when state hasn't changed
        sig = json.dumps(
            [(t["id"], t["status"], round(t.get("progress", 0.0), 1), t.get("stage", ""), t.get("speed_bps", 0), t.get("downloaded_bytes", 0)) for t in tasks]
        )
        if sig != self.last_tasks_sig:
            self.last_tasks_sig = sig
            self._render_tasks(tasks)

        if lg and self.log_visible:
            text = lg.get("log", "")
            if text != self.last_log_text:
                self.last_log_text = text
                buf = self.log_view.get_buffer()
                buf.set_text(text)
                adj = self.log_scroll.get_vadjustment()
                GLib.idle_add(lambda: adj.set_value(adj.get_upper()))

    def _render_tasks(self, tasks: list[dict]) -> None:
        for ch in self.tasks_box.get_children():
            self.tasks_box.remove(ch)

        if not tasks:
            empty = Gtk.Label()
            empty.set_markup(
                "<span foreground='#94A3B8'>No active or recent downloads.\n"
                "Install apps from <b>PocketStore</b> (multiple apps can download in parallel!)\n"
                "or paste any URL above to accelerate downloads into <b>/root/Downloads</b>.</span>"
            )
            empty.set_justify(Gtk.Justification.CENTER)
            empty.set_margin_top(28)
            self.tasks_box.pack_start(empty, False, False, 0)
            self.tasks_box.show_all()
            return

        for t in tasks:
            tid = t["id"]
            status = t.get("status", "queued")
            pct = max(0.0, min(100.0, float(t.get("progress", 0.0))))
            is_active = status in ("queued", "resolving", "downloading", "installing")

            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
            card.get_style_context().add_class("dl-card-active" if is_active else "dl-card")

            top = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
            name_lbl = Gtk.Label()
            name_lbl.set_markup(f"<b>{GLib.markup_escape_text(t.get('name', tid))}</b>")
            name_lbl.set_xalign(0.0)
            top.pack_start(name_lbl, True, True, 0)

            badge = Gtk.Label(label=status.upper())
            if status == "completed":
                badge.get_style_context().add_class("badge-done")
            elif status in ("failed", "cancelled", "interrupted"):
                badge.get_style_context().add_class("badge-fail")
            elif is_active:
                badge.get_style_context().add_class("badge-active")
            else:
                badge.get_style_context().add_class("badge-queued")
            top.pack_end(badge, False, False, 0)
            card.pack_start(top, False, False, 0)

            # Real 0-100% progress bar
            pbar = Gtk.ProgressBar()
            pbar.set_fraction(pct / 100.0)
            pbar.set_show_text(True)
            spd_txt = f" · {fmt_speed(t.get('speed_bps', 0))}" if t.get("speed_bps", 0) > 0 else ""
            sz_txt = ""
            if t.get("total_bytes", 0) > 0:
                sz_txt = f" · {fmt_bytes(t.get('downloaded_bytes', 0))} / {fmt_bytes(t.get('total_bytes', 0))}"
            pbar.set_text(f"{pct:.0f}%{spd_txt}{sz_txt}")
            card.pack_start(pbar, False, False, 0)

            bottom = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
            stage_lbl = Gtk.Label(label=t.get("stage", ""))
            stage_lbl.set_xalign(0.0)
            stage_lbl.set_ellipsize(Pango.EllipsizeMode.END)
            bottom.pack_start(stage_lbl, True, True, 0)

            log_btn = Gtk.Button(label="Log")
            log_btn.get_style_context().add_class("secondary-btn")
            log_btn.connect("clicked", lambda _b, i=tid, n=t.get("name", tid): self._select_log(i, n))
            bottom.pack_end(log_btn, False, False, 0)

            if is_active:
                cancel_btn = Gtk.Button(label="Cancel")
                cancel_btn.get_style_context().add_class("danger-btn")
                cancel_btn.connect("clicked", lambda _b, i=tid: self._async_post("/api/cancel", {"id": i}))
                bottom.pack_end(cancel_btn, False, False, 0)
            elif status in ("failed", "cancelled", "interrupted"):
                retry_btn = Gtk.Button(label="Retry")
                retry_btn.get_style_context().add_class("primary-btn")
                retry_btn.connect("clicked", lambda _b, i=tid: self._async_post("/api/retry", {"id": i}))
                bottom.pack_end(retry_btn, False, False, 0)
            elif status == "completed" and t.get("launch"):
                open_btn = Gtk.Button(label="Open")
                open_btn.get_style_context().add_class("primary-btn")
                cmd = t["launch"]
                open_btn.connect(
                    "clicked",
                    lambda _b, c=cmd: subprocess.Popen(
                        ["/bin/sh", "-c", f". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; exec {c}"]
                    ),
                )
                bottom.pack_end(open_btn, False, False, 0)

            card.pack_start(bottom, False, False, 0)
            self.tasks_box.pack_start(card, False, False, 0)

        self.tasks_box.show_all()


def main() -> None:
    win = DownloadManagerWindow()
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
