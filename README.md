# Strato

An iOS port of a Kotlin Multiplatform app that stores your files in a private Telegram channel
on your own account. There is no server; the app talks to Telegram directly through TDLib and
keeps a local index for fast browsing.

> Work in progress. The iOS build currently shows a placeholder screen that proves the Kotlin
> framework and TDLib are linked on device.

## Building the IPA

The unsigned IPA is built by GitHub Actions (`.github/workflows/ios.yml`) on a macOS runner and
uploaded as the `Strato-unsigned-ipa` artifact. Install it with AltStore or Sideloadly, which
re-sign it with your Apple ID.

Locally, on macOS:

```bash
bash iosApp/scripts/fetch-tdlib.sh      # prebuilt static TDLib, SHA-256 pinned
cd iosApp && xcodegen generate          # writes Strato.xcodeproj
open Strato.xcodeproj
```

On any host, the Kotlin side can be compiled for iOS without Xcode:

```bash
./gradlew :shared:compileKotlinIosArm64 :ui:compileKotlinIosArm64
./gradlew :shared:desktopTest           # engine test suite
```

## How the iOS side is wired

- `iosApp/` is a SwiftUI host generated with XcodeGen. It embeds the Compose UI from the
  `StratoKit` framework built by the `:ui` module.
- TDLib is the static `TDLibFramework` xcframework, called from Swift (`TdJsonBridge`) through
  its JSON interface; Kotlin sees it as `TdJsonTransport`, used by `IosTelegramClient`.
- AES-GCM uses CryptoKit (`CryptoKitAesGcm`). The file and key-backup formats live in common
  Kotlin code and are checked against fixtures produced by the Android implementation, so a
  drive written on Android opens on iOS and vice versa.

You need your own Telegram API credentials from [my.telegram.org](https://my.telegram.org).

## Credits and license

Based on [TeleDrive](https://github.com/Mahmud0808/TeleDrive) by Mahmud0808, licensed under the
Apache License 2.0. This fork keeps that license; see [LICENSE](LICENSE). Changes in this fork
add the iOS targets, the Swift host app and the iOS platform code.

This project is independent and not affiliated with or endorsed by Telegram.
