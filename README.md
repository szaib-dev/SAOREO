# SAOREO

Native Android downloader and local WhatsApp status viewer. Kotlin and Jetpack Compose, with a bundled on-device extractor. Minimum Android 13 (API 33), target Android 16 (API 36). The default build includes ARM64 and x86-64. The delivered APK is ARM64.

## Install or build

The debug APK is signed with a development key. On Android, open the APK and permit installation from the app that opened it if prompted. From a computer with Android Platform Tools and USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Install an Android SDK with API 36 and Build Tools 35.0.0 or use Android Studio to sync the project. Set `ANDROID_HOME` to the SDK location. Use a full JDK 17 or 21.

```bash
./gradlew -PsaoreoAbi=arm64-v8a :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
./gradlew -I gradle/runtime-inventory.gradle :app:writeRuntimeInventory
```

On Windows use `gradlew.bat`. Connected tests require an Android device or emulator. The release configuration deliberately has no release key.

## Use

- **Home** recognizes a supported host immediately and retrieves choices asynchronously. Accurate file sizes appear when the source exposes a byte count. Bitrate and combined stream estimates are marked with a tilde. Unknown sizes are labeled.
- **Share** accepts a URL in Android shared text. It can also save a photo or video explicitly shared to the app. Pasting a link on Home queues your default quality automatically unless Always Ask is selected. Home shows MP4 and MP3 presets, then fills in the actual qualities after the lookup. Shared links also queue automatically with a default quality. Disable Auto-download shared links to require confirmation from other apps. Allow Android notifications from Settings to see progress and results.
- **Downloads** tracks active, completed and failed transfers. The app publishes to `Movies/SAOREO`, `Pictures/SAOREO` or `Music/SAOREO`.
- **Statuses** connects independently to the user-selected WhatsApp and WhatsApp Business folders. Open a status in WhatsApp to let it cache, then refresh. The expected folder names commonly include `Android/media/com.whatsapp/WhatsApp/Media/.Statuses` and `Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses`. The app lists supported media directly inside the selected folder. If the Android picker hides the status directory, import accessible photos or videos through the file picker. Status access is offline and depends on the local cache and Android's persisted grant. Expired, unseen, and removed statuses may be absent.
- **Settings** controls the quality cap, preferred container, parallel transfers, unmetered-only background transfers, retries, status permissions, appearance, notifications, cache and the optional floating shortcut. The shortcut opens Home and uses the app's regular Paste button.

## Engine and security

See [dependency and security notes](docs/ENGINE.md) for version selection, license obligations, path and request controls, and known platform limitations. See [validation](docs/VALIDATION.md) for build and runtime evidence.

See [YouTube diagnostics](docs/YOUTUBE_DIAGNOSTICS.md) for the diagnostic build, measured host traces, and Android benchmark instructions.

See [SAOREO 2.0.2 validation](docs/RELIABILITY_2_0_2.md) for the engine comparison and download checks.

## Built-in playback and offline conversion

Play completed video and audio from Downloads. Use **Choose Video to Convert** to encode a local video to MP3 and save it to Music/SAOREO. See [offline media](docs/OFFLINE_MEDIA.md) for build setup, source verification and runtime details.

## SAOREO 2.2

See [the 2.2 upgrade notes](docs/UPGRADE_2_2.md) for the Transcript tools, progress notifications, media and Status changes, pinned native dependencies, and verification results.
