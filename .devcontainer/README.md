# Building with Dev Container

This project includes a dev container configuration that includes all required dependencies (Java 17, Android SDK, and build tools) pre-installed.

## Prerequisites

- [Docker](https://www.docker.com/products/docker-desktop) installed and running
- [VS Code](https://code.visualstudio.com/) with the [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers)

## Getting Started

1. Open the project folder in VS Code
2. Click the remote indicator in the bottom-left corner (or press `F1` and search for "Dev Containers: Reopen in Container")
3. Wait for the container to build and initialize (first time takes 5-10 minutes to download and install Android SDK)
4. The container will automatically:
   - Install Java 17
   - Download and configure Android SDK (including platform-tools, API 24 & 35, and build-tools 35.0.0)
   - Accept SDK licenses
   - Fix gradlew line endings
   - Create local.properties with SDK path

## Building

Once the dev container is ready, you can build the app:

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (signed with debug key)
./gradlew assembleRelease

# Clean build
./gradlew clean

# Install to connected device (requires ADB over network or USB passthrough)
./gradlew installDebug
```

The APK files will be located in:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

## What's Included

The dev container provides:
- **Base OS**: Ubuntu 22.04 (Jammy)
- **Java**: OpenJDK 17
- **Android SDK**: Located at `/usr/local/android-sdk`
  - Platform Tools (adb, fastboot)
  - Android API 24 (minSdk)
  - Android API 35 (compileSdk)
  - Build Tools 35.0.0
- **VS Code Extensions**:
  - Java Extension Pack
  - Gradle for Java
  - Kotlin Language Support
  - Maven

## Environment Variables

The following are automatically configured:
- `ANDROID_HOME=/usr/local/android-sdk`
- `PATH` includes SDK command-line tools and platform-tools

## Troubleshooting

**Container build fails:**
1. Rebuild: `F1` → "Dev Containers: Rebuild Container"
2. Ensure Docker has at least 4GB RAM and 20GB disk space
3. Check internet connection (SDK download is ~500MB)

**gradlew permission errors:**
```bash
chmod +x gradlew
sed -i 's/\r$//' gradlew
```

**SDK not found:**
```bash
echo $ANDROID_HOME  # Should show: /usr/local/android-sdk
sdkmanager --version  # Should show SDK manager version
```

## Local Development (Without Dev Container)

If you prefer local development, manually install:

- [Java 17 JDK](https://adoptium.net/)
- Android SDK via [Android Studio](https://developer.android.com/studio)

Then run the same Gradle commands above.
