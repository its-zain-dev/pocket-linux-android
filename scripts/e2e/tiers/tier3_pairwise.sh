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

TMP_TIER_DIR="$(mktemp -d "${OUT_DIR}/.tier3_tmp.XXXXXX")"
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
    e2e_exec_linux "for p in $procs; do pkill -TERM -x \"\$p\" 2>/dev/null || true; done; sleep 0.3; for p in $procs; do pkill -KILL -x \"\$p\" 2>/dev/null || true; done; rm -f /tmp/e2e_* /tmp/t3_* /mnt/sdcard/t3_*" 20 >/dev/null 2>&1 || true
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
        sleep 1
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

_tier_check_proc_budget() {
    local uid_line="" uid="" ps_out="" count=0
    uid_line="$(_e2e_adb shell dumpsys package "$PKG_ID" 2>/dev/null | tr -d '\r' | grep -E 'userId=|appId=' | head -n 1 || true)"
    uid="$(python3 -c 'import re,sys; m=re.search(r"(?:userId|appId|uid)=(\d+)", sys.argv[1]); print(m.group(1) if m else "")' "$uid_line" 2>/dev/null || true)"
    ps_out="$(_e2e_adb shell "ps -A -o UID,PID,NAME" 2>/dev/null | tr -d '\r' || _e2e_adb shell "ps -A" 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$uid" && -n "$ps_out" ]]; then
        count="$(PS_OUT="$ps_out" python3 - "$uid" <<'PYEOF'
import os, sys
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
)"
        if [[ "$count" -ge 27 ]]; then
            LAST_DETAIL="Android UID process count $count >= 27"
            return 1
        fi
    fi
    return 0
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
# Pairwise Tests T3-01 .. T3-14
# ==============================================================================
test_t3_01() {
    _tier_require_device || return 1
    local apk="" target_abi="" resp="" live_arch="" expected_arch=""
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    expected_arch="x86_64"
    [[ "$target_abi" == "arm64-v8a" ]] && expected_arch="aarch64"
    python3 - "$apk" "$target_abi" <<'PYEOF' || { LAST_DETAIL="APK ELF64 mismatch"; return 1; }
import struct, sys, zipfile
apk, abi = sys.argv[1], sys.argv[2]
exp = 0x00B7 if abi == "arm64-v8a" else 0x003E
with zipfile.ZipFile(apk, "r") as zf:
    for lib in ("libproot.so", "libproot-loader.so"):
        d = zf.read(f"lib/{abi}/{lib}")
        _, em = struct.unpack("<HH", d[16:20])
        assert em == exp
PYEOF
    resp="$(_tier_exec_ok "uname -m")" || return 1
    live_arch="$(_tier_json_get "$resp" "stdout" | tr -d '[:space:]')"
    e2e_assert_eq "$live_arch" "$expected_arch" "APK ELF vs live uname -m" || return 1
    LAST_DETAIL="APK ELF64 ($target_abi) matched live PRoot uname -m ($live_arch)"
}

test_t3_02() {
    _tier_require_device || return 1
    local apk="" target_abi="" asset_rel="" dev_rel=""
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    asset_rel="$(python3 - "$apk" "assets/rootfs-${target_abi}.tar.gz" <<'PYEOF' || { LAST_DETAIL="Failed extracting alpine-release from APK"; return 1; }
import gzip, re, sys, tarfile, zipfile
with zipfile.ZipFile(sys.argv[1], "r") as zf:
    with zf.open(sys.argv[2], "r") as raw:
        with gzip.GzipFile(fileobj=raw, mode="rb") as gz:
            with tarfile.open(fileobj=gz, mode="r|") as tf:
                for m in tf:
                    norm = re.sub(r"^(\./+)+", "", m.name)
                    if norm == "etc/alpine-release" and m.isfile():
                        print(tf.extractfile(m).read().decode("utf-8").strip())
                        sys.exit(0)
sys.exit(1)
PYEOF
)"
    dev_rel="$(_tier_exec_ok "cat /etc/alpine-release" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["stdout"].strip())')" || return 1
    e2e_assert_eq "$dev_rel" "$asset_rel" "rootfs alpine-release parity" || return 1
    LAST_DETAIL="APK rootfs asset alpine-release ($asset_rel) matched live extracted rootfs"
}

test_t3_03() {
    _tier_require_device || return 1
    local tok_a="T303_A_$(date +%s)" tok_b="T303_B_$(date +%s)" got_a="" got_b=""
    _tier_exec_ok "printf '%s' '$tok_a' > /mnt/sdcard/t3_03_a.txt" >/dev/null || return 1
    got_a="$(_e2e_adb shell "run-as ${PKG_ID} cat files/shared/t3_03_a.txt" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_eq "$got_a" "$tok_a" "PRoot->Android /mnt/sdcard" || { _tier_cleanup_gui_apps; return 1; }
    _e2e_adb shell "run-as ${PKG_ID} sh -c 'printf \"%s\" \"${tok_b}\" > files/shared/t3_03_b.txt'" >/dev/null 2>&1 || return 1
    got_b="$(_tier_exec_ok "cat /mnt/sdcard/t3_03_b.txt && rm -f /mnt/sdcard/t3_03_a.txt /mnt/sdcard/t3_03_b.txt" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["stdout"].strip())')" || return 1
    e2e_assert_eq "$got_b" "$tok_b" "Android->PRoot /mnt/sdcard" || return 1
    LAST_DETAIL="Bidirectional /mnt/sdcard <-> files/shared persistence verified"
}

test_t3_04() {
    _tier_require_device || return 1
    _tier_exec_ok "printf 'PART1_' > /tmp/t3_04.txt" >/dev/null || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t3_04.xml"
    _tier_ensure_foreground || return 1
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_terminal" "Terminal" || return 1
    sleep 0.6
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_input" "command" "EditText" || return 1
    _e2e_adb shell input text "printf%sPART2%s>>%s/tmp/t3_04.txt" >/dev/null 2>&1 || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_send_btn" "Send" || _e2e_adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
    sleep 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    local combined=""
    combined="$(_tier_exec_ok "cat /tmp/t3_04.txt && rm -f /tmp/t3_04.txt" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["stdout"].strip())')" || return 1
    e2e_assert_eq "$combined" "PART1_PART2" "PRoot exec + PTY shared file" || return 1
    LAST_DETAIL="Shared /tmp/t3_04.txt state verified across ACTION_EXEC_LINUX and PTY Terminal"
}

test_t3_05() {
    _tier_require_device || return 1
    local resp="" out="" st=""
    resp="$(_tier_exec_ok "python3 -c 'import hashlib; print(hashlib.sha256(b\"pocketlinux\"*500).hexdigest()[:16])' && seq 1 50 | awk '{s+=\$1} END{print s}'")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "1275" "pipeline sum" || return 1
    st="$(e2e_broadcast_status 10 2>/dev/null)" || return 1
    e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready'" || return 1
    _tier_check_proc_budget || return 1
    LAST_DETAIL="Multi-stage Python+BusyBox workload succeeded under Ready supervisor (<27 procs)"
}

test_t3_06() {
    _tier_require_device || return 1
    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
    sleep 1
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
    _tier_ensure_ready 60 >/dev/null || return 1
    sleep 1.5
    local fr=""
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True and int(d.get('nonUniformPixels',0)) > 0" || {
        LAST_DETAIL="RFB not ready immediately after boot: $fr"
        return 1
    }
    LAST_DETAIL="Warm boot -> Ready -> RFB connected with nonUniformPixels > 0"
}

test_t3_07() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t3_07.xml" info="" f0="" f1="" s0="" s1="" p1="$TMP_TIER_DIR/t307_1.png" p2="$TMP_TIER_DIR/t307_2.png"
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    info="$(_tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" 2>/dev/null)" || { LAST_DETAIL="desktop_surface missing"; return 1; }
    f0="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    s0="$(_tier_json_get "$f0" "sha256")"
    _e2e_adb exec-out screencap -p > "$p1" 2>/dev/null || return 1
    e2e_exec_linux "xsetroot -display :1 -solid '#0EA5E9'" 10 >/dev/null 2>&1 || true
    e2e_launch_gui "xclock -geometry 220x220+80+80" 15 >/dev/null 2>&1 || { e2e_exec_linux "xsetroot -display :1 -solid '#1E293B'" 10 >/dev/null 2>&1 || true; return 1; }
    _tier_wait_xwindow "xclock" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    sleep 1
    f1="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
    s1="$(_tier_json_get "$f1" "sha256")"
    _e2e_adb exec-out screencap -p > "$p2" 2>/dev/null || { _tier_cleanup_gui_apps; return 1; }
    _tier_cleanup_gui_apps
    e2e_exec_linux "xsetroot -display :1 -solid '#1E293B'" 10 >/dev/null 2>&1 || true
    [[ "$s0" != "$s1" ]] || { LAST_DETAIL="RFB sha256 did not change"; return 1; }
    cmp -s "$p1" "$p2" && { LAST_DETAIL="Physical screencap did not change"; return 1; } || true
    LAST_DETAIL="Verified synchronized RFB sha256 and physical screencap pixel delta"
}

test_t3_08() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t3_08.xml" info="" cx="" cy="" rc=0
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    info="$(_tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" 2>/dev/null)" || { LAST_DETAIL="desktop_surface missing"; return 1; }
    cx="$(printf '%s\n' "$info" | awk '{print $1}')"
    cy="$(printf '%s\n' "$info" | awk '{print $2}')"
    e2e_launch_gui "xterm" 15 >/dev/null 2>&1 || return 1
    _tier_wait_xwindow "xterm" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    sleep 0.6
    _e2e_adb shell input tap "$cx" "$cy" >/dev/null 2>&1 || true
    sleep 0.3
    _e2e_adb shell input text "touch%s/tmp/t3_08_ok" >/dev/null 2>&1 || true
    _e2e_adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1 || true
    sleep 0.6
    _tier_exec_ok "test -f /tmp/t3_08_ok && rm -f /tmp/t3_08_ok" 15 >/dev/null || rc=$?
    _tier_cleanup_gui_apps
    [[ $rc -eq 0 ]] || { LAST_DETAIL="desktop_surface input did not reach xterm"; return 1; }
    LAST_DETAIL="End-to-end touch + key forwarding into xterm verified"
}

test_t3_09() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t3_09.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_terminal" "Terminal" || return 1
    sleep 0.6
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_input" "command" "EditText" || return 1
    _e2e_adb shell input text "export%sT309_V=ok9" >/dev/null 2>&1 || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_send_btn" "Send" || _e2e_adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
    sleep 0.8
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" || return 1
    sleep 0.4
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.4
    _tier_tap_ui_node "$ui_xml" "tab_terminal" "Terminal" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_input" "command" "EditText" || return 1
    _e2e_adb shell input text "echo%sSTILL_\$T309_V" >/dev/null 2>&1 || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_send_btn" "Send" || _e2e_adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
    sleep 1
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "STILL_ok9" "PTY persistence across 3 tabs" || { LAST_DETAIL="PTY state lost across 3-tab switch"; return 1; }
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    LAST_DETAIL="PTY session survived full 3-tab recomposition cycle"
}

test_t3_10() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t3_10.xml" st=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "launcher_btn_xcalc" "xcalc" || return 1
    _tier_wait_xwindow "xcalc" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    _tier_tap_ui_node "$ui_xml" "metrics_refresh_btn" "Refresh" >/dev/null 2>&1 || true
    sleep 0.6
    st="$(e2e_broadcast_status 10 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
    _tier_cleanup_gui_apps
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    e2e_assert_json "$st" "any('xcalc' in str(w).lower() or 'calculator' in str(w).lower() for w in d.get('activeXWindows',[]))" || {
        LAST_DETAIL="xcalc not reflected in activeXWindows: $st"
        return 1
    }
    LAST_DETAIL="Apps tab xcalc launcher reflected in live X11 tree & status telemetry"
}

test_t3_11() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t3_11.xml" raw_xml="" st=""
    st="$(e2e_broadcast_status 15 2>/dev/null)" || return 1
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "Ready" "UI Ready badge" || { LAST_DETAIL="UI missing Ready badge"; return 1; }
    e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready' and int(d.get('androidPssKb',0)) > 0" || return 1
    LAST_DETAIL="Cross-checked UI status badges against ACTION_GET_STATUS telemetry"
}

test_t3_12() {
    _tier_require_device || return 1
    local f0="" f1="" f2="" s0="" s1="" s2=""
    _tier_exec_ok "python3 -c '
w,h=96,96
with open(\"/tmp/t3_feh.ppm\",\"wb\") as f:
    f.write(f\"P6\n{w} {h}\n255\n\".encode())
    f.write(b\"\x00\xff\x80\" * (w*h))
'" 15 >/dev/null || return 1
    f0="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    s0="$(_tier_json_get "$f0" "sha256")"
    e2e_launch_gui "mousepad" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    _tier_wait_xwindow "mousepad" 8 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    sleep 0.8
    f1="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
    s1="$(_tier_json_get "$f1" "sha256")"
    e2e_launch_gui "feh /tmp/t3_feh.ppm" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; return 1; }
    _tier_wait_xwindow "feh" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    sleep 0.8
    f2="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
    s2="$(_tier_json_get "$f2" "sha256")"
    _tier_cleanup_gui_apps
    if [[ "$s0" == "$s1" || "$s1" == "$s2" ]]; then
        LAST_DETAIL="Expected successive RFB sha256 deltas ($s0 -> $s1 -> $s2)"
        return 1
    fi
    LAST_DETAIL="Verified sequential RFB deltas across mousepad + feh"
}

test_t3_13() {
    _tier_require_device || return 1
    local st="" sys_resp="" sys_json="" mem_txt="" pss_kb=""
    st="$(e2e_broadcast_status 15 2>/dev/null)" || return 1
    sys_resp="$(_tier_exec_ok "/usr/local/bin/sysinfo.sh" 15)" || return 1
    sys_json="$(_tier_json_get "$sys_resp" "stdout")"
    e2e_assert_json "$sys_json" "int(d.get('process_count',0)) > 0 and int(d.get('mem_total_kb',0)) > 0" || return 1
    mem_txt="$(_e2e_adb shell dumpsys meminfo "$PKG_ID" 2>/dev/null | tr -d '\r' || true)"
    pss_kb="$(MEM_TXT="$mem_txt" python3 - <<'PYEOF'
import os, re, sys
t = os.environ.get("MEM_TXT", "")
for pat in (r"TOTAL\s+PSS:\s*(\d+)", r"^\s*TOTAL:\s*(\d+)", r"^\s*TOTAL\s+(\d+)"):
    m = re.search(pat, t, re.I | re.M)
    if m:
        print(m.group(1)); sys.exit(0)
print("")
PYEOF
)"
    [[ -n "$pss_kb" && "$pss_kb" -gt 0 ]] || { LAST_DETAIL="Could not parse PSS from dumpsys meminfo"; return 1; }
    _tier_check_proc_budget || return 1
    LAST_DETAIL="Cross-checked sysinfo.sh, ACTION_GET_STATUS, and dumpsys meminfo (PSS=${pss_kb}KB)"
}

test_t3_14() {
    _tier_require_device || return 1
    _e2e_adb shell pm clear "$PKG_ID" >/dev/null 2>&1 || { LAST_DETAIL="pm clear failed"; return 1; }
    if [[ "$API_LEVEL" -ge 33 ]]; then
        _e2e_adb shell pm grant "$PKG_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    fi
    sleep 1
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
    _tier_ensure_ready 120 >/dev/null || return 1
    local resp="" fr=""
    resp="$(_tier_exec_ok "uname -s && cat /etc/alpine-release" 20)" || return 1
    e2e_assert_contains "$(_tier_json_get "$resp" "stdout")" "3.20." "cold alpine-release" || return 1
    sleep 1
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True and int(d.get('nonUniformPixels',0)) > 0" || return 1
    LAST_DETAIL="Verified full pm clear cold provisioning -> Ready -> exec & RFB"
}

_tier_run_test "T3-01" 3 "F1+F2" test_t3_01
_tier_run_test "T3-02" 3 "F3+F4" test_t3_02
_tier_run_test "T3-03" 3 "F4+F5" test_t3_03
_tier_run_test "T3-04" 3 "F5+F6" test_t3_04
_tier_run_test "T3-05" 3 "F5+F7" test_t3_05
_tier_run_test "T3-06" 3 "F7+F8" test_t3_06
_tier_run_test "T3-07" 3 "F8+F9" test_t3_07
_tier_run_test "T3-08" 3 "F9+F11" test_t3_08
_tier_run_test "T3-09" 3 "F10+F6" test_t3_09
_tier_run_test "T3-10" 3 "F10+F11" test_t3_10
_tier_run_test "T3-11" 3 "F10+F12" test_t3_11
_tier_run_test "T3-12" 3 "F11+F8" test_t3_12
_tier_run_test "T3-13" 3 "F7+F12" test_t3_13
_tier_run_test "T3-14" 3 "F4+F7" test_t3_14

echo "[tier3] Completed 14 tests with ${TIER_FAILS} failure(s)" >&2
[[ $TIER_FAILS -eq 0 ]]
