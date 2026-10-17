#!/usr/bin/env bash
# Builds SpeakerLink.app and a zip of it for distribution.
#
#   scripts/build-app.sh               release build for this Mac's architecture
#   scripts/build-app.sh --universal   release build for arm64 and x86_64 (needs full Xcode)
#
# Output: build/SpeakerLink.app and build/SpeakerLink-<version>.zip (inside mac/).
#
# Signing: with SPEAKERLINK_SIGN_IDENTITY set (for example
# "Developer ID Application: Jane Doe (TEAMID1234)"), the app is signed with that identity,
# the hardened runtime and a secure timestamp, ready for notarization. Otherwise it is signed
# ad hoc with a designated requirement that names only the bundle identifier, so macOS keeps the
# Bluetooth permission when the app is replaced by a newer build.
#
# Works with the bash 3.2 that ships with macOS. Can be run from any working directory.
set -euo pipefail

APP_NAME="SpeakerLink"
BUNDLE_ID="io.github.cyrus-sch.speakerlink"

usage() {
  cat << 'EOF'
Usage: scripts/build-app.sh [--universal]

Builds build/SpeakerLink.app and build/SpeakerLink-<version>.zip (inside mac/).

  --universal   build for arm64 and x86_64 (needs full Xcode)

Set SPEAKERLINK_SIGN_IDENTITY to a "Developer ID Application: ..." identity to sign for
notarization; without it the app is signed ad hoc.
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

universal=0
for arg in "$@"; do
  case "$arg" in
    --universal) universal=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown argument: $arg"
      ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mac_dir="$(cd "$script_dir/.." && pwd)"
cd "$mac_dir"

for tool in swift codesign ditto iconutil plutil sips xattr lipo; do
  command -v "$tool" > /dev/null || die "$tool not found (this script needs macOS with Xcode or the Command Line Tools)"
done

version="$(tr -d '[:space:]' < VERSION)"
build_number="$(tr -d '[:space:]' < BUILD)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "VERSION must look like 1.2.3, found '$version'"
[[ "$build_number" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]] || die "BUILD must be 1 to 3 period-separated integers, found '$build_number'"

plist_template="$mac_dir/App/Info.plist"
icon_source="$mac_dir/App/AppIcon-1024.png"
[ -f "$plist_template" ] || die "missing $plist_template"
[ -f "$icon_source" ] || die "missing $icon_source"

build_args=(-c release)
if [ "$universal" -eq 1 ]; then
  build_args+=(--arch arm64 --arch x86_64)
fi

echo "==> swift build ${build_args[*]}"
swift build "${build_args[@]}"
# The products directory differs between build systems and between single- and multi-arch
# builds, so ask SwiftPM instead of guessing.
bin_dir="$(swift build "${build_args[@]}" --show-bin-path)"
binary="$bin_dir/$APP_NAME"
[ -x "$binary" ] || die "no executable at $binary"

out_dir="$mac_dir/build"
app="$out_dir/$APP_NAME.app"
zip="$out_dir/$APP_NAME-$version.zip"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/speakerlink-build.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT

echo "==> Assembling $app"
rm -rf "$app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$binary" "$app/Contents/MacOS/$APP_NAME"
printf 'APPL????' > "$app/Contents/PkgInfo"

plist="$app/Contents/Info.plist"
sed -e "s/__VERSION__/$version/g" -e "s/__BUILD__/$build_number/g" "$plist_template" > "$plist"
plutil -lint "$plist" > /dev/null || die "$plist is not a valid property list"
if grep -Eq '__(VERSION|BUILD)__' "$plist"; then
  die "placeholders left in $plist"
fi
for pair in "CFBundleIdentifier=$BUNDLE_ID" "CFBundleExecutable=$APP_NAME" \
  "CFBundleShortVersionString=$version" "CFBundleVersion=$build_number"; do
  key="${pair%%=*}"
  expected="${pair#*=}"
  actual="$(plutil -extract "$key" raw -o - "$plist")" || die "$key is missing from $plist_template"
  [ "$actual" = "$expected" ] || die "$key in $plist is '$actual', expected '$expected'"
done

echo "==> Making AppIcon.icns"
iconset="$work_dir/AppIcon.iconset"
mkdir -p "$iconset"
for size in 16 32 128 256 512; do
  sips -s format png -z "$size" "$size" "$icon_source" --out "$iconset/icon_${size}x${size}.png" > /dev/null
  double=$((size * 2))
  sips -s format png -z "$double" "$double" "$icon_source" --out "$iconset/icon_${size}x${size}@2x.png" > /dev/null
done
iconutil -c icns "$iconset" -o "$app/Contents/Resources/AppIcon.icns"

# Extended attributes (Finder info, quarantine) make codesign fail with "resource fork, Finder
# information, or similar detritus not allowed".
xattr -cr "$app"

if [ -n "${SPEAKERLINK_SIGN_IDENTITY:-}" ]; then
  echo "==> Signing with $SPEAKERLINK_SIGN_IDENTITY (hardened runtime)"
  codesign --force --options runtime --timestamp --sign "$SPEAKERLINK_SIGN_IDENTITY" "$app"
else
  echo "==> Signing ad hoc"
  # An ad-hoc signature's default designated requirement is its code hash, which changes with
  # every build, and macOS would then ask for the Bluetooth permission again after each update.
  codesign --force --sign - --requirements "=designated => identifier \"$BUNDLE_ID\"" "$app"
fi
codesign --verify --strict --verbose=2 "$app"

archs="$(lipo -archs "$app/Contents/MacOS/$APP_NAME")"
if [ "$universal" -eq 1 ]; then
  for arch in arm64 x86_64; do
    case " $archs " in
      *" $arch "*) ;;
      *) die "the universal build lacks $arch (found: $archs)" ;;
    esac
  done
fi

echo "==> Zipping $zip"
rm -f "$zip"
ditto -c -k --keepParent "$app" "$zip"

echo
echo "Built $APP_NAME $version ($build_number), architectures: $archs"
echo "  $app"
echo "  $zip"
