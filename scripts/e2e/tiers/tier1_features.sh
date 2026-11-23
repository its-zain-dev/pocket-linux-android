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

TMP_TIER_DIR="$(mktemp -d "${OUT_DIR}/.tier1_tmp.XXXXXX")"
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
    e2e_exec_linux "for p in $procs; do pkill -TERM -x \"\$p\" 2>/dev/null || true; done; sleep 0.3; for p in $procs; do pkill -KILL -x \"\$p\" 2>/dev/null || true; done; rm -f /tmp/e2e_* /tmp/t1_* /mnt/sdcard/e2e_*" 20 >/dev/null 2>&1 || true
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
        local err_s="" out_s=""
        err_s="$(_tier_json_get "$resp" "stderr" 2>/dev/null || true)"
        out_s="$(_tier_json_get "$resp" "stdout" 2>/dev/null || true)"
        LAST_DETAIL="guest exitCode=${ec} stdout=${out_s:0:120} stderr=${err_s:0:120}"
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
tree = ET.parse(xml_path)
root = tree.getroot()

def parse_bounds(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return x1, y1, x2, y2

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
            LAST_DETAIL="Android UID process count $count >= 27 (PhantomProcessKiller threshold)"
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
# F1: GitHub Actions APK CI Pipeline (5 tests)
# ==============================================================================
test_t1_f1_01() {
    local ci_yml="$REPO_ROOT/.github/workflows/ci.yml"
    local e2e_yml="$REPO_ROOT/.github/workflows/e2e.yml"
    [[ -f "$ci_yml" && -f "$e2e_yml" ]] || { LAST_DETAIL="Missing ci.yml or e2e.yml"; return 1; }
    python3 - "$ci_yml" "$e2e_yml" <<'PYEOF' || { LAST_DETAIL="Workflow missing required multi-ABI/proot/rootfs/emulator steps"; return 1; }
import pathlib, sys
ci = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
e2e = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8")
for tok in ("ubuntu-latest", "build-proot.sh", "build-rootfs.sh", "assembleArm64Debug", "assembleX86_64Debug"):
    assert tok in ci, f"ci.yml missing {tok}"
for tok in ("verify_apks.py", "android-emulator-runner@v2", "30", "34", "run-e2e.sh"):
    assert tok in e2e, f"e2e.yml missing {tok}"
PYEOF
    LAST_DETAIL="Verified ci.yml and e2e.yml multi-ABI CI/emulator pipeline definitions"
}

test_t1_f1_02() {
    local apk="" target_abi="" flag="" out_json="$TMP_TIER_DIR/t1_f1_02.json"
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK provided and none pullable from device"; return 1; }
    [[ -f "$apk" ]] || { LAST_DETAIL="APK not found: $apk"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    if [[ "$target_abi" == "arm64-v8a" ]]; then flag="--arm64-apk"; else flag="--x86-64-apk"; fi
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$apk" --out-json "$out_json" >/dev/null 2>&1 || {
        LAST_DETAIL="verify_apks.py failed on $apk ($target_abi)"
        return 1
    }
    python3 - "$out_json" "$target_abi" <<'PYEOF' || { LAST_DETAIL="APK JSON checks failed"; return 1; }
import json, sys
d = json.load(open(sys.argv[1]))
a = d["apks"][sys.argv[2]]
assert d["overallStatus"] != "FAIL"
assert a["zipCrcOk"] is True and a["coreEntriesOk"] is True
assert a["sizeMiB"] <= 85.0
PYEOF
    local sz=""
    sz="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["apks"][sys.argv[2]]["sizeMiB"])' "$out_json" "$target_abi")"
    LAST_DETAIL="verify_apks.py passed on ${target_abi} APK (${sz} MiB)"
}

test_t1_f1_03() {
    local out_json="$TMP_TIER_DIR/t1_f1_03.json"
    if [[ -n "$ARM64_APK" && -n "$X86_64_APK" ]]; then
        python3 "$E2E_ROOT/verify_apks.py" --arm64-apk "$ARM64_APK" --x86-64-apk "$X86_64_APK" --out-json "$out_json" >/dev/null 2>&1 || {
            LAST_DETAIL="Dual-ABI verify_apks.py failed"
            return 1
        }
        python3 - "$out_json" <<'PYEOF' || { LAST_DETAIL="Dual-ABI isolation check failed"; return 1; }
import json, sys
d = json.load(open(sys.argv[1]))
assert d["overallStatus"] != "FAIL"
for abi in ("arm64-v8a", "x86_64"):
    assert d["apks"][abi]["abiIsolationOk"] is True
PYEOF
        LAST_DETAIL="Dual-ABI parity and strict ABI isolation verified on both arm64-v8a and x86_64 APKs"
        return 0
    fi
    if [[ "${CI:-}" == "true" || "$E2E_REQUIRE_DUAL_ABI" == "1" ]]; then
        LAST_DETAIL="Dual-ABI required (CI=true or E2E_REQUIRE_DUAL_ABI=1) but ARM64_APK/X86_64_APK missing"
        return 1
    fi
    local apk="" target_abi="" flag=""
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No active APK for single-ABI fallback"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    if [[ "$target_abi" == "arm64-v8a" ]]; then flag="--arm64-apk"; else flag="--x86-64-apk"; fi
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$apk" --out-json "$out_json" >/dev/null 2>&1 || {
        LAST_DETAIL="verify_apks.py ABI isolation check failed on $apk"
        return 1
    }
    python3 - "$out_json" "$target_abi" "$REPO_ROOT/app/build.gradle.kts" <<'PYEOF' || { LAST_DETAIL="Single-ABI isolation or Gradle multi-ABI config check failed"; return 1; }
import json, pathlib, sys
d = json.load(open(sys.argv[1]))
assert d["apks"][sys.argv[2]]["abiIsolationOk"] is True
gradle = pathlib.Path(sys.argv[3]).read_text(encoding="utf-8")
assert 'create("arm64")' in gradle and 'create("x86_64")' in gradle
PYEOF
    LAST_DETAIL="Verified active ${target_abi} APK abiIsolationOk=true + Gradle arm64/x86_64 flavors (arm64_apk=not_provided)"
}

test_t1_f1_04() {
    local apk="" target_abi="" flag="" out_json="$TMP_TIER_DIR/t1_f1_04.json"
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available for manifest/size/signature audit"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    if [[ "$target_abi" == "arm64-v8a" ]]; then flag="--arm64-apk"; else flag="--x86-64-apk"; fi
    python3 "$E2E_ROOT/verify_apks.py" "$flag" "$apk" --out-json "$out_json" >/dev/null 2>&1 || {
        LAST_DETAIL="verify_apks.py failed manifest/signature check"
        return 1
    }
    local manifest_file="$REPO_ROOT/app/src/main/AndroidManifest.xml"
    python3 - "$out_json" "$target_abi" "$apk" "$manifest_file" <<'PYEOF' || { LAST_DETAIL="Manifest/signature/size verification failed"; return 1; }
import json, pathlib, sys, zipfile
d = json.load(open(sys.argv[1]))
a = d["apks"][sys.argv[2]]
assert a["sizeMiB"] <= 85.0
man = a.get("manifest", {})
if not man.get("skipped", True):
    assert man.get("package") == "com.pocketlinux"
    assert man.get("extractNativeLibs") is True
    assert man.get("specialUseService") is True
    perms = man.get("permissions", [])
    assert "android.permission.FOREGROUND_SERVICE_SPECIAL_USE" in perms
    assert "android.permission.POST_NOTIFICATIONS" in perms
else:
    mtxt = pathlib.Path(sys.argv[4]).read_text(encoding="utf-8")
    for tok in ("FOREGROUND_SERVICE_SPECIAL_USE", "POST_NOTIFICATIONS", 'extractNativeLibs="true"', 'foregroundServiceType="specialUse"'):
        assert tok in mtxt
with open(sys.argv[3], "rb") as f:
    raw = f.read()
has_v2 = b"APK Sig Block 42" in raw
with zipfile.ZipFile(sys.argv[3], "r") as zf:
    has_v1 = any(n.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC")) for n in zf.namelist())
assert has_v2 or has_v1, "APK lacks v1/v2 signature block"
PYEOF
    LAST_DETAIL="Verified APK size <= 85 MiB, manifest attributes, and signature"
}

test_t1_f1_05() {
    _tier_require_device || return 1
    local pm_path="" pkg_dump="" expected_abi=""
    pm_path="$(_e2e_adb shell pm path "$PKG_ID" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_contains "$pm_path" "package:" "pm path" || { LAST_DETAIL="App $PKG_ID not installed"; return 1; }
    pkg_dump="$(_e2e_adb shell dumpsys package "$PKG_ID" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_contains "$pkg_dump" "versionName=1.0.0" "versionName" || { LAST_DETAIL="versionName=1.0.0 missing in dumpsys package"; return 1; }
    expected_abi="$(_tier_resolve_target_abi)"
    if [[ "$pkg_dump" == *"primaryCpuAbi="* ]]; then
        e2e_assert_contains "$pkg_dump" "primaryCpuAbi=${expected_abi}" "primaryCpuAbi" || {
            LAST_DETAIL="primaryCpuAbi mismatch (expected $expected_abi)"
            return 1
        }
    fi
    LAST_DETAIL="Installed package verified ($PKG_ID versionName=1.0.0 abi=${expected_abi})"
}

# ==============================================================================
# F2: NDK PRoot + Unbundled Loader (5 tests)
# ==============================================================================
test_t1_f2_01() {
    local apk="" target_abi=""
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available for ELF64 check"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    python3 - "$apk" "$target_abi" <<'PYEOF' || { LAST_DETAIL="ELF64 header check failed for native libs"; return 1; }
import struct, sys, zipfile
apk, abi = sys.argv[1], sys.argv[2]
expected_machine = 0x00B7 if abi == "arm64-v8a" else 0x003E
with zipfile.ZipFile(apk, "r") as zf:
    for lib in ("libproot.so", "libproot-loader.so", "libptybridge.so"):
        path = f"lib/{abi}/{lib}"
        data = zf.read(path)
        assert data[:4] == b"\x7fELF", f"{path} bad magic"
        assert data[4] == 2 and data[5] == 1, f"{path} not ELF64 LE"
        e_type, e_machine = struct.unpack("<HH", data[16:20])
        assert e_machine == expected_machine, f"{path} machine {hex(e_machine)} != {hex(expected_machine)}"
PYEOF
    LAST_DETAIL="Verified ELF64 headers for libproot.so, libproot-loader.so, libptybridge.so (${target_abi})"
}

test_t1_f2_02() {
    local apk="" target_abi=""
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available for static talloc audit"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    python3 - "$apk" "$target_abi" "$REPO_ROOT/scripts/ci/build-proot.sh" <<'PYEOF' || { LAST_DETAIL="Static talloc / unbundled loader check failed"; return 1; }
import pathlib, struct, sys, zipfile
apk, abi, build_sh = sys.argv[1], sys.argv[2], sys.argv[3]
script = pathlib.Path(build_sh).read_text(encoding="utf-8")
assert "libtalloc.a" in script and "PROOT_UNBUNDLE_LOADER" in script
with zipfile.ZipFile(apk, "r") as zf:
    data = zf.read(f"lib/{abi}/libproot.so")
    loader = zf.read(f"lib/{abi}/libproot-loader.so")
assert len(loader) > 0
# Parse ELF64 section headers or program headers for PT_DYNAMIC DT_NEEDED
e_phoff = struct.unpack("<Q", data[32:40])[0]
e_phentsize, e_phnum = struct.unpack("<HH", data[54:58])
e_shoff = struct.unpack("<Q", data[40:48])[0]
e_shentsize, e_shnum = struct.unpack("<HH", data[58:62])
dyn_bytes = b""
strtab_bytes = b""
for i in range(e_shnum):
    off = e_shoff + i * e_shentsize
    if off + 64 > len(data):
        break
    sh_type = struct.unpack("<I", data[off+4:off+8])[0]
    sh_offset, sh_size = struct.unpack("<QQ", data[off+24:off+40])
    sh_link = struct.unpack("<I", data[off+40:off+44])[0]
    if sh_type == 6:  # SHT_DYNAMIC
        dyn_bytes = data[sh_offset:sh_offset+sh_size]
        if sh_link < e_shnum:
            loff = e_shoff + sh_link * e_shentsize
            str_off, str_sz = struct.unpack("<QQ", data[loff+24:loff+40])
            strtab_bytes = data[str_off:str_off+str_sz]
        break
if dyn_bytes and strtab_bytes:
    for j in range(0, len(dyn_bytes) - 15, 16):
        d_tag, d_val = struct.unpack("<qQ", dyn_bytes[j:j+16])
        if d_tag == 0:
            break
        if d_tag == 1 and d_val < len(strtab_bytes):
            end = strtab_bytes.find(b"\x00", d_val)
            name = strtab_bytes[d_val:end].decode("utf-8", errors="replace")
            assert "talloc" not in name.lower(), f"Dynamic libtalloc dependency found: {name}"
PYEOF
    LAST_DETAIL="Verified static talloc (zero DT_NEEDED libtalloc) and unbundled loader config"
}

test_t1_f2_03() {
    _tier_require_device || return 1
    local pkg_dump="" lib_dir="" ls_out=""
    pkg_dump="$(_e2e_adb shell dumpsys package "$PKG_ID" 2>/dev/null | tr -d '\r' || true)"
    lib_dir="$(python3 -c 'import re,sys; m=re.search(r"nativeLibraryDir=(\S+)", sys.argv[1]); print(m.group(1) if m else "")' "$pkg_dump" 2>/dev/null || true)"
    [[ -n "$lib_dir" ]] || { LAST_DETAIL="nativeLibraryDir not found in dumpsys package"; return 1; }
    ls_out="$(_e2e_adb shell "ls -la '${lib_dir}' /data/app" 2>/dev/null | tr -d '\r' || true)"
    local dir_ls=""
    dir_ls="$(_e2e_adb shell "ls -l '${lib_dir}'" 2>/dev/null | tr -d '\r' || true)"
    for lib in libproot.so libproot-loader.so libptybridge.so; do
        e2e_assert_contains "$dir_ls" "$lib" "extracted $lib" || { LAST_DETAIL="$lib missing in $lib_dir"; return 1; }
    done
    LAST_DETAIL="All 3 native .so binaries extracted in $lib_dir"
}

test_t1_f2_04() {
    _tier_require_device || return 1
    local resp="" out="" expected_arch=""
    resp="$(_tier_exec_ok "echo PROOT_EXEC_OK && uname -m")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "PROOT_EXEC_OK" "proot stdout" || { LAST_DETAIL="Missing PROOT_EXEC_OK"; return 1; }
    if [[ "$(_tier_resolve_target_abi)" == "arm64-v8a" ]]; then expected_arch="aarch64"; else expected_arch="x86_64"; fi
    e2e_assert_contains "$out" "$expected_arch" "uname -m" || { LAST_DETAIL="uname -m did not match $expected_arch: $out"; return 1; }
    LAST_DETAIL="PRoot executed genuine guest uname -m ($expected_arch)"
}

test_t1_f2_05() {
    _tier_require_device || return 1
    local resp="" out="" logs=""
    resp="$(_tier_exec_ok "ln -sf /bin/busybox /tmp/e2e_sym_f2 && readlink /tmp/e2e_sym_f2 && /tmp/e2e_sym_f2 echo WX_EXEC_OK; rm -f /tmp/e2e_sym_f2")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "/bin/busybox" "symlink target" || { LAST_DETAIL="readlink mismatch: $out"; return 1; }
    e2e_assert_contains "$out" "WX_EXEC_OK" "symlink exec" || { LAST_DETAIL="W^X execution failed: $out"; return 1; }
    local log_f="$TMP_TIER_DIR/f2_05_logcat.txt"
    _e2e_adb logcat -d 2>/dev/null | tr -d '\r' > "$log_f" || true
    if python3 - "$log_f" <<'PYEOF'
import re, sys
with open(sys.argv[1], "r", encoding="utf-8", errors="replace") as f:
    for ln in f:
        if re.search(r"avc:\s+denied.*(proot|pocketlinux)", ln):
            sys.exit(0)
sys.exit(1)
PYEOF
    then
        LAST_DETAIL="SELinux avc: denied detected for proot/pocketlinux"
        return 1
    fi
    LAST_DETAIL="PRoot symlink creation and W^X-compliant guest execution succeeded with zero avc: denied"
}

# ==============================================================================
# F3: Genuine Alpine v3.20 GUI Rootfs (6 tests)
# ==============================================================================
test_t1_f3_01() {
    local apk="" target_abi="" expected_asset=""
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available for rootfs stream check"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    expected_asset="assets/rootfs-${target_abi}.tar.gz"
    python3 - "$apk" "$expected_asset" <<'PYEOF' || { LAST_DETAIL="Rootfs tar.gz stream audit failed"; return 1; }
import gzip, pathlib, re, sys, tarfile, zipfile
apk, asset = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "r") as zf:
    info = zf.getinfo(asset)
    assert info.compress_type == zipfile.ZIP_STORED, f"{asset} not ZIP_STORED"
    symlinks = 0
    with zf.open(asset, "r") as raw:
        with gzip.GzipFile(fileobj=raw, mode="rb") as gz:
            with tarfile.open(fileobj=gz, mode="r|") as tf:
                for m in tf:
                    assert not (m.name.startswith("/") and m.name != "/"), f"abs path {m.name}"
                    assert ".." not in pathlib.PurePosixPath(m.name).parts, f"traversal {m.name}"
                    assert not (m.ischr() or m.isblk() or m.isfifo()), f"special file {m.name}"
                    base = m.name.rsplit("/", 1)[-1]
                    assert not (base.startswith("qemu-") and (base.endswith("-static") or base in ("qemu-aarch64", "qemu-x86_64"))), f"qemu leftover {m.name}"
                    if m.issym():
                        symlinks += 1
            while gz.read(65536):
                pass
    assert symlinks > 0, "zero symlinks in rootfs"
PYEOF
    LAST_DETAIL="Audited ZIP_STORED ${expected_asset} stream (symlinks>0, zero traversal/qemu/specials)"
}

test_t1_f3_02() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "cat /etc/alpine-release && /bin/bash --version | head -n1 && busybox --help | head -n1")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "3.20." "alpine-release" || { LAST_DETAIL="Expected Alpine 3.20.x, got: $out"; return 1; }
    e2e_assert_contains "$out" "bash" "bash version" || { LAST_DETAIL="Missing GNU bash"; return 1; }
    e2e_assert_contains "$out" "BusyBox" "busybox" || { LAST_DETAIL="Missing BusyBox"; return 1; }
    LAST_DETAIL="Verified genuine Alpine 3.20 + GNU bash + BusyBox inside PRoot"
}

test_t1_f3_03() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "for b in Xvnc jwm xterm xcalc xclock feh mousepad galculator pcmanfm xwininfo xdpyinfo xrandr xsetroot python3 apk curl; do command -v \"\$b\" >/dev/null || { echo MISSING:\$b; exit 1; }; done; echo ALL_BINS_OK")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "ALL_BINS_OK" "all bundled binaries" || { LAST_DETAIL="Missing bundled binary: $out"; return 1; }
    LAST_DETAIL="Verified all 16 bundled CLI, X11, and GUI binaries present on PATH"
}

test_t1_f3_04() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "test -s /root/.jwmrc && grep -q xterm /root/.jwmrc && grep -q mousepad /root/.jwmrc && grep -q feh /root/.jwmrc && fc-list | grep -qi dejavu && echo JWM_FONTS_OK")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "JWM_FONTS_OK" "jwmrc and dejavu fonts" || { LAST_DETAIL=".jwmrc or DejaVu font check failed: $out"; return 1; }
    LAST_DETAIL="Verified /root/.jwmrc menu entries and DejaVu fonts via fc-list"
}

test_t1_f3_05() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "test -x /usr/local/bin/start-desktop.sh && test -x /usr/local/bin/sysinfo.sh && /usr/local/bin/sysinfo.sh")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_json "$out" "str(d.get('alpine_release','')).startswith('3.20') and int(d.get('process_count', 0)) > 0" || {
        LAST_DETAIL="sysinfo.sh returned invalid JSON: $out"
        return 1
    }
    LAST_DETAIL="Verified executable start-desktop.sh & sysinfo.sh JSON telemetry"
}

test_t1_f3_06() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "cat /etc/resolv.conf")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "nameserver" "nameserver in resolv.conf" || { LAST_DETAIL="/etc/resolv.conf lacks nameserver"; return 1; }
    if [[ "$out" == *"127.0.0.53"* ]]; then
        LAST_DETAIL="/etc/resolv.conf contains invalid systemd stub 127.0.0.53"
        return 1
    fi
    LAST_DETAIL="Verified valid /etc/resolv.conf nameservers without 127.0.0.53"
}

# ==============================================================================
# F4: Rootfs First-Boot Provisioner (5 tests)
# ==============================================================================
test_t1_f4_01() {
    _tier_require_device || return 1
    local out=""
    out="$(_e2e_adb shell "run-as ${PKG_ID} sh -c 'cat files/rootfs/.rootfs_ready_v1 && test -e files/rootfs/bin/busybox && test ! -e files/rootfs.staging && echo READY_OK'" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_contains "$out" "v1" "rootfs marker" || { LAST_DETAIL=".rootfs_ready_v1 missing or invalid: $out"; return 1; }
    e2e_assert_contains "$out" "READY_OK" "rootfs staging clean" || { LAST_DETAIL="rootfs readiness/staging check failed: $out"; return 1; }
    LAST_DETAIL="Verified .rootfs_ready_v1 marker and absence of rootfs.staging"
}

test_t1_f4_02() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "test -L /bin/sh && test -x /usr/local/bin/start-desktop.sh && test -x /usr/bin/Xvnc && echo PERMS_OK")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "PERMS_OK" "symlinks and exec perms" || { LAST_DETAIL="Symlink or exec permission missing: $out"; return 1; }
    LAST_DETAIL="Verified /bin/sh symlink and executable permissions preserved after extraction"
}

test_t1_f4_03() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "stat -c '%a' /tmp && stat -c '%a' /tmp/.X11-unix")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    python3 - "$out" <<'PYEOF' || { LAST_DETAIL="Expected 1777 permissions on /tmp and /tmp/.X11-unix, got: $out"; return 1; }
import sys
lines = [ln.strip() for ln in sys.argv[1].splitlines() if ln.strip()]
assert len(lines) >= 2 and lines[0] == "1777" and lines[1] == "1777", f"got {lines}"
PYEOF
    LAST_DETAIL="Verified 1777 sticky permissions on /tmp and /tmp/.X11-unix"
}

test_t1_f4_04() {
    _tier_require_device || return 1
    local token="E2E_F4_TOK_$(date +%s)"
    _e2e_adb shell "run-as ${PKG_ID} sh -c 'mkdir -p files/shared && printf \"%s\" \"${token}\" > files/shared/e2e_f4.txt'" >/dev/null 2>&1 || {
        LAST_DETAIL="Failed to write files/shared/e2e_f4.txt via run-as"
        return 1
    }
    local resp="" out=""
    resp="$(_tier_exec_ok "cat /mnt/sdcard/e2e_f4.txt; rm -f /mnt/sdcard/e2e_f4.txt")" || {
        _e2e_adb shell "run-as ${PKG_ID} rm -f files/shared/e2e_f4.txt" >/dev/null 2>&1 || true
        return 1
    }
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_eq "$out" "$token" "/mnt/sdcard shared mount" || { LAST_DETAIL="Token mismatch: $out"; return 1; }
    LAST_DETAIL="Verified <filesDir>/shared <-> /mnt/sdcard bind mount roundtrip"
}

test_t1_f4_05() {
    _tier_require_device || return 1
    local m1="" m2="" st="" startup_ms=""
    m1="$(_e2e_adb shell "run-as ${PKG_ID} stat -c '%Y' files/rootfs/.rootfs_ready_v1" 2>/dev/null | tr -d '\r[:space:]' || true)"
    [[ "$m1" =~ ^[0-9]+$ ]] || { LAST_DETAIL="Could not read .rootfs_ready_v1 mtime: '$m1'"; return 1; }
    _e2e_adb shell am force-stop "$PKG_ID" >/dev/null 2>&1 || true
    sleep 1
    _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null 2>&1 || { LAST_DETAIL="am start failed"; return 1; }
    st="$(_tier_ensure_ready 60)" || return 1
    m2="$(_e2e_adb shell "run-as ${PKG_ID} stat -c '%Y' files/rootfs/.rootfs_ready_v1" 2>/dev/null | tr -d '\r[:space:]' || true)"
    e2e_assert_eq "$m2" "$m1" "idempotent marker mtime" || { LAST_DETAIL="Marker mtime changed ($m1 -> $m2), unexpected re-extraction"; return 1; }
    startup_ms="$(_tier_json_get "$st" "startupMs")"
    [[ "${startup_ms:-0}" -gt 0 ]] || { LAST_DETAIL="Expected startupMs > 0, got '$startup_ms'"; return 1; }
    LAST_DETAIL="Idempotent warm boot preserved .rootfs_ready_v1 mtime=$m1 (startupMs=${startup_ms})"
}

# ==============================================================================
# F5: PRoot Execution & Syscall Engine (6 tests)
# ==============================================================================
test_t1_f5_01() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "d=/tmp/e2e_f5_hl; rm -rf \$d && mkdir -p \$d && printf 'hello_hl' > \$d/a && ln \$d/a \$d/b && chmod 750 \$d/a && test -x \$d/a && cat \$d/b; rm -rf \$d")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_eq "$out" "hello_hl" "hardlink content" || { LAST_DETAIL="Hardlink/chmod test failed: $out"; return 1; }
    LAST_DETAIL="Verified --link2symlink hardlink and chmod 750 inside PRoot"
}

test_t1_f5_02() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "printf 'c\na\nb\n' | sort | tr '\n' ','")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_eq "$out" "a,b,c," "pipe sort output" || { LAST_DETAIL="Pipeline output mismatch: $out"; return 1; }
    LAST_DETAIL="Verified shell pipeline integrity inside PRoot"
}

test_t1_f5_03() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "test -r /proc/meminfo && test -c /dev/null && test -d /sys/class && uname -s && id -u")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "Linux" "uname -s" || { LAST_DETAIL="uname -s missing Linux: $out"; return 1; }
    e2e_assert_contains "$out" "0" "id -u" || { LAST_DETAIL="id -u missing 0: $out"; return 1; }
    LAST_DETAIL="Verified /proc, /dev, /sys mounts and uid=0 Linux emulation"
}

test_t1_f5_04() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "python3 -c 'import os; fd=os.memfd_create(\"e2e\"); os.write(fd,b\"MEMFD_OK\"); os.lseek(fd,0,0); print(os.read(fd,8).decode())'")" || return 1
    out="$(_tier_json_get "$resp" "stdout" | tr -d '\r\n')"
    e2e_assert_eq "$out" "MEMFD_OK" "memfd_create" || { LAST_DETAIL="memfd_create failed: $out"; return 1; }
    LAST_DETAIL="Verified ashmem-memfd syscall support inside PRoot"
}

test_t1_f5_05() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "cat <<'EOF' > /tmp/e2e_shebang.py
#!/usr/bin/env python3
import platform
print('PY_ELF_OK:' + platform.machine())
EOF
chmod +x /tmp/e2e_shebang.py && /tmp/e2e_shebang.py; rc=\$?; rm -f /tmp/e2e_shebang.py; exit \$rc")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "PY_ELF_OK:" "shebang exec" || { LAST_DETAIL="Shebang execution failed: $out"; return 1; }
    LAST_DETAIL="Verified direct shebang + musl ELF binary execution ($out)"
}

test_t1_f5_06() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "printf '%s|%s|%s\n' \"\$HOME\" \"\$USER\" \"\$DISPLAY\"")" || return 1
    out="$(_tier_json_get "$resp" "stdout" | tr -d '\r\n')"
    e2e_assert_eq "$out" "/root|root|:1" "PRoot environment" || { LAST_DETAIL="Unexpected PRoot env: $out"; return 1; }
    LAST_DETAIL="Verified default PRoot environment (/root|root|:1)"
}

# ==============================================================================
# F6: Native PTY Interactive Shell Bridge (5 tests)
# ==============================================================================
test_t1_f6_01() {
    local apk="" target_abi=""
    apk="$(_tier_resolve_active_apk)" || { LAST_DETAIL="No APK available for libptybridge.so symbol audit"; return 1; }
    target_abi="$(_tier_resolve_target_abi)"
    python3 - "$apk" "$target_abi" <<'PYEOF' || { LAST_DETAIL="Missing JNI PTY symbols in libptybridge.so"; return 1; }
import sys, zipfile
apk, abi = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "r") as zf:
    data = zf.read(f"lib/{abi}/libptybridge.so")
for sym in (b"nativeSpawnPty", b"nativeRead", b"nativeWrite", b"nativeSetWindowSize"):
    assert sym in data, f"Missing symbol {sym!r} in lib/{abi}/libptybridge.so"
PYEOF
    if _tier_has_device; then
        _tier_exec_ok "test -d /dev/pts && echo PTS_OK" >/dev/null || return 1
    fi
    LAST_DETAIL="Verified libptybridge.so JNI symbols (nativeSpawnPty/Read/Write/SetWindowSize)"
}

_tier_switch_to_terminal_tab() {
    local ui_xml="$TMP_TIER_DIR/ui_tab_term.xml"
    _tier_ensure_foreground || return 1
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_terminal" "Terminal" || return 1
    sleep 1
}

_tier_send_terminal_cmd() {
    local cmd="$1"
    local ui_xml="$TMP_TIER_DIR/ui_term_send.xml"
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "terminal_input" "command" "EditText" || return 1
    sleep 0.3
    local encoded=""
    encoded="$(python3 -c 'import sys; print(sys.argv[1].replace(" ", "%s"))' "$cmd")"
    _e2e_adb shell input text "$encoded" >/dev/null 2>&1 || return 1
    sleep 0.2
    _tier_tap_ui_node "$ui_xml" "terminal_send_btn" "Send" || _e2e_adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
    sleep 1.2
}

test_t1_f6_02() {
    _tier_require_device || return 1
    _tier_switch_to_terminal_tab || return 1
    _tier_send_terminal_cmd "tty && echo PTY_TTY_OK" || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f6_02.xml" out_info=""
    _tier_dump_ui "$ui_xml" || return 1
    out_info="$(_tier_find_ui_node "$ui_xml" "terminal_output" "PTY_TTY_OK" 2>/dev/null || true)"
    local raw_xml=""
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "/dev/pts/" "PTY tty path" || { LAST_DETAIL="/dev/pts/ not found in Terminal output"; return 1; }
    e2e_assert_contains "$raw_xml" "PTY_TTY_OK" "PTY_TTY_OK" || { LAST_DETAIL="PTY_TTY_OK not found in Terminal output"; return 1; }
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    LAST_DETAIL="Interactive Terminal tab allocated genuine /dev/pts/* PTY"
}

test_t1_f6_03() {
    _tier_require_device || return 1
    _tier_switch_to_terminal_tab || return 1
    _tier_send_terminal_cmd "cd /tmp && export E2E_VAR=pty42" || return 1
    _tier_send_terminal_cmd "pwd && echo \$E2E_VAR" || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f6_03.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "/tmp" "stateful cd" || { LAST_DETAIL="Stateful cd /tmp failed"; return 1; }
    e2e_assert_contains "$raw_xml" "pty42" "stateful export" || { LAST_DETAIL="Stateful export E2E_VAR failed"; return 1; }
    _tier_send_terminal_cmd "cd /root && unset E2E_VAR" >/dev/null 2>&1 || true
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    LAST_DETAIL="Verified stateful PTY shell session across consecutive commands"
}

test_t1_f6_04() {
    _tier_require_device || return 1
    _tier_switch_to_terminal_tab || return 1
    _tier_send_terminal_cmd "stty size" || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f6_04.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    python3 - "$ui_xml" <<'PYEOF' || { LAST_DETAIL="stty size did not report valid >=10x20 PTY dimensions"; return 1; }
import re, sys
txt = open(sys.argv[1], "r", encoding="utf-8", errors="replace").read()
matches = re.findall(r"\b(\d+)\s+(\d+)\b", txt)
ok = any(int(r) >= 10 and int(c) >= 20 for r, c in matches)
assert ok, f"no valid stty rows cols in {matches}"
PYEOF
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    LAST_DETAIL="Verified PTY TIOCSWINSZ dimensions via stty size"
}

test_t1_f6_05() {
    _tier_require_device || return 1
    _tier_switch_to_terminal_tab || return 1
    _tier_send_terminal_cmd "sleep 30" || return 1
    sleep 0.5
    local ui_xml="$TMP_TIER_DIR/ui_f6_05.xml"
    _tier_dump_ui "$ui_xml" >/dev/null 2>&1 || true
    if _tier_tap_ui_node "$ui_xml" "" "Ctrl" >/dev/null 2>&1; then
        sleep 0.2
        _e2e_adb shell input text "c" >/dev/null 2>&1 || true
    else
        _e2e_adb shell input keyevent --longpress 113 31 >/dev/null 2>&1 || _e2e_adb shell input keyevent KEYCODE_BREAK >/dev/null 2>&1 || true
    fi
    sleep 0.5
    _tier_send_terminal_cmd "echo AFTER_INT" || return 1
    _tier_dump_ui "$ui_xml" || return 1
    local raw_xml=""
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "AFTER_INT" "PTY SIGINT recovery" || {
        _tier_cleanup_gui_apps
        LAST_DETAIL="Terminal prompt did not recover AFTER_INT after Ctrl+C"
        return 1
    }
    _tier_cleanup_gui_apps
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    LAST_DETAIL="Verified PTY Ctrl+C interrupt and prompt recovery"
}

# ==============================================================================
# F7: Foreground Linux Runtime Supervisor (5 tests)
# ==============================================================================
test_t1_f7_01() {
    _tier_require_device || return 1
    local dump=""
    dump="$(_e2e_adb shell dumpsys activity services "$PKG_ID" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_contains "$dump" "LinuxRuntimeService" "LinuxRuntimeService active" || { LAST_DETAIL="LinuxRuntimeService not active"; return 1; }
    e2e_assert_contains "$dump" "isForeground=true" "isForeground" || { LAST_DETAIL="LinuxRuntimeService not foreground"; return 1; }
    if [[ "$API_LEVEL" -ge 34 ]]; then
        if [[ "$dump" != *"specialUse"* && "$dump" != *"0x40000000"* ]]; then
            LAST_DETAIL="API $API_LEVEL dumpsys missing specialUse foregroundServiceType"
            return 1
        fi
    fi
    LAST_DETAIL="Verified LinuxRuntimeService foreground service (API=$API_LEVEL)"
}

test_t1_f7_02() {
    _tier_require_device || return 1
    local st=""
    st="$(e2e_broadcast_status 15 2>/dev/null)" || { LAST_DETAIL="ACTION_GET_STATUS failed"; return 1; }
    e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready' and int(d.get('vncPort',0)) == 5901 and int(d.get('startupMs',0)) > 0" || {
        LAST_DETAIL="Unexpected status JSON: $st"
        return 1
    }
    LAST_DETAIL="ACTION_GET_STATUS confirmed Ready, vncPort=5901, startupMs>0"
}

test_t1_f7_03() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "test -f /tmp/.pocketlinux_desktop_ready && xdpyinfo -display :1 | head -n 8")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "name of display:" "xdpyinfo display" || { LAST_DETAIL="xdpyinfo :1 failed: $out"; return 1; }
    e2e_assert_contains "$out" ":1" "display :1" || { LAST_DETAIL="Display :1 missing"; return 1; }
    LAST_DETAIL="Verified .pocketlinux_desktop_ready and active X display :1"
}

test_t1_f7_04() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "pgrep -x jwm >/dev/null && xwininfo -display :1 -root -tree")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "Root window id:" "xwininfo root" || { LAST_DETAIL="xwininfo root missing: $out"; return 1; }
    LAST_DETAIL="Verified live JWM process and X11 root window tree"
}

test_t1_f7_05() {
    _tier_require_device || return 1
    local r1="" pid1="" r2="" pid2="" st=""
    r1="$(_tier_exec_ok "pgrep -x Xvnc | head -n 1")" || return 1
    pid1="$(_tier_json_get "$r1" "stdout" | tr -d '[:space:]')"
    [[ -n "$pid1" ]] || { LAST_DETAIL="Xvnc PID empty"; return 1; }
    _e2e_adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1 || true
    sleep 2
    st="$(e2e_broadcast_status 10 2>/dev/null)" || { LAST_DETAIL="Status failed while backgrounded"; return 1; }
    e2e_assert_json "$st" "str(d.get('state','')).lower() == 'ready'" || { LAST_DETAIL="Service did not stay Ready in background"; return 1; }
    _tier_ensure_foreground || return 1
    r2="$(_tier_exec_ok "pgrep -x Xvnc | head -n 1")" || return 1
    pid2="$(_tier_json_get "$r2" "stdout" | tr -d '[:space:]')"
    e2e_assert_eq "$pid2" "$pid1" "Xvnc PID persistence" || { LAST_DETAIL="Xvnc restarted across background/resume ($pid1 -> $pid2)"; return 1; }
    LAST_DETAIL="Verified supervisor keeps Xvnc PID=$pid1 alive across background/resume"
}

# ==============================================================================
# F8: Pure-Kotlin RFB 3.8 Client (5 tests)
# ==============================================================================
test_t1_f8_01() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local fr=""
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { LAST_DETAIL="ACTION_VERIFY_DESKTOP_FRAME failed"; return 1; }
    e2e_assert_json "$fr" "d.get('connected') is True and int(d.get('width',0)) >= 640 and int(d.get('height',0)) >= 480" || {
        LAST_DETAIL="Invalid RFB dimensions/state: $fr"
        return 1
    }
    LAST_DETAIL="RFB client connected with valid desktop dimensions"
}

test_t1_f8_02() {
    _tier_require_device || return 1
    local fr=""
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { LAST_DETAIL="ACTION_VERIFY_DESKTOP_FRAME failed"; return 1; }
    e2e_assert_json "$fr" "d.get('connected') is True and int(d.get('nonUniformPixels',0)) > 0 and len(str(d.get('sha256',''))) == 64" || {
        LAST_DETAIL="Blank or invalid RFB frame: $fr"
        return 1
    }
    LAST_DETAIL="Verified non-uniform RFB framebuffer and 64-hex sha256"
}

test_t1_f8_03() {
    _tier_require_device || return 1
    local f0="" f1="" s0="" s1=""
    f0="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { LAST_DETAIL="Baseline frame failed"; return 1; }
    s0="$(_tier_json_get "$f0" "sha256")"
    e2e_launch_gui "xclock" 15 >/dev/null 2>&1 || { LAST_DETAIL="Failed to launch xclock"; return 1; }
    _tier_wait_xwindow "xclock" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    sleep 1
    f1="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { _tier_cleanup_gui_apps; LAST_DETAIL="Post-xclock frame failed"; return 1; }
    s1="$(_tier_json_get "$f1" "sha256")"
    _tier_cleanup_gui_apps
    if [[ "$s0" == "$s1" ]]; then
        LAST_DETAIL="RFB sha256 unchanged after launching xclock ($s0)"
        return 1
    fi
    LAST_DETAIL="Verified RFB sha256 changed on GUI window open ($s0 -> $s1)"
}

test_t1_f8_04() {
    _tier_require_device || return 1
    local f_open="" f_closed="" s_open="" s_closed=""
    e2e_launch_gui "xclock" 15 >/dev/null 2>&1 || { LAST_DETAIL="Failed to launch xclock"; return 1; }
    _tier_wait_xwindow "xclock" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    sleep 0.8
    f_open="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { _tier_cleanup_gui_apps; return 1; }
    s_open="$(_tier_json_get "$f_open" "sha256")"
    _tier_cleanup_gui_apps
    sleep 0.8
    f_closed="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { LAST_DETAIL="Post-close frame failed"; return 1; }
    s_closed="$(_tier_json_get "$f_closed" "sha256")"
    if [[ "$s_open" == "$s_closed" ]]; then
        LAST_DETAIL="RFB sha256 unchanged after closing xclock ($s_open)"
        return 1
    fi
    LAST_DETAIL="Verified RFB sha256 updated on GUI window close"
}

test_t1_f8_05() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "python3 -c 'import socket; s=socket.create_connection((\"127.0.0.1\",5901),3); b=s.recv(12); s.close(); print(b.decode(\"ascii\",\"replace\").strip())'")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" "RFB 003.008" "RFB 3.8 banner" || { LAST_DETAIL="Unexpected RFB banner: $out"; return 1; }
    LAST_DETAIL="Verified direct RFB 3.8 server handshake ($out)"
}

# ==============================================================================
# F9: Hardware-Accelerated Desktop Surface (5 tests)
# ==============================================================================
test_t1_f9_01() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f9_01.xml" info=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    info="$(_tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" 2>/dev/null)" || {
        LAST_DETAIL="desktop_surface / SurfaceView not found in UI dump"
        return 1
    }
    python3 - "$info" <<'PYEOF' || { LAST_DETAIL="desktop_surface bounds too small: $info"; return 1; }
import sys
parts = sys.argv[1].split()
x1, y1, x2, y2 = map(int, parts[2:6])
assert x2 - x1 > 200 and y2 - y1 > 200
PYEOF
    LAST_DETAIL="Verified desktop_surface rendered with >200x200 bounds"
}

test_t1_f9_02() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f9_02.xml" info="" p1="$TMP_TIER_DIR/s1.png" p2="$TMP_TIER_DIR/s2.png"
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    info="$(_tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" 2>/dev/null)" || {
        LAST_DETAIL="desktop_surface not found"
        return 1
    }
    _e2e_adb exec-out screencap -p > "$p1" 2>/dev/null || { LAST_DETAIL="screencap before failed"; return 1; }
    e2e_launch_gui "xclock -bg white -fg black -geometry 300x300+100+100" 15 >/dev/null 2>&1 || { LAST_DETAIL="xclock launch failed"; return 1; }
    _tier_wait_xwindow "xclock" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    sleep 1
    _e2e_adb exec-out screencap -p > "$p2" 2>/dev/null || { _tier_cleanup_gui_apps; LAST_DETAIL="screencap after failed"; return 1; }
    _tier_cleanup_gui_apps
    python3 - "$p1" "$p2" "$info" <<'PYEOF' || { LAST_DETAIL="Physical screencap surface crop lacked non-uniformity or pixel diff"; return 1; }
import struct, sys, zlib

def decode_png_rgb(path):
    raw = open(path, "rb").read()
    assert raw[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    w = h = ct = 0
    idat = bytearray()
    while pos < len(raw):
        ln, ctype = struct.unpack(">I4s", raw[pos:pos+8])
        cdata = raw[pos+8:pos+8+ln]
        pos += 12 + ln
        if ctype == b"IHDR":
            w, h, bd, ct, comp, flt, inter = struct.unpack(">IIBBBBB", cdata)
        elif ctype == b"IDAT":
            idat.extend(cdata)
        elif ctype == b"IEND":
            break
    bpp = 4 if ct == 6 else 3
    decomp = zlib.decompress(bytes(idat))
    stride = w * bpp
    rows = []
    idx = 0
    prev = bytearray(stride)
    for _ in range(h):
        ft = decomp[idx]; idx += 1
        row = bytearray(decomp[idx:idx+stride]); idx += stride
        if ft == 1:
            for i in range(bpp, stride): row[i] = (row[i] + row[i-bpp]) & 0xff
        elif ft == 2:
            for i in range(stride): row[i] = (row[i] + prev[i]) & 0xff
        elif ft == 3:
            for i in range(stride):
                a = row[i-bpp] if i >= bpp else 0
                row[i] = (row[i] + ((a + prev[i]) // 2)) & 0xff
        elif ft == 4:
            for i in range(stride):
                a = row[i-bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i-bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                row[i] = (row[i] + pr) & 0xff
        rows.append(bytes(row))
        prev = row
    return w, h, bpp, rows

p1, p2, info = sys.argv[1:4]
parts = info.split()
x1, y1, x2, y2 = int(parts[2]), int(parts[3]), int(parts[4]), int(parts[5])
w1, h1, bpp1, r1 = decode_png_rgb(p1)
w2, h2, bpp2, r2 = decode_png_rgb(p2)
x1, y1 = max(0, x1), max(0, y1)
x2, y2 = min(w1, w2, x2), min(h1, h2, y2)
unique_colors = set()
diff_count = 0
step_y = max(1, (y2 - y1) // 40)
step_x = max(1, (x2 - x1) // 40)
for y in range(y1, y2, step_y):
    row1, row2 = r1[y], r2[y]
    for x in range(x1, x2, step_x):
        c1 = row1[x*bpp1:x*bpp1+3]
        c2 = row2[x*bpp2:x*bpp2+3]
        unique_colors.add(c1)
        if c1 != c2:
            diff_count += 1
assert len(unique_colors) >= 5, f"unique colors too low: {len(unique_colors)}"
assert diff_count > 0, "no pixel diff inside desktop_surface crop"
PYEOF
    LAST_DETAIL="Verified non-uniform surface crop and physical pixel diff after xclock render"
}

test_t1_f9_03() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f9_03.xml" info="" f0="" f1="" s0="" s1=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    info="$(_tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" 2>/dev/null)" || { LAST_DETAIL="desktop_surface missing"; return 1; }
    f0="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    s0="$(_tier_json_get "$f0" "sha256")"
    local tx="" ty=""
    tx="$(python3 -c 'p=input().split(); x1,y1,x2,y2=map(int,p[2:6]); print(x1 + max(12, (x2-x1)//32))' <<< "$info")"
    ty="$(python3 -c 'p=input().split(); x1,y1,x2,y2=map(int,p[2:6]); print(y2 - max(8, (y2-y1)//40))' <<< "$info")"
    _e2e_adb shell input tap "$tx" "$ty" >/dev/null 2>&1 || true
    sleep 1
    f1="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    s1="$(_tier_json_get "$f1" "sha256")"
    _e2e_adb shell input keyevent KEYCODE_ESCAPE >/dev/null 2>&1 || true
    if [[ "$s0" == "$s1" ]]; then
        LAST_DETAIL="Tap on JWM Apps tray ($tx,$ty) did not change RFB frame"
        return 1
    fi
    LAST_DETAIL="Verified direct touch input on desktop_surface triggered JWM Apps menu"
}

test_t1_f9_04() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f9_04.xml" info="" cx="" cy=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    sleep 0.4
    _tier_dump_ui "$ui_xml" || return 1
    info="$(_tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" 2>/dev/null)" || { LAST_DETAIL="desktop_surface missing"; return 1; }
    cx="$(printf '%s\n' "$info" | awk '{print $1}')"
    cy="$(printf '%s\n' "$info" | awk '{print $2}')"
    e2e_launch_gui "xterm" 15 >/dev/null 2>&1 || { LAST_DETAIL="xterm launch failed"; return 1; }
    _tier_wait_xwindow "xterm" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    sleep 0.6
    _e2e_adb shell input tap "$cx" "$cy" >/dev/null 2>&1 || true
    sleep 0.3
    _e2e_adb shell input text "touch%s/tmp/e2e_kbd_ok" >/dev/null 2>&1 || true
    _e2e_adb shell input keyevent KEYCODE_ENTER >/dev/null 2>&1 || true
    sleep 0.6
    local rc=0
    _tier_exec_ok "test -f /tmp/e2e_kbd_ok && rm -f /tmp/e2e_kbd_ok" 15 >/dev/null || rc=$?
    _tier_cleanup_gui_apps
    [[ $rc -eq 0 ]] || { LAST_DETAIL="/tmp/e2e_kbd_ok not created via desktop_surface keyboard forwarding"; return 1; }
    LAST_DETAIL="Verified desktop_surface touch focus + keyboard forwarding into xterm"
}

test_t1_f9_05() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f9_05.xml" fr=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" >/dev/null 2>&1 || true
    sleep 0.6
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    sleep 1
    fr="$(e2e_verify_desktop_frame 15 2>/dev/null)" || return 1
    e2e_assert_json "$fr" "d.get('connected') is True and int(d.get('nonUniformPixels',0)) > 0" || {
        LAST_DETAIL="Desktop surface failed after tab switch: $fr"
        return 1
    }
    LAST_DETAIL="Verified SurfaceView stability across tab switching"
}

# ==============================================================================
# F10: Complete Linux UI Shell (3 Tabs) (5 tests)
# ==============================================================================
test_t1_f10_01() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f10_01.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "Ready" "status badge Ready" || { LAST_DETAIL="Top bar missing Ready badge"; return 1; }
    python3 - "$ui_xml" <<'PYEOF' || { LAST_DETAIL="Missing startup time (ms) or memory (MB/MiB/KB) badge"; return 1; }
import re, sys
t = open(sys.argv[1], "r", encoding="utf-8", errors="replace").read()
assert re.search(r"\b\d+\s*ms\b", t, re.I), "missing ms badge"
assert re.search(r"\b\d+(?:\.\d+)?\s*(?:MB|MiB|KB|KiB)\b", t, re.I), "missing memory badge"
PYEOF
    LAST_DETAIL="Verified top status bar badges (Ready, startup ms, memory)"
}

test_t1_f10_02() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f10_02.xml"
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_terminal" "Terminal" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_find_ui_node "$ui_xml" "terminal_input" "" "EditText" >/dev/null 2>&1 || { LAST_DETAIL="Terminal tab controls missing"; return 1; }
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_find_ui_node "$ui_xml" "command_input" "" "EditText" >/dev/null 2>&1 || { LAST_DETAIL="Apps tab controls missing"; return 1; }
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_find_ui_node "$ui_xml" "desktop_surface" "" "SurfaceView" >/dev/null 2>&1 || { LAST_DETAIL="Desktop tab surface missing"; return 1; }
    LAST_DETAIL="Verified 3-tab navigation (Desktop, Terminal, Apps)"
}

test_t1_f10_03() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f10_03.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "command_input" "" "EditText" || return 1
    sleep 0.2
    _e2e_adb shell input text "echo%sUI_CMD_99%s&&%suname%s-s" >/dev/null 2>&1 || return 1
    sleep 0.2
    _tier_tap_ui_node "$ui_xml" "command_run_btn" "Run" || return 1
    sleep 2
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    e2e_assert_contains "$raw_xml" "UI_CMD_99" "UI command output" || { LAST_DETAIL="UI_CMD_99 missing in command_result"; return 1; }
    e2e_assert_contains "$raw_xml" "Linux" "uname -s output" || { LAST_DETAIL="Linux missing in command_result"; return 1; }
    LAST_DETAIL="Verified Apps tab command runner output (UI_CMD_99 + Linux)"
}

test_t1_f10_04() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f10_04.xml" raw_xml=""
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "metrics_refresh_btn" "Refresh" || return 1
    sleep 1.5
    _tier_dump_ui "$ui_xml" || return 1
    raw_xml="$(cat "$ui_xml")"
    _tier_find_ui_node "$ui_xml" "metrics_summary" "MB" >/dev/null 2>&1 || {
        python3 -c 'import re,sys; assert re.search(r"(PSS|RAM|Memory|Processes|Windows)", sys.argv[1], re.I)' "$raw_xml" || {
            LAST_DETAIL="metrics_summary not populated after refresh"
            return 1
        }
    }
    LAST_DETAIL="Verified Apps tab metrics_refresh_btn & metrics_summary"
}

test_t1_f10_05() {
    _tier_require_device || return 1
    _tier_ensure_foreground || return 1
    local ui_xml="$TMP_TIER_DIR/ui_f10_05.xml"
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "tab_apps" "Apps" || return 1
    sleep 0.5
    _tier_dump_ui "$ui_xml" || return 1
    _tier_tap_ui_node "$ui_xml" "launcher_btn_xclock" "xclock" || return 1
    _tier_wait_xwindow "xclock" 6 >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    _tier_cleanup_gui_apps
    _tier_tap_ui_node "$ui_xml" "tab_desktop" "Desktop" >/dev/null 2>&1 || true
    LAST_DETAIL="Verified Apps tab launcher_btn_xclock spawned live X11 xclock"
}

# ==============================================================================
# F11: Bundled GUI & CLI Apps Out-of-Box (8 tests)
# ==============================================================================
_tier_test_gui_app() {
    local launch_cmd="$1"
    local win_pattern="$2"
    local wait_sec="${3:-6}"
    _tier_require_device || return 1
    local resp=""
    resp="$(e2e_launch_gui "$launch_cmd" 20 2>/dev/null)" || { LAST_DETAIL="e2e_launch_gui failed for $launch_cmd"; return 1; }
    e2e_assert_json "$resp" "launched" "true" || { LAST_DETAIL="launched!=true for $launch_cmd: $resp"; return 1; }
    _tier_wait_xwindow "$win_pattern" "$wait_sec" >/dev/null || { _tier_cleanup_gui_apps; return 1; }
    _tier_cleanup_gui_apps
    _tier_check_proc_budget || return 1
    LAST_DETAIL="Launched and verified X11 window for '$launch_cmd'"
}

test_t1_f11_01() { _tier_test_gui_app "xterm" "xterm" 6; }
test_t1_f11_02() { _tier_test_gui_app "xclock" "xclock" 6; }
test_t1_f11_03() { _tier_test_gui_app "xcalc" "xcalc" 6; }
test_t1_f11_04() { _tier_test_gui_app "mousepad" "mousepad" 8; }
test_t1_f11_05() { _tier_test_gui_app "galculator" "galculator" 8; }
test_t1_f11_06() { _tier_test_gui_app "pcmanfm" "pcmanfm" 8; }

test_t1_f11_07() {
    _tier_require_device || return 1
    _tier_exec_ok "python3 -c '
w,h=64,64
with open(\"/tmp/e2e_feh.ppm\",\"wb\") as f:
    f.write(f\"P6\n{w} {h}\n255\n\".encode())
    f.write(b\"\xff\x00\x80\" * (w*h))
'" 15 >/dev/null || return 1
    local rc=0
    _tier_test_gui_app "feh /tmp/e2e_feh.ppm" "feh" 6 || rc=$?
    _tier_cleanup_gui_apps
    return "$rc"
}

test_t1_f11_08() {
    _tier_require_device || return 1
    local resp="" out=""
    resp="$(_tier_exec_ok "python3 -c 'import json,zlib; print(json.dumps({\"ok\":True}))' && apk --version && apk info | head -n 5 && curl --version | head -n 1")" || return 1
    out="$(_tier_json_get "$resp" "stdout")"
    e2e_assert_contains "$out" '{"ok": true}' "python3 json" || { LAST_DETAIL="python3 output mismatch: $out"; return 1; }
    e2e_assert_contains "$out" "apk-tools" "apk-tools" || { LAST_DETAIL="apk --version missing apk-tools"; return 1; }
    e2e_assert_contains "$out" "curl" "curl" || { LAST_DETAIL="curl --version missing curl"; return 1; }
    LAST_DETAIL="Verified python3, apk-tools, and curl execution"
}

# ==============================================================================
# F12: Automation & Telemetry Hooks (5 tests)
# ==============================================================================
test_t1_f12_01() {
    _tier_require_device || return 1
    local st=""
    st="$(e2e_broadcast_status 15 2>/dev/null)" || { LAST_DETAIL="ACTION_GET_STATUS failed"; return 1; }
    e2e_assert_json "$st" "bool(d.get('reqId')) and str(d.get('state','')).lower()=='ready' and int(d.get('vncPort',0))==5901 and int(d.get('startupMs',0))>0 and int(d.get('androidPssKb',0))>0 and int(d.get('androidRssKb',0))>0 and isinstance(d.get('activeXWindows'), list)" || {
        LAST_DETAIL="ACTION_GET_STATUS missing required telemetry fields: $st"
        return 1
    }
    LAST_DETAIL="Verified ACTION_GET_STATUS schema and telemetry"
}

test_t1_f12_02() {
    _tier_require_device || return 1
    local r1="" r2="" rc=0
    r1="$(e2e_exec_linux "echo FIRST_OK" 15 2>/dev/null)" || { LAST_DETAIL="First exec failed"; return 1; }
    e2e_assert_json "$r1" "int(d.get('exitCode',-1)) == 0 and 'FIRST_OK' in str(d.get('stdout',''))" || { LAST_DETAIL="First exec mismatch: $r1"; return 1; }
    r2="$(e2e_exec_linux "echo SECOND_ERR >&2; exit 7" 15 2>/dev/null)" || rc=$?
    [[ $rc -eq 0 ]] || { LAST_DETAIL="Second exec broadcast failed"; return 1; }
    e2e_assert_json "$r2" "int(d.get('exitCode',-1)) == 7 and 'SECOND_ERR' in str(d.get('stderr','')) and int(d.get('durationMs',-1)) >= 0" || {
        LAST_DETAIL="Second exec exitCode/stderr mismatch: $r2"
        return 1
    }
    LAST_DETAIL="Verified ACTION_EXEC_LINUX exitCode, stdout/stderr, and durationMs"
}

test_t1_f12_03() {
    _tier_require_device || return 1
    local resp=""
    resp="$(e2e_launch_gui "xclock" 15 2>/dev/null)" || { LAST_DETAIL="ACTION_LAUNCH_GUI failed"; return 1; }
    _tier_cleanup_gui_apps
    e2e_assert_json "$resp" "bool(d.get('reqId')) and d.get('launched') is True and 'xclock' in str(d.get('app','')) and isinstance(d.get('xWindows'), list)" || {
        LAST_DETAIL="ACTION_LAUNCH_GUI schema mismatch: $resp"
        return 1
    }
    LAST_DETAIL="Verified ACTION_LAUNCH_GUI JSON contract"
}

test_t1_f12_04() {
    _tier_require_device || return 1
    local resp=""
    resp="$(e2e_verify_desktop_frame 15 2>/dev/null)" || { LAST_DETAIL="ACTION_VERIFY_DESKTOP_FRAME failed"; return 1; }
    e2e_assert_json "$resp" "bool(d.get('reqId')) and d.get('connected') is True and int(d.get('width',0)) > 0 and int(d.get('height',0)) > 0 and int(d.get('nonUniformPixels',0)) > 0 and len(str(d.get('sha256',''))) == 64" || {
        LAST_DETAIL="ACTION_VERIFY_DESKTOP_FRAME schema mismatch: $resp"
        return 1
    }
    LAST_DETAIL="Verified ACTION_VERIFY_DESKTOP_FRAME JSON contract"
}

test_t1_f12_05() {
    _tier_require_device || return 1
    local e2e_logs="" full_log_f="$TMP_TIER_DIR/f12_05_logcat.txt"
    e2e_logs="$(_e2e_adb logcat -d -s "${E2E_LOG_TAG}:I" 2>/dev/null | tr -d '\r' || true)"
    e2e_assert_contains "$e2e_logs" "E2E_JSON:" "logcat E2E_JSON marker" || { LAST_DETAIL="No E2E_JSON lines in logcat"; return 1; }
    _e2e_adb logcat -d 2>/dev/null | tr -d '\r' > "$full_log_f" || true
    python3 - "$full_log_f" "$PKG_ID" <<'PYEOF' || { LAST_DETAIL="Crash/ANR/SIGSEGV/PhantomProcessList hit in logcat"; return 1; }
import re, sys
with open(sys.argv[1], "r", encoding="utf-8", errors="replace") as f:
    lines = f.read().splitlines()
pkg = sys.argv[2]
pkg_re = re.compile(rf"(?<![A-Za-z0-9_.]){re.escape(pkg)}(?![A-Za-z0-9_.])")
for i, ln in enumerate(lines):
    if "FATAL EXCEPTION" in ln and pkg_re.search("\n".join(lines[i:i+6])):
        raise AssertionError(ln)
    if re.search(rf"ANR in\s+{re.escape(pkg)}", ln):
        raise AssertionError(ln)
    if re.search(rf"(?:Fatal signal \d+|SIGSEGV).*{re.escape(pkg)}", ln):
        raise AssertionError(ln)
    if re.search(rf"PhantomProcessList.*{re.escape(pkg)}", ln, re.I):
        raise AssertionError(ln)
PYEOF
    LAST_DETAIL="Verified logcat E2E_JSON lines and zero crashes/ANRs/phantom kills"
}

# Execute all 65 Tier 1 tests
_tier_run_test "T1-F1-01" 1 "F1" test_t1_f1_01
_tier_run_test "T1-F1-02" 1 "F1" test_t1_f1_02
_tier_run_test "T1-F1-03" 1 "F1" test_t1_f1_03
_tier_run_test "T1-F1-04" 1 "F1" test_t1_f1_04
_tier_run_test "T1-F1-05" 1 "F1" test_t1_f1_05

_tier_run_test "T1-F2-01" 1 "F2" test_t1_f2_01
_tier_run_test "T1-F2-02" 1 "F2" test_t1_f2_02
_tier_run_test "T1-F2-03" 1 "F2" test_t1_f2_03
_tier_run_test "T1-F2-04" 1 "F2" test_t1_f2_04
_tier_run_test "T1-F2-05" 1 "F2" test_t1_f2_05

_tier_run_test "T1-F3-01" 1 "F3" test_t1_f3_01
_tier_run_test "T1-F3-02" 1 "F3" test_t1_f3_02
_tier_run_test "T1-F3-03" 1 "F3" test_t1_f3_03
_tier_run_test "T1-F3-04" 1 "F3" test_t1_f3_04
_tier_run_test "T1-F3-05" 1 "F3" test_t1_f3_05
_tier_run_test "T1-F3-06" 1 "F3" test_t1_f3_06

_tier_run_test "T1-F4-01" 1 "F4" test_t1_f4_01
_tier_run_test "T1-F4-02" 1 "F4" test_t1_f4_02
_tier_run_test "T1-F4-03" 1 "F4" test_t1_f4_03
_tier_run_test "T1-F4-04" 1 "F4" test_t1_f4_04
_tier_run_test "T1-F4-05" 1 "F4" test_t1_f4_05

_tier_run_test "T1-F5-01" 1 "F5" test_t1_f5_01
_tier_run_test "T1-F5-02" 1 "F5" test_t1_f5_02
_tier_run_test "T1-F5-03" 1 "F5" test_t1_f5_03
_tier_run_test "T1-F5-04" 1 "F5" test_t1_f5_04
_tier_run_test "T1-F5-05" 1 "F5" test_t1_f5_05
_tier_run_test "T1-F5-06" 1 "F5" test_t1_f5_06

_tier_run_test "T1-F6-01" 1 "F6" test_t1_f6_01
_tier_run_test "T1-F6-02" 1 "F6" test_t1_f6_02
_tier_run_test "T1-F6-03" 1 "F6" test_t1_f6_03
_tier_run_test "T1-F6-04" 1 "F6" test_t1_f6_04
_tier_run_test "T1-F6-05" 1 "F6" test_t1_f6_05

_tier_run_test "T1-F7-01" 1 "F7" test_t1_f7_01
_tier_run_test "T1-F7-02" 1 "F7" test_t1_f7_02
_tier_run_test "T1-F7-03" 1 "F7" test_t1_f7_03
_tier_run_test "T1-F7-04" 1 "F7" test_t1_f7_04
_tier_run_test "T1-F7-05" 1 "F7" test_t1_f7_05

_tier_run_test "T1-F8-01" 1 "F8" test_t1_f8_01
_tier_run_test "T1-F8-02" 1 "F8" test_t1_f8_02
_tier_run_test "T1-F8-03" 1 "F8" test_t1_f8_03
_tier_run_test "T1-F8-04" 1 "F8" test_t1_f8_04
_tier_run_test "T1-F8-05" 1 "F8" test_t1_f8_05

_tier_run_test "T1-F9-01" 1 "F9" test_t1_f9_01
_tier_run_test "T1-F9-02" 1 "F9" test_t1_f9_02
_tier_run_test "T1-F9-03" 1 "F9" test_t1_f9_03
_tier_run_test "T1-F9-04" 1 "F9" test_t1_f9_04
_tier_run_test "T1-F9-05" 1 "F9" test_t1_f9_05

_tier_run_test "T1-F10-01" 1 "F10" test_t1_f10_01
_tier_run_test "T1-F10-02" 1 "F10" test_t1_f10_02
_tier_run_test "T1-F10-03" 1 "F10" test_t1_f10_03
_tier_run_test "T1-F10-04" 1 "F10" test_t1_f10_04
_tier_run_test "T1-F10-05" 1 "F10" test_t1_f10_05

_tier_run_test "T1-F11-01" 1 "F11" test_t1_f11_01
_tier_run_test "T1-F11-02" 1 "F11" test_t1_f11_02
_tier_run_test "T1-F11-03" 1 "F11" test_t1_f11_03
_tier_run_test "T1-F11-04" 1 "F11" test_t1_f11_04
_tier_run_test "T1-F11-05" 1 "F11" test_t1_f11_05
_tier_run_test "T1-F11-06" 1 "F11" test_t1_f11_06
_tier_run_test "T1-F11-07" 1 "F11" test_t1_f11_07
_tier_run_test "T1-F11-08" 1 "F11" test_t1_f11_08

_tier_run_test "T1-F12-01" 1 "F12" test_t1_f12_01
_tier_run_test "T1-F12-02" 1 "F12" test_t1_f12_02
_tier_run_test "T1-F12-03" 1 "F12" test_t1_f12_03
_tier_run_test "T1-F12-04" 1 "F12" test_t1_f12_04
_tier_run_test "T1-F12-05" 1 "F12" test_t1_f12_05

echo "[tier1] Completed 65 tests with ${TIER_FAILS} failure(s)" >&2
[[ $TIER_FAILS -eq 0 ]]
