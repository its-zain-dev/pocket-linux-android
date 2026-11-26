#!/bin/sh
# Multi-layer DNS & Android Native Network Bridge activator for PocketLinux.
# 1) Tests if direct UDP DNS works inside PRoot.
# 2) If UDP 53 is blocked (e.g., Android Private DNS / restrictive Wi-Fi/carrier),
#    activates the Android Native HTTP/HTTPS Bridge (127.0.0.1:18080) in network.env
#    so Firefox, Luakit, Chromium, curl, git, and apk resolve ALL domains cleanly!
# 3) Also caches dl-cdn.alpinelinux.org via Cloudflare/Google DoH as belt-and-suspenders.
set -eu

HOST="dl-cdn.alpinelinux.org"
ARCH="$(uname -m 2>/dev/null || echo aarch64)"
CONF_DIR="/root/.config/pocketlinux"
NET_ENV="$CONF_DIR/network.env"
NET_MODE_FILE="$CONF_DIR/network_mode"
BRIDGE_URL="${POCKETLINUX_NET_BRIDGE:-http://127.0.0.1:18080}"

mkdir -p "$CONF_DIR" 2>/dev/null || true

MODE="auto"
if [ -r "$NET_MODE_FILE" ]; then
    MODE="$(tr -d ' \r\n' < "$NET_MODE_FILE" 2>/dev/null || echo auto)"
fi

write_proxy_on() {
    cat > "$NET_ENV" <<EOF
# Auto-configured by PocketLinux Network Bridge (mode=$MODE)
export http_proxy="$BRIDGE_URL"
export https_proxy="$BRIDGE_URL"
export HTTP_PROXY="$BRIDGE_URL"
export HTTPS_PROXY="$BRIDGE_URL"
export all_proxy="$BRIDGE_URL"
export ALL_PROXY="$BRIDGE_URL"
export no_proxy="localhost,127.0.0.1,::1"
export NO_PROXY="localhost,127.0.0.1,::1"
EOF
    sync_firefox_proxy "on"
}

write_proxy_off() {
    cat > "$NET_ENV" <<EOF
# Auto-configured by PocketLinux Network Bridge (mode=$MODE)
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY
EOF
    sync_firefox_proxy "off"
}

sync_firefox_proxy() {
    state="$1"
    for ff_dir in /usr/lib/firefox /usr/lib/firefox-esr; do
        if [ -d "$ff_dir" ]; then
            mkdir -p "$ff_dir/distribution" 2>/dev/null || true
            if [ "$state" = "on" ]; then
                cat > "$ff_dir/distribution/policies.json" 2>/dev/null <<'FFEOF' || true
{
  "policies": {
    "Proxy": {
      "Mode": "manual",
      "HTTPProxy": "127.0.0.1:18080",
      "UseHTTPProxyForAllProtocols": true,
      "Passthrough": "localhost, 127.0.0.1",
      "UseProxyForDNS": true
    }
  }
}
FFEOF
            else
                rm -f "$ff_dir/distribution/policies.json" 2>/dev/null || true
            fi
        fi
    done
}

# Respect explicit user override from PocketLinux Settings
if [ "$MODE" = "always" ]; then
    write_proxy_on
    exit 0
elif [ "$MODE" = "off" ]; then
    write_proxy_off
    exit 0
fi

# Auto mode: check if direct UDP DNS already resolves external domains cleanly
if curl -4 --noproxy '*' -sI --connect-timeout 2 --max-time 4 "https://${HOST}/alpine/v3.20/main/${ARCH}/APKINDEX.tar.gz" >/dev/null 2>&1; then
    write_proxy_off
    exit 0
fi

# Direct UDP DNS failed! Check if Android Native Network Bridge (127.0.0.1:18080) is reachable
if curl -4 -x "$BRIDGE_URL" -sI --connect-timeout 2 --max-time 5 "https://${HOST}/alpine/v3.20/main/${ARCH}/APKINDEX.tar.gz" >/dev/null 2>&1; then
    write_proxy_on
fi

# Belt-and-suspenders: also resolve dl-cdn.alpinelinux.org via Cloudflare / Google DoH into /etc/hosts
if ! grep -q "[[:space:]]${HOST}$" /etc/hosts 2>/dev/null; then
    extract_ipv4() {
        grep -oE '"data":"[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+"' 2>/dev/null | head -n 1 | cut -d'"' -f4 || true
    }
    IP="$(curl -4 --noproxy '*' -s --connect-timeout 3 --max-time 5 -H "accept: application/dns-json" \
        "https://1.1.1.1/dns-query?name=${HOST}&type=A" 2>/dev/null | extract_ipv4)"
    if [ -z "$IP" ]; then
        IP="$(curl -4 --noproxy '*' -s --connect-timeout 3 --max-time 5 -H "accept: application/dns-json" \
            "https://8.8.8.8/resolve?name=${HOST}&type=A" 2>/dev/null | extract_ipv4)"
    fi
    if [ -n "$IP" ]; then
        printf '%s %s\n' "$IP" "$HOST" >> /etc/hosts 2>/dev/null || true
    fi
fi

exit 0
