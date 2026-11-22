#!/bin/sh
set -eu

exec /usr/bin/python3 - "$@" <<'PYEOF'
import json
import os
import subprocess
import sys

def read_first_line(path, default=""):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            return f.readline().strip()
    except OSError:
        return default

def read_meminfo():
    info = {}
    try:
        with open("/proc/meminfo", "r", encoding="utf-8", errors="replace") as f:
            for line in f:
                if ":" in line:
                    k, v = line.split(":", 1)
                    parts = v.strip().split()
                    if parts and parts[0].isdigit():
                        info[k] = int(parts[0])
    except OSError:
        pass
    return info

def read_uptime():
    raw = read_first_line("/proc/uptime", "0 0").split()
    try:
        return float(raw[0])
    except (IndexError, ValueError):
        return 0.0

def count_procs():
    try:
        return sum(1 for name in os.listdir("/proc") if name.isdigit())
    except OSError:
        return 0

def query_x_windows(display=":1"):
    windows = []
    try:
        env = dict(os.environ)
        env["DISPLAY"] = display
        res = subprocess.run(
            ["xwininfo", "-display", display, "-root", "-tree"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=3,
            env=env,
        )
        if res.returncode == 0:
            for line in res.stdout.splitlines():
                s = line.strip()
                if s.startswith("0x"):
                    windows.append(s)
    except Exception:
        pass
    return windows

uname = os.uname()
mem = read_meminfo()
payload = {
    "sysname": uname.sysname,
    "nodename": uname.nodename,
    "release": uname.release,
    "machine": uname.machine,
    "alpine_release": read_first_line("/etc/alpine-release", "unknown"),
    "uptime_sec": read_uptime(),
    "mem_total_kb": mem.get("MemTotal", 0),
    "mem_available_kb": mem.get("MemAvailable", mem.get("MemFree", 0)),
    "mem_free_kb": mem.get("MemFree", 0),
    "process_count": count_procs(),
    "x_windows": query_x_windows(os.environ.get("DISPLAY", ":1")),
}

if "--text" in sys.argv[1:]:
    print(f"OS: Alpine Linux {payload['alpine_release']} ({payload['machine']})")
    print(f"Kernel: {payload['sysname']} {payload['release']}")
    print(f"Memory: {payload['mem_available_kb']} / {payload['mem_total_kb']} kB available")
    print(f"Processes: {payload['process_count']}")
    print(f"X11 Windows: {len(payload['x_windows'])}")
else:
    print(json.dumps(payload))
PYEOF
