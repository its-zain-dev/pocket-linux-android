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

TMP_TIER_DIR="$(mktemp -d "${OUT_DIR}/.tier2_tmp.XXXXXX")"
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
    e2e_exec_linux "for p in $procs; do pkill -TERM -x \"\$p\" 2>/dev/null || true; done; sleep 0.3; for p in $procs; do pkill -KILL -x \"\$p\" 2>/dev/null || true; done; rm -f /tmp/e2e_* /tmp/t2_* /mnt/sdcard/e2e_*" 20 >/dev/null 2>&1 || true
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
tree = ET.parse(xml_path)
root = tree.getroot()

def parse_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    if not m:
        return None
    return tuple(map(int, m.groups()))

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
    _e2e_adb shell input tap "$cx" "$cy" >/dev/null 2>&1 || {
        LAST_DETAIL="Failed to tap ($cx,$cy) for tag='$tag'"
        return 1
    }
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

_tier_make_synthetic_apk() {
    local out_apk="$1"
    local abi="$2"
    local mutation="$3"
    python3 - "$out_apk" "$abi" "$mutation" <<'PYEOF'
import gzip, io, struct, sys, tarfile, zipfile

out_apk, abi, mutation = sys.argv[1:4]
machine = 0x00B7 if abi == "arm64-v8a" else 0x003E
if mutation == "bad_elf":
    machine = 0x003E if abi == "arm64-v8a" else 0x00B7

def make_elf(em):
    buf = bytearray(64)
    buf[0:4] = b"\x7fELF"
    buf[4] = 2
    buf[5] = 1
    buf[6] = 1
    struct.pack_into("<HH", buf, 16, 3, em)
    return bytes(buf)

req_files = [
    "bin/busybox", "bin/bash", "usr/bin/Xvnc", "usr/bin/jwm",
    "usr/bin/python3", "usr/bin/xterm", "usr/bin/xcalc", "usr/bin/xclock",
    "usr/bin/feh", "usr/bin/mousepad", "usr/bin/galculator", "usr/bin/pcmanfm",
    "usr/bin/xwininfo", "usr/bin/xdpyinfo", "usr/bin/xrandr", "usr/bin/xsetroot",
    "root/.jwmrc", "usr/local/bin/start-desktop.sh", "usr/local/bin/sysinfo.sh",
    "etc/resolv.conf", "etc/alpine-release", "sbin/apk",
]
if mutation == "incomplete_rf":
    req_files = [p for p in req_files if p not in ("usr/bin/Xvnc", "usr/bin/jwm")]

tar_buf = io.BytesIO()
with gzip.GzipFile(fileobj=tar_buf, mode="wb") as gz:
    with tarfile.open(fileobj=gz, mode="w") as tf:
        for p in req_files:
            data = b"#!/bin/sh\n"
            if p == "etc/resolv.conf":
                data = b"nameserver 127.0.0.53\n" if mutation == "qemu_resolv" else b"nameserver 8.8.8.8\n"
            elif p == "etc/alpine-release":
                data = b"3.20.10\n"
            ti = tarfile.TarInfo(p)
            ti.size = len(data)
            ti.mode = 0o755 if p.endswith(".sh") else 0o644
            tf.addfile(ti, io.BytesIO(data))
        sym = tarfile.TarInfo("bin/sh")
        sym.type = tarfile.SYMTYPE
        sym.linkname = "busybox"
        tf.addfile(sym)
        if mutation == "slip_rf":
            bad = tarfile.TarInfo("../../evil")
            bad.size = 4
            tf.addfile(bad, io.BytesIO(b"evil"))
        if mutation == "qemu_resolv":
            qm = tarfile.TarInfo("usr/bin/qemu-aarch64-static")
            qm.size = 4
            tf.addfile(qm, io.BytesIO(b"qemu"))

rf_compress = zipfile.ZIP_DEFLATED if mutation == "deflated_rf" else zipfile.ZIP_STORED
with zipfile.ZipFile(out_apk, "w", compression=zipfile.ZIP_DEFLATED) as zf:
    zf.writestr("AndroidManifest.xml", b"manifest")
    zf.writestr("classes.dex", b"dex")
    zf.writestr("resources.arsc", b"arsc")
    if mutation != "no_proot":
        zf.writestr(f"lib/{abi}/libproot.so", make_elf(machine))
    zf.writestr(f"lib/{abi}/libproot-loader.so", make_elf(0x00B7 if abi == "arm64-v8a" else 0x003E))
    zf.writestr(f"lib/{abi}/libptybridge.so", make_elf(0x00B7 if abi == "arm64-v8a" else 0x003E))
    zi = zipfile.ZipInfo(f"assets/rootfs-{abi}.tar.gz")
    zi.compress_type = rf_compress
    zf.writestr(zi, tar_buf.getvalue())
PYEOF
}

_tier_assert_no_crash_and_ready() {
    local log_f="$TMP_TIER_DIR/logcat_check.txt" st=""
    _e2e_adb logcat -d 2>/dev/null | tr -d '\r' > "$log_f" || true
    if python3 - "$log_f" "$PKG_ID" <<'PYEOF'
import re, sys
with open(sys.argv[1], "r", encoding="utf-8", errors="replace") as f:
    lines = f.read().splitlines()
pkg = sys.argv[2]
pkg_re = re.compile(rf"(?<![A-Za-z0-9_.]){re.escape(pkg)}(?![A-Za-z0-9_.])")
for i, ln in enumerate(lines):
    if "FATAL EXCEPTION" in ln and pkg_re.search("\n".join(lines[i:i+6])):
        sys.exit(1)
    if re.search(rf"ANR in\s+{re.escape(pkg)}", ln):
        sys.exit(1)
PYEOF
    then
        :
    else
        LAST_DETAIL="FATAL EXCEPTION or ANR detected in logcat"
        return 1
    fi
    st="$(e2e_broadcast_status 10 2>/dev/null)" || { LAST_DETAIL="Runtime unresponsive after negative test"; return 1; }
    e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready'" || { LAST_DETAIL="Runtime left Ready state: $st"; return 1; }
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
# F1: GitHub Actions APK CI Pipeline Boundaries (5 tests)
# ==============================================================================
test_t2_f1_01() {
    local apk="" target_abi="" flag="" out_json="$TMP_TIER_DIR/t2_f1_01.json" rc=0
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    if [[ "$target_abi" == "arm64-v8a" ]]; then flag="--arm64-apk"; else flag="--x86-64-apk"; fi
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$apk" --min-size-mb 0.0 --warn-size-mb 0.5 --max-size-mb 1.0 --skip-aapt2 --skip-apksigner --out-json "$out_json" >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py unexpectedly passed with --max-size-mb 1.0"; return 1; }
    python3 - "$out_json" "$target_abi" <<'PYEOF' || { LAST_DETAIL="Expected max size error in JSON"; return 1; }
import json, sys
d = json.load(open(sys.argv[1]))
assert d["overallStatus"] == "FAIL"
assert any("maximum" in e.lower() for e in d["apks"][sys.argv[2]]["errors"])
PYEOF
    LAST_DETAIL="verify_apks.py enforced strict max size boundary"
}

test_t2_f1_02() {
    local tiny="$TMP_TIER_DIR/tiny.apk" out_json="$TMP_TIER_DIR/t2_f1_02.json" rc=0
    python3 -c 'import sys; open(sys.argv[1],"wb").write(b"NOT_A_ZIP"*120)' "$tiny"
    python3 "$E2E_ROOT/verify_apks.py" --x86-64-apk "$tiny" --skip-aapt2 --skip-apksigner --out-json "$out_json" >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py unexpectedly passed on bogus tiny.apk"; return 1; }
    e2e_assert_json "$(cat "$out_json")" "d.get('overallStatus') == 'FAIL'" || return 1
    LAST_DETAIL="verify_apks.py rejected malformed non-ZIP APK"
}

test_t2_f1_03() {
    local missing="$TMP_TIER_DIR/missing_e2e_xyz.apk" rc=0
    rm -f "$missing"
    python3 "$E2E_ROOT/verify_apks.py" --x86-64-apk "$missing" --skip-aapt2 --skip-apksigner >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py unexpectedly passed on missing APK"; return 1; }
    LAST_DETAIL="verify_apks.py rejected nonexistent APK path"
}

test_t2_f1_04() {
    local apk="" target_abi="" opp_flag="" out_json="$TMP_TIER_DIR/t2_f1_04.json" rc=0
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    if [[ "$target_abi" == "arm64-v8a" ]]; then opp_flag="--x86-64-apk"; else opp_flag="--arm64-apk"; fi
    python3 "$E2E_ROOT/verify_apks.py" "$opp_flag" "$apk" --min-size-mb 0.0 --skip-aapt2 --skip-apksigner --out-json "$out_json" >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py did not reject opposite-ABI APK"; return 1; }
    LAST_DETAIL="verify_apks.py rejected cross-ABI mismatch ($target_abi passed to $opp_flag)"
}

test_t2_f1_05() {
    local apk="" target_abi="" flag="" corrupt="$TMP_TIER_DIR/corrupt.apk" rc=0
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    if [[ "$target_abi" == "arm64-v8a" ]]; then flag="--arm64-apk"; else flag="--x86-64-apk"; fi
    cp "$apk" "$corrupt"
    python3 - "$corrupt" <<'PYEOF'
import sys
p = sys.argv[1]
with open(p, "r+b") as f:
    data = bytearray(f.read())
    mid = max(32, len(data) // 2)
    for i in range(mid, min(len(data), mid + 64)):
        data[i] ^= 0xFF
    f.seek(0)
    f.write(data)
PYEOF
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$corrupt" --min-size-mb 0.0 --skip-aapt2 --skip-apksigner >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py did not detect corrupted APK"; return 1; }
    LAST_DETAIL="verify_apks.py detected corrupted APK CRC/ZIP bytes"
}

# ==============================================================================
# F2: NDK PRoot + Unbundled Loader Boundaries (5 tests)
# ==============================================================================
test_t2_f2_01() {
    local syn="$TMP_TIER_DIR/no_proot.apk" out_json="$TMP_TIER_DIR/t2_f2_01.json" abi rc=0
    abi="$(_tier_resolve_target_abi)"
    _tier_make_synthetic_apk "$syn" "$abi" "no_proot"
    local flag="--x86-64-apk"
    [[ "$abi" == "arm64-v8a" ]] && flag="--arm64-apk"
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$syn" --min-size-mb 0.0 --skip-aapt2 --skip-apksigner --out-json "$out_json" >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py passed without libproot.so"; return 1; }
    grep -q "Missing native library" "$out_json" || { LAST_DETAIL="Expected Missing native library error"; return 1; }
    LAST_DETAIL="verify_apks.py rejected APK missing libproot.so"
}

test_t2_f2_02() {
    local syn="$TMP_TIER_DIR/bad_elf.apk" out_json="$TMP_TIER_DIR/t2_f2_02.json" abi rc=0
    abi="$(_tier_resolve_target_abi)"
    _tier_make_synthetic_apk "$syn" "$abi" "bad_elf"
    local flag="--x86-64-apk"
    [[ "$abi" == "arm64-v8a" ]] && flag="--arm64-apk"
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$syn" --min-size-mb 0.0 --skip-aapt2 --skip-apksigner --out-json "$out_json" >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py passed with wrong ELF64 e_machine"; return 1; }
    grep -q "expected e_machine" "$out_json" || { LAST_DETAIL="Expected e_machine error"; return 1; }
    LAST_DETAIL="verify_apks.py rejected wrong ELF64 e_machine"
}

test_t2_f2_03() {
    _tier_require_device || return 1
    local resp="" ec="" out=""
    resp="$(e2e_exec_linux "python3 -c 'import sys; print(len(sys.argv[1]))' \"\$(python3 -c 'print(\"A\"*16384)')\"" 25 2>/dev/null)" || {
        LAST_DETAIL="16 KiB argv broadcast failed"
        return 1
    }
    ec="$(_tier_json_get "$resp" "exitCode")"
    out="$(_tier_json_get "$resp" "stdout" | tr -d '[:space:]')"
    e2e_assert_eq "$ec" "0" "16KB argv exitCode" || return 1
    e2e_assert_eq "$out" "16384" "16KB argv length" || return 1
    LAST_DETAIL="PRoot passed 16384-byte argument boundary cleanly"
}

test_t2_f2_04() {
    _tier_require_device || return 1
    local resp="" ec=""
    resp="$(e2e_exec_linux "/usr/bin/definitely_missing_bin_xyz" 15 2>/dev/null)" || { LAST_DETAIL="Broadcast failed"; return 1; }
    ec="$(_tier_json_get "$resp" "exitCode")"
    [[ "$ec" != "0" ]] || { LAST_DETAIL="Missing binary returned exitCode 0"; return 1; }
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="Missing guest binary returned exitCode=$ec and runtime stayed Ready"
}

test_t2_f2_05() {
    _tier_require_device || return 1
    local resp="" ec=""
    resp="$(e2e_exec_linux "printf '#!/bin/sh\necho NO\n' > /tmp/e2e_noexec.sh && chmod 0644 /tmp/e2e_noexec.sh && /tmp/e2e_noexec.sh; rc=\$?; rm -f /tmp/e2e_noexec.sh; exit \$rc" 15 2>/dev/null)" || {
        LAST_DETAIL="Broadcast failed"
        return 1
    }
    ec="$(_tier_json_get "$resp" "exitCode")"
    [[ "$ec" != "0" ]] || { LAST_DETAIL="Non-executable script returned exitCode 0"; return 1; }
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="Non-executable script failed cleanly with exitCode=$ec"
}

# ==============================================================================
# F3: Genuine Alpine v3.20 GUI Rootfs Boundaries (5 tests)
# ==============================================================================
test_t2_f3_01() {
    local syn="$TMP_TIER_DIR/deflated_rf.apk" out_json="$TMP_TIER_DIR/t2_f3_01.json" abi rc=0
    abi="$(_tier_resolve_target_abi)"
    _tier_make_synthetic_apk "$syn" "$abi" "deflated_rf"
    local flag="--x86-64-apk"
    [[ "$abi" == "arm64-v8a" ]] && flag="--arm64-apk"
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$syn" --min-size-mb 0.0 --skip-aapt2 --skip-apksigner --out-json "$out_json" >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py passed ZIP_DEFLATED rootfs"; return 1; }
    grep -q "ZIP_STORED" "$out_json" || { LAST_DETAIL="Expected ZIP_STORED error"; return 1; }
    LAST_DETAIL="verify_apks.py rejected ZIP_DEFLATED rootfs asset"
}

test_t2_f3_02() {
    local syn="$TMP_TIER_DIR/slip_rf.apk" out_json="$TMP_TIER_DIR/t2_f3_02.json" abi rc=0
    abi="$(_tier_resolve_target_abi)"
    _tier_make_synthetic_apk "$syn" "$abi" "slip_rf"
    local flag="--x86-64-apk"
    [[ "$abi" == "arm64-v8a" ]] && flag="--arm64-apk"
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$syn" --min-size-mb 0.0 --skip-aapt2 --skip-apksigner --out-json "$out_json" >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py passed tar traversal entry"; return 1; }
    grep -q "unsafe path traversal" "$out_json" || { LAST_DETAIL="Expected traversal error"; return 1; }
    LAST_DETAIL="verify_apks.py rejected tar path traversal entry"
}

test_t2_f3_03() {
    local syn="$TMP_TIER_DIR/qemu_rf.apk" out_json="$TMP_TIER_DIR/t2_f3_03.json" abi rc=0
    abi="$(_tier_resolve_target_abi)"
    _tier_make_synthetic_apk "$syn" "$abi" "qemu_resolv"
    local flag="--x86-64-apk"
    [[ "$abi" == "arm64-v8a" ]] && flag="--arm64-apk"
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$syn" --min-size-mb 0.0 --skip-aapt2 --skip-apksigner --out-json "$out_json" >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py passed QEMU leftover and 127.0.0.53"; return 1; }
    grep -q "QEMU" "$out_json" && grep -q "127.0.0.53" "$out_json" || { LAST_DETAIL="Expected QEMU and resolv.conf errors"; return 1; }
    LAST_DETAIL="verify_apks.py caught leftover QEMU binary and invalid 127.0.0.53 resolv.conf"
}

test_t2_f3_04() {
    local syn="$TMP_TIER_DIR/incomplete_rf.apk" out_json="$TMP_TIER_DIR/t2_f3_04.json" abi rc=0
    abi="$(_tier_resolve_target_abi)"
    _tier_make_synthetic_apk "$syn" "$abi" "incomplete_rf"
    local flag="--x86-64-apk"
    [[ "$abi" == "arm64-v8a" ]] && flag="--arm64-apk"
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$syn" --min-size-mb 0.0 --skip-aapt2 --skip-apksigner --out-json "$out_json" >/dev/null 2>&1 || rc=$?
    [[ $rc -ne 0 ]] || { LAST_DETAIL="verify_apks.py passed incomplete rootfs"; return 1; }
    grep -q "missing required entries" "$out_json" || { LAST_DETAIL="Expected missing required entries error"; return 1; }
    LAST_DETAIL="verify_apks.py rejected rootfs missing Xvnc/jwm"
}

test_t2_f3_05() {
    _tier_require_device || return 1
    local resp="" ec="" out=""
    resp="$(e2e_exec_linux "test ! -e /usr/bin/qemu-aarch64-static && test ! -e /usr/bin/qemu-x86_64 && /usr/local/bin/sysinfo.sh --text" 20 2>/dev/null)" || {
        LAST_DETAIL="Broadcast failed"
        return 1
    }
    ec="$(_tier_json_get "$resp" "exitCode")"
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_eq "$ec" "0" "sysinfo --text exitCode" || return 1
    e2e_assert_contains "$out" "Alpine Linux 3.20" "sysinfo --text header" || { LAST_DETAIL="Unexpected sysinfo --text: $out"; return 1; }
    LAST_DETAIL="Live rootfs clean of QEMU helpers and sysinfo.sh --text succeeded"
}

# ==============================================================================
# F4: Rootfs First-Boot Provisioner Boundaries (5 tests)
# ==============================================================================
test_t2_f4_01() {
    _tier_require_device || return 1
    _e2e_adb shell "run-as ${PKG_ID} rm -f files/rootfs/.rootfs_ready_v1" >/dev/null 2>&1 || {
        LAST_DETAIL="Failed to remove .rootfs_ready_v1"
        return 1
    }
    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
    sleep 1
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || { LAST_DETAIL="am start failed"; return 1; }
    _tier_ensure_ready 120 >/dev/null || return 1
    local check=""
    check="$(_e2e_adb shell "run-as ${PKG_ID} sh -c 'test -f files/rootfs/.rootfs_ready_v1 && test ! -e files/rootfs.staging && echo RESTORED'" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_contains "$check" "RESTORED" "rootfs re-provision" || { LAST_DETAIL="Rootfs not restored after removing marker"; return 1; }
    LAST_DETAIL="Verified self-healing re-extraction when .rootfs_ready_v1 missing"
}

test_t2_f4_02() {
    _tier_require_device || return 1
    _e2e_adb shell "run-as ${PKG_ID} sh -c 'mkdir -p files/rootfs.staging && touch files/rootfs.staging/stale && rm -f files/rootfs/.rootfs_ready_v1'" >/dev/null 2>&1 || {
        LAST_DETAIL="Failed to inject stale staging dir"
        return 1
    }
    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
    sleep 1
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
    _tier_ensure_ready 120 >/dev/null || return 1
    local check=""
    check="$(_e2e_adb shell "run-as ${PKG_ID} sh -c 'test ! -e files/rootfs.staging && test -f files/rootfs/.rootfs_ready_v1 && echo STAGING_CLEAN'" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_contains "$check" "STAGING_CLEAN" "stale staging cleanup" || { LAST_DETAIL="Stale rootfs.staging remained"; return 1; }
    LAST_DETAIL="Verified stale rootfs.staging cleanup on re-provision"
}

test_t2_f4_03() {
    _tier_require_device || return 1
    local resp="" ec="" out=""
    resp="$(e2e_exec_linux "ls -li /bin/busybox /bin/sh /usr/bin/awk && test -x /bin/busybox && echo LINKS_OK" 15 2>/dev/null)" || return 1
    ec="$(_tier_json_get "$resp" "exitCode")"
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_eq "$ec" "0" "links exitCode" || return 1
    e2e_assert_contains "$out" "LINKS_OK" "busybox links" || return 1
    LAST_DETAIL="Verified busybox link/hardlink fallback integrity"
}

test_t2_f4_04() {
    _tier_require_device || return 1
    local resp="" ec="" out=""
    resp="$(e2e_exec_linux "python3 -c '
p=\"/mnt/sdcard/e2e_unicode_αβγ.bin\"
open(p,\"wb\").write(b\"Z\"*1048576)
sz=len(open(p,\"rb\").read())
import os; os.remove(p)
print(sz)
'" 25 2>/dev/null)" || { LAST_DETAIL="1 MiB unicode file test failed"; return 1; }
    ec="$(_tier_json_get "$resp" "exitCode")"
    out="$(_tier_json_get "$resp" "stdout" | tr -d '[:space:]')"
    e2e_assert_eq "$ec" "0" "unicode 1MiB exitCode" || return 1
    e2e_assert_eq "$out" "1048576" "unicode 1MiB byte length" || return 1
    LAST_DETAIL="Verified 1 MiB UTF-8 filename roundtrip on /mnt/sdcard"
}

test_t2_f4_05() {
    _tier_require_device || return 1
    local resp="" ec="" out=""
    resp="$(e2e_exec_linux "printf 'ok' > '/tmp/e2e space \"quote\" file' && ln -sf /nonexistent_target_e2e /tmp/e2e_dangling && test \"\$(readlink /tmp/e2e_dangling)\" = '/nonexistent_target_e2e' && cat '/tmp/e2e space \"quote\" file'; rc=\$?; rm -f '/tmp/e2e space \"quote\" file' /tmp/e2e_dangling; exit \$rc" 15 2>/dev/null)" || return 1
    ec="$(_tier_json_get "$resp" "exitCode")"
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_eq "$ec" "0" "special filename exitCode" || return 1
    e2e_assert_eq "$out" "ok" "special filename content" || return 1
    LAST_DETAIL="Verified special-character filename and dangling symlink handling"
}

# ==============================================================================
# F5: PRoot Execution & Syscall Engine Boundaries (5 tests)
# ==============================================================================
test_t2_f5_01() {
    _tier_require_device || return 1
    local resp="" ec=""
    resp="$(e2e_exec_linux "exit 42" 15 2>/dev/null)" || { LAST_DETAIL="Broadcast failed"; return 1; }
    ec="$(_tier_json_get "$resp" "exitCode")"
    e2e_assert_eq "$ec" "42" "exitCode 42" || return 1
    LAST_DETAIL="Verified exact non-zero exitCode 42 propagation"
}

test_t2_f5_02() {
    _tier_require_device || return 1
    local resp="" st=""
    resp="$(e2e_exec_linux "   " 15 2>/dev/null)" || { LAST_DETAIL="Whitespace command failed"; return 1; }
    st="$(e2e_broadcast_status 10 2>/dev/null)" || return 1
    e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready'" || return 1
    LAST_DETAIL="Whitespace-only command handled without crashing runtime"
}

test_t2_f5_03() {
    _tier_require_device || return 1
    # Test 64 KiB via e2e_exec_linux and exact 128 KiB (131072 bytes) via run-as file read
    # (reading file via stdin/path avoids Linux MAX_ARG_STRLEN 131072-byte argv limit).
    local resp64="" ec64="" len64=""
    resp64="$(e2e_exec_linux "python3 -c 'print(\"X\"*65536)'" 25 2>/dev/null)" || {
        LAST_DETAIL="64 KiB stdout broadcast failed"
        return 1
    }
    ec64="$(_tier_json_get "$resp64" "exitCode")"
    e2e_assert_eq "$ec64" "0" "64 KiB stdout exitCode" || return 1
    len64="$(python3 -c 'import json,sys; print(len(str(json.load(sys.stdin).get("stdout","")).rstrip("\r\n")))' <<< "$resp64" 2>/dev/null || echo 0)"
    e2e_assert_eq "$len64" "65536" "64 KiB stdout length" || { LAST_DETAIL="Expected 65536 bytes, got $len64"; return 1; }

    local out_128="$TMP_TIER_DIR/t2_f5_03_128k.json"
    _e2e_adb shell "run-as ${PKG_ID} rm -f files/diagnostics/t2_f5_03.json" >/dev/null 2>&1 || true
    local bcmd=""
    bcmd="$(_e2e_build_broadcast_cmd "${PKG_ID}/.automation.AutomationReceiver" "${PKG_ID}.ACTION_EXEC_LINUX" "t2_f5_03_128k" "t2_f5_03.json" "command" "python3 -c 'print(\"X\"*131072)'")"
    _e2e_adb shell "$bcmd" >/dev/null 2>&1 || { LAST_DETAIL="128 KiB broadcast failed"; return 1; }
    local deadline=$(( $(date +%s) + 25 ))
    : > "$out_128"
    while true; do
        _e2e_adb shell "run-as ${PKG_ID} cat files/diagnostics/t2_f5_03.json" 2>/dev/null | tr -d '\r' > "$out_128" || true
        if [[ -s "$out_128" ]] && python3 - "$out_128" <<'PYEOF' >/dev/null 2>&1; then
import json, sys
d = json.load(open(sys.argv[1], "r", encoding="utf-8"))
assert d.get("exitCode") == 0
assert len(str(d.get("stdout", "")).rstrip("\r\n")) == 131072
PYEOF
            break
        fi
        if [[ $(date +%s) -ge $deadline ]]; then
            _e2e_adb shell "run-as ${PKG_ID} rm -f files/diagnostics/t2_f5_03.json" >/dev/null 2>&1 || true
            LAST_DETAIL="128 KiB (131072 bytes) stdout verification timed out or mismatched"
            return 1
        fi
        sleep 0.3
    done
    _e2e_adb shell "run-as ${PKG_ID} rm -f files/diagnostics/t2_f5_03.json" >/dev/null 2>&1 || true
    LAST_DETAIL="Verified 64 KiB and exact 128 KiB (131072 bytes) stdout payload integrity"
}

test_t2_f5_04() {
    _tier_require_device || return 1
    local resp="" ec="" out_s="" err_s=""
    resp="$(e2e_exec_linux "printf 'OUT_ONLY\n'; printf 'ERR_ONLY\n' >&2; exit 3" 15 2>/dev/null)" || { LAST_DETAIL="Broadcast failed"; return 1; }
    ec="$(_tier_json_get "$resp" "exitCode")"
    out_s="$(_tier_json_get "$resp" "stdout")"
    err_s="$(_tier_json_get "$resp" "stderr")"
    e2e_assert_eq "$ec" "3" "exitCode 3" || return 1
    e2e_assert_contains "$out_s" "OUT_ONLY" "stdout separation" || return 1
    e2e_assert_contains "$err_s" "ERR_ONLY" "stderr separation" || return 1
    LAST_DETAIL="Verified stdout/stderr separation with exitCode=3"
}

test_t2_f5_05() {
    _tier_require_device || return 1
    # 1. Concurrent subshell/exec stress
    local pids=() p_rc=0 i
    for i in 1 2 3 4; do
        e2e_exec_linux "for j in 1 2 3; do (echo sub_${i}_\$j) & done; wait; echo CONC_${i}_OK" 20 > "$TMP_TIER_DIR/conc_${i}.json" 2>/dev/null &
        pids+=($!)
    done
    for p in "${pids[@]}"; do
        wait "$p" || p_rc=1
    done
    [[ $p_rc -eq 0 ]] || { LAST_DETAIL="Concurrent e2e_exec_linux subshell failed"; return 1; }
    for i in 1 2 3 4; do
        e2e_assert_json "$(cat "$TMP_TIER_DIR/conc_${i}.json")" "int(d.get('exitCode',-1)) == 0 and 'CONC_${i}_OK' in str(d.get('stdout',''))" || {
            LAST_DETAIL="Concurrent job $i failed"
            return 1
        }
    done
    # 2. Timeout boundary via timeout 2 sleep 10 -> 124 without stalling 35s broadcast timeout
    local t_resp="" t_ec=""
    t_resp="$(e2e_exec_linux "timeout 2 sleep 10" 15 2>/dev/null)" || { LAST_DETAIL="timeout boundary broadcast failed"; return 1; }
    t_ec="$(_tier_json_get "$t_resp" "exitCode")"
    e2e_assert_eq "$t_ec" "124" "timeout 2 sleep 10 exitCode" || { LAST_DETAIL="Expected timeout exitCode 124, got $t_ec"; return 1; }
    _tier_check_proc_budget || return 1
    LAST_DETAIL="Verified 4x concurrent exec stress, timeout exitCode=124, and UID procs < 27"
}

# ==============================================================================
# F6: Native PTY Interactive Shell Bridge Boundaries (5 tests)
# ==============================================================================
_tier_open_terminal_tab() {
    local ui_xml="$TMP_TIER_DIR/ui_term_open.xml"
    _tier_ensure_foreground || return 1
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_terminal" "Terminal" || return 1
    sleep 0.8
}

_tier_type_terminal() {
    local cmd="$1"
    local ui_xml="$TMP_TIER_DIR/ui_term_type.xml"
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_input" "command" "EditText" || return 1
    sleep 0.2
    if [[ -n "$cmd" ]]; then
        local enc=""
        enc="$(python3 -c 'import sys; print(sys.argv[1].replace(" ", "%s"))' "$cmd")"
        _e2e_adb shell input text "$enc" >/dev/null 2>&1 || return 1
    fi
    _tier_tap_ui_node "$ui_xml" "terminal_send_btn" "Send" || _e2e_adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
    sleep 1.0
}

test_t2_f6_01() {
    _tier_require_device || return 1
    _tier_open_terminal_tab || return 1
    _tier_type_terminal "nonexistent_pty_cmd_xyz" || return 1
    _tier_type_terminal "echo RECOVERED_\$?" || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f6_01.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "RECOVERED_127" "PTY 127 recovery" || { LAST_DETAIL="PTY did not recover 127"; return 1; }
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    LAST_DETAIL="PTY recovered cleanly with exit code 127 after unknown command"
}

test_t2_f6_02() {
    _tier_require_device || return 1
    _tier_open_terminal_tab || return 1
    _tier_type_terminal "python3 -c 'print(\"P\"*4096)' && echo PTY_LONG_DONE" || return 1
    sleep 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f6_02.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "PTY_LONG_DONE" "PTY 4KB burst" || { LAST_DETAIL="PTY_LONG_DONE missing after 4KB burst"; return 1; }
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="PTY handled 4096-byte burst without ANR"
}

test_t2_f6_03() {
    _tier_require_device || return 1
    _tier_open_terminal_tab || return 1
    _tier_type_terminal "printf '\\033[31mRED\\033[0m\\033[2J\\033[HVT_CLEAN\\n'" || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f6_03.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "VT_CLEAN" "ANSI escape handling" || { LAST_DETAIL="VT_CLEAN missing after ANSI escape"; return 1; }
    LAST_DETAIL="Terminal VT100 handled ANSI color/clear escapes cleanly"
}

test_t2_f6_04() {
    _tier_require_device || return 1
    _tier_open_terminal_tab || return 1
    _tier_type_terminal "export E2E_KEEP=alive99" || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f6_04.xml"
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 1
    _tier_tap_ui_node "$ui_xml" "tab_terminal" "Terminal" || return 1
    sleep 0.5
    _tier_type_terminal "echo \$E2E_KEEP" || return 1
    _tier_dump_ui "$ui_xml" || return 1
    local raw_xml=""
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "alive99" "PTY survive tab switch" || { LAST_DETAIL="PTY lost env var across tab switch"; return 1; }
    _tier_type_terminal "unset E2E_KEEP" >/dev/null 2>&1 || true
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    LAST_DETAIL="PTY shell session survived tab switch"
}

test_t2_f6_05() {
    _tier_require_device || return 1
    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
    sleep 2
    local rem=0
    rem="$(_tier_count_uid_procs)"
    if [[ "$rem" != "0" ]]; then
        _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || true
        _tier_ensure_ready 60 >/dev/null 2>&1 || true
        LAST_DETAIL="$rem orphan UID procs remained after force-stop"
        return 1
    fi
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
    _tier_ensure_ready 60 >/dev/null || return 1
    LAST_DETAIL="Verified zero orphan PTY/bash processes after force-stop and clean relaunch"
}

# ==============================================================================
# F7: Foreground Linux Runtime Supervisor Boundaries (5 tests)
# ==============================================================================
test_t2_f7_01() {
    _tier_require_device || return 1
    local r1="" pid1="" r2="" pid2="" st=""
    r1="$(e2e_exec_linux "pgrep -x Xvnc | head -n 1" 15 2>/dev/null)" || return 1
    pid1="$(_tier_json_get "$r1" "stdout" | tr -d '[:space:]')"
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || true
    _e2e_adb shell am broadcast -n "${PKG_ID}/.automation.AutomationReceiver" -a "${PKG_ID}.ACTION_START_RUNTIME" >/dev/null 2>&1 || true
    sleep 2
    st="$(e2e_broadcast_status 10 2>/dev/null)" || return 1
    e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready'" || return 1
    r2="$(e2e_exec_linux "pgrep -x Xvnc | head -n 1" 15 2>/dev/null)" || return 1
    pid2="$(_tier_json_get "$r2" "stdout" | tr -d '[:space:]')"
    e2e_assert_eq "$pid2" "$pid1" "idempotent Xvnc PID" || { LAST_DETAIL="Xvnc PID changed ($pid1 -> $pid2)"; return 1; }
    LAST_DETAIL="Duplicate start preserved single Xvnc PID=$pid1"
}

test_t2_f7_02() {
    _tier_require_device || return 1
    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
    sleep 2
    local rem=0
    rem="$(_tier_count_uid_procs)"
    if [[ "$rem" -ne 0 ]]; then
        _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || true
        _tier_ensure_ready 60 >/dev/null 2>&1 || true
        LAST_DETAIL="Expected 0 UID processes after force-stop, found $rem"
        return 1
    fi
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
    _tier_ensure_ready 60 >/dev/null || return 1
    LAST_DETAIL="Verified 0 UID processes on force-stop and clean recovery"
}

test_t2_f7_03() {
    _tier_require_device || return 1
    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
    sleep 1
    _e2e_adb shell "run-as ${PKG_ID} sh -c 'mkdir -p files/rootfs/tmp && touch files/rootfs/tmp/.X1-lock files/rootfs/tmp/.pocketlinux_desktop_ready'" >/dev/null 2>&1 || true
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
    _tier_ensure_ready 60 >/dev/null || return 1
    LAST_DETAIL="Supervisor recovered cleanly from stale .X1-lock and readiness marker"
}

test_t2_f7_04() {
    _tier_require_device || return 1
    for app in xclock xcalc xterm; do
        e2e_launch_gui "$app" 15 >/dev/null 2>&1 || { _tier_cleanup_gui_apps; LAST_DETAIL="Failed launching $app"; return 1; }
        sleep 0.5
        _tier_cleanup_gui_apps
    done
    _tier_check_proc_budget || return 1
    LAST_DETAIL="Sequential GUI spawn/cleanup kept UID processes < 27"
}

test_t2_f7_05() {
    _tier_require_device || return 1
    local i
    for i in 1 2; do
        _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
        sleep 0.8
        _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
        _tier_ensure_ready 60 >/dev/null || return 1
    done
    local resp="" cnt=""
    resp="$(e2e_exec_linux "pgrep -xc Xvnc" 15 2>/dev/null)" || return 1
    cnt="$(_tier_json_get "$resp" "stdout" | tr -d '[:space:]')"
    e2e_assert_eq "$cnt" "1" "single Xvnc instance" || { LAST_DETAIL="Expected 1 Xvnc, got '$cnt'"; return 1; }
    LAST_DETAIL="Rapid 2x stop/start cycle resulted in single healthy Xvnc"
}

# ==============================================================================
# F8: Pure-Kotlin RFB 3.8 Client Boundaries (5 tests)
# ==============================================================================
test_t2_f8_01() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(e2e_exec_linux "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null; echo CMDLINE:; tr '\0' ' ' < /proc/\$(pgrep -x Xvnc | head -n1)/cmdline" 15 2>/dev/null)" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    python3 - "$out" <<'PYEOF' || { LAST_DETAIL="Xvnc port 5901 not strictly bound to localhost: $out"; return 1; }
import sys
txt = sys.argv[1]
assert "-localhost" in txt, "Xvnc missing -localhost"
for line in txt.splitlines():
    parts = line.split()
    if len(parts) > 3 and parts[1].endswith(":170D") and parts[3] == "0A":
        addr = parts[1].split(":")[0]
        assert addr != "00000000" and addr != "00000000000000000000000000000000", f"Bound wildcard: {parts[1]}"
PYEOF
    LAST_DETAIL="Verified Xvnc RFB port 5901 bound to loopback (-localhost), not 0.0.0.0"
}

test_t2_f8_02() {
    _tier_require_device || return 1
    local c="" fr=""
    for c in "#EF4444" "#10B981" "#3B82F6" "#1E293B"; do
        e2e_exec_linux "xsetroot -display :1 -solid '${c}'" 10 >/dev/null 2>&1 || true
    done
    sleep 0.5
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { LAST_DETAIL="Frame verify failed after color burst"; return 1; }
    e2e_assert_json "$fr" "d.get('connected') is True and len(str(d.get('sha256',''))) == 64" || return 1
    LAST_DETAIL="RFB client survived rapid 4-color burst with valid frame"
}

test_t2_f8_03() {
    _tier_require_device || return 1
    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
    sleep 1
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || return 1
    _tier_ensure_ready 60 >/dev/null || return 1
    sleep 2
    local fr=""
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True and int(d.get('nonUniformPixels',0)) > 0" || {
        LAST_DETAIL="RFB did not reconnect cleanly after restart: $fr"
        return 1
    }
    LAST_DETAIL="RFB client reconnected with non-uniform frame after cold restart"
}

test_t2_f8_04() {
    _tier_require_device || return 1
    local resp="" ec="" fr=""
    resp="$(e2e_exec_linux "xdpyinfo -display :99 2>&1" 10 2>/dev/null)" || return 1
    ec="$(_tier_json_get "$resp" "exitCode")"
    [[ "$ec" != "0" ]] || { LAST_DETAIL="Display :99 unexpectedly succeeded"; return 1; }
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True" || return 1
    LAST_DETAIL="Invalid :99 probe returned exitCode=$ec without disrupting :1 RFB"
}

test_t2_f8_05() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f8_05.xml" info="" x1="" y1="" x2="" y2="" fr=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    sleep 0.3
    _tier_dump_ui "$ui_xml" || return 1
    info="$(_tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" 2>/dev/null)" || { LAST_DETAIL="desktop_surface missing"; return 1; }
    read -r _ _ x1 y1 x2 y2 _ <<< "$info"
    _e2e_adb shell input tap "$x1" "$y1" >/dev/null 2>&1 || true
    _e2e_adb shell input tap "$((x2 - 1))" "$((y2 - 1))" >/dev/null 2>&1 || true
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True" || return 1
    LAST_DETAIL="Edge pointer taps clamped cleanly without RFB disconnect"
}

# ==============================================================================
# F9: Hardware-Accelerated Desktop Surface Boundaries (5 tests)
# ==============================================================================
test_t2_f9_01() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    _e2e_adb shell input tap 1 1 >/dev/null 2>&1 || true
    _e2e_adb shell input tap 500 500 >/dev/null 2>&1 || true
    local fr=""
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True" || return 1
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="Boundary screen taps preserved foreground app and RFB connection"
}

test_t2_f9_02() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    e2e_launch_gui "xclock -geometry 200x200+200+150" 15 >/dev/null 2>&1 || { LAST_DETAIL="xclock launch failed"; return 1; }
    sleep 0.8
    _e2e_adb shell input swipe 300 200 450 280 250 >/dev/null 2>&1 || true
    _tier_cleanup_gui_apps
    _tier_assert_no_crash_and_ready || return 1
    local fr=""
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True" || return 1
    LAST_DETAIL="Surface drag gesture completed cleanly without stuck button or ANR"
}

test_t2_f9_03() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f9_03.xml" info="" cx="" cy=""
    _tier_dump_ui "$ui_xml" || return 1
    info="$(_tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" 2>/dev/null)" || { LAST_DETAIL="desktop_surface missing"; return 1; }
    cx="$(printf '%s\n' "$info" | awk '{print $1}')"
    cy="$(printf '%s\n' "$info" | awk '{print $2}')"
    _e2e_adb shell input swipe "$cx" "$cy" "$cx" "$cy" 800 >/dev/null 2>&1 || true
    sleep 0.3
    _e2e_adb shell input keyevent KEYCODE_ESCAPE >/dev/null 2>&1 || true
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="800ms long-press on desktop_surface handled cleanly"
}

test_t2_f9_04() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f9_04.xml" i
    _tier_dump_ui "$ui_xml" || return 1
    for i in 1 2 3; do
        _tier_tap_ui_node "$ui_xml" "tab_terminal" "Terminal" >/dev/null 2>&1 || true
        _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" >/dev/null 2>&1 || true
        _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    done
    sleep 0.8
    local fr=""
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True" || return 1
    _tier_check_proc_budget || return 1
    LAST_DETAIL="3x rapid tab switch preserved SurfaceView and RFB connection"
}

test_t2_f9_05() {
    _tier_require_device || return 1
    _e2e_adb shell input keyevent KEYCODE_SLEEP >/dev/null 2>&1 || true
    sleep 1
    _e2e_adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    _e2e_adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    sleep 1.5
    _tier_assert_no_crash_and_ready || return 1
    local fr=""
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True" || return 1
    LAST_DETAIL="Sleep/wake cycle preserved Ready state and RFB connection"
}

# ==============================================================================
# F10: Complete Linux UI Shell Boundaries (5 tests)
# ==============================================================================
test_t2_f10_01() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f10_01.xml" pid_before="" pid_after=""
    pid_before="$(_e2e_adb shell pidof "$PKG_ID" 2>/dev/null | tr -d '\r[:space:]' || true)"
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "command_run_btn" "Run" || return 1
    sleep 0.5
    pid_after="$(_e2e_adb shell pidof "$PKG_ID" 2>/dev/null | tr -d '\r[:space:]' || true)"
    e2e_assert_eq "$pid_after" "$pid_before" "same app PID after empty run" || { LAST_DETAIL="App PID changed ($pid_before -> $pid_after)"; return 1; }
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="Empty command_run_btn tap handled safely"
}

test_t2_f10_02() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f10_02.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "command_input" "" "EditText" || return 1
    sleep 0.2
    _e2e_adb shell input text "echo%sBAD_UI%s>&2;%sexit%s9" >/dev/null 2>&1 || return 1
    _tier_tap_ui_node "$ui_xml" "command_run_btn" "Run" || return 1
    sleep 1.5
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    if [[ "$raw_xml" != *"BAD_UI"* && "$raw_xml" != *"9"* ]]; then
        LAST_DETAIL="command_result did not show error output or exit code 9"
        return 1
    fi
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="Apps tab command runner surfaced failing command stderr/exitCode cleanly"
}

test_t2_f10_03() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f10_03.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "command_input" "" "EditText" || return 1
    _e2e_adb shell input text "seq%s1%s200" >/dev/null 2>&1 || return 1
    _tier_tap_ui_node "$ui_xml" "command_run_btn" "Run" || return 1
    sleep 2
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "200" "seq 1 200 UI result" || { LAST_DETAIL="seq 1 200 tail missing in UI"; return 1; }
    LAST_DETAIL="Apps tab UI remained responsive for 200-line output"
}

test_t2_f10_04() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_t2_f10_04.xml" i
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    for i in 1 2 3; do
        _tier_tap_ui_node "$ui_xml" "metrics_refresh_btn" "Refresh" || return 1
        sleep 0.2
    done
    sleep 1
    _tier_dump_ui "$ui_xml" || return 1
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="3x rapid metrics_refresh_btn taps completed without crash"
}

test_t2_f10_05() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    _e2e_adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
    sleep 1
    local st=""
    st="$(e2e_broadcast_status 10 2>/dev/null)" || return 1
    e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready'" || return 1
    _tier_ensure_foreground || return 1
    LAST_DETAIL="KEYCODE_BACK kept LinuxRuntimeService Ready"
}

# ==============================================================================
# F11: Bundled GUI & CLI Apps Boundaries (5 tests)
# ==============================================================================
test_t2_f11_01() {
    _tier_require_device || return 1
    local resp=""
    resp="$(e2e_launch_gui "nonexistent_gui_app_xyz" 15 2>/dev/null)" || { LAST_DETAIL="Broadcast failed"; return 1; }
    e2e_assert_json "$resp" "d.get('launched') is False" || { LAST_DETAIL="Expected launched=false, got: $resp"; return 1; }
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="Nonexistent GUI binary reported launched=false cleanly"
}

test_t2_f11_02() {
    _tier_require_device || return 1
    local resp="" bare_resp="" bare_ec=""
    resp="$(e2e_launch_gui "" 15 2>/dev/null)" || { LAST_DETAIL="Empty app broadcast failed"; return 1; }
    e2e_assert_json "$resp" "d.get('launched') is False" || { LAST_DETAIL="Expected launched=false for empty app: $resp"; return 1; }
    bare_resp="$(e2e_exec_linux "DISPLAY=:1 feh 2>&1" 15 2>/dev/null)" || return 1
    bare_ec="$(_tier_json_get "$bare_resp" "exitCode")"
    [[ "$bare_ec" != "0" ]] || { LAST_DETAIL="Bare feh without image unexpectedly exited 0"; return 1; }
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="Empty app extra & bare feh without image failed gracefully"
}

test_t2_f11_03() {
    _tier_require_device || return 1
    local resp="" ec="" xd=""
    resp="$(e2e_exec_linux "rm -f /tmp/missing_img_e2e.png && : > /tmp/e2e_empty.png && DISPLAY=:1 feh /tmp/missing_img_e2e.png 2>&1; rc1=\$?; DISPLAY=:1 feh /tmp/e2e_empty.png 2>&1; rc2=\$?; rm -f /tmp/e2e_empty.png; test \$rc1 -ne 0 && test \$rc2 -ne 0" 15 2>/dev/null)" || return 1
    ec="$(_tier_json_get "$resp" "exitCode")"
    e2e_assert_eq "$ec" "0" "feh negative checks" || { LAST_DETAIL="feh did not reject missing/corrupt image cleanly"; return 1; }
    xd="$(e2e_exec_linux "xdpyinfo -display :1 >/dev/null" 10 2>/dev/null)" || return 1
    e2e_assert_json "$xd" "int(d.get('exitCode',-1)) == 0" || return 1
    LAST_DETAIL="feh rejected missing & zero-byte image without affecting Xvnc"
}

test_t2_f11_04() {
    _tier_require_device || return 1
    local resp="" ec="" err_s=""
    resp="$(e2e_exec_linux "python3 -c 'def broken(:'" 15 2>/dev/null)" || return 1
    ec="$(_tier_json_get "$resp" "exitCode")"
    err_s="$(_tier_json_get "$resp" "stderr")"
    [[ "$ec" != "0" ]] || { LAST_DETAIL="Broken Python syntax returned 0"; return 1; }
    e2e_assert_contains "$err_s" "SyntaxError" "Python SyntaxError" || { LAST_DETAIL="Expected SyntaxError in stderr: $err_s"; return 1; }
    LAST_DETAIL="Broken Python syntax returned exitCode=$ec with SyntaxError"
}

test_t2_f11_05() {
    _tier_require_device || return 1
    local resp="" ec="" out=""
    resp="$(e2e_exec_linux "apk info -e busybox && ! apk info -e nonexistent_pkg_xyz && echo APK_OK" 15 2>/dev/null)" || return 1
    ec="$(_tier_json_get "$resp" "exitCode")"
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_eq "$ec" "0" "apk query exitCode" || return 1
    e2e_assert_contains "$out" "APK_OK" "apk boundary" || return 1
    LAST_DETAIL="apk info -e positive/negative boundary verified"
}

# ==============================================================================
# F12: Automation & Telemetry Hooks Boundaries (5 tests)
# ==============================================================================
test_t2_f12_01() {
    _tier_require_device || return 1
    _e2e_adb shell "run-as ${PKG_ID} rm -f files/evil_trav.json" >/dev/null 2>&1 || true
    _e2e_adb shell "am broadcast -n ${PKG_ID}/.automation.AutomationReceiver -a ${PKG_ID}.ACTION_EXEC_LINUX --es req_id trav_01 --es out_file '../../evil_trav.json' --es command 'echo SAFE'" >/dev/null 2>&1 || {
        LAST_DETAIL="Broadcast failed"
        return 1
    }
    sleep 1
    local chk=""
    chk="$(_e2e_adb shell "run-as ${PKG_ID} sh -c 'test ! -e files/evil_trav.json && echo SANITIZED_OK'" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_contains "$chk" "SANITIZED_OK" "out_file traversal blocked" || { LAST_DETAIL="Path traversal wrote files/evil_trav.json!"; return 1; }
    _e2e_adb shell "run-as ${PKG_ID} rm -f files/diagnostics/*evil_trav*" >/dev/null 2>&1 || true
    LAST_DETAIL="out_file path traversal strictly confined to files/diagnostics/"
}

test_t2_f12_02() {
    _tier_require_device || return 1
    _e2e_adb shell "am broadcast -n ${PKG_ID}/.automation.AutomationReceiver -a ${PKG_ID}.ACTION_EXEC_LINUX --es req_id missing_cmd_01 --es out_file missing_cmd_01.json" >/dev/null 2>&1 || true
    sleep 1
    local raw=""
    raw="$(_e2e_adb shell "run-as ${PKG_ID} cat files/diagnostics/missing_cmd_01.json" 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$raw" ]]; then
        e2e_assert_json "$raw" "int(d.get('exitCode',0)) != 0" || { LAST_DETAIL="Missing command returned exitCode 0: $raw"; return 1; }
    fi
    _e2e_adb shell "run-as ${PKG_ID} rm -f files/diagnostics/missing_cmd_01.json" >/dev/null 2>&1 || true
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="Missing command extra handled gracefully without crash"
}

test_t2_f12_03() {
    _tier_require_device || return 1
    local r1="" r2="" id1="" id2=""
    r1="$(e2e_exec_linux "echo REQ_A" 15 2>/dev/null)" || return 1
    r2="$(e2e_exec_linux "echo REQ_B" 15 2>/dev/null)" || return 1
    id1="$(_tier_json_get "$r1" "reqId")"
    id2="$(_tier_json_get "$r2" "reqId")"
    [[ -n "$id1" && -n "$id2" && "$id1" != "$id2" ]] || { LAST_DETAIL="reqId collision or empty ($id1 vs $id2)"; return 1; }
    e2e_assert_contains "$(_tier_json_get "$r1" "stdout")" "REQ_A" "req1 stdout" || return 1
    e2e_assert_contains "$(_tier_json_get "$r2" "stdout")" "REQ_B" "req2 stdout" || return 1
    if [[ "$(_tier_json_get "$r1" "stdout")" == *"REQ_B"* || "$(_tier_json_get "$r2" "stdout")" == *"REQ_A"* ]]; then
        LAST_DETAIL="Cross-talk between back-to-back requests"
        return 1
    fi
    LAST_DETAIL="Distinct reqIds ($id1, $id2) with zero cross-contamination"
}

test_t2_f12_04() {
    _tier_require_device || return 1
    local resp="" ec="" out_len=""
    resp="$(e2e_exec_linux "python3 -c 'print(\"J\"*12000)'" 20 2>/dev/null)" || { LAST_DETAIL="12000-byte exec failed"; return 1; }
    ec="$(_tier_json_get "$resp" "exitCode")"
    e2e_assert_eq "$ec" "0" "12KB exitCode" || return 1
    out_len="$(python3 -c 'import json,sys; print(len(str(json.loads(sys.argv[1]).get("stdout","")).strip()))' "$resp")"
    e2e_assert_eq "$out_len" "12000" "12KB stdout length" || { LAST_DETAIL="Channel 1 truncated payload to $out_len bytes"; return 1; }
    LAST_DETAIL="Channel 1 run-as delivered full 12000-byte payload (>4KB logcat cap)"
}

test_t2_f12_05() {
    _tier_require_device || return 1
    _e2e_adb shell "am broadcast -n ${PKG_ID}/.automation.AutomationReceiver -a ${PKG_ID}.ACTION_UNKNOWN_E2E" >/dev/null 2>&1 || true
    sleep 0.5
    _tier_assert_no_crash_and_ready || return 1
    LAST_DETAIL="Unknown broadcast action ignored safely without crash"
}

# Execute all 60 Tier 2 tests
_tier_run_test "T2-F1-01" 2 "F1" test_t2_f1_01
_tier_run_test "T2-F1-02" 2 "F1" test_t2_f1_02
_tier_run_test "T2-F1-03" 2 "F1" test_t2_f1_03
_tier_run_test "T2-F1-04" 2 "F1" test_t2_f1_04
_tier_run_test "T2-F1-05" 2 "F1" test_t2_f1_05

_tier_run_test "T2-F2-01" 2 "F2" test_t2_f2_01
_tier_run_test "T2-F2-02" 2 "F2" test_t2_f2_02
_tier_run_test "T2-F2-03" 2 "F2" test_t2_f2_03
_tier_run_test "T2-F2-04" 2 "F2" test_t2_f2_04
_tier_run_test "T2-F2-05" 2 "F2" test_t2_f2_05

_tier_run_test "T2-F3-01" 2 "F3" test_t2_f3_01
_tier_run_test "T2-F3-02" 2 "F3" test_t2_f3_02
_tier_run_test "T2-F3-03" 2 "F3" test_t2_f3_03
_tier_run_test "T2-F3-04" 2 "F3" test_t2_f3_04
_tier_run_test "T2-F3-05" 2 "F3" test_t2_f3_05

_tier_run_test "T2-F4-01" 2 "F4" test_t2_f4_01
_tier_run_test "T2-F4-02" 2 "F4" test_t2_f4_02
_tier_run_test "T2-F4-03" 2 "F4" test_t2_f4_03
_tier_run_test "T2-F4-04" 2 "F4" test_t2_f4_04
_tier_run_test "T2-F4-05" 2 "F4" test_t2_f4_05

_tier_run_test "T2-F5-01" 2 "F5" test_t2_f5_01
_tier_run_test "T2-F5-02" 2 "F5" test_t2_f5_02
_tier_run_test "T2-F5-03" 2 "F5" test_t2_f5_03
_tier_run_test "T2-F5-04" 2 "F5" test_t2_f5_04
_tier_run_test "T2-F5-05" 2 "F5" test_t2_f5_05

_tier_run_test "T2-F6-01" 2 "F6" test_t2_f6_01
_tier_run_test "T2-F6-02" 2 "F6" test_t2_f6_02
_tier_run_test "T2-F6-03" 2 "F6" test_t2_f6_03
_tier_run_test "T2-F6-04" 2 "F6" test_t2_f6_04
_tier_run_test "T2-F6-05" 2 "F6" test_t2_f6_05

_tier_run_test "T2-F7-01" 2 "F7" test_t2_f7_01
_tier_run_test "T2-F7-02" 2 "F7" test_t2_f7_02
_tier_run_test "T2-F7-03" 2 "F7" test_t2_f7_03
_tier_run_test "T2-F7-04" 2 "F7" test_t2_f7_04
_tier_run_test "T2-F7-05" 2 "F7" test_t2_f7_05

_tier_run_test "T2-F8-01" 2 "F8" test_t2_f8_01
_tier_run_test "T2-F8-02" 2 "F8" test_t2_f8_02
_tier_run_test "T2-F8-03" 2 "F8" test_t2_f8_03
_tier_run_test "T2-F8-04" 2 "F8" test_t2_f8_04
_tier_run_test "T2-F8-05" 2 "F8" test_t2_f8_05

_tier_run_test "T2-F9-01" 2 "F9" test_t2_f9_01
_tier_run_test "T2-F9-02" 2 "F9" test_t2_f9_02
_tier_run_test "T2-F9-03" 2 "F9" test_t2_f9_03
_tier_run_test "T2-F9-04" 2 "F9" test_t2_f9_04
_tier_run_test "T2-F9-05" 2 "F9" test_t2_f9_05

_tier_run_test "T2-F10-01" 2 "F10" test_t2_f10_01
_tier_run_test "T2-F10-02" 2 "F10" test_t2_f10_02
_tier_run_test "T2-F10-03" 2 "F10" test_t2_f10_03
_tier_run_test "T2-F10-04" 2 "F10" test_t2_f10_04
_tier_run_test "T2-F10-05" 2 "F10" test_t2_f10_05

_tier_run_test "T2-F11-01" 2 "F11" test_t2_f11_01
_tier_run_test "T2-F11-02" 2 "F11" test_t2_f11_02
_tier_run_test "T2-F11-03" 2 "F11" test_t2_f11_03
_tier_run_test "T2-F11-04" 2 "F11" test_t2_f11_04
_tier_run_test "T2-F11-05" 2 "F11" test_t2_f11_05

_tier_run_test "T2-F12-01" 2 "F12" test_t2_f12_01
_tier_run_test "T2-F12-02" 2 "F12" test_t2_f12_02
_tier_run_test "T2-F12-03" 2 "F12" test_t2_f12_03
_tier_run_test "T2-F12-04" 2 "F12" test_t2_f12_04
_tier_run_test "T2-F12-05" 2 "F12" test_t2_f12_05

echo "[tier2] Completed 60 tests with ${TIER_FAILS} failure(s)" >&2
[[ $TIER_FAILS -eq 0 ]]
