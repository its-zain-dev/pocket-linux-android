#!/usr/bin/env bash
# Dual-channel ADB automation & assertion library for PocketLinux E2E tests.
# Zero jq dependency — uses python3 stdlib for JSON parsing, serialization, and shlex quoting.
# Designed to be sourced cleanly by scripts running with `set -euo pipefail`.

if [[ -n "${_POCKETLINUX_ADB_COMMON_SOURCED:-}" ]]; then
    return 0
fi
_POCKETLINUX_ADB_COMMON_SOURCED=1

OUT_DIR="${OUT_DIR:-e2e-reports}"
PKG_ID="${PKG_ID:-com.pocketlinux}"
E2E_LOG_TAG="${E2E_LOG_TAG:-PocketLinuxE2E}"
E2E_BROADCAST_TIMEOUT_SEC="${E2E_BROADCAST_TIMEOUT_SEC:-35}"
ADB_SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
E2E_ALLOW_NO_DEVICE="${E2E_ALLOW_NO_DEVICE:-0}"

export OUT_DIR PKG_ID E2E_LOG_TAG E2E_BROADCAST_TIMEOUT_SEC ADB_SERIAL E2E_ALLOW_NO_DEVICE

_e2e_adb() {
    if [[ -n "${ADB_SERIAL:-}" ]]; then
        adb -s "$ADB_SERIAL" "$@"
    else
        adb "$@"
    fi
}

_e2e_now_ms() {
    python3 -c 'import time; print(int(time.time() * 1000))'
}

_e2e_next_seq() {
    local out_dir="${OUT_DIR:-e2e-reports}"
    mkdir -p "$out_dir"
    python3 - "$out_dir" <<'PYEOF'
import os
import sys

out_dir = sys.argv[1]
seq_path = os.path.join(out_dir, ".seq")
lock_path = os.path.join(out_dir, ".seq.lock")

try:
    import fcntl
except ImportError:
    fcntl = None

if fcntl is not None:
    with open(lock_path, "a+", encoding="utf-8") as lf:
        fcntl.flock(lf.fileno(), fcntl.LOCK_EX)
        try:
            cur = 0
            if os.path.exists(seq_path):
                with open(seq_path, "r", encoding="utf-8") as sf:
                    raw = sf.read().strip()
                    if raw.isdigit():
                        cur = int(raw)
            nxt = cur + 1
            tmp_path = f"{seq_path}.tmp.{os.getpid()}"
            with open(tmp_path, "w", encoding="utf-8") as tf:
                tf.write(f"{nxt}\n")
            os.replace(tmp_path, seq_path)
            print(nxt)
        finally:
            fcntl.flock(lf.fileno(), fcntl.LOCK_UN)
else:
    cur = 0
    if os.path.exists(seq_path):
        with open(seq_path, "r", encoding="utf-8") as sf:
            raw = sf.read().strip()
            if raw.isdigit():
                cur = int(raw)
    nxt = cur + 1
    with open(seq_path, "w", encoding="utf-8") as sf:
        sf.write(f"{nxt}\n")
    print(nxt)
PYEOF
}

_e2e_sanitize_out_file() {
    python3 - "$1" <<'PYEOF'
import re
import sys

raw = sys.argv[1]
base = raw.replace("/", "_").replace("\\", "_")
base = re.sub(r"\.\.+", "_", base)
base = re.sub(r"[^A-Za-z0-9._-]+", "_", base).strip("._-")
if not base:
    base = "e2e_action"
if len(base) > 64:
    base = base[:64].rstrip("._-")
if not base.endswith(".json"):
    base = f"{base}.json"
assert "/" not in base and ".." not in base
print(base)
PYEOF
}

_e2e_build_broadcast_cmd() {
    python3 - "$@" <<'PYEOF'
import shlex
import sys

component, action, req_id, out_file = sys.argv[1:5]
extras = sys.argv[5:]
if len(extras) % 2 != 0:
    raise SystemExit("Internal error: odd number of broadcast extra key/value args")

parts = [
    "am",
    "broadcast",
    "-n",
    component,
    "-a",
    action,
    "--es",
    "req_id",
    req_id,
    "--es",
    "out_file",
    out_file,
]
for i in range(0, len(extras), 2):
    parts.extend(["--es", extras[i], extras[i + 1]])

print(" ".join(shlex.quote(p) for p in parts))
PYEOF
}

_e2e_validate_json_response() {
    local raw_json="$1"
    local expected_req_id="$2"
    local require_req_id="${3:-0}"
    python3 - "$raw_json" "$expected_req_id" "$require_req_id" <<'PYEOF'
import json
import sys

raw, expected_req_id, require_req_id = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
raw = raw.strip()
if not raw:
    sys.exit(1)
try:
    data = json.loads(raw)
except Exception:
    sys.exit(1)
if not isinstance(data, dict):
    sys.exit(1)

req_id = data.get("reqId")
if require_req_id:
    if req_id != expected_req_id:
        sys.exit(1)
elif req_id not in (None, "") and req_id != expected_req_id:
    sys.exit(1)

print(json.dumps(data, separators=(",", ":")))
PYEOF
}

_e2e_extract_logcat_json() {
    local log_text="$1"
    local action="$2"
    local expected_req_id="$3"
    local tag="${E2E_LOG_TAG:-PocketLinuxE2E}"
    python3 - "$log_text" "$action" "$expected_req_id" "$tag" <<'PYEOF'
import json
import re
import sys

log_text, action, expected_req_id, tag = sys.argv[1:5]
short_action = action.rsplit(".", 1)[-1]
pattern = re.compile(
    r"(?:\[" + re.escape(tag) + r"\]\s+)?E2E_JSON:([^:\s]+):([^:\s]+):(\{.*\})\s*$"
)

for line in reversed(log_text.splitlines()):
    m = pattern.search(line)
    if not m:
        continue
    act, req_id, payload = m.group(1), m.group(2), m.group(3)
    if act not in (action, short_action):
        continue
    if req_id != expected_req_id:
        continue
    try:
        data = json.loads(payload)
    except Exception:
        continue
    if isinstance(data, dict):
        if data.get("reqId") not in (None, "") and data.get("reqId") != expected_req_id:
            continue
        print(json.dumps(data, separators=(",", ":")))
        sys.exit(0)

sys.exit(1)
PYEOF
}

_e2e_broadcast_and_wait() {
    local action="$1"
    local timeout_sec="${2:-${E2E_BROADCAST_TIMEOUT_SEC:-35}}"
    shift 2 || true

    local pkg="${PKG_ID:-com.pocketlinux}"
    local component="${pkg}/.automation.AutomationReceiver"
    local seq
    seq="$(_e2e_next_seq)"
    local ts
    ts="$(date -u +%Y%m%dT%H%M%S)"
    local short_action="${action##*.}"
    local req_id="e2e_${ts}_${seq}"
    local out_file
    out_file="$(_e2e_sanitize_out_file "${short_action}_${seq}")"

    # Best-effort cleanup of target diagnostics file before sending broadcast.
    _e2e_adb shell "run-as ${pkg} rm -f files/diagnostics/${out_file}" >/dev/null 2>&1 || true

    local remote_cmd
    remote_cmd="$(_e2e_build_broadcast_cmd "$component" "$action" "$req_id" "$out_file" "$@")"

    local bcast_out=""
    local bcast_rc=0
    bcast_out="$(_e2e_adb shell "$remote_cmd" 2>&1)" || bcast_rc=$?
    if [[ $bcast_rc -ne 0 ]]; then
        echo "[adb_common] ERROR: adb shell am broadcast failed (rc=$bcast_rc) for $action: $bcast_out" >&2
        return 1
    fi
    if [[ "$bcast_out" == *"Error:"* || "$bcast_out" == *"Exception"* ]]; then
        echo "[adb_common] ERROR: am broadcast returned error for $action: $bcast_out" >&2
        return 1
    fi

    local now_ms deadline_ms
    now_ms="$(_e2e_now_ms)"
    deadline_ms=$((now_ms + timeout_sec * 1000))

    local raw validated log_dump
    while true; do
        # Channel 1: Dedicated per-request diagnostics file via run-as
        raw="$(_e2e_adb shell "run-as ${pkg} cat files/diagnostics/${out_file}" 2>/dev/null | tr -d '\r' || true)"
        if [[ -n "$raw" ]]; then
            if validated="$(_e2e_validate_json_response "$raw" "$req_id" 0 2>/dev/null)"; then
                printf '%s\n' "$validated"
                return 0
            fi
        fi

        # Channel 2: Secondary file fallback (last_action.json), accepted ONLY on matching reqId
        raw="$(_e2e_adb shell "run-as ${pkg} cat files/diagnostics/last_action.json" 2>/dev/null | tr -d '\r' || true)"
        if [[ -n "$raw" ]]; then
            if validated="$(_e2e_validate_json_response "$raw" "$req_id" 1 2>/dev/null)"; then
                printf '%s\n' "$validated"
                return 0
            fi
        fi

        # Channel 3: Logcat fallback for non-debuggable / release APKs
        log_dump="$(_e2e_adb logcat -d -s "${E2E_LOG_TAG}:I" 2>/dev/null | tr -d '\r' || true)"
        if [[ -n "$log_dump" ]]; then
            if validated="$(_e2e_extract_logcat_json "$log_dump" "$action" "$req_id" 2>/dev/null)"; then
                printf '%s\n' "$validated"
                return 0
            fi
        fi

        now_ms="$(_e2e_now_ms)"
        if [[ $now_ms -ge $deadline_ms ]]; then
            echo "[adb_common] ERROR: Timed out after ${timeout_sec}s waiting for $action (req_id=$req_id, out_file=$out_file)" >&2
            echo "[adb_common] Broadcast output: ${bcast_out:-<empty>}" >&2
            local runas_diag
            runas_diag="$(_e2e_adb shell "run-as ${pkg} ls -la files/diagnostics" 2>&1 | tr -d '\r' || true)"
            echo "[adb_common] diagnostics dir: ${runas_diag:-<unavailable>}" >&2
            if [[ -n "$log_dump" ]]; then
                echo "[adb_common] Recent ${E2E_LOG_TAG} logcat:" >&2
                printf '%s\n' "$log_dump" | tail -n 10 >&2
            fi
            return 1
        fi

        python3 -c 'import time; time.sleep(0.25)'
    done
}

e2e_init() {
    if [[ $# -ge 1 && -n "${1:-}" ]]; then
        OUT_DIR="$1"
    else
        OUT_DIR="${OUT_DIR:-e2e-reports}"
    fi
    PKG_ID="${PKG_ID:-com.pocketlinux}"
    E2E_LOG_TAG="${E2E_LOG_TAG:-PocketLinuxE2E}"
    E2E_BROADCAST_TIMEOUT_SEC="${E2E_BROADCAST_TIMEOUT_SEC:-35}"
    ADB_SERIAL="${ADB_SERIAL:-${ANDROID_SERIAL:-}}"
    export OUT_DIR PKG_ID E2E_LOG_TAG E2E_BROADCAST_TIMEOUT_SEC ADB_SERIAL

    mkdir -p "$OUT_DIR"
    touch "$OUT_DIR/tier_results.jsonl"
    if [[ ! -f "$OUT_DIR/.seq" ]]; then
        printf '0\n' > "$OUT_DIR/.seq"
    fi

    if [[ "${E2E_ALLOW_NO_DEVICE:-0}" != "1" ]]; then
        if ! command -v adb >/dev/null 2>&1; then
            echo "[adb_common] ERROR: adb not found in PATH (set E2E_ALLOW_NO_DEVICE=1 for offline tests)" >&2
            return 1
        fi
        local state
        state="$(_e2e_adb get-state 2>/dev/null | tr -d '\r' || true)"
        if [[ "$state" != "device" ]]; then
            echo "[adb_common] ERROR: Expected adb device state 'device', got '${state:-none}'" >&2
            return 1
        fi
    fi
}

e2e_broadcast_status() {
    local timeout_sec="${1:-${E2E_BROADCAST_TIMEOUT_SEC:-35}}"
    _e2e_broadcast_and_wait "${PKG_ID:-com.pocketlinux}.ACTION_GET_STATUS" "$timeout_sec"
}

e2e_exec_linux() {
    if [[ $# -lt 1 ]]; then
        echo "[adb_common] ERROR: e2e_exec_linux requires <command>" >&2
        return 2
    fi
    local command_str="$1"
    local timeout_sec="${2:-${E2E_BROADCAST_TIMEOUT_SEC:-35}}"
    _e2e_broadcast_and_wait "${PKG_ID:-com.pocketlinux}.ACTION_EXEC_LINUX" "$timeout_sec" "command" "$command_str"
}

e2e_launch_gui() {
    if [[ $# -lt 1 ]]; then
        echo "[adb_common] ERROR: e2e_launch_gui requires <app>" >&2
        return 2
    fi
    local app_name="$1"
    local timeout_sec="${2:-${E2E_BROADCAST_TIMEOUT_SEC:-35}}"
    _e2e_broadcast_and_wait "${PKG_ID:-com.pocketlinux}.ACTION_LAUNCH_GUI" "$timeout_sec" "app" "$app_name"
}

e2e_verify_desktop_frame() {
    local timeout_sec="${1:-${E2E_BROADCAST_TIMEOUT_SEC:-35}}"
    _e2e_broadcast_and_wait "${PKG_ID:-com.pocketlinux}.ACTION_VERIFY_DESKTOP_FRAME" "$timeout_sec"
}

e2e_record_result() {
    if [[ $# -lt 6 ]]; then
        echo "[adb_common] ERROR: e2e_record_result requires <id> <tier> <feature> <status> <duration_ms> <detail>" >&2
        return 2
    fi
    local test_id="$1"
    local tier="$2"
    local feature="$3"
    local status="$4"
    local duration_ms="$5"
    local detail="$6"
    local out_dir="${OUT_DIR:-e2e-reports}"
    mkdir -p "$out_dir"

    python3 - "$out_dir/tier_results.jsonl" "$test_id" "$tier" "$feature" "$status" "$duration_ms" "$detail" <<'PYEOF'
import json
import os
import sys

jsonl_path, test_id, tier_raw, feature, status, dur_raw, detail = sys.argv[1:8]
try:
    tier = int(tier_raw)
except ValueError:
    raise SystemExit(f"[adb_common] Invalid tier (must be integer 1..4): {tier_raw!r}")
if tier < 1 or tier > 4:
    raise SystemExit(f"[adb_common] Invalid tier (must be 1..4): {tier}")

if status not in ("PASS", "FAIL"):
    raise SystemExit(f"[adb_common] Invalid status (must be PASS or FAIL): {status!r}")

try:
    duration_ms = int(float(dur_raw))
except ValueError:
    raise SystemExit(f"[adb_common] Invalid duration_ms: {dur_raw!r}")
if duration_ms < 0:
    duration_ms = 0

record = {
    "id": test_id,
    "tier": tier,
    "feature": feature,
    "status": status,
    "durationMs": duration_ms,
    "detail": detail,
}
line = json.dumps(record, separators=(",", ":")) + "\n"

try:
    import fcntl
except ImportError:
    fcntl = None

with open(jsonl_path, "a", encoding="utf-8") as f:
    if fcntl is not None:
        fcntl.flock(f.fileno(), fcntl.LOCK_EX)
    try:
        f.write(line)
        f.flush()
    finally:
        if fcntl is not None:
            fcntl.flock(f.fileno(), fcntl.LOCK_UN)

print(f"[E2E][T{tier}][{feature}] {test_id}: {status} ({duration_ms}ms) - {detail}", file=sys.stderr)
PYEOF
}

e2e_assert_eq() {
    local actual="${1:-}"
    local expected="${2:-}"
    local msg="${3:-mismatch}"
    if [[ "$actual" == "$expected" ]]; then
        return 0
    fi
    echo "[adb_common] ASSERT_EQ FAIL (${msg}): expected='${expected}' actual='${actual}'" >&2
    return 1
}

e2e_assert_contains() {
    local haystack="${1:-}"
    local needle="${2:-}"
    local msg="${3:-missing substring}"
    if [[ "$haystack" == *"$needle"* ]]; then
        return 0
    fi
    echo "[adb_common] ASSERT_CONTAINS FAIL (${msg}): needle='${needle}'" >&2
    return 1
}

e2e_assert_json() {
    if [[ $# -lt 2 ]]; then
        echo "[adb_common] ERROR: e2e_assert_json requires <json_str> <expr_or_key> [expected_val]" >&2
        return 2
    fi
    local json_str="$1"
    local expr_or_key="$2"
    local has_expected="0"
    local expected_val=""
    if [[ $# -ge 3 ]]; then
        has_expected="1"
        expected_val="$3"
    fi

    python3 - "$json_str" "$expr_or_key" "$has_expected" "$expected_val" <<'PYEOF'
import json
import re
import sys

json_str, expr_or_key, has_expected, expected_val = sys.argv[1:5]

try:
    data = json.loads(json_str)
except Exception as exc:
    print(f"[adb_common] ASSERT_JSON FAIL: invalid JSON ({exc})", file=sys.stderr)
    sys.exit(1)

if has_expected == "0":
    safe_builtins = {
        "len": len,
        "str": str,
        "int": int,
        "float": float,
        "bool": bool,
        "list": list,
        "dict": dict,
        "set": set,
        "tuple": tuple,
        "any": any,
        "all": all,
        "min": min,
        "max": max,
        "sum": sum,
        "isinstance": isinstance,
        "True": True,
        "False": False,
        "None": None,
    }
    try:
        result = eval(expr_or_key, {"__builtins__": safe_builtins}, {"data": data, "d": data})
    except Exception as exc:
        print(f"[adb_common] ASSERT_JSON FAIL: expression error ({expr_or_key!r}): {exc}", file=sys.stderr)
        sys.exit(1)
    if not bool(result):
        print(f"[adb_common] ASSERT_JSON FAIL: expression falsy ({expr_or_key!r}) on {json_str[:240]}", file=sys.stderr)
        sys.exit(1)
    sys.exit(0)

# 3-arg dot/index path traversal
cur = data
tokens = re.findall(r"[^.\[\]]+|\[\d+\]", expr_or_key)
if not tokens:
    print(f"[adb_common] ASSERT_JSON FAIL: empty key path", file=sys.stderr)
    sys.exit(1)

for tok in tokens:
    if tok.startswith("[") and tok.endswith("]"):
        idx = int(tok[1:-1])
        if not isinstance(cur, list) or idx < 0 or idx >= len(cur):
            print(f"[adb_common] ASSERT_JSON FAIL: index {tok} out of range for path {expr_or_key!r}", file=sys.stderr)
            sys.exit(1)
        cur = cur[idx]
    else:
        if isinstance(cur, dict) and tok in cur:
            cur = cur[tok]
        elif isinstance(cur, list) and tok.isdigit():
            idx = int(tok)
            if idx < 0 or idx >= len(cur):
                print(f"[adb_common] ASSERT_JSON FAIL: index [{idx}] out of range for path {expr_or_key!r}", file=sys.stderr)
                sys.exit(1)
            cur = cur[idx]
        else:
            print(f"[adb_common] ASSERT_JSON FAIL: key {tok!r} missing for path {expr_or_key!r}", file=sys.stderr)
            sys.exit(1)

def norm_val(v):
    if isinstance(v, bool):
        return "true" if v else "false"
    if v is None:
        return "null"
    return str(v)

def norm_expected(s):
    if s == "True":
        return "true"
    if s == "False":
        return "false"
    return s

actual_str = norm_val(cur)
exp_str = norm_expected(expected_val)
if actual_str != exp_str:
    print(
        f"[adb_common] ASSERT_JSON FAIL ({expr_or_key}): expected={exp_str!r} actual={actual_str!r}",
        file=sys.stderr,
    )
    sys.exit(1)
sys.exit(0)
PYEOF
}

export -f \
    _e2e_adb \
    _e2e_now_ms \
    _e2e_next_seq \
    _e2e_sanitize_out_file \
    _e2e_build_broadcast_cmd \
    _e2e_validate_json_response \
    _e2e_extract_logcat_json \
    _e2e_broadcast_and_wait \
    e2e_init \
    e2e_broadcast_status \
    e2e_exec_linux \
    e2e_launch_gui \
    e2e_verify_desktop_frame \
    e2e_record_result \
    e2e_assert_eq \
    e2e_assert_contains \
    e2e_assert_json
