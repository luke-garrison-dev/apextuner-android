#!/bin/sh
# ApexTuner verified Gradle bootstrap.
# This source package intentionally does not ship an unverified gradle-wrapper.jar.
# The bootstrap downloads the pinned Gradle distribution and verifies Gradle's
# published SHA-256 before extracting or executing it.
set -eu

GRADLE_VERSION="9.5.0"
GRADLE_SHA256="553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746"
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
BOOTSTRAP_ROOT="$GRADLE_USER_HOME/wrapper/apextuner-bootstrap"
INSTALL_DIR="$BOOTSTRAP_ROOT/gradle-${GRADLE_VERSION}"
GRADLE_BIN="$INSTALL_DIR/bin/gradle"
ZIP_PATH="$BOOTSTRAP_ROOT/gradle-${GRADLE_VERSION}-bin.zip"
TMP_ZIP="$ZIP_PATH.part.$$"
TMP_DIR="$BOOTSTRAP_ROOT/.extract-${GRADLE_VERSION}-$$"

cleanup() {
  rm -f "$TMP_ZIP" 2>/dev/null || true
  rm -rf "$TMP_DIR" 2>/dev/null || true
}
trap cleanup EXIT HUP INT TERM

verify_sha256() {
  file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$file" | awk '{print $1}')
  elif command -v shasum >/dev/null 2>&1; then
    actual=$(shasum -a 256 "$file" | awk '{print $1}')
  elif command -v openssl >/dev/null 2>&1; then
    actual=$(openssl dgst -sha256 "$file" | awk '{print $NF}')
  else
    echo "ApexTuner Gradle bootstrap: no SHA-256 utility found (sha256sum, shasum, or openssl required)." >&2
    return 1
  fi
  [ "$actual" = "$GRADLE_SHA256" ] || {
    echo "ApexTuner Gradle bootstrap: Gradle distribution checksum mismatch." >&2
    echo "Expected: $GRADLE_SHA256" >&2
    echo "Actual:   $actual" >&2
    return 1
  }
}

download_gradle() {
  mkdir -p "$BOOTSTRAP_ROOT"
  rm -f "$TMP_ZIP"
  if command -v curl >/dev/null 2>&1; then
    curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 20 \
      --output "$TMP_ZIP" "$GRADLE_URL"
  elif command -v wget >/dev/null 2>&1; then
    wget --https-only --tries=3 --timeout=30 --output-document="$TMP_ZIP" "$GRADLE_URL"
  else
    echo "ApexTuner Gradle bootstrap: curl or wget is required for the first Gradle download." >&2
    return 1
  fi
  verify_sha256 "$TMP_ZIP"
  mv -f "$TMP_ZIP" "$ZIP_PATH"
}

extract_gradle() {
  rm -rf "$TMP_DIR"
  mkdir -p "$TMP_DIR"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP_PATH" -d "$TMP_DIR"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$ZIP_PATH" "$TMP_DIR"
  else
    echo "ApexTuner Gradle bootstrap: unzip or python3 is required to extract Gradle." >&2
    return 1
  fi
  extracted="$TMP_DIR/gradle-$GRADLE_VERSION"
  [ -x "$extracted/bin/gradle" ] || {
    echo "ApexTuner Gradle bootstrap: downloaded archive has an unexpected layout." >&2
    return 1
  }
  rm -rf "$INSTALL_DIR"
  mv "$extracted" "$INSTALL_DIR"
  rm -rf "$TMP_DIR"
}

if [ ! -x "$GRADLE_BIN" ]; then
  if [ ! -f "$ZIP_PATH" ] || ! verify_sha256 "$ZIP_PATH"; then
    rm -f "$ZIP_PATH"
    download_gradle
  fi
  extract_gradle
fi

exec "$GRADLE_BIN" "$@"
