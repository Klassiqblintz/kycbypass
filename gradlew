#!/bin/sh
# Gradle wrapper script
GRADLE_HOME="${HOME}/.gradle/wrapper/dists/gradle-8.2-bin"
if [ -d "${GRADLE_HOME}/gradle-8.2" ]; then
    exec "${GRADLE_HOME}/gradle-8.2/bin/gradle" "$@"
else
    echo "Gradle not found. Run sdkmanager install 'command-line-tools;latest' and then run this script again."
    exit 1
fi
