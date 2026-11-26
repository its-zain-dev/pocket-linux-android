#!/bin/sh
# Multi-provider DNS-over-HTTPS resolver for dl-cdn.alpinelinux.org when Android Private DNS
# or ISP firewall blocks UDP port 53. Zero Python dependency for instant execution.
set -eu

HOST="dl-cdn.alpinelinux.org"
ARCH="$(uname -m 2>/dev/null || echo aarch64)"

if grep -q "[[:space:]]${HOST}$" /etc/hosts 2>/dev/null; then
    exit 0
fi

# Quick check if standard system DNS already resolves HTTPS mirror
if curl -4 -sI --connect-timeout 2 --max-time 4 "https://${HOST}/alpine/v3.20/main/${ARCH}/APKINDEX.tar.gz" >/dev/null 2>&1; then
    exit 0
fi

extract_ipv4() {
    grep -oE '"data":"[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+"' 2>/dev/null | head -n 1 | cut -d'"' -f4 || true
}

# Fallback 1: Cloudflare 1.1.1.1 DoH JSON
IP="$(curl -4 -s --connect-timeout 3 --max-time 5 -H "accept: application/dns-json" \
    "https://1.1.1.1/dns-query?name=${HOST}&type=A" 2>/dev/null | extract_ipv4)"

# Fallback 2: Google 8.8.8.8 DoH JSON (for ISPs blocking 1.1.1.1)
if [ -z "$IP" ]; then
    IP="$(curl -4 -s --connect-timeout 3 --max-time 5 -H "accept: application/dns-json" \
        "https://8.8.8.8/resolve?name=${HOST}&type=A" 2>/dev/null | extract_ipv4)"
fi

if [ -n "$IP" ]; then
    printf '%s %s\n' "$IP" "$HOST" >> /etc/hosts 2>/dev/null || true
fi
exit 0
