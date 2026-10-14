#!/usr/bin/env bash
# Checks whether this Linux machine can see the treadmill over Bluetooth.
# Run on the host (not in Docker), with the treadmill on and the Echelon
# tablet's Bluetooth turned off:   bash check_host.sh

SCAN_SECONDS=${SCAN_SECONDS:-20}
ok()   { printf '  \033[32m[ OK ]\033[0m %s\n' "$*"; }
bad()  { printf '  \033[31m[FAIL]\033[0m %s\n' "$*"; FAILED=1; }
warn() { printf '  \033[33m[WARN]\033[0m %s\n' "$*"; }
FAILED=0

echo "1. Bluetooth hardware"
if ls /sys/class/bluetooth/hci* >/dev/null 2>&1; then
  ok "adapter(s): $(ls /sys/class/bluetooth | tr '\n' ' ')"
else
  bad "no Bluetooth adapter found. Plug in a USB Bluetooth dongle (BT 4.0 or newer)."
fi

echo "2. Blocked by rfkill?"
if command -v rfkill >/dev/null; then
  if rfkill list bluetooth 2>/dev/null | grep -q "blocked: yes"; then
    bad "Bluetooth is blocked. Fix with: sudo rfkill unblock bluetooth"
  else
    ok "not blocked"
  fi
else
  warn "rfkill not installed, skipping"
fi

echo "3. BlueZ service (bluetoothd)"
if ! command -v bluetoothctl >/dev/null; then
  bad "bluetoothctl missing. Install BlueZ: sudo apt install bluez  (or: sudo dnf install bluez)"
elif systemctl is-active --quiet bluetooth 2>/dev/null; then
  ok "bluetooth service is running"
else
  bad "bluetooth service not running. Fix with: sudo systemctl enable --now bluetooth"
fi

echo "4. D-Bus socket the container will use"
if [ -S /run/dbus/system_bus_socket ]; then
  ok "/run/dbus/system_bus_socket exists"
else
  bad "/run/dbus/system_bus_socket missing (is dbus running?)"
fi

if [ "$FAILED" = 1 ] || ! command -v bluetoothctl >/dev/null; then
  echo; echo "Fix the failures above, then run this again."; exit 1
fi

echo "5. Adapter powered on"
if bluetoothctl show 2>/dev/null | grep -q "Powered: yes"; then
  ok "powered"
else
  warn "adapter is off, turning it on"
  bluetoothctl power on >/dev/null 2>&1
  sleep 1
  bluetoothctl show 2>/dev/null | grep -q "Powered: yes" && ok "powered" || { bad "couldn't power on adapter"; exit 1; }
fi

echo "6. Scanning for ${SCAN_SECONDS}s (treadmill ON, tablet Bluetooth OFF)..."
bluetoothctl --timeout "$SCAN_SECONDS" scan on >/dev/null 2>&1
FOUND=$(bluetoothctl devices 2>/dev/null | grep -iE "stride|echelon|ech-" || true)
if [ -z "$FOUND" ]; then
  bad "no treadmill found."
  echo "     Things to try: turn the tablet's Bluetooth off, power-cycle the treadmill,"
  echo "     move the PC or a USB dongle closer. Everything the scan did see:"
  bluetoothctl devices 2>/dev/null | sed 's/^/       /' | head -30
  exit 1
fi
echo "$FOUND" | while read -r _ mac name; do
  rssi=$(bluetoothctl info "$mac" 2>/dev/null | awk -F'[()]' '/RSSI/ {print $2}' | head -1)
  ok "found: $name  ($mac)  signal: ${rssi:-unknown} dBm"
  if [ -n "$rssi" ]; then
    if [ "$rssi" -ge -75 ]; then echo "       signal is good"
    elif [ "$rssi" -ge -88 ]; then echo "       signal is weak but should work; a USB dongle on an extension cable closer to the treadmill would help"
    else echo "       signal is very weak and likely to drop; move the PC or a dongle closer"
    fi
  fi
  echo "       Put this in compose.yaml:  --address=$mac"
done
echo; echo "Looks good. Next: deploy the stack (Komodo, or docker compose up -d --build)"
