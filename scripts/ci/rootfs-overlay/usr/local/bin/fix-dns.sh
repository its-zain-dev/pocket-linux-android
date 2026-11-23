#!/bin/sh
# Automatically resolves dl-cdn.alpinelinux.org via Cloudflare DNS-over-HTTPS
# if standard UDP DNS (port 53) is blocked by Android Private DNS or ISP.
set -eu

HOST="dl-cdn.alpinelinux.org"

if grep -q "[[:space:]]${HOST}$" /etc/hosts 2>/dev/null; then
    exit 0
fi

# Quick check if normal DNS already works
if curl -sI --max-time 3 "https://${HOST}/alpine/v3.20/main/aarch64/APKINDEX.tar.gz" >/dev/null 2>&1; then
    exit 0
fi

# Fallback: resolve via Cloudflare HTTPS DNS JSON API (1.1.1.1)
IP="$(curl -s --max-time 6 -H "accept: application/dns-json" \
    "https://1.1.1.1/dns-query?name=${HOST}&type=A" 2>/dev/null | \
    python3 -c 'import sys,json; d=json.load(sys.stdin); ips=[a["data"] for a in d.get("Answer",[]) if a.get("type")==1]; print(ips[0] if ips else "")' 2>/dev/null || true)"

if [ -n "$IP" ]; then
    printf '%s %s\n' "$IP" "$HOST" >> /etc/hosts 2>/dev/null || true
fi
exit 0
