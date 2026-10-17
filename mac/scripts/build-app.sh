#!/usr/bin/env bash
# Builds SpeakerLink.app and SpeakerLink-<version>.zip in mac/build.
#
# Usage: scripts/build-app.sh [--universal]
#
#   --universal   build for both arm64 and x86_64 (needs a full Xcode, not just
#                 the Command Line Tools)
#
# Environment:
#   SPEAKERLINK_SIGN_IDENTITY  sign with this Developer ID identity (name or SHA-1),
#                              with the hardened runtime and a secure timestamp.
#                              Unset: ad-hoc signature.
#   SPEAKERLINK_KEYCHAIN       optional keychain that holds the identity.
#
# Copyright (C) 2026 Cyrus-Sch
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

usage() {
  sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
}

UNIVERSAL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --universal) UNIVERSAL=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$MAC_DIR"

APP_NAME="SpeakerLink"
BUILD_DIR="$MAC_DIR/build"
APP="$BUILD_DIR/$APP_NAME.app"
PLIST_TEMPLATE="$MAC_DIR/App/Info.plist"
ICON_SOURCE="$MAC_DIR/App/AppIcon-1024.png"

fail() {
  echo "error: $*" >&2
  exit 1
}

for tool in swift plutil sips iconutil codesign ditto xattr; do
  command -v "$tool" > /dev/null 2>&1 || fail "'$tool' not found. This script needs macOS with Xcode installed."
done

[ -f "$PLIST_TEMPLATE" ] || fail "missing $PLIST_TEMPLATE"
[ -f "$ICON_SOURCE" ] || fail "missing $ICON_SOURCE"
[ -f "$MAC_DIR/VERSION" ] || fail "missing $MAC_DIR/VERSION"
[ -f "$MAC_DIR/BUILD" ] || fail "missing $MAC_DIR/BUILD"

VERSION="$(tr -d '[:space:]' < "$MAC_DIR/VERSION")"
BUILD="$(tr -d '[:space:]' < "$MAC_DIR/BUILD")"
# CFBundleShortVersionString and CFBundleVersion: one to three period-separated integers.
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] || fail "VERSION must look like 1.0.0, got '$VERSION'"
[[ "$BUILD" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] || fail "BUILD must look like 1 or 1.0.0, got '$BUILD'"

SWIFT_ARGS=(-c release)
if [ "$UNIVERSAL" -eq 1 ]; then
  SWIFT_ARGS+=(--arch arm64 --arch x86_64)
fi

echo "==> Building $APP_NAME $VERSION ($BUILD), universal: $([ "$UNIVERSAL" -eq 1 ] && echo yes || echo no)"
swift build "${SWIFT_ARGS[@]}"
# The output folder differs between build systems and Swift versions, so ask SwiftPM.
BIN_DIR="$(swift build "${SWIFT_ARGS[@]}" --show-bin-path)"
BINARY="$BIN_DIR/$APP_NAME"
[ -f "$BINARY" ] || fail "built binary not found at $BINARY"

echo "==> Assembling $APP"
rm -rf "$APP" "$BUILD_DIR/$APP_NAME-"*.zip
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BINARY" "$APP/Contents/MacOS/$APP_NAME"
chmod 755 "$APP/Contents/MacOS/$APP_NAME"
printf 'APPL????' > "$APP/Contents/PkgInfo"

PLIST="$APP/Contents/Info.plist"
sed -e "s/__VERSION__/$VERSION/g" -e "s/__BUILD__/$BUILD/g" "$PLIST_TEMPLATE" > "$PLIST"
if grep -qE '__(VERSION|BUILD)__' "$PLIST"; then
  fail "placeholders left in $PLIST"
fi
plutil -lint "$PLIST"

plist_value() {
  plutil -extract "$1" raw -o - "$PLIST"
}
PLIST_EXECUTABLE="$(plist_value CFBundleExecutable)"
PLIST_VERSION="$(plist_value CFBundleShortVersionString)"
PLIST_BUILD="$(plist_value CFBundleVersion)"
BUNDLE_ID="$(plist_value CFBundleIdentifier)"
[ "$PLIST_EXECUTABLE" = "$APP_NAME" ] || fail "CFBundleExecutable in $PLIST_TEMPLATE is '$PLIST_EXECUTABLE', not $APP_NAME"
[ "$PLIST_VERSION" = "$VERSION" ] || fail "CFBundleShortVersionString is '$PLIST_VERSION', not $VERSION"
[ "$PLIST_BUILD" = "$BUILD" ] || fail "CFBundleVersion is '$PLIST_BUILD', not $BUILD"
[ -n "$BUNDLE_ID" ] || fail "CFBundleIdentifier missing in $PLIST_TEMPLATE"

echo "==> Making AppIcon.icns"
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/speakerlink-build.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
ICONSET="$WORK_DIR/AppIcon.iconset"
mkdir -p "$ICONSET"
for size in 16 32 128 256 512; do
  double=$((size * 2))
  sips -s format png -z "$size" "$size" "$ICON_SOURCE" --out "$ICONSET/icon_${size}x${size}.png" > /dev/null
  sips -s format png -z "$double" "$double" "$ICON_SOURCE" --out "$ICONSET/icon_${size}x${size}@2x.png" > /dev/null
done
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

# Finder info or resource forks would make codesign fail.
xattr -cr "$APP"

if [ -n "${SPEAKERLINK_SIGN_IDENTITY:-}" ]; then
  echo "==> Signing with the Developer ID identity (hardened runtime, timestamp)"
  SIGN_ARGS=(--force --options runtime --timestamp --sign "$SPEAKERLINK_SIGN_IDENTITY")
  if [ -n "${SPEAKERLINK_KEYCHAIN:-}" ]; then
    SIGN_ARGS+=(--keychain "$SPEAKERLINK_KEYCHAIN")
  fi
  codesign "${SIGN_ARGS[@]}" "$APP"
else
  # An ad-hoc signature's default designated requirement is its code hash, so
  # macOS would forget the Bluetooth permission after every update. Requiring
  # only the bundle identifier keeps the permission across updates.
  echo "==> Signing ad-hoc (designated requirement: identifier \"$BUNDLE_ID\")"
  codesign --force --sign - --requirements "=designated => identifier \"$BUNDLE_ID\"" "$APP"
fi

echo "==> Verifying the signature"
codesign --verify --strict --verbose=2 "$APP"

if [ "$UNIVERSAL" -eq 1 ] && command -v lipo > /dev/null 2>&1; then
  echo "Architectures: $(lipo -archs "$APP/Contents/MacOS/$APP_NAME")"
fi

# Swift 6.4's default build system can record the deployment target as the SDK
# version, which makes AppKit fall back to the old look on macOS 26 and later.
if command -v vtool > /dev/null 2>&1; then
  build_version="$(vtool -show-build "$APP/Contents/MacOS/$APP_NAME" 2> /dev/null || true)"
  minos="$(awk '$1 == "minos" { print $2; exit }' <<< "$build_version")"
  sdk="$(awk '$1 == "sdk" { print $2; exit }' <<< "$build_version")"
  if [ -n "$sdk" ]; then
    echo "LC_BUILD_VERSION: minos $minos, sdk $sdk"
    if [ "$sdk" = "$minos" ]; then
      echo "warning: the binary records SDK $sdk, the same as its deployment target." >&2
      echo "warning: build with -Xlinker -platform_version -Xlinker macos -Xlinker $minos -Xlinker \$(xcrun --sdk macosx --show-sdk-version)" >&2
    fi
  fi
fi

echo "==> Zipping"
ZIP="$BUILD_DIR/$APP_NAME-$VERSION.zip"
ditto -c -k --keepParent "$APP" "$ZIP"

echo "Built $APP"
echo "Built $ZIP"
