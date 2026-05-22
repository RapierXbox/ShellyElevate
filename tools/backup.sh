#!/usr/bin/env bash
# Backup ShellyElevate settings via HTTP API
# Usage: ./backup.sh <device-ip> [output-file]
#   device-ip   IP address of the Shelly device (required)
#   output-file Path to save the backup JSON (default: backup_<ip>_<timestamp>.json)

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <device-ip> [output-file]" >&2
  exit 1
fi

DEVICE_IP="$1"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
OUTPUT="${2:-backup_${DEVICE_IP}_${TIMESTAMP}.json}"

echo "Fetching settings from http://${DEVICE_IP}:8080/settings ..."

HTTP_CODE=$(curl -s -o "$OUTPUT" -w "%{http_code}" "http://${DEVICE_IP}:8080/settings")

if [[ "$HTTP_CODE" != "200" ]]; then
  echo "Error: received HTTP $HTTP_CODE from device." >&2
  rm -f "$OUTPUT"
  exit 1
fi

# Verify the response contains "success": true
if ! grep -q '"success"\s*:\s*true' "$OUTPUT"; then
  echo "Error: device returned an unsuccessful response:" >&2
  cat "$OUTPUT" >&2
  rm -f "$OUTPUT"
  exit 1
fi

echo "Backup saved to: $OUTPUT"
