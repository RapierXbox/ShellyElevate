#!/usr/bin/env bash
# Restore ShellyElevate settings via HTTP API
# Usage: ./restore.sh <device-ip> <backup-file>
#   device-ip    IP address of the Shelly device (required)
#   backup-file  Path to the backup JSON produced by backup.sh (required)

set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <device-ip> <backup-file>" >&2
  exit 1
fi

DEVICE_IP="$1"
BACKUP_FILE="$2"

if [[ ! -f "$BACKUP_FILE" ]]; then
  echo "Error: backup file '$BACKUP_FILE' not found." >&2
  exit 1
fi

# The backup file wraps settings under a "settings" key; extract that object
# so we POST only the settings map (matching the POST /settings contract).
SETTINGS_JSON=$(python3 -c "
import sys, json
data = json.load(open('$BACKUP_FILE'))
settings = data.get('settings', data)  # fall back to root if already flat
print(json.dumps(settings))
" 2>/dev/null) || {
  echo "Error: could not parse '$BACKUP_FILE' as JSON." >&2
  exit 1
}

echo "Restoring settings to http://${DEVICE_IP}:8080/settings ..."

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "http://${DEVICE_IP}:8080/settings" \
  -H "Content-Type: application/json" \
  -d "$SETTINGS_JSON")

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | head -n -1)

if [[ "$HTTP_CODE" != "200" ]]; then
  echo "Error: received HTTP $HTTP_CODE from device." >&2
  echo "$BODY" >&2
  exit 1
fi

if ! echo "$BODY" | grep -q '"success"\s*:\s*true'; then
  echo "Error: device returned an unsuccessful response:" >&2
  echo "$BODY" >&2
  exit 1
fi

echo "Settings restored successfully."
