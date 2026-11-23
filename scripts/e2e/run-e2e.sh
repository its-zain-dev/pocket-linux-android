#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/e2e/lib/adb_common.sh
source "$SCRIPT_DIR/lib/adb_common.sh"

APK_PATH=""
ARM64_APK=""
ABI=""
TIER="all"
OUT_DIR="${OUT_DIR:-e2e-reports}"
SKIP_INSTALL=false
SKIP_METRICS=false
E2E_BOOT_TIMEOUT_SEC="${E2E_BOOT_TIMEOUT_SEC:-120}"

usage() {
    cat <<'EOF'
Usage: run-e2e.sh [options]

Main host/ADB E2E test runner for PocketLinux Android.
Verifies APKs, prepares emulator/device, boots the app to Ready state,
runs modular tier suites (tier1..tier4), collects metrics, and emits
consolidated e2e-summary.json & e2e-summary.md reports.

Options:
  --apk <path>          Primary APK to verify and install (e.g. x86_64 or arm64)
  --arm64-apk <path>    Optional arm64 APK path for static dual-ABI verification
  --abi <x86_64|arm64>  Target ABI (auto-detected from device if omitted)
  --tier <spec>         Tiers to execute: all | 1 | 2 | 3 | 4 | none (default: all)
  --out-dir <dir>       Output directory for reports (default: e2e-reports)
  --skip-install        Skip adb install (still verifies APKs if provided)
  --skip-metrics        Skip collect_metrics.sh execution
  --help                Show this help message and exit
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk)
            if [[ $# -lt 2 ]]; then
                echo "[run-e2e] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            APK_PATH="$2"
            shift 2
            ;;
        --arm64-apk)
            if [[ $# -lt 2 ]]; then
                echo "[run-e2e] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            ARM64_APK="$2"
            shift 2
            ;;
        --abi)
            if [[ $# -lt 2 ]]; then
                echo "[run-e2e] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            ABI="$2"
            shift 2
            ;;
        --tier)
            if [[ $# -lt 2 ]]; then
                echo "[run-e2e] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            TIER="$2"
            shift 2
            ;;
        --out-dir)
            if [[ $# -lt 2 ]]; then
                echo "[run-e2e] ERROR: Option '$1' requires a value" >&2
                usage >&2
                exit 2
            fi
            OUT_DIR="$2"
            shift 2
            ;;
        --skip-install)
            SKIP_INSTALL=true
            shift
            ;;
        --skip-metrics)
            SKIP_METRICS=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "[run-e2e] ERROR: Unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if [[ -n "$ABI" && "$ABI" != "x86_64" && "$ABI" != "arm64" ]]; then
    echo "[run-e2e] ERROR: Invalid --abi '$ABI' (expected x86_64 or arm64)" >&2
    exit 2
fi

REQUESTED_TIERS=()
case "$TIER" in
    all)
        REQUESTED_TIERS=(1 2 3 4)
        ;;
    none)
        REQUESTED_TIERS=()
        ;;
    1|2|3|4)
        REQUESTED_TIERS=("$TIER")
        ;;
    *,*)
        IFS=',' read -ra _tier_parts <<< "$TIER"
        for t in "${_tier_parts[@]}"; do
            if [[ "$t" != "1" && "$t" != "2" && "$t" != "3" && "$t" != "4" ]]; then
                echo "[run-e2e] ERROR: Invalid tier '$t' in --tier '$TIER'" >&2
                exit 2
            fi
            REQUESTED_TIERS+=("$t")
        done
        ;;
    *)
        echo "[run-e2e] ERROR: Invalid --tier '$TIER' (expected all|1|2|3|4|none)" >&2
        exit 2
        ;;
esac

mkdir -p "$OUT_DIR"
: > "$OUT_DIR/tier_results.jsonl"

e2e_init "$OUT_DIR"

RUN_START_MS="$(_e2e_now_ms)"

# 1. Wait for device & boot completion
echo "[run-e2e] Waiting for ADB device and Android boot completion..."
_e2e_adb wait-for-device

boot_deadline=$(( $(date +%s) + 120 ))
while true; do
    boot_completed="$(_e2e_adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r[:space:]' || true)"
    if [[ "$boot_completed" == "1" ]]; then
        break
    fi
    if [[ $(date +%s) -ge $boot_deadline ]]; then
        echo "[run-e2e] ERROR: Timed out waiting for sys.boot_completed == 1" >&2
        exit 1
    fi
    sleep 2
done

DEVICE_ABI="$(_e2e_adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r[:space:]' || echo "")"
API_LEVEL="$(_e2e_adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r[:space:]' || echo "0")"
[[ "$API_LEVEL" =~ ^[0-9]+$ ]] || API_LEVEL=0

if [[ -z "$ABI" ]]; then
    if [[ "$DEVICE_ABI" == arm64* ]]; then
        ABI="arm64"
    elif [[ "$DEVICE_ABI" == x86_64* ]]; then
        ABI="x86_64"
    else
        echo "[run-e2e] ERROR: Could not map device ABI '$DEVICE_ABI' to arm64 or x86_64; pass --abi" >&2
        exit 1
    fi
fi

if [[ "$ABI" == "arm64" && "$DEVICE_ABI" != arm64* ]]; then
    echo "[run-e2e] WARN: Requested ABI arm64 differs from device ABI '$DEVICE_ABI'" >&2
elif [[ "$ABI" == "x86_64" && "$DEVICE_ABI" != x86_64* ]]; then
    echo "[run-e2e] WARN: Requested ABI x86_64 differs from device ABI '$DEVICE_ABI'" >&2
fi

X86_64_APK=""
if [[ "$ABI" == "x86_64" && -n "$APK_PATH" ]]; then
    X86_64_APK="$APK_PATH"
elif [[ "$ABI" == "arm64" && -n "$APK_PATH" && -z "$ARM64_APK" ]]; then
    ARM64_APK="$APK_PATH"
fi

# 2. Static APK verification (when APKs provided)
APK_VERIFY_RC=0
if [[ -n "$APK_PATH" || -n "$ARM64_APK" ]]; then
    verify_args=(
        "--out-json" "$OUT_DIR/apk-verification.json"
        "--out-md" "$OUT_DIR/apk-verification.md"
    )
    if [[ -n "$X86_64_APK" ]]; then
        verify_args+=("--x86-64-apk" "$X86_64_APK")
    fi
    if [[ -n "$ARM64_APK" ]]; then
        verify_args+=("--arm64-apk" "$ARM64_APK")
    fi
    echo "[run-e2e] Running static APK verification..."
    if ! python3 "$SCRIPT_DIR/verify_apks.py" "${verify_args[@]}"; then
        APK_VERIFY_RC=1
        echo "[run-e2e] ERROR: verify_apks.py reported FAIL" >&2
    fi
else
    echo "[run-e2e] No --apk or --arm64-apk provided; skipping verify_apks.py"
fi

# 3. Disable animations & wake screen
echo "[run-e2e] Preparing device settings (animations off, wake/unlock)..."
_e2e_adb shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
_e2e_adb shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
_e2e_adb shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
_e2e_adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
_e2e_adb shell wm dismiss-keyguard >/dev/null 2>&1 || true

# 4. Install APK & grant runtime permissions
if [[ "$SKIP_INSTALL" == false ]]; then
    if [[ -z "$APK_PATH" ]]; then
        echo "[run-e2e] ERROR: --apk is required unless --skip-install is passed" >&2
        exit 2
    fi
    if [[ ! -f "$APK_PATH" ]]; then
        echo "[run-e2e] ERROR: APK file not found: $APK_PATH" >&2
        exit 1
    fi
    echo "[run-e2e] Installing $APK_PATH..."
    if ! _e2e_adb install -r -t -g "$APK_PATH" >/dev/null 2>&1; then
        _e2e_adb install -r -t "$APK_PATH"
    fi
fi

if [[ "$API_LEVEL" -ge 33 ]]; then
    _e2e_adb shell pm grant "$PKG_ID" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
fi

# 5. Cold launch & Ready polling
echo "[run-e2e] Clearing logcat once and launching ${PKG_ID}/.ui.MainActivity..."
_e2e_adb logcat -c >/dev/null 2>&1 || true

BOOT_RC=0
BOOT_WALL_MS=0
BOOT_DETAIL="not started"
boot_t0="$(_e2e_now_ms)"
if ! _e2e_adb shell am start -W -n "${PKG_ID}/.ui.MainActivity" >/dev/null; then
    BOOT_RC=1
    BOOT_DETAIL="am start -W failed"
    echo "[run-e2e] ERROR: Failed to start ${PKG_ID}/.ui.MainActivity" >&2
else
    boot_deadline_ms=$((boot_t0 + E2E_BOOT_TIMEOUT_SEC * 1000))
    while true; do
        if status_json="$(e2e_broadcast_status 10 2>/dev/null)"; then
            state_val="$(python3 -c 'import json,sys; print(str(json.loads(sys.argv[1]).get("state","")).strip().lower())' "$status_json" 2>/dev/null || echo "")"
            if [[ "$state_val" == "ready" ]]; then
                boot_t1="$(_e2e_now_ms)"
                BOOT_WALL_MS=$((boot_t1 - boot_t0))
                BOOT_DETAIL="Ready in ${BOOT_WALL_MS}ms"
                echo "[run-e2e] PocketLinux runtime Ready (${BOOT_WALL_MS}ms)"
                break
            elif [[ "$state_val" == "error" ]]; then
                BOOT_RC=1
                BOOT_DETAIL="Runtime reported Error state: $status_json"
                echo "[run-e2e] ERROR: $BOOT_DETAIL" >&2
                break
            fi
        fi
        if [[ $(_e2e_now_ms) -ge $boot_deadline_ms ]]; then
            BOOT_RC=1
            BOOT_DETAIL="Timed out after ${E2E_BOOT_TIMEOUT_SEC}s waiting for state==Ready"
            echo "[run-e2e] ERROR: $BOOT_DETAIL" >&2
            break
        fi
        sleep 1
    done
fi

# 6. Modular tier execution
export OUT_DIR PKG_ID ABI ARM64_APK X86_64_APK ADB_SERIAL

TIER_FAILURES=0
SKIPPED_TIERS=()
EXECUTED_TIERS=()

tier_script_for() {
    case "$1" in
        1) echo "$SCRIPT_DIR/tiers/tier1_features.sh" ;;
        2) echo "$SCRIPT_DIR/tiers/tier2_boundaries.sh" ;;
        3) echo "$SCRIPT_DIR/tiers/tier3_pairwise.sh" ;;
        4) echo "$SCRIPT_DIR/tiers/tier4_scenarios.sh" ;;
    esac
}

if [[ $BOOT_RC -ne 0 ]]; then
    for t in "${REQUESTED_TIERS[@]}"; do
        echo "[run-e2e] [WARN] Skipping tier $t because initial app boot failed" >&2
        SKIPPED_TIERS+=("$t")
    done
else
    for t in "${REQUESTED_TIERS[@]}"; do
        tier_script="$(tier_script_for "$t")"
        if [[ -f "$tier_script" ]]; then
            echo "[run-e2e] Executing Tier $t: $tier_script"
            EXECUTED_TIERS+=("$t")
            if ! bash "$tier_script"; then
                TIER_FAILURES=$((TIER_FAILURES + 1))
                echo "[run-e2e] ERROR: Tier $t exited with non-zero status" >&2
            fi
        else
            echo "[run-e2e] [WARN] Tier $t script not found ($tier_script); skipping" >&2
            SKIPPED_TIERS+=("$t")
        fi
    done
fi

# 7. Metrics & logcat audit
METRICS_RC=0
if [[ "$SKIP_METRICS" == false ]]; then
    echo "[run-e2e] Collecting metrics and auditing logcat..."
    metrics_args=("--out-dir" "$OUT_DIR" "--abi" "$ABI")
    if [[ -n "$APK_PATH" ]]; then
        metrics_args+=("--apk" "$APK_PATH")
    fi
    if [[ -n "$ARM64_APK" ]]; then
        metrics_args+=("--arm64-apk" "$ARM64_APK")
    fi
    if [[ $BOOT_RC -ne 0 ]]; then
        metrics_args+=("--skip-lifecycle")
    fi
    if ! bash "$SCRIPT_DIR/collect_metrics.sh" "${metrics_args[@]}"; then
        METRICS_RC=1
        echo "[run-e2e] ERROR: collect_metrics.sh failed a metric gate" >&2
    fi
else
    echo "[run-e2e] Skipping collect_metrics.sh (--skip-metrics)"
fi

RUN_END_MS="$(_e2e_now_ms)"
TOTAL_WALL_MS=$((RUN_END_MS - RUN_START_MS))

# 8. Consolidated summary generation
SKIPPED_CSV=""
if [[ ${#SKIPPED_TIERS[@]} -gt 0 ]]; then
    SKIPPED_CSV="$(IFS=,; echo "${SKIPPED_TIERS[*]}")"
fi
EXECUTED_CSV=""
if [[ ${#EXECUTED_TIERS[@]} -gt 0 ]]; then
    EXECUTED_CSV="$(IFS=,; echo "${EXECUTED_TIERS[*]}")"
fi

export OUT_DIR PKG_ID ABI API_LEVEL DEVICE_ABI APK_VERIFY_RC BOOT_RC BOOT_WALL_MS BOOT_DETAIL
export TIER_FAILURES METRICS_RC SKIP_METRICS SKIPPED_CSV EXECUTED_CSV TOTAL_WALL_MS

python3 - <<'PYEOF'
from collections import defaultdict
from datetime import datetime, timezone
import json
import os
import sys

out_dir = os.environ["OUT_DIR"]
jsonl_path = os.path.join(out_dir, "tier_results.jsonl")
metrics_path = os.path.join(out_dir, "metrics.json")
apk_verify_path = os.path.join(out_dir, "apk-verification.json")

apk_verify_rc = int(os.environ["APK_VERIFY_RC"])
boot_rc = int(os.environ["BOOT_RC"])
boot_wall_ms = int(os.environ["BOOT_WALL_MS"])
boot_detail = os.environ["BOOT_DETAIL"]
tier_failures = int(os.environ["TIER_FAILURES"])
metrics_rc = int(os.environ["METRICS_RC"])
skip_metrics = os.environ["SKIP_METRICS"] == "true"
total_wall_ms = int(os.environ["TOTAL_WALL_MS"])

skipped_tiers = [int(x) for x in os.environ.get("SKIPPED_CSV", "").split(",") if x.strip()]
executed_tiers = [int(x) for x in os.environ.get("EXECUTED_CSV", "").split(",") if x.strip()]

records = []
if os.path.isfile(jsonl_path):
    with open(jsonl_path, "r", encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if not s:
                continue
            try:
                records.append(json.loads(s))
            except Exception:
                pass

passed = sum(1 for r in records if r.get("status") == "PASS")
failed = sum(1 for r in records if r.get("status") != "PASS")
total_tests = len(records)

by_tier = defaultdict(lambda: {"total": 0, "passed": 0, "failed": 0})
by_feature = defaultdict(lambda: {"total": 0, "passed": 0, "failed": 0})

for r in records:
    t = str(r.get("tier", "unknown"))
    feat = str(r.get("feature", "unknown"))
    is_pass = r.get("status") == "PASS"
    by_tier[t]["total"] += 1
    by_feature[feat]["total"] += 1
    if is_pass:
        by_tier[t]["passed"] += 1
        by_feature[feat]["passed"] += 1
    else:
        by_tier[t]["failed"] += 1
        by_feature[feat]["failed"] += 1

metrics_summary = None
if os.path.isfile(metrics_path):
    try:
        with open(metrics_path, "r", encoding="utf-8") as f:
            metrics_summary = json.load(f)
    except Exception:
        metrics_summary = None

apk_summary = None
if os.path.isfile(apk_verify_path):
    try:
        with open(apk_verify_path, "r", encoding="utf-8") as f:
            apk_summary = json.load(f)
    except Exception:
        apk_summary = None

overall_pass = (
    apk_verify_rc == 0
    and boot_rc == 0
    and tier_failures == 0
    and metrics_rc == 0
    and failed == 0
)

summary = {
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "packageId": os.environ["PKG_ID"],
    "abi": os.environ["ABI"],
    "device": {
        "apiLevel": int(os.environ.get("API_LEVEL") or 0),
        "abi": os.environ.get("DEVICE_ABI") or "",
    },
    "overallPass": overall_pass,
    "overallStatus": "PASS" if overall_pass else "FAIL",
    "totalWallMs": total_wall_ms,
    "gates": {
        "apkVerification": {
            "passed": apk_verify_rc == 0,
            "status": (apk_summary or {}).get("overallStatus", "SKIPPED" if apk_verify_rc == 0 else "FAIL"),
        },
        "runtimeBoot": {
            "passed": boot_rc == 0,
            "wallMs": boot_wall_ms,
            "detail": boot_detail,
        },
        "tierSuites": {
            "passed": tier_failures == 0 and failed == 0,
            "executedTiers": executed_tiers,
            "skippedTiers": skipped_tiers,
            "tierScriptFailures": tier_failures,
        },
        "metrics": {
            "passed": metrics_rc == 0,
            "status": "SKIPPED" if skip_metrics else (metrics_summary or {}).get("overallStatus", "FAIL" if metrics_rc != 0 else "UNKNOWN"),
        },
    },
    "testCounts": {
        "totalTests": total_tests,
        "passed": passed,
        "failed": failed,
        "byTier": dict(sorted(by_tier.items())),
        "byFeature": dict(sorted(by_feature.items())),
    },
}

summary_json_path = os.path.join(out_dir, "e2e-summary.json")
with open(summary_json_path, "w", encoding="utf-8") as f:
    json.dump(summary, f, indent=2)
    f.write("\n")

md = [
    "# PocketLinux E2E Summary Report",
    "",
    f"- **Generated At**: `{summary['generatedAt']}`",
    f"- **Overall Result**: **{'PASS' if overall_pass else 'FAIL'}**",
    f"- **Target**: `{summary['packageId']}` (`{summary['abi']}`, API `{summary['device']['apiLevel']}`)",
    f"- **Total Wall Time**: `{total_wall_ms} ms`",
    "",
    "## Gate Status",
    "| Gate | Status | Details |",
    "|---|:---:|---|",
    f"| Static APK Verification | **{summary['gates']['apkVerification']['status']}** | rc={apk_verify_rc} |",
    f"| App Cold Launch -> Ready | **{'PASS' if boot_rc == 0 else 'FAIL'}** | {boot_detail} |",
    f"| Tier Test Suites | **{'PASS' if (tier_failures == 0 and failed == 0) else 'FAIL'}** | executed={executed_tiers}, skipped={skipped_tiers}, passed={passed}/{total_tests}, failed={failed} |",
    f"| Performance & Logcat Metrics | **{summary['gates']['metrics']['status']}** | rc={metrics_rc} |",
    "",
]

if by_tier:
    md.extend([
        "## Results by Tier",
        "| Tier | Passed | Failed | Total |",
        "|---|---:|---:|---:|",
    ])
    for t, counts in sorted(by_tier.items()):
        md.append(f"| Tier {t} | {counts['passed']} | {counts['failed']} | {counts['total']} |")
    md.append("")

if by_feature:
    md.extend([
        "## Results by Feature",
        "| Feature | Passed | Failed | Total |",
        "|---|---:|---:|---:|",
    ])
    for feat, counts in sorted(by_feature.items()):
        md.append(f"| {feat} | {counts['passed']} | {counts['failed']} | {counts['total']} |")
    md.append("")

failed_records = [r for r in records if r.get("status") != "PASS"]
if failed_records:
    md.extend([
        "## Failed Test Cases",
        "| ID | Tier | Feature | Detail |",
        "|---|---|---|---|",
    ])
    for r in failed_records[:50]:
        md.append(f"| `{r.get('id')}` | {r.get('tier')} | {r.get('feature')} | {r.get('detail')} |")
    md.append("")

summary_md_path = os.path.join(out_dir, "e2e-summary.md")
with open(summary_md_path, "w", encoding="utf-8") as f:
    f.write("\n".join(md))

print(f"[run-e2e] Summary: {'PASS' if overall_pass else 'FAIL'} ({passed}/{total_tests} tests passed) -> {summary_json_path}")
sys.exit(0 if overall_pass else 1)
PYEOF
