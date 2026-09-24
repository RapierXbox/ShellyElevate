#!/bin/bash

set -e

echo "Setting up ShellyElevate development environment..."

# windows checkouts leave crlf endings which break the gradlew shebang
if [ -f "gradlew" ]; then
    echo "Fixing gradlew line endings..."
    sed -i 's/\r$//' gradlew
    chmod +x gradlew
    echo "gradlew ready"
fi

if [ -d "$ANDROID_HOME" ]; then
    echo "✓ Android SDK found at: $ANDROID_HOME"
    echo "✓ SDK Tools version: $(sdkmanager --version 2>/dev/null || echo 'unknown')"
else
    echo "✗ Android SDK not found. Please rebuild the dev container."
    exit 1
fi

echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "✓ Created local.properties"

echo ""
echo "Setup complete! You can now build with:"
echo "  ./gradlew assembleDebug    # Debug build"
echo "  ./gradlew assembleRelease  # Release build"
echo ""
