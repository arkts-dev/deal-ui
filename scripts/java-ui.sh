#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SYSTEM_JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")
LOCAL_JAVA_HOME="$ROOT/.deps/jdk25-full"

if [[ ! -f "$SYSTEM_JAVA_HOME/lib/libawt_xawt.so" ]]; then
  mkdir -p "$ROOT/.deps"
  if [[ ! -f "$ROOT/.deps/openjdk-25-jre/usr/lib/jvm/java-25-openjdk-amd64/lib/libawt_xawt.so" ]]; then
    (
      cd "$ROOT/.deps"
      apt-get download openjdk-25-jre
      DEB=$(printf '%s\n' openjdk-25-jre_*.deb)
      rm -rf openjdk-25-jre
      dpkg-deb -x "$DEB" openjdk-25-jre
      rm "$DEB"
    )
  fi
  rm -rf "$LOCAL_JAVA_HOME"
  cp -a "$SYSTEM_JAVA_HOME" "$LOCAL_JAVA_HOME"
  cp -a "$ROOT/.deps/openjdk-25-jre/usr/lib/jvm/java-25-openjdk-amd64/lib/." "$LOCAL_JAVA_HOME/lib/"
  printf '%s\n' "$LOCAL_JAVA_HOME/bin/java"
else
  printf '%s\n' "$SYSTEM_JAVA_HOME/bin/java"
fi
