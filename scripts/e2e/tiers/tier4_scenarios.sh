#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$E2E_ROOT/../.." && pwd)"
# shellcheck source=scripts/e2e/lib/adb_common.sh
source "$E2E_ROOT/lib/adb_common.sh"

OUT_DIR="${OUT_DIR:-e2e-reports}"
PKG_ID="${PKG_ID:-com.pocketlinux}"
ABI="${ABI:-}"
ARM64_APK="${ARM64_APK:-}"
X86_64_APK="${X86_64_APK:-}"
ADB_SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
E2E_ALLOW_NO_DEVICE="${E2E_ALLOW_NO_DEVICE:-0}"
E2E_REQUIRE_DUAL_ABI="${E2E_REQUIRE_DUAL_ABI:-0}"

e2e_init "$OUT_DIR"

TMP_TIER_DIR="$(mktemp -d "${OUT_DIR}/.tier4_tmp.XXXXXX")"
LAST_DETAIL=""
TIER_FAILS=0
CACHED_DEVICE_APK=""
API_LEVEL=0
DEVICE_ABI=""

_tier_has_device() {
    command -v adb >/dev/null 2>&1 || return 1
    local st=""
    st="$(_e2e_adb get-state 2>/dev/null | tr -d '\r[:space:]' || true)"
    [[ "$st" == "device" ]]
}

_tier_cleanup_gui_apps() {
    local procs="${1:-xterm xclock xcalc mousepad galculator pcmanfm feh sleep gvfsd}"
    e2e_exec_linux "for p in $procs; do pkill -TERM -x \"\$p\" 2>/dev/null || true; done; sleep 0.3; for p in $procs; do pkill -KILL -x \"\$p\" 2>/dev/null || true; done; rm -rf /tmp/t4_* /root/e2e_ws /root/t4_* /mnt/sdcard/t4_*" 20 >/dev/null 2>&1 || true
}

_tier_on_exit() {
    if _tier_has_device; then
        _tier_cleanup_gui_apps >/dev/null 2>&1 || true
    fi
    rm -rf "$TMP_TIER_DIR" || true
}
trap _tier_on_exit EXIT

if _tier_has_device; then
    API_LEVEL="$(_e2e_adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r[:space:]' || echo "0")"
    [[ "$API_LEVEL" =~ ^[0-9]+$ ]] || API_LEVEL=0
    DEVICE_ABI="$(_e2e_adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r[:space:]' || echo "")"
fi

_tier_require_device() {
    if ! _tier_has_device; then
        LAST_DETAIL="No ADB device connected; live-device test cannot be mocked"
        return 1
    fi
    return 0
}

_tier_resolve_target_abi() {
    if [[ "$ABI" == "arm64" || "$ABI" == "arm64-v8a" ]]; then
        echo "arm64-v8a"
    elif [[ "$ABI" == "x86_64" ]]; then
        echo "x86_64"
    elif [[ "$DEVICE_ABI" == arm64* ]]; then
        echo "arm64-v8a"
    elif [[ "$DEVICE_ABI" == x86_64* ]]; then
        echo "x86_64"
    elif [[ -n "$X86_64_APK" ]]; then
        echo "x86_64"
    elif [[ -n "$ARM64_APK" ]]; then
        echo "arm64-v8a"
    else
        echo "x86_64"
    fi
}

_tier_resolve_active_apk() {
    local target_abi
    target_abi="$(_tier_resolve_target_abi)"
    if [[ "$target_abi" == "arm64-v8a" && -n "$ARM64_APK" ]]; then
        printf '%s\n' "$ARM64_APK"
        return 0
    fi
    if [[ -n "$X86_64_APK" && "$target_abi" == "x86_64" ]]; then
        printf '%s\n' "$X86_64_APK"
        return 0
    fi
    if [[ -n "$ARM64_APK" ]]; then
        printf '%s\n' "$ARM64_APK"
        return 0
    fi
    if [[ -n "$X86_64_APK" ]]; then
        printf '%s\n' "$X86_64_APK"
        return 0
    fi
    if [[ -n "$CACHED_DEVICE_APK" && -f "$CACHED_DEVICE_APK" ]]; then
        printf '%s\n' "$CACHED_DEVICE_APK"
        return 0
    fi
    if _tier_has_device; then
        local pm_line="" dev_apk=""
        pm_line="$(_e2e_adb shell pm path "$PKG_ID" 2>/dev/null | tr -d '\r' | head -n 1 || true)"
        dev_apk="${pm_line#package:}"
        if [[ -n "$dev_apk" ]]; then
            CACHED_DEVICE_APK="$TMP_TIER_DIR/device_base.apk"
            if _e2e_adb pull "$dev_apk" "$CACHED_DEVICE_APK" >/dev/null 2>&1 && [[ -f "$CACHED_DEVICE_APK" ]]; then
                printf '%s\n' "$CACHED_DEVICE_APK"
                return 0
            fi
        fi
    fi
    return 1
}

_tier_json_get() {
    local json_str="$1"
    local expr="$2"
    python3 - "$json_str" "$expr" <<'PYEOF'
import json, sys
data = json.loads(sys.argv[1])
cur = data
for part in sys.argv[2].split("."):
    if isinstance(cur, dict):
        cur = cur.get(part)
    elif isinstance(cur, list) and part.isdigit():
        idx = int(part)
        cur = cur[idx] if 0 <= idx < len(cur) else None
    else:
        cur = None
if cur is None:
    print("")
elif isinstance(cur, bool):
    print("true" if cur else "false")
elif isinstance(cur, (dict, list)):
    print(json.dumps(cur, separators=(",", ":")))
else:
    print(cur)
PYEOF
}

_tier_exec_ok() {
    local cmd="$1"
    local timeout_sec="${2:-25}"
    local rc=0 resp="" ec=""
    resp="$(e2e_exec_linux "$cmd" "$timeout_sec" 2>/dev/null)" || rc=$?
    if [[ $rc -ne 0 || -z "$resp" ]]; then
        LAST_DETAIL="e2e_exec_linux failed (rc=$rc) for: $cmd"
        return 1
    fi
    ec="$(_tier_json_get "$resp" "exitCode" 2>/dev/null || echo "")"
    if [[ "$ec" != "0" ]]; then
        LAST_DETAIL="guest exitCode=$ec for: $cmd"
        return 1
    fi
    printf '%s\n' "$resp"
}

_tier_ensure_ready() {
    local timeout_sec="${1:-90}"
    local poll_sleep="${2:-1}"
    local now_ms deadline_ms st_json="" st_val="" rc=0
    now_ms="$(_e2e_now_ms)"
    deadline_ms=$((now_ms + timeout_sec * 1000))
    while true; do
        rc=0
        st_json="$(e2e_broadcast_status 10 2>/dev/null)" || rc=$?
        if [[ $rc -eq 0 && -n "$st_json" ]]; then
            st_val="$(python3 -c 'import json,sys; print(str(json.loads(sys.argv[1]).get("state","")).strip().lower())' "$st_json" 2>/dev/null || true)"
            if [[ "$st_val" == "ready" ]]; then
                printf '%s\n' "$st_json"
                return 0
            elif [[ "$st_val" == "error" ]]; then
                LAST_DETAIL="Runtime entered Error state: $st_json"
                return 1
            fi
        fi
        now_ms="$(_e2e_now_ms)"
        if [[ $now_ms -ge $deadline_ms ]]; then
            LAST_DETAIL="Timed out after ${timeout_sec}s waiting for RuntimeState.Ready"
            return 1
        fi
        sleep "$poll_sleep"
    done
}

_tier_ensure_foreground() {
    _e2e_adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    _e2e_adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || {
        LAST_DETAIL="Failed to bring MainActivity to foreground"
        return 1
    }
}

_tier_dump_ui() {
    local out_xml="$1"
    local attempt=1 rc=0
    while [[ $attempt -le 3 ]]; do
        _e2e_adb shell rm -f /data/local/tmp/e2e_ui.xml >/dev/null 2>&1 || true
        rc=0
        _e2e_adb shell uiautomator dump /data/local/tmp/e2e_ui.xml >/dev/null 2>&1 || rc=$?
        if [[ $rc -ne 0 ]]; then
            _e2e_adb shell uiautomator dump --compressed /data/local/tmp/e2e_ui.xml >/dev/null 2>&1 || true
        fi
        _e2e_adb exec-out cat /data/local/tmp/e2e_ui.xml > "$out_xml" 2>/dev/null || true
        _e2e_adb shell rm -f /data/local/tmp/e2e_ui.xml >/dev/null 2>&1 || true
        if [[ -s "$out_xml" ]] && python3 -c 'import xml.etree.ElementTree as ET, sys; ET.parse(sys.argv[1])' "$out_xml" >/dev/null 2>&1; then
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 0.5
    done
    LAST_DETAIL="uiautomator dump failed after 3 attempts"
    return 1
}

_tier_find_ui_node() {
    local xml_path="$1"
    local tag="$2"
    local fallback_text="${3:-}"
    local class_hint="${4:-}"
    python3 - "$xml_path" "$tag" "$fallback_text" "$class_hint" <<'PYEOF'
import re, sys, xml.etree.ElementTree as ET

xml_path, tag, fallback, class_hint = sys.argv[1:5]
root = ET.parse(xml_path).getroot()

def parse_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    return tuple(map(int, m.groups())) if m else None

candidates = []
for node in root.iter("node"):
    rid = node.attrib.get("resource-id", "")
    txt = node.attrib.get("text", "")
    cd = node.attrib.get("content-desc", "")
    cls = node.attrib.get("class", "")
    b = parse_bounds(node.attrib.get("bounds", ""))
    if not b:
        continue
    x1, y1, x2, y2 = b
    if x2 <= x1 or y2 <= y1:
        continue
    score = 0
    if tag and (rid == tag or rid.endswith(f":id/{tag}") or rid.endswith(f"/{tag}")):
        score = 100
    elif fallback and (fallback.lower() in txt.lower() or fallback.lower() in cd.lower()):
        score = 50
    elif class_hint and class_hint in cls:
        score = 25
    if score > 0:
        candidates.append((score, (x1 + x2) // 2, (y1 + y2) // 2, x1, y1, x2, y2, txt))

if not candidates:
    sys.exit(1)
candidates.sort(key=lambda item: item[0], reverse=True)
_, cx, cy, x1, y1, x2, y2, txt = candidates[0]
print(f"{cx} {cy} {x1} {y1} {x2} {y2} {txt}")
PYEOF
}

_tier_tap_ui_node() {
    local xml_path="$1"
    local tag="$2"
    local fallback_text="${3:-}"
    local class_hint="${4:-}"
    local info="" cx="" cy=""
    info="$(_tier_find_ui_node "$xml_path" "$tag" "$fallback_text" "$class_hint" 2>/dev/null)" || {
        LAST_DETAIL="UI node not found: tag='$tag' fallback='$fallback_text'"
        return 1
    }
    cx="$(printf '%s\n' "$info" | awk '{print $1}')"
    cy="$(printf '%s\n' "$info" | awk '{print $2}')"
    _e2e_adb shell input tap "$cx" "$cy" >/dev/null 2>&1 || return 1
}

_tier_wait_xwindow() {
    local pattern="$1"
    local timeout_sec="${2:-8}"
    local deadline=$(( $(date +%s) + timeout_sec ))
    local resp="" out="" rc=0
    while true; do
        rc=0
        resp="$(e2e_exec_linux "xwininfo -display :1 -root -tree" 15 2>/dev/null)" || rc=$?
        if [[ $rc -eq 0 && -n "$resp" ]]; then
            out="$(_tier_json_get "$resp" "stdout" 2>/dev/null || true)"
            if python3 -c 'import sys; sys.exit(0 if sys.argv[1].lower() in sys.argv[2].lower() else 1)' "$pattern" "$out"; then
                printf '%s\n' "$out"
                return 0
            fi
        fi
        if [[ $(date +%s) -ge $deadline ]]; then
            LAST_DETAIL="Window matching '${pattern}' did not appear within ${timeout_sec}s"
            return 1
        fi
        sleep 0.5
    done
}

_tier_count_uid_procs() {
    local uid_line="" uid="" ps_out=""
    uid_line="$(_e2e_adb shell dumpsys package "$PKG_ID" 2>/dev/null | tr -d '\r' | grep -E 'userId=|appId=' | head -n 1 || true)"
    uid="$(python3 -c 'import re,sys; m=re.search(r"(?:userId|appId|uid)=(\d+)", sys.argv[1]); print(m.group(1) if m else "")' "$uid_line" 2>/dev/null || true)"
    ps_out="$(_e2e_adb shell "ps -A -o UID,PID,NAME" 2>/dev/null | tr -d '\r' || _e2e_adb shell "ps -A" 2>/dev/null | tr -d '\r' || true)"
    PS_OUT="$ps_out" python3 - "$uid" <<'PYEOF'
import os, sys
if not sys.argv[1]:
    print(0); sys.exit(0)
uid = int(sys.argv[1])
aliases = {str(uid)}
if uid >= 10000:
    aliases.add(f"u0_a{uid - 10000}")
cnt = 0
for line in os.environ.get("PS_OUT", "").splitlines():
    parts = line.split()
    if parts and parts[0] in aliases:
        cnt += 1
print(cnt)
PYEOF
}

_tier_parse_meminfo_pss_kb() {
    local mem_txt=""
    mem_txt="$(_e2e_adb shell dumpsys meminfo "$PKG_ID" 2>/dev/null | tr -d '\r' || true)"
    MEM_TXT="$mem_txt" python3 - <<'PYEOF'
import os, re, sys
t = os.environ.get("MEM_TXT", "")
for pat in (r"TOTAL\s+PSS:\s*(\d+)", r"^\s*TOTAL:\s*(\d+)", r"^\s*TOTAL\s+(\d+)"):
    m = re.search(pat, t, re.I | re.M)
    if m:
        print(m.group(1)); sys.exit(0)
print(0)
PYEOF
}

_tier_run_test() {
    local test_id="$1"
    local tier_num="$2"
    local feature="$3"
    local fn="$4"
    local t0 t1 dur rc=0 status="PASS"
    LAST_DETAIL=""
    t0="$(_e2e_now_ms)"
    "$fn" || rc=$?
    t1="$(_e2e_now_ms)"
    dur=$((t1 - t0))
    if [[ $dur -lt 0 ]]; then dur=0; fi
    if [[ $rc -ne 0 ]]; then
        status="FAIL"
        TIER_FAILS=$((TIER_FAILS + 1))
    fi
    e2e_record_result "$test_id" "$tier_num" "$feature" "$status" "$dur" "${LAST_DETAIL:-ok}"
}

# ==============================================================================
# T4-01: Python Data Script Dev & /mnt/sdcard Cross-Storage Persistence
# ==============================================================================
test_t4_01() {
    _tier_require_device || return 1
    _tier_exec_ok "cat <<'PYEOF' > /mnt/sdcard/t4_pipeline.py
import json, sqlite3, statistics
conn = sqlite3.connect('/mnt/sdcard/t4_data.db')
conn.execute('CREATE TABLE IF NOT EXISTS metrics (id INTEGER PRIMARY KEY, val REAL)')
conn.execute('DELETE FROM metrics')
conn.executemany('INSERT INTO metrics(val) VALUES (?)', [(float(i * 2 + 1),) for i in range(500)])
conn.commit()
vals = [r[0] for r in conn.execute('SELECT val FROM metrics').fetchall()]
conn.close()
summary = {
    'row_count': len(vals),
    'mean': round(statistics.mean(vals), 2),
    'min': min(vals),
    'max': max(vals),
    'stdev': round(statistics.stdev(vals), 2),
}
with open('/mnt/sdcard/t4_summary.json', 'w') as f:
    json.dump(summary, f)
with open('/mnt/sdcard/t4_chart.ppm', 'wb') as f:
    f.write(b'P6\n64 64\n255\n' + (b'\x02\x84\xc7' * (64 * 64)))
print('T4_01_DONE')
PYEOF" 20 >/dev/null || return 1

    # Execute via Terminal tab PTY bridge
    local ui_xml="$TMP_TIER_DIR/ui_t4_01.xml"
    _tier_ensure_foreground || return 1
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_terminal" "Terminal" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_input" "command" "EditText" || return 1
    _e2e_adb shell input text "python3%s/mnt/sdcard/t4_pipeline.py" >/dev/null 2>&1 || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_send_btn" "Send" || _e2e_adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
    sleep 2
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true

    local android_summary=""
    android_summary="$(_e2e_adb shell "run-as ${PKG_ID} cat files/shared/t4_summary.json" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_json "$android_summary" "int(d.get('row_count',0)) == 500 and float(d.get('mean',0)) == 500.0" || {
        _tier_cleanup_gui_apps
        LAST_DETAIL="Cross-storage summary JSON mismatch: $android_summary"
        return 1
    }
    _tier_exec_ok "test -s /mnt/sdcard/t4_chart.ppm && rm -f /mnt/sdcard/t4_*" 15 >/dev/null || return 1
    LAST_DETAIL="Executed SQLite/statistics Python pipeline via PTY with /mnt/sdcard persistence"
}

# ==============================================================================
# T4-02: Multi-Window Linux Desktop Productivity (xterm, mousepad, galculator, pcmanfm)
# ==============================================================================
test_t4_02() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local f0="" f1="" s0="" s1="" st="" pss_kb=0 procs=0
    _tier_exec_ok "mkdir -p /root/e2e_ws && printf 'PocketLinux workspace\n' > /root/e2e_ws/readme.txt" 15 >/dev/null || return 1
    f0="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    s0="$(_tier_json_get "$f0" "sha256")"

    e2e_launch_gui "xterm -title WS_TERM" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    e2e_launch_gui "mousepad /root/e2e_ws/readme.txt" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    e2e_launch_gui "galculator" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    e2e_launch_gui "pcmanfm /root/e2e_ws" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }

    local win=""
    for win in WS_TERM mousepad galculator pcmanfm; do
        _tier_wait_xwindow "$win" 8 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    done
    sleep 1
    f1="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
    s1="$(_tier_json_get "$f1" "sha256")"
    [[ "$s0" != "$s1" ]] || { _tier_cleanup_gui_apps; LAST_DETAIL="RFB frame unchanged after 4 GUI windows"; return 1; }

    st="$(e2e_broadcast_status 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
    e2e_assert_json "$st" "len(d.get('activeXWindows',[])) >= 4" || { _tier_cleanup_gui_apps; LAST_DETAIL="activeXWindows < 4: $st"; return 1; }

    pss_kb="$(_tier_parse_meminfo_pss_kb)"
    procs="$(_tier_count_uid_procs)"
    _tier_cleanup_gui_apps
    if [[ "$pss_kb" -gt 327680 ]]; then
        LAST_DETAIL="Multi-window PSS ${pss_kb}KB > 320 MiB gate"
        return 1
    fi
    if [[ "$procs" -ge 27 ]]; then
        LAST_DETAIL="Multi-window UID procs ${procs} >= 27"
        return 1
    fi
    LAST_DETAIL="4 simultaneous GUI productivity apps verified (PSS=${pss_kb}KB, procs=${procs})"
}

# ==============================================================================
# T4-03: Visual X11 Tools & Image Viewer Workflow (xclock, xcalc, feh) + RFB Interaction
# ==============================================================================
test_t4_03() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t4_03.xml" info="" f0="" f1="" f2="" s0="" s1="" s2=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    info="$(_tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" 2>/dev/null)" || { LAST_DETAIL="desktop_surface missing"; return 1; }

    _tier_exec_ok "python3 -c '
w,h=240,180
rows=[]
for y in range(h):
    for x in range(w):
        c = b\"\xff\xff\xff\" if ((x//20)+(y//20))%2==0 else b\"\x0f\x17\x2a\"
        rows.append(c)
with open(\"/tmp/t4_board.ppm\",\"wb\") as f:
    f.write(f\"P6\n{w} {h}\n255\n\".encode() + b\"\".join(rows))
'" 15 >/dev/null || return 1

    f0="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    s0="$(_tier_json_get "$f0" "sha256")"
    e2e_launch_gui "xclock -geometry 180x180+20+20" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    e2e_launch_gui "xcalc -geometry +220+20" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    e2e_launch_gui "feh --title T403_IMG /tmp/t4_board.ppm" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    for win in xclock xcalc T403_IMG; do
        _tier_wait_xwindow "$win" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    done
    sleep 0.8
    f1="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
    s1="$(_tier_json_get "$f1" "sha256")"
    [[ "$s0" != "$s1" ]] || { _tier_cleanup_gui_apps; LAST_DETAIL="RFB unchanged after launching visual apps"; return 1; }

    local cx="" cy=""
    cx="$(printf '%s\n' "$info" | awk '{print $1}')"
    cy="$(printf '%s\n' "$info" | awk '{print $2}')"
    _e2e_adb shell input tap "$cx" "$cy" >/dev/null 2>&1 || true
    sleep 0.4
    f2="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
    e2e_assert_json "$f2" "d.get('connected') is True" || { _tier_cleanup_gui_apps; return 1; }
    _tier_cleanup_gui_apps
    LAST_DETAIL="Visual X11 workflow (xclock + xcalc + feh) & RFB interaction verified"
}

# ==============================================================================
# T4-04: Package Inspection (apk), Native Musl ELF Verification & /proc Monitoring
# ==============================================================================
test_t4_04() {
    _tier_require_device || return 1
    local expected_em="62"
    [[ "$(_tier_resolve_target_abi)" == "arm64-v8a" ]] && expected_em="183"
    local resp="" out=""
    resp="$(_tier_exec_ok "apk info -v | head -n 10 && python3 -c '
import struct
for p in (\"/bin/busybox\", \"/usr/bin/python3\", \"/usr/bin/Xvnc\", \"/usr/bin/jwm\"):
    data = open(p, \"rb\").read(64)
    assert data[:4] == b\"\\x7fELF\" and data[4] == 2
    _, em = struct.unpack(\"<HH\", data[16:20])
    assert em == ${expected_em}, (p, em)
print(\"MUSL_ELF_OK\")
' && /usr/local/bin/sysinfo.sh" 25)" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "MUSL_ELF_OK" "ELF64 binary inspection" || { LAST_DETAIL="MUSL_ELF_OK missing: $out"; return 1; }
    e2e_assert_contains "$out" "alpine_release" "sysinfo JSON" || { LAST_DETAIL="sysinfo JSON missing alpine_release: $out"; return 1; }

    local ui_xml="$TMP_TIER_DIR/ui_t4_04.xml"
    _tier_ensure_foreground || return 1
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "metrics_refresh_btn" "Refresh" || return 1
    sleep 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    LAST_DETAIL="Verified apk inventory, native musl ELF64 binaries, sysinfo.sh, and Apps metrics"
}

# ==============================================================================
# T4-05: Full Lifecycle: Cold Boot -> CLI -> GUI -> Force-Stop -> Warm Boot
# ==============================================================================
test_t4_05() {
    _tier_require_device || return 1
    _e2e_adb shell pm clear "$PKG_ID" >/dev/null 2>&1 || return 1
    if [[ "$API_LEVEL" -ge 33 ]]; then
        _e2e_adb shell pm grant "$PKG_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    fi
    sleep 1
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
    _tier_ensure_ready 120 1 >/dev/null || return 1

    _tier_exec_ok "printf 'T405_OK' > /root/t4_persist.txt && printf 'T405_SD' > /mnt/sdcard/t4_shared.txt" 15 >/dev/null || return 1
    e2e_launch_gui "xclock" 15 >/dev/null 2>&1 || return 1
    _tier_wait_xwindow "xclock" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }

    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
    sleep 1
    local rem=0
    rem="$(_tier_count_uid_procs)"
    e2e_assert_eq "$rem" "0" "0 procs after force-stop" || { LAST_DETAIL="$rem procs left after force-stop"; return 1; }

    local wt0="" wt1="" warm_wall=0 warm_st="" startup_ms=0
    wt0="$(_e2e_now_ms)"
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
    warm_st="$(_tier_ensure_ready 30 0.25)" || return 1
    wt1="$(_e2e_now_ms)"
    warm_wall=$((wt1 - wt0))
    startup_ms="$(_tier_json_get "$warm_st" "startupMs")"
    if [[ "${startup_ms:-0}" -gt 7000 ]]; then
        LAST_DETAIL="Warm startupMs ${startup_ms}ms > 7000ms gate"
        return 1
    fi

    local resp="" fr=""
    resp="$(_tier_exec_ok "cat /root/t4_persist.txt && cat /mnt/sdcard/t4_shared.txt && rm -f /root/t4_persist.txt /mnt/sdcard/t4_shared.txt" 15)" || return 1
    e2e_assert_contains "$(_tier_json_get "$resp" "stdout")" "T405_OK" "persist file" || return 1
    e2e_assert_contains "$(_tier_json_get "$resp" "stdout")" "T405_SD" "shared persist file" || return 1
    sleep 1
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True" || return 1
    LAST_DETAIL="Full cold->CLI->GUI->force-stop->warm (${startup_ms}ms runtime / ${warm_wall}ms wall) lifecycle verified"
}

# ==============================================================================
# T4-06: Concurrent CLI + GUI Stress & Resource Stability (30s Soak, < 27 Procs)
# ==============================================================================
test_t4_06() {
    _tier_require_device || return 1
    e2e_launch_gui "xclock" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    e2e_launch_gui "xterm" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    _tier_wait_xwindow "xclock" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }

    local max_procs=0 max_pss=0 iter=1
    while [[ $iter -le 6 ]]; do
        local pids=() p_rc=0
        e2e_exec_linux "python3 -c 'import hashlib; print(hashlib.sha256(b\"soak\"*2000).hexdigest())'" 20 >/dev/null 2>&1 &
        pids+=($!)
        e2e_exec_linux "/usr/local/bin/sysinfo.sh" 20 >/dev/null 2>&1 &
        pids+=($!)
        for p in "${pids[@]}"; do
            wait "$p" || p_rc=1
        done
        if [[ $p_rc -ne 0 ]]; then
            _tier_cleanup_gui_apps
            LAST_DETAIL="Soak iteration $iter exec failure"
            return 1
        fi
        local fr="" st="" procs=0 pss=0
        fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
        e2e_assert_json "$fr" "d.get('connected') is True" || { _tier_cleanup_gui_apps; return 1; }
        st="$(e2e_broadcast_status 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
        e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready'" || { _tier_cleanup_gui_apps; return 1; }
        procs="$(_tier_count_uid_procs)"
        pss="$(_tier_parse_meminfo_pss_kb)"
        [[ "$procs" -lt 27 ]] || { _tier_cleanup_gui_apps; LAST_DETAIL="Soak iter $iter UID procs $procs >= 27"; return 1; }
        [[ "$pss" -le 327680 ]] || { _tier_cleanup_gui_apps; LAST_DETAIL="Soak iter $iter PSS ${pss}KB > 320 MiB"; return 1; }
        if [[ "$procs" -gt "$max_procs" ]]; then max_procs="$procs"; fi
        if [[ "$pss" -gt "$max_pss" ]]; then max_pss="$pss"; fi
        sleep 4
        iter=$((iter + 1))
    done

    _tier_cleanup_gui_apps
    sleep 1
    local final_procs=0
    final_procs="$(_tier_count_uid_procs)"
    [[ "$final_procs" -lt 27 ]] || { LAST_DETAIL="Post-soak procs $final_procs >= 27"; return 1; }
    LAST_DETAIL="30s concurrent CLI+GUI soak passed (maxProcs=${max_procs}, maxPSS=${max_pss}KB, finalProcs=${final_procs})"
}

# ==============================================================================
# T4-07: GitHub Actions CI Dual-ABI Release Verification (arm64 + x86_64 + Emulator)
# ==============================================================================
test_t4_07() {
    local out_json="$TMP_TIER_DIR/t4_07_apks.json" dual_note=""
    if [[ -n "$ARM64_APK" && -n "$X86_64_APK" ]]; then
        python3 "$E2E_ROOT/verify_apks.py" --arm64-apk "$ARM64_APK" --x86-64-apk "$X86_64_APK" --out-json "$out_json" >/dev/null 2>&1 || {
            LAST_DETAIL="Dual-ABI verify_apks.py failed"
            return 1
        }
        dual_note="dual_abi=verified"
    elif [[ "${CI:-}" == "true" || "$E2E_REQUIRE_DUAL_ABI" == "1" ]]; then
        LAST_DETAIL="CI/E2E_REQUIRE_DUAL_ABI requires both ARM64_APK and X86_64_APK"
        return 1
    else
        local apk="" target_abi="" flag=""
        apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No active APK available"; return 1; }
        target_abi="$(_tier_resolve_target_abi)"
        if [[ "$target_abi" == "arm64-v8a" ]]; then flag="--arm64-apk"; else flag="--x86-64-apk"; fi
        python3 "$E2E_ROOT/verify_apks.py" "$flag" "$apk" --out-json "$out_json" >/dev/null 2>&1 || {
            LAST_DETAIL="verify_apks.py failed on $apk"
            return 1
        }
        python3 - "$REPO_ROOT/app/build.gradle.kts" "$REPO_ROOT/scripts/ci/build-proot.sh" "$REPO_ROOT/scripts/ci/build-rootfs.sh" <<'PYEOF' || { LAST_DETAIL="Multi-ABI CI script config missing"; return 1; }
import pathlib, sys
g = pathlib.Path(sys.argv[1]).read_text()
bp = pathlib.Path(sys.argv[2]).read_text()
br = pathlib.Path(sys.argv[3]).read_text()
assert "arm64" in g and "x86_64" in g
assert "arm64-v8a" in bp and "x86_64" in bp
assert "aarch64" in br and "x86_64" in br
PYEOF
        dual_note="arm64_apk=not_provided"
    fi

    _tier_require_device || return 1
    local st="" resp="" expected_arch="x86_64"
    [[ "$(_tier_resolve_target_abi)" == "arm64-v8a" ]] && expected_arch="aarch64"
    st="$(e2e_broadcast_status 15 2>/dev/null)" || return 1
    e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready'" || return 1
    resp="$(_tier_exec_ok "uname -m && cat /etc/alpine-release" 15)" || return 1
    e2e_assert_contains "$(_tier_json_get "$resp" "stdout")" "$expected_arch" "emulator arch" || return 1
    e2e_assert_contains "$(_tier_json_get "$resp" "stdout")" "3.20." "alpine version" || return 1
    LAST_DETAIL="CI release verification passed (${dual_note}, arch=${expected_arch})"
}

_tier_run_test "T4-01" 4 "F4,F5,F6,F7,F10,F11,F12" test_t4_01
_tier_run_test "T4-02" 4 "F3,F7,F8,F9,F10,F11,F12" test_t4_02
_tier_run_test "T4-03" 4 "F5,F7,F8,F9,F11,F12" test_t4_03
_tier_run_test "T4-04" 4 "F3,F5,F7,F10,F11,F12" test_t4_04
_tier_run_test "T4-05" 4 "F4,F5,F7,F8,F9,F10,F11,F12" test_t4_05
_tier_run_test "T4-06" 4 "F5,F6,F7,F8,F9,F10,F11,F12" test_t4_06
_tier_run_test "T4-07" 4 "F1,F2,F3,F12" test_t4_07

echo "[tier4] Completed 7 tests with ${TIER_FAILS} failure(s)" >&2
[[ $TIER_FAILS -eq 0 ]]
