#!/usr/bin/env sh
# fails when code outside the deprecated package and its wiring points uses it
# the removal release then only has to delete the package and edit these files
set -eu
cd "$(dirname "$0")/.."
src=app/src/main/java/me/rapierxbox/shellyelevatev2
allowed="
$src/ShellyElevateApplication.java
$src/SettingsFragment.kt
$src/HttpServer.java
$src/api/ApiInfo.java
$src/voice/VoiceEngine.java
"
bad=0
for f in $(grep -rl 'shellyelevatev2\.deprecated' app/src --include='*.java' --include='*.kt' | grep -v "^$src/deprecated/" || true); do
  case "$allowed" in
    *"$f"*) ;;
    *) echo "uses the deprecated package outside a wiring point: $f"; bad=1 ;;
  esac
done
exit $bad
