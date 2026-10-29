#!/bin/sh
# Gradle 垫片：ARM64 proot 下 gradle-wrapper.jar 下载发行版容易失败，
# /opt/gradle-8.6 已在本机就绪，直接转发。
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
exec /opt/gradle-8.6/bin/gradle "$@"