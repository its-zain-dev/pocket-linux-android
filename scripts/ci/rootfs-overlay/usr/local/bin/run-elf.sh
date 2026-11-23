#!/bin/sh
set -eu

if [ $# -lt 1 ]; then
    echo "Usage: run-elf.sh [--info] <elf-binary> [args...]" >&2
    exit 1
fi

INFO_ONLY=0
if [ "$1" = "--info" ]; then
    INFO_ONLY=1
    shift
fi

TARGET="$1"
shift

if [ ! -e "$TARGET" ]; then
    echo "ERROR: File not found: $TARGET" >&2
    exit 1
fi

if [ "$INFO_ONLY" -eq 1 ]; then
    file -L "$TARGET" || true
    ldd "$TARGET" 2>&1 || true
    exit 0
fi

if [ ! -x "$TARGET" ]; then
    chmod +x "$TARGET" 2>/dev/null || true
fi

exec "$TARGET" "$@"
