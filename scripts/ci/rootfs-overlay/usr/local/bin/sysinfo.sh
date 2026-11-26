#!/bin/sh
# Fast POSIX sh + awk system telemetry helper (zero Python startup overhead under PRoot)
set -eu

MODE_ARG="${1:-}"
DISPLAY_VAL="${DISPLAY:-:1}"
SYSNAME="$(uname -s 2>/dev/null || echo Linux)"
NODENAME="$(uname -n 2>/dev/null || echo localhost)"
RELEASE="$(uname -r 2>/dev/null || echo unknown)"
MACHINE="$(uname -m 2>/dev/null || echo unknown)"

ALPINE_REL="unknown"
if [ -r /etc/alpine-release ]; then
    IFS= read -r ALPINE_REL < /etc/alpine-release || ALPINE_REL="unknown"
fi

UPTIME_SEC="0.0"
if [ -r /proc/uptime ]; then
    UPTIME_SEC="$(awk '{printf "%.2f", $1+0; exit}' /proc/uptime 2>/dev/null || echo "0.0")"
fi

MEM_LINE="0 0 0"
if [ -r /proc/meminfo ]; then
    MEM_LINE="$(awk '
        /^MemTotal:/     { total = $2 }
        /^MemFree:/      { free  = $2 }
        /^MemAvailable:/ { avail = $2 }
        END {
            if (total == "") total = 0
            if (free  == "") free  = 0
            if (avail == "") avail = free
            printf "%d %d %d", total, avail, free
        }
    ' /proc/meminfo 2>/dev/null || echo "0 0 0")"
fi
set -- $MEM_LINE
MEM_TOTAL="${1:-0}"
MEM_AVAIL="${2:-0}"
MEM_FREE="${3:-0}"

PROC_COUNT=0
for d in /proc/[0-9]*; do
    if [ -d "$d" ]; then
        PROC_COUNT=$((PROC_COUNT + 1))
    fi
done
if [ "$PROC_COUNT" -le 0 ]; then
    PROC_COUNT=1
fi

X_WINS_JSON=""
X_WIN_COUNT=0
if command -v xwininfo >/dev/null 2>&1; then
    X_RAW="$(DISPLAY="$DISPLAY_VAL" xwininfo -display "$DISPLAY_VAL" -root -children 2>/dev/null || true)"
    if [ -n "$X_RAW" ]; then
        X_WINS_JSON="$(printf '%s\n' "$X_RAW" | awk '
            /^[[:space:]]*0x[0-9a-fA-F]+/ {
                sub(/^[[:space:]]+/, "", $0)
                gsub(/\\/, "\\\\", $0)
                gsub(/"/, "\\\"", $0)
                gsub(/\r/, "", $0)
                items[++n] = "\"" $0 "\""
            }
            END {
                for (i = 1; i <= n; i++) {
                    printf "%s%s", (i > 1 ? "," : ""), items[i]
                }
            }
        ')"
        X_WIN_COUNT="$(printf '%s\n' "$X_RAW" | awk '/^[[:space:]]*0x[0-9a-fA-F]+/ { c++ } END { print c+0 }')"
    fi
fi

if [ "$MODE_ARG" = "--text" ]; then
    printf 'OS: Alpine Linux %s (%s)\n' "$ALPINE_REL" "$MACHINE"
    printf 'Kernel: %s %s\n' "$SYSNAME" "$RELEASE"
    printf 'Memory: %s / %s kB available\n' "$MEM_AVAIL" "$MEM_TOTAL"
    printf 'Processes: %s\n' "$PROC_COUNT"
    printf 'X11 Windows: %s\n' "$X_WIN_COUNT"
else
    json_escape() {
        printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
    }
    printf '{"sysname":"%s","nodename":"%s","release":"%s","machine":"%s","alpine_release":"%s","uptime_sec":%s,"mem_total_kb":%s,"mem_available_kb":%s,"mem_free_kb":%s,"process_count":%s,"x_windows":[%s]}\n' \
        "$(json_escape "$SYSNAME")" \
        "$(json_escape "$NODENAME")" \
        "$(json_escape "$RELEASE")" \
        "$(json_escape "$MACHINE")" \
        "$(json_escape "$ALPINE_REL")" \
        "$UPTIME_SEC" \
        "$MEM_TOTAL" \
        "$MEM_AVAIL" \
        "$MEM_FREE" \
        "$PROC_COUNT" \
        "$X_WINS_JSON"
fi
