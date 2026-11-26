#!/usr/bin/env python3
"""PocketLinux Settings & GPU Control Center (GTK-3 for Alpine 3.20)."""

import os
import subprocess
import threading

import gi

gi.require_version("Gtk", "3.0")
from gi.repository import Gdk, GLib, Gtk  # noqa: E402

GPU_CONF_DIR = "/root/.config/pocketlinux"
GPU_ENV_PATH = os.path.join(GPU_CONF_DIR, "gpu.env")

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
.panel-card {
    background-color: #111827;
    border: 1px solid #1E293B;
    border-radius: 12px;
    padding: 12px;
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
button.secondary-btn {
    background-image: none;
    background-color: #1E293B;
    color: #E2E8F0;
    border-radius: 8px;
    border: 1px solid #334155;
    padding: 7px 12px;
}
textview, textview text {
    background-color: #050811;
    color: #38BDF8;
    font-family: "DejaVu Sans Mono", monospace;
    font-size: 11px;
}
"""


def read_file_safe(path: str, default: str = "Unknown") -> str:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            val = f.read().strip()
            return val if val else default
    except OSError:
        return default


class PocketSettingsWindow(Gtk.Window):
    def __init__(self) -> None:
        super().__init__(title="PocketLinux Settings & GPU Control")
        self.set_default_size(860, 500)
        self.set_position(Gtk.WindowPosition.CENTER)

        self._apply_css()
        self._build_ui()
        self._load_saved_gpu_config()
        self._refresh_hw_probe()

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
        header.set_title("PocketLinux Settings")
        header.set_subtitle("GPU Acceleration · Display · System Repair")
        self.set_titlebar(header)

        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        root.set_border_width(10)
        self.add(root)

        self.stack = Gtk.Stack()
        self.stack.set_transition_type(Gtk.StackTransitionType.CROSSFADE)
        switcher = Gtk.StackSwitcher()
        switcher.set_stack(self.stack)
        switcher.set_halign(Gtk.Align.CENTER)
        root.pack_start(switcher, False, False, 0)

        self.stack.add_titled(self._build_gpu_tab(), "gpu", "GPU & Acceleration")
        self.stack.add_titled(self._build_display_tab(), "display", "Display & Speed")
        self.stack.add_titled(self._build_system_tab(), "system", "System & Repair")
        root.pack_start(self.stack, True, True, 0)

        # Shared live console output at bottom
        self.console = Gtk.TextView()
        self.console.set_editable(False)
        self.console.set_wrap_mode(Gtk.WrapMode.WORD_CHAR)
        cscroll = Gtk.ScrolledWindow()
        cscroll.set_size_request(-1, 130)
        cscroll.add(self.console)
        root.pack_start(cscroll, False, False, 0)

    def _build_gpu_tab(self) -> Gtk.Widget:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)

        hw_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        hw_card.get_style_context().add_class("panel-card")
        self.hw_label = Gtk.Label(label="Probing Android GPU & VirGL server...")
        self.hw_label.set_xalign(0.0)
        self.hw_label.set_line_wrap(True)
        hw_card.pack_start(self.hw_label, False, False, 0)
        box.pack_start(hw_card, False, False, 0)

        cfg_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        cfg_card.get_style_context().add_class("panel-card")

        # Driver mode
        row1 = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        row1.pack_start(Gtk.Label(label="GPU Driver Mode:", xalign=0.0), True, True, 0)
        self.driver_combo = Gtk.ComboBoxText()
        self.driver_combo.append("auto", "Auto (VirGL Hardware if active, else LLVMpipe)")
        self.driver_combo.append("virpipe", "VirGL Hardware GPU (GALLIUM_DRIVER=virpipe)")
        self.driver_combo.append("llvmpipe", "Fast CPU Software (GALLIUM_DRIVER=llvmpipe)")
        self.driver_combo.append("zink", "Vulkan Zink (Adreno 6xx/7xx)")
        self.driver_combo.set_active_id("auto")
        row1.pack_start(self.driver_combo, False, False, 0)
        cfg_card.pack_start(row1, False, False, 0)

        # OpenGL version override
        row2 = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        row2.pack_start(Gtk.Label(label="OpenGL Version Override:", xalign=0.0), True, True, 0)
        self.gl_combo = Gtk.ComboBoxText()
        for val, lbl in (("default", "Default"), ("3.3", "OpenGL 3.3"), ("4.0", "OpenGL 4.0"), ("4.5", "OpenGL 4.5")):
            self.gl_combo.append(val, lbl)
        self.gl_combo.set_active_id("default")
        row2.pack_start(self.gl_combo, False, False, 0)
        cfg_card.pack_start(row2, False, False, 0)

        # Render threads
        row3 = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        row3.pack_start(Gtk.Label(label="Software Threads (LP_NUM_THREADS):", xalign=0.0), True, True, 0)
        self.threads_combo = Gtk.ComboBoxText()
        for val, lbl in (("4", "4 Threads (Recommended)"), ("2", "2 Threads (Low RAM)"), ("auto", "Auto")):
            self.threads_combo.append(val, lbl)
        self.threads_combo.set_active_id("4")
        row3.pack_start(self.threads_combo, False, False, 0)
        cfg_card.pack_start(row3, False, False, 0)

        # MESA_NO_ERROR switch
        row4 = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        row4.pack_start(Gtk.Label(label="Skip GL Error Checks (MESA_NO_ERROR=1 for extra FPS):", xalign=0.0), True, True, 0)
        self.no_error_switch = Gtk.Switch()
        self.no_error_switch.set_active(True)
        row4.pack_start(self.no_error_switch, False, False, 0)
        cfg_card.pack_start(row4, False, False, 0)

        # Action buttons
        btn_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        save_btn = Gtk.Button(label="Apply & Save GPU Settings")
        save_btn.get_style_context().add_class("primary-btn")
        save_btn.connect("clicked", lambda *_: self._save_gpu_config())
        btn_row.pack_start(save_btn, False, False, 0)

        glx_btn = Gtk.Button(label="Test 3D (glxgears)")
        glx_btn.get_style_context().add_class("secondary-btn")
        glx_btn.connect("clicked", lambda *_: self._launch_with_gpu("glxgears"))
        btn_row.pack_start(glx_btn, False, False, 0)

        info_btn = Gtk.Button(label="Show OpenGL Info")
        info_btn.get_style_context().add_class("secondary-btn")
        info_btn.connect("clicked", lambda *_: self._run_command("OpenGL Renderer Info", ". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null; glxinfo -B 2>&1 || echo 'Install mesa-demos from PocketStore for glxinfo'"))
        btn_row.pack_start(info_btn, False, False, 0)

        cfg_card.pack_start(btn_row, False, False, 4)
        box.pack_start(cfg_card, False, False, 0)

        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        scroll.add(box)
        return scroll

    def _build_display_tab(self) -> Gtk.Widget:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)

        res_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        res_card.get_style_context().add_class("panel-card")
        res_card.pack_start(Gtk.Label(label="Desktop Resolution Presets (Live xrandr):", xalign=0.0), False, False, 0)

        res_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        for label, res in (
            ("960x540 (Turbo 3D)", "960x540"),
            ("1024x600", "1024x600"),
            ("1280x720", "1280x720"),
            ("1440x810", "1440x810"),
            ("1600x900", "1600x900"),
        ):
            btn = Gtk.Button(label=label)
            btn.get_style_context().add_class("secondary-btn")
            w, h = res.split("x")
            cmd = (
                f"OUT=$(xrandr 2>/dev/null | awk '/ connected/{{print $1; exit}}'); "
                f"xrandr --fb {res} 2>/dev/null || "
                f"(xrandr --newmode {res}_60 60 {w} {w} {w} {w} {h} {h} {h} {h} 2>/dev/null || true; "
                f"[ -n \"$OUT\" ] && xrandr --addmode \"$OUT\" {res}_60 2>/dev/null || true; "
                f"[ -n \"$OUT\" ] && xrandr --output \"$OUT\" --mode {res}_60 2>/dev/null || xrandr -s {res} 2>&1); "
                f"feh --no-fehbg --bg-scale /usr/share/backgrounds/pocketlinux-wallpaper.png 2>/dev/null || true; "
                f"echo 'Active display geometry:' && xdpyinfo | grep dimensions"
            )
            btn.connect("clicked", lambda _b, r=res, c=cmd: self._run_command(f"Set Resolution {r}", c))
            res_row.pack_start(btn, False, False, 0)
        res_card.pack_start(res_row, False, False, 0)
        box.pack_start(res_card, False, False, 0)

        perf_card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        perf_card.get_style_context().add_class("panel-card")
        perf_card.pack_start(Gtk.Label(label="Performance & Desktop Actions:", xalign=0.0), False, False, 0)

        p_row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        for lbl, cmd in (
            ("Clear APK & Cache", "rm -rf /var/cache/apk/* /root/.cache/thumbnails/* 2>/dev/null || true; df -h /root"),
            ("Restart JWM", "jwm -restart && echo 'JWM restarted cleanly'"),
            ("Open htop", "xterm -T 'htop' -fa 'DejaVu Sans Mono' -fs 11 -bg '#0F172A' -fg '#E2E8F0' -e htop &"),
            ("Open PocketStore", "pocketlinux-store.py &"),
        ):
            btn = Gtk.Button(label=lbl)
            btn.get_style_context().add_class("secondary-btn")
            btn.connect("clicked", lambda _b, t=lbl, c=cmd: self._run_command(t, c))
            p_row.pack_start(btn, False, False, 0)
        perf_card.pack_start(p_row, False, False, 0)
        box.pack_start(perf_card, False, False, 0)

        return box

    def _build_system_tab(self) -> Gtk.Widget:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)

        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        card.get_style_context().add_class("panel-card")
        card.pack_start(Gtk.Label(label="Auto-Dependency Repair, APK Lock Recovery & Linux Verification:", xalign=0.0), False, False, 0)

        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        fix_btn = Gtk.Button(label="Unlock & Fix All Libraries")
        fix_btn.get_style_context().add_class("primary-btn")
        fix_btn.connect(
            "clicked",
            lambda *_: self._run_command(
                "Unlock DB & Repair All Libraries",
                "rm -f /lib/apk/db/lock 2>/dev/null || true; /usr/local/sbin/apk update && /usr/local/sbin/apk upgrade && /usr/local/sbin/apk fix",
            ),
        )
        row.pack_start(fix_btn, False, False, 0)

        health_btn = Gtk.Button(label="Run ELF & Linux Health Check")
        health_btn.get_style_context().add_class("secondary-btn")
        health_btn.connect("clicked", lambda *_: self._run_command("ELF & Linux Health Check", "/usr/local/bin/linux-health.sh"))
        row.pack_start(health_btn, False, False, 0)

        mem_btn = Gtk.Button(label="CPU, /dev/shm & RAM Info")
        mem_btn.get_style_context().add_class("secondary-btn")
        mem_btn.connect(
            "clicked",
            lambda *_: self._run_command(
                "Hardware Governor, Shared Memory & RAM",
                "echo \"CPU Cores: $(nproc 2>/dev/null || echo 1)\"; free -h; echo ''; df -h /root /dev/shm /mnt/sdcard 2>/dev/null || df -h /root",
            ),
        )
        row.pack_start(mem_btn, False, False, 0)

        card.pack_start(row, False, False, 0)
        box.pack_start(card, False, False, 0)
        return box

    def _refresh_hw_probe(self) -> None:
        gpu_model = read_file_safe("/sys/class/kgsl/kgsl-3d0/gpu_model", "Android EGL/GLES GPU")
        kgsl_ok = "Accessible" if os.path.exists("/dev/kgsl-3d0") else "EGL Surfaceless"
        has_virgl = os.path.exists("/tmp/.virgl_ipc/.virgl_test") or os.path.exists("/tmp/.virgl_test")
        virgl_ok = (
            "<span foreground='#34D399'><b>Active — Hardware GPU Accelerated (VirGL)</b></span>"
            if has_virgl
            else "Standby (using multi-threaded LLVMpipe)"
        )
        shm_ok = "Mounted (/dev/shm)" if os.path.isdir("/dev/shm") else "Fallback (/tmp)"
        cores = os.cpu_count() or 1
        self.hw_label.set_markup(
            f"<b>Detected Hardware GPU:</b> {GLib.markup_escape_text(gpu_model)}   |   <b>VirGL Server:</b> {virgl_ok}\n"
            f"<b>CPU Governor:</b> {cores} Cores Active   |   <b>POSIX Shared Memory:</b> {shm_ok}   |   <b>GPU Node:</b> {kgsl_ok}"
        )

    def _load_saved_gpu_config(self) -> None:
        if not os.path.isfile(GPU_ENV_PATH):
            return
        content = read_file_safe(GPU_ENV_PATH, "")
        for line in content.splitlines():
            if line.startswith("# MODE="):
                mode = line.split("=", 1)[1].strip()
                self.driver_combo.set_active_id(mode)
            elif "MESA_GL_VERSION_OVERRIDE=" in line:
                ver = line.split("=", 1)[1].strip().strip('"')
                self.gl_combo.set_active_id(ver)
            elif "LP_NUM_THREADS=" in line:
                th = line.split("=", 1)[1].strip().strip('"')
                self.threads_combo.set_active_id(th)
            elif "MESA_NO_ERROR=0" in line:
                self.no_error_switch.set_active(False)

    def _save_gpu_config(self) -> None:
        os.makedirs(GPU_CONF_DIR, exist_ok=True)
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
        elif mode in ("auto", "virpipe"):
            lines.append('if [ "${GALLIUM_DRIVER:-}" = "virpipe" ]; then export MESA_GL_VERSION_OVERRIDE=4.0; export MESA_GLSL_VERSION_OVERRIDE=400; fi')
        if threads != "auto":
            lines.append(f"export LP_NUM_THREADS={threads}")

        with open(GPU_ENV_PATH, "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")

        self._refresh_hw_probe()
        self._log(f"\nSaved GPU settings to {GPU_ENV_PATH} (mode={mode}, GL={gl_ver}, threads={threads}).\nNew apps will immediately use these settings.\n")

    def _launch_with_gpu(self, cmd: str) -> None:
        self._log(f"\nLaunching {cmd} with active GPU settings...\n")
        env = os.environ.copy()
        env.setdefault("DISPLAY", ":1")
        subprocess.Popen(["/bin/sh", "-c", f". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; {cmd}"], env=env)

    def _log(self, text: str) -> None:
        buf = self.console.get_buffer()
        buf.insert(buf.get_end_iter(), text)

    def _run_command(self, title: str, cmd: str) -> None:
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

        threading.Thread(target=worker, daemon=True).start()


def main() -> None:
    win = PocketSettingsWindow()
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
