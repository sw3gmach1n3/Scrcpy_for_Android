#!/bin/sh
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -classpath "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
