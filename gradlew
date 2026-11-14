#!/bin/sh
# Gradle wrapper script
GRADLE_HOME="/opt/gradle/gradle-8.2"
if [ -d "${GRADLE_HOME}" ]; then
    exec "${GRADLE_HOME}/bin/gradle" "$@"
else
    echo "Gradle not found at ${GRADLE_HOME}"
    exit 1
fi
