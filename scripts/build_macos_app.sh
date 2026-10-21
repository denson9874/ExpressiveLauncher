#!/usr/bin/env bash
set -euo pipefail

# Build script for ExpressiveKeyStudio native macOS Application
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$REPO_ROOT/tools/macos/ExpressiveKeyStudio.app"
CONTENTS_DIR="$APP_DIR/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
RESOURCES_DIR="$CONTENTS_DIR/Resources"

echo "Building ExpressiveKeyStudio.app (Native SwiftUI / CryptoKit)..."

mkdir -p "$MACOS_DIR"
mkdir -p "$RESOURCES_DIR"

# Detect best available SDK
SDK_ARG=""
if [ -d "/Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk" ]; then
  SDK_ARG="-sdk /Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk"
elif [ -d "/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk" ]; then
  SDK_ARG="-sdk /Library/Developer/CommandLineTools/SDKs/MacOSX.sdk"
fi

# Build AppIcon.icns from the official Expressive Bloom logo
SRC_ICON="$REPO_ROOT/docs/assets/expressive/expressive-bloom.png"
if [ ! -f "$SRC_ICON" ]; then
  SRC_ICON="$REPO_ROOT/fastlane/metadata/android/en-US/images/icon.png"
fi

if [ -f "$SRC_ICON" ]; then
  echo "Generating AppIcon from Expressive logo ($SRC_ICON)..."
  ICONSET_TMP="$(mktemp -d)/AppIcon.iconset"
  mkdir -p "$ICONSET_TMP"
  sips -z 16 16     "$SRC_ICON" --out "$ICONSET_TMP/icon_16x16.png" >/dev/null 2>&1
  sips -z 32 32     "$SRC_ICON" --out "$ICONSET_TMP/icon_16x16@2x.png" >/dev/null 2>&1
  sips -z 32 32     "$SRC_ICON" --out "$ICONSET_TMP/icon_32x32.png" >/dev/null 2>&1
  sips -z 64 64     "$SRC_ICON" --out "$ICONSET_TMP/icon_32x32@2x.png" >/dev/null 2>&1
  sips -z 128 128   "$SRC_ICON" --out "$ICONSET_TMP/icon_128x128.png" >/dev/null 2>&1
  sips -z 256 256   "$SRC_ICON" --out "$ICONSET_TMP/icon_128x128@2x.png" >/dev/null 2>&1
  sips -z 256 256   "$SRC_ICON" --out "$ICONSET_TMP/icon_256x256.png" >/dev/null 2>&1
  sips -z 512 512   "$SRC_ICON" --out "$ICONSET_TMP/icon_256x256@2x.png" >/dev/null 2>&1
  sips -z 512 512   "$SRC_ICON" --out "$ICONSET_TMP/icon_512x512.png" >/dev/null 2>&1
  sips -z 1024 1024 "$SRC_ICON" --out "$ICONSET_TMP/icon_512x512@2x.png" >/dev/null 2>&1
  iconutil -c icns "$ICONSET_TMP" -o "$RESOURCES_DIR/AppIcon.icns"
  cp "$SRC_ICON" "$RESOURCES_DIR/AppIcon.png"
  rm -rf "$(dirname "$ICONSET_TMP")"
fi

# Collect all Swift source files
SWIFT_SOURCES=(
  "$REPO_ROOT/tools/macos/src/Crypto/LicenseEncoder.swift"
  "$REPO_ROOT/tools/macos/src/Crypto/KeyStore.swift"
  "$REPO_ROOT/tools/macos/src/Services/CloudflareClient.swift"
  "$REPO_ROOT/tools/macos/src/Services/AdbBridge.swift"
  "$REPO_ROOT/tools/macos/src/UI/SingleKeyView.swift"
  "$REPO_ROOT/tools/macos/src/UI/BatchKeyView.swift"
  "$REPO_ROOT/tools/macos/src/UI/IssuedKeysView.swift"
  "$REPO_ROOT/tools/macos/src/UI/KeyInspectorView.swift"
  "$REPO_ROOT/tools/macos/src/UI/AdbDevicesView.swift"
  "$REPO_ROOT/tools/macos/src/UI/CloudflareKvView.swift"
  "$REPO_ROOT/tools/macos/src/UI/SettingsView.swift"
  "$REPO_ROOT/tools/macos/src/UI/ContentView.swift"
  "$REPO_ROOT/tools/macos/src/UI/MenuBarView.swift"
  "$REPO_ROOT/tools/macos/src/main.swift"
)

# Compile Swift sources into native binary
swiftc -O \
  $SDK_ARG \
  -framework Cocoa \
  -framework SwiftUI \
  -framework CryptoKit \
  -framework UniformTypeIdentifiers \
  "${SWIFT_SOURCES[@]}" \
  -o "$MACOS_DIR/ExpressiveKeyStudio"

# Write Info.plist
cat <<EOF > "$CONTENTS_DIR/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>ExpressiveKeyStudio</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundleIdentifier</key>
    <string>dev.launcher.expressive.keystudio</string>
    <key>CFBundleName</key>
    <string>Expressive Key Studio</string>
    <key>CFBundleDisplayName</key>
    <string>Expressive Key Studio</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>2.0.0</string>
    <key>CFBundleVersion</key>
    <string>2</string>
    <key>LSMinimumSystemVersion</key>
    <string>12.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSAppTransportSecurity</key>
    <dict>
        <key>NSAllowsLocalNetworking</key>
        <true/>
        <key>NSAllowsArbitraryLoads</key>
        <true/>
    </dict>
</dict>
</plist>
EOF

chmod +x "$MACOS_DIR/ExpressiveKeyStudio"

echo "Built successfully at: $APP_DIR"
