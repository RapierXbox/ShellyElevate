#!/bin/bash

set -e

echo "Setting up ShellyElevate development environment..."

# Fix gradlew line endings if needed (Windows CRLF to Unix LF)
if [ -f "gradlew" ]; then
    echo "Fixing gradlew line endings..."
    sed -i 's/\r$//' gradlew
    chmod +x gradlew
    echo "gradlew ready"
fi

# Verify Android SDK installation
if [ -d "$ANDROID_HOME" ]; then
    echo "✓ Android SDK found at: $ANDROID_HOME"
    echo "✓ SDK Tools version: $(sdkmanager --version 2>/dev/null || echo 'unknown')"
else
    echo "✗ Android SDK not found. Please rebuild the dev container."
    exit 1
fi

# Create local.properties with SDK location
echo "sdk.dir=$ANDROID_HOME" > local.properties
echo "✓ Created local.properties"

echo ""
echo "Setup complete! You can now build with:"
echo "  ./gradlew assembleDebug    # Debug build"
echo "  ./gradlew assembleRelease  # Release build"
echo ""
