FROM mcr.microsoft.com/devcontainers/base:jammy

# Install Java 17 and Android SDK dependencies
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget \
    unzip \
    git \
    && rm -rf /var/lib/apt/lists/*

# Set environment variables
ENV ANDROID_HOME=/usr/local/android-sdk
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

# Install Android SDK Command Line Tools
RUN mkdir -p /tmp/android-install && cd /tmp/android-install && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip -q commandlinetools-linux-11076708_latest.zip && \
    mkdir -p $ANDROID_HOME/cmdline-tools && \
    mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest && \
    cd / && rm -rf /tmp/android-install

# Accept Android SDK licenses and install required components
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" \
               "platforms;android-35" \
               "platforms;android-24" \
               "build-tools;35.0.0"

# Fix gradlew line endings and set proper ownership
RUN chown -R vscode:vscode $ANDROID_HOME
