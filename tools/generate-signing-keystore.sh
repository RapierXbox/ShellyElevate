#!/usr/bin/env bash
# Generate a release signing keystore and print the values for the four
# SIGNING_* GitHub Actions secrets that release-apk.yml and pr-apk.yml use.
# Without these secrets every CI build falls back to an ephemeral debug key,
# so no two artifacts can update each other on a device.
# Usage: ./generate-signing-keystore.sh [output-keystore] [alias]
#   output-keystore Path for the new keystore (default: shellyelevate-release.keystore)
#   alias           Key alias (default: shellyelevate)
# Keep the keystore and password out of the repository and back them up:
# losing them means future builds can no longer update existing installs.

set -euo pipefail

KEYSTORE="${1:-shellyelevate-release.keystore}"
ALIAS="${2:-shellyelevate}"

if [[ -e "$KEYSTORE" ]]; then
  echo "Refusing to overwrite existing $KEYSTORE" >&2
  exit 1
fi

command -v keytool >/dev/null || { echo "keytool not found (install a JDK)" >&2; exit 1; }

# pkcs12 keystores use one password for store and key
PASSWORD="$(openssl rand -base64 18)"

keytool -genkeypair -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=ShellyElevate release, O=ShellyElevate"

echo
echo "Keystore written to $KEYSTORE - back it up together with the password."
echo
echo "Set the repository secrets (or paste the same values in the GitHub UI"
echo "under Settings -> Secrets and variables -> Actions):"
echo
echo "  gh secret set SIGNING_KEY_ALIAS       --body \"$ALIAS\""
echo "  gh secret set SIGNING_STORE_PASSWORD  --body \"$PASSWORD\""
echo "  gh secret set SIGNING_KEY_PASSWORD    --body \"$PASSWORD\""
echo "  gh secret set SIGNING_KEYSTORE_BASE64 --body \"\$(openssl base64 -A -in $KEYSTORE)\""
