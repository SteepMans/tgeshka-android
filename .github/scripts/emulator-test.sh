#!/usr/bin/env bash
# Smoke test on the Android emulator: the app starts with the key screen, after
# the key (passed with a tgeshka://gateway link) the login flow loads, which
# needs a working path to Telegram through the gateway. The network log of
# tgnet must show only gateway connections.
set -uo pipefail

APK="$1"
OUT="${2:-emulator-out}"
PKG=ru.tgeshka.app
mkdir -p "$OUT"

shot() {
    adb exec-out screencap -p > "$OUT/$1.png" || true
}

adb root > /dev/null 2>&1 || true
sleep 3
adb install -r -g "$APK"

# Turn on the app logs before the first start (release builds keep them off).
adb shell "mkdir -p /data/data/$PKG/shared_prefs"
adb shell "echo '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map><boolean name=\"logsEnabled\" value=\"true\" /></map>' > /data/data/$PKG/shared_prefs/systemConfig.xml"
APP_UID=$(adb shell "dumpsys package $PKG | grep -m1 userId=" | sed 's/.*userId=\([0-9]*\).*/\1/' | tr -d '\r')
adb shell "chown -R $APP_UID:$APP_UID /data/data/$PKG/shared_prefs" || true

adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null
sleep 20
shot 1-key-screen

if [ -n "${TGESHKA_TEST_TOKEN:-}" ]; then
    adb shell am start -a android.intent.action.VIEW -d "tgeshka://gateway?token=${TGESHKA_TEST_TOKEN}" "$PKG" > /dev/null
fi
sleep 25
shot 2-intro

# "Start Messaging" is the big button at the bottom of the intro screen.
SIZE=$(adb shell wm size | sed 's/.*: //' | tr -d '\r')
W=${SIZE%x*}
H=${SIZE#*x}
adb shell input tap $((W / 2)) $((H * 88 / 100))
sleep 25
shot 3-login

LOGDIRS="/sdcard/Android/data/$PKG/files/logs /data/data/$PKG/cache/logs /data/data/$PKG/files/logs"
adb shell "ls -la $LOGDIRS" > "$OUT/log-files.txt" 2>&1 || true
adb shell "for d in $LOGDIRS; do cat \$d/*_net.txt; done" > "$OUT/net.txt" 2>/dev/null || true
adb shell "for d in $LOGDIRS; do cat \$d/*.txt; done" > "$OUT/all-logs.txt" 2>/dev/null || true
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true

# TCP connections of the app: every remote endpoint has to be the gateway.
adb shell "cat /proc/net/tcp /proc/net/tcp6" > "$OUT/proc-net-tcp.txt" 2>/dev/null || true
echo "app uid: $APP_UID" > "$OUT/summary.txt"
python3 - "$OUT/proc-net-tcp.txt" "$APP_UID" >> "$OUT/summary.txt" <<'EOF'
import socket, struct, sys
path, uid = sys.argv[1], sys.argv[2]
seen = set()
for line in open(path, errors='ignore'):
    parts = line.split()
    if len(parts) < 8 or parts[0] == 'sl' or parts[7] != uid:
        continue
    remote = parts[2]
    host, port = remote.split(':')
    if len(host) == 8:
        ip = socket.inet_ntoa(struct.pack('<I', int(host, 16)))
    else:
        raw = bytes.fromhex(host)
        words = [raw[i:i + 4][::-1] for i in range(0, 16, 4)]
        ip = socket.inet_ntop(socket.AF_INET6, b''.join(words))
    seen.add('%s:%d' % (ip, int(port, 16)))
print('app tcp remotes:', ' '.join(sorted(seen)) or '-')
EOF

{
    echo "gateway opened: $(grep -c 'gateway WebSocket opened' "$OUT/all-logs.txt" || true)"
    echo "connecting via gateway: $(grep -c 'connecting via gateway' "$OUT/all-logs.txt" || true)"
    echo "gateway errors: $(grep -c 'gateway: error' "$OUT/all-logs.txt" || true)"
    echo "direct connects (connecting via proxy / bad ipv4): $(grep -c 'connecting via proxy' "$OUT/all-logs.txt" || true)"
    echo "auth key / config: $(grep -c -i 'config\|auth key' "$OUT/all-logs.txt" || true)"
} >> "$OUT/summary.txt"

# The artifacts of a public repository are visible: never keep the token in them.
if [ -n "${TGESHKA_TEST_TOKEN:-}" ]; then
    for f in "$OUT"/*.txt; do
        sed -i "s/${TGESHKA_TEST_TOKEN}/tc_***/g" "$f"
    done
fi
sed -i -E 's/tc_[0-9A-Za-z]{43}/tc_***/g' "$OUT"/*.txt
# Nor the gateway address: it is passed to the build as a secret.
if [ -n "${TGESHKA_GATEWAY_URL:-}" ]; then
    GW_HOST=$(echo "$TGESHKA_GATEWAY_URL" | sed -E 's#^wss://([^/:]+).*#\1#')
    for ip in $(getent ahostsv4 "$GW_HOST" | awk '{print $1}' | sort -u); do
        sed -i "s/${ip}/gateway-ip/g" "$OUT"/*.txt
    done
    sed -i "s/${GW_HOST}/gateway-host/g" "$OUT"/*.txt
fi

cat "$OUT/summary.txt"
grep -h 'gateway' "$OUT/all-logs.txt" | head -40 || true

if ! grep -q 'gateway WebSocket opened' "$OUT/all-logs.txt"; then
    echo "No gateway WebSocket was opened"
    exit 1
fi
