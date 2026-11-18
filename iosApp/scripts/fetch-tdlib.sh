#!/usr/bin/env bash
# Downloads the prebuilt static TDLib xcframework (Swiftgram/TDLibFramework) into
# iosApp/Frameworks/ and checks it against a pinned SHA-256. Run from anywhere on macOS.
set -euo pipefail

TDLIB_TAG="1.8.69-4c6377aa"   # TDLib 1.8.69, tdlib/td commit 4c6377aa
TDLIB_SHA256="c86ca527be4adb4e2562693176b28a74290a86e9a55988af5c169420c6b77d49"

cd "$(dirname "$0")/.."
DEST="Frameworks"
if [ -d "$DEST/TDLibFramework.xcframework" ]; then
  echo "TDLibFramework already present"
  exit 0
fi

mkdir -p "$DEST"
ZIP="$(mktemp -d)/TDLibFramework.zip"
curl -fL --retry 3 -o "$ZIP" \
  "https://github.com/Swiftgram/TDLibFramework/releases/download/${TDLIB_TAG}/TDLibFramework.zip"
echo "${TDLIB_SHA256}  ${ZIP}" | shasum -a 256 -c -

# Only the iOS device and simulator slices are needed.
unzip -q "$ZIP" \
  "TDLibFramework.xcframework/Info.plist" \
  "TDLibFramework.xcframework/ios-arm64/*" \
  "TDLibFramework.xcframework/ios-arm64_x86_64-simulator/*" \
  -d "$DEST"
rm -f "$ZIP"
echo "TDLibFramework ${TDLIB_TAG} ready in iosApp/${DEST}"
