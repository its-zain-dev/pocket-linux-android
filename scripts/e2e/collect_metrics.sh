#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/e2e/lib/adb_common.sh
source "$SCRIPT_DIR/lib/adb_common.sh"

OUT_DIR="${OUT_DIR:-e2e-reports}"
APK_PATH="${APK_PATH:-}"
ARM64_APK="${ARM64_APK:-}"
ABI="${ABI:-}"
DEVICE_PROFILE="auto"
SKIP_LIFECYCLE=false

usage() {
    cat <<'EOF'
Usage: collect_metrics.sh [options]

Collects APK size, startup latency (cold/warm/resume), memory footprint
(dumpsys meminfo API 30/34, /proc/meminfo, sysinfo.sh), child process counts,
CLI latency (3x), and logcat crash/ANR/PhantomProcess audit for PocketLinux.

Options:
  --out-dir <dir>              Output directory (default: e2e-reports)
  --apk <path>                 Primary APK path (e.g., x86_64 or arm64)
  --arm64-apk <path>           Optional arm64 APK path for size reporting
  --abi <x86_64|arm64>         Primary APK ABI label
  --device-profile <profile>   Threshold profile: auto | ci | sm-t295 (default: auto)
  --skip-lifecycle             Skip pm clear / force-stop startup timing measurements
  --help                       Show this help message and exit
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --out-dir)
            if [[ $# -lt 2 ]]; then
                echo "[collect_metrics] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            OUT_DIR="$2"
            shift 2
            ;;
        --apk)
            if [[ $# -lt 2 ]]; then
                echo "[collect_metrics] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            APK_PATH="$2"
            shift 2
            ;;
        --arm64-apk)
            if [[ $# -lt 2 ]]; then
                echo "[collect_metrics] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            ARM64_APK="$2"
            shift 2
            ;;
        --abi)
            if [[ $# -lt 2 ]]; then
                echo "[collect_metrics] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            ABI="$2"
            shift 2
            ;;
        --device-profile)
            if [[ $# -lt 2 ]]; then
                echo "[collect_metrics] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            DEVICE_PROFILE="$2"
            shift 2
            ;;
        --skip-lifecycle)
            SKIP_LIFECYCLE=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "[collect_metrics] ERROR: Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

case "$DEVICE_PROFILE" in
    auto|ci|sm-t295) ;;
    *)
        echo "[collect_metrics] ERROR: Invalid --device-profile '$DEVICE_PROFILE' (expected auto|ci|sm-t295)" >&2
        exit 2
        ;;
esac

e2e_init "$OUT_DIR"

TMP_WORK="$(mktemp -d "${OUT_DIR}/.metrics_tmp.XXXXXX")"
cleanup_tmp() {
    rm -rf "$TMP_WORK" || true
}
trap cleanup_tmp EXIT

API_LEVEL="$(_e2e_adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || echo "0")"
[[ "$API_LEVEL" =~ ^[0-9]+$ ]] || API_LEVEL=0
DEVICE_MODEL="$(_e2e_adb shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "")"
DEVICE_ABI="$(_e2e_adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || echo "")"
QEMU_PROP="$(_e2e_adb shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r' || echo "")"
HARDWARE_PROP="$(_e2e_adb shell getprop ro.hardware 2>/dev/null | tr -d '\r' || echo "")"

if [[ -z "$ABI" ]]; then
    if [[ "$DEVICE_ABI" == arm64* ]]; then
        ABI="arm64"
    else
        ABI="x86_64"
    fi
fi

if [[ "$DEVICE_PROFILE" == "auto" ]]; then
    if [[ "${DEVICE_MODEL^^}" == *"SM-T295"* ]]; then
        DEVICE_PROFILE="sm-t295"
    elif [[ "$QEMU_PROP" == "1" || "$HARDWARE_PROP" == "ranchu" || "$HARDWARE_PROP" == "goldfish" ]]; then
        DEVICE_PROFILE="ci"
    else
        DEVICE_PROFILE="ci"
    fi
fi

echo "[collect_metrics] API=$API_LEVEL model='${DEVICE_MODEL}' abi='${DEVICE_ABI}' profile='${DEVICE_PROFILE}'"

_parse_am_time_ms() {
    local am_output="$1"
    local field="$2"
    python3 - "$am_output" "$field" <<'PYEOF'
import re
import sys

out, field = sys.argv[1], sys.argv[2]
m = re.search(rf"{re.escape(field)}:\s*(\d+)", out)
print(m.group(1) if m else "")
PYEOF
}

_poll_until_ready() {
    local timeout_sec="${1:-120}"
    local now_ms deadline_ms status_json state
    now_ms="$(_e2e_now_ms)"
    deadline_ms=$((now_ms + timeout_sec * 1000))

    while true; do
        if status_json="$(e2e_broadcast_status 10 2>/dev/null)"; then
            state="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(str(d.get("state","")).strip().lower())' "$status_json" 2>/dev/null || echo "")"
            if [[ "$state" == "ready" ]]; then
                printf '%s\n' "$status_json"
                return 0
            fi
            if [[ "$state" == "error" ]]; then
                echo "[collect_metrics] ERROR: LinuxRuntimeService entered Error state: $status_json" >&2
                return 1
            fi
        fi
        now_ms="$(_e2e_now_ms)"
        if [[ $now_ms -ge $deadline_ms ]]; then
            echo "[collect_metrics] ERROR: Timed out after ${timeout_sec}s waiting for Ready state" >&2
            return 1
        fi
        sleep 0.25
    done
}

# 1. APK size measurement (if no host paths supplied, inspect installed APK path on device)
INSTALLED_APK_BYTES=""
if [[ -z "$APK_PATH" && -z "$ARM64_APK" ]]; then
    pm_path_line="$(_e2e_adb shell pm path "$PKG_ID" 2>/dev/null | tr -d '\r' | head -n 1 || true)"
    dev_apk="${pm_path_line#package:}"
    if [[ -n "$dev_apk" ]]; then
        INSTALLED_APK_BYTES="$(_e2e_adb shell "stat -c%s '$dev_apk' 2>/dev/null || wc -c < '$dev_apk'" 2>/dev/null | tr -d '\r[:space:]' || true)"
        [[ "$INSTALLED_APK_BYTES" =~ ^[0-9]+$ ]] || INSTALLED_APK_BYTES=""
    fi
fi

# 2. Startup latency measurements (cold first-boot, warm runtime, warm activity resume)
COLD_AM_TOTAL_MS=""
COLD_WALL_TO_READY_MS=""
COLD_RUNTIME_STARTUP_MS=""
WARM_AM_TOTAL_MS=""
WARM_WALL_TO_READY_MS=""
WARM_RUNTIME_STARTUP_MS=""
WARM_RESUME_MS=""

if [[ "$SKIP_LIFECYCLE" == false ]]; then
    echo "[collect_metrics] Measuring cold first-boot startup (pm clear)..."
    _e2e_adb shell pm clear "$PKG_ID" >/dev/null
    if [[ "$API_LEVEL" -ge 33 ]]; then
        _e2e_adb shell pm grant "$PKG_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    fi
    sleep 1
    cold_start_ms="$(_e2e_now_ms)"
    cold_am_out="$(_e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" 2>&1 | tr -d '\r' || true)"
    COLD_AM_TOTAL_MS="$(_parse_am_time_ms "$cold_am_out" "TotalTime")"
    if cold_status="$(_poll_until_ready 120)"; then
        cold_end_ms="$(_e2e_now_ms)"
        COLD_WALL_TO_READY_MS=$((cold_end_ms - cold_start_ms))
        COLD_RUNTIME_STARTUP_MS="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("startupMs",""))' "$cold_status" 2>/dev/null || echo "")"
    else
        echo "[collect_metrics] WARN: Cold startup failed to reach Ready" >&2
    fi

    echo "[collect_metrics] Measuring warm runtime startup (force-stop)..."
    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null
    sleep 1
    warm_start_ms="$(_e2e_now_ms)"
    warm_am_out="$(_e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" 2>&1 | tr -d '\r' || true)"
    WARM_AM_TOTAL_MS="$(_parse_am_time_ms "$warm_am_out" "TotalTime")"
    if warm_status="$(_poll_until_ready 60)"; then
        warm_end_ms="$(_e2e_now_ms)"
        WARM_WALL_TO_READY_MS=$((warm_end_ms - warm_start_ms))
        WARM_RUNTIME_STARTUP_MS="$(python3 -c 'import json,sys; print(json.loads(sys.argv[1]).get("startupMs",""))' "$warm_status" 2>/dev/null || echo "")"
    else
        echo "[collect_metrics] WARN: Warm startup failed to reach Ready" >&2
    fi

    echo "[collect_metrics] Measuring warm activity resume (KEYCODE_HOME)..."
    _e2e_adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    sleep 1
    resume_am_out="$(_e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" 2>&1 | tr -d '\r' || true)"
    WARM_RESUME_MS="$(_parse_am_time_ms "$resume_am_out" "TotalTime")"
else
    echo "[collect_metrics] --skip-lifecycle active; ensuring runtime is ready..."
    _poll_until_ready 60 >/dev/null || true
fi

# Allow runtime to settle before idle memory snapshot
sleep 2

# 3. Idle Memory & Status Cross-Check
_e2e_adb shell dumpsys meminfo "$PKG_ID" > "$TMP_WORK/idle_meminfo.txt" 2>/dev/null || true
IDLE_STATUS_JSON="$(e2e_broadcast_status 15 2>/dev/null || echo '{}')"
printf '%s\n' "$IDLE_STATUS_JSON" > "$TMP_WORK/idle_status.json"

# 4. Active Multi-App PSS (xterm + xclock)
echo "[collect_metrics] Launching xterm + xclock for active memory measurement..."
active_launch_ok=true
for gui_app in xterm xclock; do
    if gui_resp="$(e2e_launch_gui "$gui_app" 20 2>/dev/null)" && e2e_assert_json "$gui_resp" "launched" "true" >/dev/null 2>&1; then
        :
    else
        echo "[collect_metrics] WARN: GUI launch did not confirm launched=true for '${gui_app}'" >&2
        active_launch_ok=false
        break
    fi
done

if [[ "$active_launch_ok" == true ]]; then
    sleep 2
    _e2e_adb shell dumpsys meminfo "$PKG_ID" > "$TMP_WORK/active_meminfo.txt" 2>/dev/null || true
else
    echo "[collect_metrics] WARN: Failed to launch GUI apps for active meminfo; active_meminfo unavailable" >&2
    : > "$TMP_WORK/active_meminfo.txt"
fi

# 5. Device /proc/meminfo, Linux sysinfo.sh, Android UID process list
_e2e_adb shell cat /proc/meminfo > "$TMP_WORK/device_proc_meminfo.txt" 2>/dev/null || true
SYSINFO_RESP="$(e2e_exec_linux "/usr/local/bin/sysinfo.sh" 20 2>/dev/null || echo '{}')"
printf '%s\n' "$SYSINFO_RESP" > "$TMP_WORK/sysinfo_resp.json"

_e2e_adb shell dumpsys package "$PKG_ID" > "$TMP_WORK/dumpsys_pkg.txt" 2>/dev/null || true
if ! _e2e_adb shell "ps -A -o UID,PID,RSS,NAME" > "$TMP_WORK/ps_all.txt" 2>/dev/null; then
    _e2e_adb shell "ps -A" > "$TMP_WORK/ps_all.txt" 2>/dev/null || true
fi

# Clean up GUI apps launched for active memory measurement
e2e_exec_linux "for p in xterm xclock; do pkill -TERM -x \"\$p\" 2>/dev/null || true; done; sleep 0.2; for p in xterm xclock; do pkill -KILL -x \"\$p\" 2>/dev/null || true; done" 15 >/dev/null 2>&1 || true

# 6. CLI latency under load (3x uname -a && id)
echo "[collect_metrics] Running 3x CLI latency probes..."
: > "$TMP_WORK/cli_samples.jsonl"
for idx in 1 2 3; do
    t0="$(_e2e_now_ms)"
    if cli_resp="$(e2e_exec_linux "uname -a && id" 20 2>/dev/null)"; then
        t1="$(_e2e_now_ms)"
        wall_ms=$((t1 - t0))
        python3 - "$cli_resp" "$wall_ms" "$idx" >> "$TMP_WORK/cli_samples.jsonl" <<'PYEOF'
import json, sys
resp = json.loads(sys.argv[1])
wall_ms = int(sys.argv[2])
idx = int(sys.argv[3])
print(json.dumps({
    "run": idx,
    "exitCode": resp.get("exitCode"),
    "durationMs": resp.get("durationMs"),
    "wallMs": wall_ms,
}))
PYEOF
    else
        echo "[collect_metrics] WARN: CLI latency probe #$idx failed" >&2
    fi
done

# 7. Logcat crash / ANR / SIGSEGV / PhantomProcessList audit
_e2e_adb logcat -d -v threadtime > "$TMP_WORK/logcat.txt" 2>/dev/null || true

# 8. Parse everything, evaluate gates, and emit metrics.json + metrics.md
export TMP_WORK OUT_DIR PKG_ID ABI APK_PATH ARM64_APK INSTALLED_APK_BYTES DEVICE_PROFILE API_LEVEL DEVICE_MODEL DEVICE_ABI
export COLD_AM_TOTAL_MS COLD_WALL_TO_READY_MS COLD_RUNTIME_STARTUP_MS
export WARM_AM_TOTAL_MS WARM_WALL_TO_READY_MS WARM_RUNTIME_STARTUP_MS WARM_RESUME_MS
export SKIP_LIFECYCLE

python3 - <<'PYEOF'
from datetime import datetime, timezone
import json
import os
import re
import statistics
import sys

tmp_work = os.environ["TMP_WORK"]
out_dir = os.environ["OUT_DIR"]
pkg_id = os.environ["PKG_ID"]
abi = os.environ["ABI"]
apk_path = os.environ.get("APK_PATH") or ""
arm64_apk = os.environ.get("ARM64_APK") or ""
installed_apk_bytes_raw = os.environ.get("INSTALLED_APK_BYTES") or ""
profile = os.environ["DEVICE_PROFILE"]
api_level = int(os.environ.get("API_LEVEL") or 0)
device_model = os.environ.get("DEVICE_MODEL") or ""
device_abi = os.environ.get("DEVICE_ABI") or ""
skip_lifecycle = os.environ.get("SKIP_LIFECYCLE") == "true"


def opt_int(val: str) -> int | None:
    val = (val or "").strip()
    if val.isdigit():
        return int(val)
    try:
        return int(float(val))
    except (ValueError, TypeError):
        return None


def read_text(name: str) -> str:
    p = os.path.join(tmp_work, name)
    if not os.path.isfile(p):
        return ""
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def parse_dumpsys_meminfo(text: str) -> dict[str, int | None]:
    if not text.strip():
        return {
            "pss_kb": None,
            "rss_kb": None,
            "java_heap_kb": None,
            "native_heap_kb": None,
            "graphics_kb": None,
        }

    def first_int(patterns: list[str]) -> int | None:
        for pat in patterns:
            m = re.search(pat, text, flags=re.IGNORECASE | re.MULTILINE)
            if m:
                return int(m.group(1))
        return None

    pss_kb = first_int([
        r"TOTAL\s+PSS:\s*(\d+)",
        r"^\s*TOTAL:\s*(\d+)",
        r"^\s*TOTAL\s+(\d+)",
    ])
    rss_kb = first_int([
        r"TOTAL\s+RSS:\s*(\d+)",
    ])
    # Fallback for API 34/30 main table row `TOTAL   <pss>  <private_dirty> ...`
    if rss_kb is None:
        hdr_m = re.search(r"^\s*Pss\s+Private\s+Private\s+Swap.*$", text, flags=re.MULTILINE)
        if hdr_m and "Rss" in hdr_m.group(0):
            cols = hdr_m.group(0).split()
            tot_m = re.search(r"^\s*TOTAL\s+(.+)$", text, flags=re.MULTILINE)
            if tot_m:
                vals = tot_m.group(1).split()
                for idx, c in enumerate(cols):
                    if c.lower().startswith("rss") and idx < len(vals) and vals[idx].isdigit():
                        rss_kb = int(vals[idx])
                        break

    java_heap_kb = first_int([
        r"Java\s+Heap:\s*(\d+)",
        r"^\s*Dalvik\s+Heap\s+(\d+)",
    ])
    native_heap_kb = first_int([
        r"Native\s+Heap:\s*(\d+)",
        r"^\s*Native\s+Heap\s+(\d+)",
    ])
    graphics_kb = first_int([
        r"Graphics:\s*(\d+)",
    ])
    if graphics_kb is None:
        gl_m = re.search(r"^\s*GL\s+mtrack\s+(\d+)", text, flags=re.MULTILINE)
        egl_m = re.search(r"^\s*EGL\s+mtrack\s+(\d+)", text, flags=re.MULTILINE)
        if gl_m or egl_m:
            graphics_kb = (int(gl_m.group(1)) if gl_m else 0) + (int(egl_m.group(1)) if egl_m else 0)

    return {
        "pss_kb": pss_kb,
        "rss_kb": rss_kb,
        "java_heap_kb": java_heap_kb,
        "native_heap_kb": native_heap_kb,
        "graphics_kb": graphics_kb,
    }


def parse_proc_meminfo(text: str) -> dict[str, int | None]:
    vals: dict[str, int] = {}
    for line in text.splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            parts = v.strip().split()
            if parts and parts[0].isdigit():
                vals[k.strip()] = int(parts[0])
    return {
        "device_mem_total_kb": vals.get("MemTotal"),
        "device_mem_available_kb": vals.get("MemAvailable", vals.get("MemFree")),
    }


def parse_uid_procs(dumpsys_pkg: str, ps_text: str) -> tuple[int | None, int | None, int | None]:
    uid_m = re.search(r"(?:userId|appId|uid)=(\d+)", dumpsys_pkg)
    if not uid_m:
        return None, None, None
    uid = int(uid_m.group(1))
    uid_aliases = {str(uid)}
    if uid >= 10000:
        app_idx = uid - 10000
        uid_aliases.add(f"u0_a{app_idx}")
        uid_aliases.add(f"u{uid // 100000}_a{(uid % 100000) - 10000}")

    count = 0
    total_rss = 0
    saw_rows = False
    saw_header = False
    uid_col = 0
    rss_col: int | None = None
    for line in ps_text.splitlines():
        parts = line.split()
        if not parts:
            continue
        upper_parts = [p.upper() for p in parts]
        if upper_parts[0] in ("UID", "USER") or (
            "PID" in upper_parts and ("UID" in upper_parts or "USER" in upper_parts)
        ):
            saw_header = True
            uid_col = upper_parts.index("UID") if "UID" in upper_parts else upper_parts.index("USER")
            if "RSS" in upper_parts:
                rss_col = upper_parts.index("RSS")
            elif "RES" in upper_parts:
                rss_col = upper_parts.index("RES")
            else:
                rss_col = None
            continue
        if len(parts) < 4:
            continue
        saw_rows = True
        if uid_col < len(parts) and parts[uid_col] in uid_aliases:
            count += 1
            if rss_col is not None:
                if rss_col < len(parts) and parts[rss_col].isdigit():
                    total_rss += int(parts[rss_col])
            elif not saw_header:
                for col in (2, 4, 3):
                    if col < len(parts) and parts[col].isdigit():
                        total_rss += int(parts[col])
                        break
    if not saw_rows:
        return uid, None, None
    return uid, count, total_rss


def audit_logcat(text: str, pkg: str) -> dict[str, Any]:
    lines = text.splitlines()
    pkg_boundary_re = rf"(?<![A-Za-z0-9_.]){re.escape(pkg)}(?![A-Za-z0-9_.])"
    pkg_re = re.compile(pkg_boundary_re)
    anr_re = re.compile(rf"ANR in\s+{pkg_boundary_re}")
    native_re = re.compile(
        rf"(?:Fatal signal \d+|SIGSEGV).*{pkg_boundary_re}|>>>\s*{pkg_boundary_re}\s*<<<"
    )
    phantom_re = re.compile(
        rf"PhantomProcessList.*{pkg_boundary_re}|Killing .*{pkg_boundary_re}.*phantom",
        re.IGNORECASE,
    )
    fatal_hits: list[str] = []
    anr_hits: list[str] = []
    native_hits: list[str] = []
    phantom_hits: list[str] = []

    for i, line in enumerate(lines):
        if "FATAL EXCEPTION" in line:
            window = "\n".join(lines[i : i + 6])
            if pkg_re.search(window):
                fatal_hits.append(line.strip())
        if anr_re.search(line):
            anr_hits.append(line.strip())
        if native_re.search(line):
            native_hits.append(line.strip())
        if phantom_re.search(line):
            phantom_hits.append(line.strip())

    return {
        "fatalExceptions": len(fatal_hits),
        "anrs": len(anr_hits),
        "nativeCrashes": len(native_hits),
        "phantomKills": len(phantom_hits),
        "samples": {
            "fatal": fatal_hits[:5],
            "anr": anr_hits[:5],
            "native": native_hits[:5],
            "phantom": phantom_hits[:5],
        },
    }


# APK sizes
apk_sizes: dict[str, Any] = {}
apk_exact_mibs: list[float] = []
if apk_path and os.path.isfile(apk_path):
    sz = os.path.getsize(apk_path)
    exact_mib = sz / (1024.0 * 1024.0)
    apk_exact_mibs.append(exact_mib)
    apk_sizes[abi] = {"path": apk_path, "bytes": sz, "mib": round(exact_mib, 3)}
if arm64_apk and os.path.isfile(arm64_apk) and os.path.abspath(arm64_apk) != os.path.abspath(apk_path or ""):
    sz = os.path.getsize(arm64_apk)
    exact_mib = sz / (1024.0 * 1024.0)
    apk_exact_mibs.append(exact_mib)
    apk_sizes["arm64-v8a"] = {"path": arm64_apk, "bytes": sz, "mib": round(exact_mib, 3)}
if not apk_sizes and installed_apk_bytes_raw.isdigit():
    sz = int(installed_apk_bytes_raw)
    exact_mib = sz / (1024.0 * 1024.0)
    apk_exact_mibs.append(exact_mib)
    apk_sizes[abi] = {"path": "device-installed", "bytes": sz, "mib": round(exact_mib, 3)}

idle_mem = parse_dumpsys_meminfo(read_text("idle_meminfo.txt"))
active_mem = parse_dumpsys_meminfo(read_text("active_meminfo.txt"))
proc_mem = parse_proc_meminfo(read_text("device_proc_meminfo.txt"))

try:
    idle_status = json.loads(read_text("idle_status.json") or "{}")
except Exception:
    idle_status = {}

try:
    sysinfo_resp = json.loads(read_text("sysinfo_resp.json") or "{}")
except Exception:
    sysinfo_resp = {}

sysinfo_payload: dict[str, Any] = {}
if isinstance(sysinfo_resp.get("stdout"), str) and sysinfo_resp["stdout"].strip():
    try:
        sysinfo_payload = json.loads(sysinfo_resp["stdout"].strip())
    except Exception:
        sysinfo_payload = {}

pkg_uid, android_uid_procs, android_uid_rss_kb = parse_uid_procs(
    read_text("dumpsys_pkg.txt"),
    read_text("ps_all.txt"),
)

cli_samples: list[dict[str, Any]] = []
for raw_line in read_text("cli_samples.jsonl").splitlines():
    if raw_line.strip():
        try:
            cli_samples.append(json.loads(raw_line))
        except Exception:
            pass

cli_durations = [
    int(s["durationMs"]) if s.get("durationMs") is not None else int(s["wallMs"])
    for s in cli_samples
    if s.get("exitCode") == 0
]
cli_median_ms = int(statistics.median(cli_durations)) if cli_durations else None
cli_max_ms = max(cli_durations) if cli_durations else None

logcat_audit = audit_logcat(read_text("logcat.txt"), pkg_id)

measurements: dict[str, Any] = {
    "apkSizes": apk_sizes,
    "cold_am_total_ms": opt_int(os.environ.get("COLD_AM_TOTAL_MS", "")),
    "cold_wall_to_ready_ms": opt_int(os.environ.get("COLD_WALL_TO_READY_MS", "")),
    "cold_runtime_startup_ms": opt_int(os.environ.get("COLD_RUNTIME_STARTUP_MS", "")),
    "warm_am_total_ms": opt_int(os.environ.get("WARM_AM_TOTAL_MS", "")),
    "warm_wall_to_ready_ms": opt_int(os.environ.get("WARM_WALL_TO_READY_MS", "")),
    "warm_runtime_startup_ms": opt_int(os.environ.get("WARM_RUNTIME_STARTUP_MS", "")),
    "warm_resume_ms": opt_int(os.environ.get("WARM_RESUME_MS", "")),
    "idle_pss_kb": idle_mem["pss_kb"],
    "idle_rss_kb": idle_mem["rss_kb"],
    "idle_java_heap_kb": idle_mem["java_heap_kb"],
    "idle_native_heap_kb": idle_mem["native_heap_kb"],
    "idle_graphics_kb": idle_mem["graphics_kb"],
    "status_android_pss_kb": idle_status.get("androidPssKb"),
    "status_android_rss_kb": idle_status.get("androidRssKb"),
    "active_pss_kb": active_mem["pss_kb"],
    "active_rss_kb": active_mem["rss_kb"],
    "device_mem_total_kb": proc_mem["device_mem_total_kb"],
    "device_mem_available_kb": proc_mem["device_mem_available_kb"],
    "linux_mem_total_kb": sysinfo_payload.get("mem_total_kb"),
    "linux_mem_available_kb": sysinfo_payload.get("mem_available_kb"),
    "linux_process_count": sysinfo_payload.get("process_count"),
    "x_windows_count": len(sysinfo_payload.get("x_windows") or []) if sysinfo_payload else None,
    "android_uid": pkg_uid,
    "android_uid_proc_count": android_uid_procs,
    "android_uid_total_rss_kb": android_uid_rss_kb,
    "cli_latency_ms": cli_median_ms,
    "cli_latency_max_ms": cli_max_ms,
    "cli_samples": cli_samples,
    "logcat_audit": logcat_audit,
}


def eval_threshold(val: float | int | None, pass_max: float, warn_max: float, required: bool = True) -> str:
    if val is None:
        return "FAIL" if required else "WARN"
    if val <= pass_max:
        return "PASS"
    if val <= warn_max:
        return "WARN"
    return "FAIL"


max_apk_exact_mib = max(apk_exact_mibs, default=None)
max_apk_mib = max((v["mib"] for v in apk_sizes.values()), default=None)
cold_pass = 10000 if profile == "ci" else 15000
cold_fail = 20000 if profile == "ci" else 30000

proc_candidates = [x for x in (measurements["linux_process_count"], android_uid_procs) if x is not None]
max_procs = max(proc_candidates) if proc_candidates else None

total_crashes = (
    logcat_audit["fatalExceptions"]
    + logcat_audit["anrs"]
    + logcat_audit["nativeCrashes"]
    + logcat_audit["phantomKills"]
)

gates: dict[str, str] = {
    "apk_size": eval_threshold(max_apk_exact_mib, 75.0, 85.0, required=bool(apk_sizes)),
    "cold_startup": "SKIP" if skip_lifecycle else eval_threshold(measurements["cold_wall_to_ready_ms"], cold_pass, cold_fail),
    "warm_startup": "SKIP" if skip_lifecycle else eval_threshold(measurements["warm_wall_to_ready_ms"], 3500, 7000),
    "idle_pss": eval_threshold(measurements["idle_pss_kb"], 130 * 1024, 200 * 1024),
    "active_pss": eval_threshold(measurements["active_pss_kb"], 220 * 1024, 320 * 1024),
    "cli_latency": eval_threshold(cli_median_ms, 500, 2000),
    "process_budget": eval_threshold(max_procs, 20, 26),
    "crash_free": "PASS" if total_crashes == 0 else "FAIL",
}

if "FAIL" in gates.values():
    overall_status = "FAIL"
elif "WARN" in gates.values():
    overall_status = "WARN"
else:
    overall_status = "PASS"

report = {
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "packageId": pkg_id,
    "profile": profile,
    "device": {
        "apiLevel": api_level,
        "model": device_model,
        "abi": device_abi,
    },
    "overallStatus": overall_status,
    "gates": gates,
    "measurements": measurements,
}

json_path = os.path.join(out_dir, "metrics.json")
with open(json_path, "w", encoding="utf-8") as f:
    json.dump(report, f, indent=2)
    f.write("\n")

def fmt_mib(kb: int | None) -> str:
    if kb is None:
        return "N/A"
    return f"{kb} KB ({kb / 1024.0:.1f} MiB)"

md_lines = [
    "# PocketLinux Performance & Footprint Metrics",
    "",
    f"- **Generated At**: `{report['generatedAt']}`",
    f"- **Profile**: `{profile}` (API `{api_level}`, model `{device_model or 'unknown'}`, ABI `{device_abi or 'unknown'}`)",
    f"- **Overall Status**: **{overall_status}**",
    "",
    "| Gate | Value | Threshold (Pass / Fail) | Status |",
    "|---|---:|---|:---:|",
    f"| APK Size (max) | {f'{max_apk_mib:.2f} MiB' if max_apk_mib is not None else 'N/A'} | <= 75 MiB / > 85 MiB | **{gates['apk_size']}** |",
    f"| Cold Startup to Ready | {measurements['cold_wall_to_ready_ms'] if measurements['cold_wall_to_ready_ms'] is not None else 'N/A'} ms | <= {cold_pass} ms / > {cold_fail} ms | **{gates['cold_startup']}** |",
    f"| Warm Startup to Ready | {measurements['warm_wall_to_ready_ms'] if measurements['warm_wall_to_ready_ms'] is not None else 'N/A'} ms | <= 3500 ms / > 7000 ms | **{gates['warm_startup']}** |",
    f"| Idle Android PSS | {fmt_mib(measurements['idle_pss_kb'])} | <= 130 MiB / > 200 MiB | **{gates['idle_pss']}** |",
    f"| Active Multi-App PSS | {fmt_mib(measurements['active_pss_kb'])} | <= 220 MiB / > 320 MiB | **{gates['active_pss']}** |",
    f"| CLI Latency (median 3x) | {cli_median_ms if cli_median_ms is not None else 'N/A'} ms | <= 500 ms / > 2000 ms | **{gates['cli_latency']}** |",
    f"| Process Budget (max) | {max_procs if max_procs is not None else 'N/A'} | <= 20 / >= 27 | **{gates['process_budget']}** |",
    f"| Logcat Crash/ANR/Phantom | {total_crashes} | 0 / > 0 | **{gates['crash_free']}** |",
    "",
    "## Detailed Measurements",
    f"- Warm Activity Resume (`TotalTime`): `{measurements['warm_resume_ms']}` ms",
    f"- Idle Breakdown: RSS={fmt_mib(measurements['idle_rss_kb'])}, JavaHeap={fmt_mib(measurements['idle_java_heap_kb'])}, NativeHeap={fmt_mib(measurements['idle_native_heap_kb'])}, Graphics={fmt_mib(measurements['idle_graphics_kb'])}",
    f"- Linux `sysinfo.sh`: total={fmt_mib(measurements['linux_mem_total_kb'])}, available={fmt_mib(measurements['linux_mem_available_kb'])}, procs=`{measurements['linux_process_count']}`, X11 windows=`{measurements['x_windows_count']}`",
    f"- Android UID (`{pkg_uid}`) processes: count=`{android_uid_procs}`, total RSS={fmt_mib(android_uid_rss_kb)}",
    f"- Logcat Audit: fatal=`{logcat_audit['fatalExceptions']}`, ANR=`{logcat_audit['anrs']}`, native=`{logcat_audit['nativeCrashes']}`, phantomKills=`{logcat_audit['phantomKills']}`",
    "",
]

md_path = os.path.join(out_dir, "metrics.md")
with open(md_path, "w", encoding="utf-8") as f:
    f.write("\n".join(md_lines))

print(f"[collect_metrics] Overall metrics status: {overall_status} (wrote {json_path} and {md_path})")
sys.exit(1 if overall_status == "FAIL" else 0)
PYEOF
