#!/usr/bin/env sh
set -eu
MAVEN_VERSION=3.9.11
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MAVEN_DIR="$BASE_DIR/.mvn/apache-maven-$MAVEN_VERSION"
if [ ! -x "$MAVEN_DIR/bin/mvn" ]; then
  echo "Downloading Apache Maven $MAVEN_VERSION..."
  mkdir -p "$BASE_DIR/.mvn"
  ARCHIVE="${TMPDIR:-/tmp}/ledgerbank-maven.tar.gz"
  curl -fsSL "https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" -o "$ARCHIVE"
  tar -xzf "$ARCHIVE" -C "$BASE_DIR/.mvn"
  rm -f "$ARCHIVE"
fi
exec "$MAVEN_DIR/bin/mvn" "$@"

