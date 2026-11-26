#!/usr/bin/env python3
"""Synchronize all installed Linux GUI apps across Desktop, Applications folder, JWM Start Menu, and Android Apps tab."""

import json
import os
import re
import shlex
import shutil
import signal
import subprocess
from xml.sax.saxutils import escape as xml_escape

DESKTOP_DIR = "/root/Desktop"
APPS_DIR = "/root/Applications"
SYS_APPS_DIR = "/usr/share/applications"
CONF_DIR = "/root/.config/pocketlinux"
MANIFEST_PATH = os.path.join(CONF_DIR, "installed_apps.json")
JWMRC_APPS_PATH = "/root/.jwmrc-apps"
DESKTOP_PREFS_PATH = os.path.join(CONF_DIR, "desktop.json")

# Known app launch wrappers so browsers & PRoot apps always launch with safe flags
SAFE_LAUNCH_OVERRIDES = {
    "firefox": "firefox --no-remote",
    "firefox-esr": "firefox --no-remote",
    "chromium": "chromium --no-sandbox --disable-gpu-sandbox --disable-dev-shm-usage",
    "chromium-browser": "chromium --no-sandbox --disable-gpu-sandbox --disable-dev-shm-usage",
    "luakit": "luakit",
    "mpv": "mpv --player-operation-mode=pseudo-gui",
    "dosbox": "dosbox",
    "supertux2": "supertux2",
    "gimp": "gimp",
    "geany": "geany",
    "kolourpaint": "kolourpaint",
    "abiword": "abiword",
    "gnumeric": "gnumeric",
    "mupdf": "mupdf",
}

# Built-in system desktop entries we never overwrite or treat as third-party
BUILTIN_DESKTOP_NAMES = {
    "store.desktop",
    "downloads.desktop",
    "settings.desktop",
    "terminal.desktop",
    "files.desktop",
    "editor.desktop",
    "calculator.desktop",
    "sysinfo.desktop",
}

BUILTIN_EXEC_BINS = {
    "pocketlinux-store.py",
    "pocketlinux-downloads.py",
    "pocketlinux-settings.py",
    "xterm",
    "pcmanfm",
    "mousepad",
    "galculator",
    "xcalc",
    "xclock",
    "feh",
    "lxshortcut",
}

# Featured catalog fallback for CLI/non-.desktop apps installed via PocketStore
CATALOG_FALLBACKS = [
    ("Firefox ESR", "Internet", "firefox --no-remote", "firefox", "web-browser"),
    ("Chromium Browser", "Internet", "chromium --no-sandbox --disable-gpu-sandbox --disable-dev-shm-usage", "chromium", "web-browser"),
    ("Luakit Browser", "Internet", "luakit", "luakit", "web-browser"),
    ("Geany IDE", "Development", "geany", "geany", "accessories-text-editor"),
    ("GIMP", "Graphics", "gimp", "gimp", "applications-graphics"),
    ("KolourPaint", "Graphics", "kolourpaint", "kolourpaint", "applications-graphics"),
    ("MPV Player", "Multimedia", "mpv --player-operation-mode=pseudo-gui", "mpv", "multimedia-video-player"),
    ("AbiWord", "Office", "abiword", "abiword", "x-office-document"),
    ("Gnumeric", "Office", "gnumeric", "gnumeric", "x-office-spreadsheet"),
    ("MuPDF", "Office", "mupdf", "mupdf", "application-pdf"),
    ("SuperTux", "Games", "supertux2", "supertux2", "applications-games"),
    ("DOSBox Staging", "Games", "dosbox", "dosbox", "applications-games"),
    ("Fastfetch", "System", "xterm -fa 'DejaVu Sans Mono' -fs 11 -bg '#0F172A' -fg '#E2E8F0' -hold -e fastfetch", "fastfetch", "utilities-system-monitor"),
    ("Node.js Shell", "Development", "xterm -fa 'DejaVu Sans Mono' -fs 12 -bg '#0F172A' -fg '#E2E8F0' -e sh -c 'node -v; npm -v; exec bash -l'", "node", "utilities-terminal"),
    ("C/C++ GCC Shell", "Development", "xterm -fa 'DejaVu Sans Mono' -fs 12 -bg '#0F172A' -fg '#E2E8F0' -e sh -c 'gcc --version | head -n1; exec bash -l'", "gcc", "utilities-terminal"),
]


def load_auto_desktop_shortcut_enabled() -> bool:
    try:
        with open(DESKTOP_PREFS_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
            return bool(data.get("auto_desktop_shortcuts", True))
    except Exception:
        return True


def parse_desktop_file(path: str) -> dict | None:
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

    if fields.get("Type") != "Application":
        return None
    if fields.get("NoDisplay", "").lower() == "true" or fields.get("Hidden", "").lower() == "true":
        return None

    name = fields.get("Name", "").strip()
    raw_exec = fields.get("Exec", "").strip()
    if not name or not raw_exec:
        return None

    clean_exec = re.sub(r"\s+%[fFuUdDnNickvm]", "", raw_exec).strip()
    try:
        tokens = shlex.split(clean_exec)
    except ValueError:
        tokens = clean_exec.split()
    if not tokens:
        return None
    exe_base = os.path.basename(tokens[0])
    if exe_base in BUILTIN_EXEC_BINS:
        return None
    if shutil.which(tokens[0]) is None and not os.path.exists(tokens[0]):
        return None

    if exe_base in SAFE_LAUNCH_OVERRIDES:
        clean_exec = SAFE_LAUNCH_OVERRIDES[exe_base]

    cats = fields.get("Categories", "")
    category = "Apps"
    for candidate in ("Internet", "Network", "Development", "Graphics", "Office", "Game", "AudioVideo", "Multimedia", "System", "Utility"):
        if candidate.lower() in cats.lower():
            category = "Internet" if candidate == "Network" else ("Multimedia" if candidate == "AudioVideo" else ("Games" if candidate == "Game" else candidate))
            break

    return {
        "id": re.sub(r"[^a-z0-9_-]+", "-", os.path.splitext(os.path.basename(path))[0].lower()).strip("-") or "app",
        "name": name,
        "comment": fields.get("Comment", f"Launch {name}").strip() or f"Launch {name}",
        "exec": clean_exec,
        "icon": fields.get("Icon", "application-x-executable").strip() or "application-x-executable",
        "category": category,
        "terminal": fields.get("Terminal", "false").lower() == "true",
        "binary": exe_base,
    }


def write_desktop_shortcut(dest_path: str, app: dict) -> None:
    exec_cmd = app["exec"]
    if app.get("terminal"):
        exec_cmd = f"xterm -fa 'DejaVu Sans Mono' -fs 12 -bg '#0F172A' -fg '#E2E8F0' -e {exec_cmd}"
    # Wrap with pocketlinux-gpu.sh so GPU + Browser sandbox + Network Bridge env always apply
    wrapped_exec = f'/bin/sh -c ". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; exec {exec_cmd}"'
    content = (
        "[Desktop Entry]\n"
        "Type=Application\n"
        f"Name={app['name']}\n"
        f"Comment={app['comment']}\n"
        f"Exec={wrapped_exec}\n"
        f"Icon={app['icon']}\n"
        "Terminal=false\n"
        f"Categories={app['category']};\n"
        "X-PocketLinux-Auto=true\n"
    )
    with open(dest_path, "w", encoding="utf-8") as f:
        f.write(content)
    os.chmod(dest_path, 0o755)


def is_auto_generated_desktop(path: str) -> bool:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return "X-PocketLinux-Auto=true" in f.read()
    except OSError:
        return False


def sync_all() -> list[dict]:
    os.makedirs(DESKTOP_DIR, exist_ok=True)
    os.makedirs(APPS_DIR, exist_ok=True)
    os.makedirs(CONF_DIR, exist_ok=True)

    discovered: dict[str, dict] = {}
    seen_bins: set[str] = set()

    # 1. Scan /usr/share/applications/*.desktop
    if os.path.isdir(SYS_APPS_DIR):
        for fname in sorted(os.listdir(SYS_APPS_DIR)):
            if not fname.endswith(".desktop"):
                continue
            parsed = parse_desktop_file(os.path.join(SYS_APPS_DIR, fname))
            if parsed and parsed["binary"] not in seen_bins:
                discovered[parsed["id"]] = parsed
                seen_bins.add(parsed["binary"])

    # 2. Add installed catalog apps that may not ship a .desktop file (e.g. node, gcc, fastfetch, mupdf)
    for name, cat, launch, binary, icon in CATALOG_FALLBACKS:
        if binary in seen_bins:
            continue
        if shutil.which(binary) is not None:
            app_id = re.sub(r"[^a-z0-9_-]+", "-", binary.lower())
            discovered[app_id] = {
                "id": app_id,
                "name": name,
                "comment": f"Launch {name}",
                "exec": launch,
                "icon": icon,
                "category": cat,
                "terminal": False,
                "binary": binary,
            }
            seen_bins.add(binary)

    apps = sorted(discovered.values(), key=lambda a: a["name"].lower())
    auto_desktop = load_auto_desktop_shortcut_enabled()
    expected_desktop_files = set()

    # Sync /root/Applications (always keep full mirror of installed apps)
    for fn in list(os.listdir(APPS_DIR)):
        if fn.endswith(".desktop"):
            p = os.path.join(APPS_DIR, fn)
            if is_auto_generated_desktop(p):
                try:
                    os.remove(p)
                except OSError:
                    pass

    for app in apps:
        fname = f"pl-app-{app['id']}.desktop"
        expected_desktop_files.add(fname)
        write_desktop_shortcut(os.path.join(APPS_DIR, fname), app)
        if auto_desktop:
            desk_path = os.path.join(DESKTOP_DIR, fname)
            if not os.path.exists(desk_path) or is_auto_generated_desktop(desk_path):
                write_desktop_shortcut(desk_path, app)

    # Remove stale auto-generated shortcuts on Desktop for uninstalled apps
    for fn in list(os.listdir(DESKTOP_DIR)):
        if fn in BUILTIN_DESKTOP_NAMES or not fn.startswith("pl-app-") or not fn.endswith(".desktop"):
            continue
        if fn not in expected_desktop_files:
            p = os.path.join(DESKTOP_DIR, fn)
            if is_auto_generated_desktop(p):
                try:
                    os.remove(p)
                except OSError:
                    pass

    # Write Android-readable installed apps manifest
    tmp_manifest = MANIFEST_PATH + ".tmp"
    with open(tmp_manifest, "w", encoding="utf-8") as f:
        json.dump(apps, f, indent=2)
    os.replace(tmp_manifest, MANIFEST_PATH)

    # Generate JWM dynamic Installed Apps menu fragment (/root/.jwmrc-apps)
    jwm_lines = ["<?xml version=\"1.0\"?>", "<JWM>"]
    if apps:
        for app in apps:
            lbl = xml_escape(app["name"])
            ico = xml_escape(app["icon"])
            cmd = xml_escape(
                f'/bin/sh -c ". /etc/profile.d/pocketlinux-gpu.sh 2>/dev/null || true; exec {app["exec"]}"'
            )
            jwm_lines.append(f'    <Program label="{lbl}" icon="{ico}">{cmd}</Program>')
    else:
        jwm_lines.append('    <Program label="Install Apps via PocketStore..." icon="system-software-install">pocketlinux-store.py</Program>')
    jwm_lines.append("</JWM>\n")
    with open(JWMRC_APPS_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(jwm_lines))

    # Refresh XDG desktop database, JWM menu, and PocketLinux desktop icon canvas
    if shutil.which("update-desktop-database"):
        subprocess.run(["update-desktop-database", SYS_APPS_DIR], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if os.environ.get("DISPLAY") and shutil.which("jwm"):
        subprocess.run(["jwm", "-reload"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    # Signal pocketlinux-desktop.py to immediately repaint new icons
    pid_file = "/tmp/.pocketlinux_desktop.pid"
    if os.path.isfile(pid_file):
        try:
            pid = int(open(pid_file, encoding="utf-8").read().strip())
            os.kill(pid, signal.SIGUSR1)
        except Exception:
            pass

    return apps


if __name__ == "__main__":
    synced = sync_all()
    print(f"Synced {len(synced)} installed app(s) to Desktop, Applications, JWM Menu & Android.")
