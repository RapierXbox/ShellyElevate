FROM mcr.microsoft.com/devcontainers/base:jammy

# java 17 plus the tools needed to fetch and unpack the android sdk
RUN apt-get update && apt-get install -y \
    openjdk-17-jdk \
    wget \
    unzip \
    git \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/usr/local/android-sdk
ENV PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

# android sdk command line tools used below to pull the rest of the sdk
RUN mkdir -p /tmp/android-install && cd /tmp/android-install && \
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip && \
    unzip -q commandlinetools-linux-11076708_latest.zip && \
    mkdir -p $ANDROID_HOME/cmdline-tools && \
    mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest && \
    cd / && rm -rf /tmp/android-install

# platforms;android-24 matches minsdk and platforms;android-36 matches compilesdk
RUN yes | sdkmanager --licenses && \
    sdkmanager "platform-tools" \
               "platforms;android-36" \
               "platforms;android-24" \
               "build-tools;36.0.0"

# gradlew line endings are fixed by setup.sh at container create time; just set ownership here
RUN chown -R vscode:vscode $ANDROID_HOME
