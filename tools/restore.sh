#!/usr/bin/env bash
# restore shellyelevate settings via http api
# usage: ./restore.sh <device-ip> <backup-file>
#   device-ip    ip address of the shelly device (required)
#   backup-file  path to the backup json produced by backup.sh (required)

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

# the backup file wraps settings under a "settings" key; extract that object
# so we post only the settings map (matching the post /settings contract)
# pass the path via argv so quotes in it cannot break the python source
SETTINGS_JSON=$(python3 -c "
import sys, json
data = json.load(open(sys.argv[1]))
settings = data.get('settings', data)  # fall back to root if already flat
print(json.dumps(settings))
" "$BACKUP_FILE") || {
  echo "Error: could not parse '$BACKUP_FILE' as JSON." >&2
  exit 1
}

echo "Restoring settings to http://${DEVICE_IP}:8080/settings ..."

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "http://${DEVICE_IP}:8080/settings" \
  -H "Content-Type: application/json" \
  -d "$SETTINGS_JSON")

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
# head -n -1 is gnu only so strip the last line portably instead
BODY=$(echo "$RESPONSE" | sed '$d')

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
