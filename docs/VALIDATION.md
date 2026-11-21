# SAOREO 2.0.0 validation

## Build and APK

The ARM64 debug package was built for `com.codetic.saoreo` with min SDK 33 and target SDK 36. Android Build Tools verified the APK signature and ZIP alignment. The APK contains the pinned yt-dlp 2026.10.26 asset, whose embedded bytes match the official SHA-256. No release signing key was configured. The source archive includes the Gradle Wrapper and the runtime SBOM.

Gradle completed APK assembly, JVM tests, the instrumentation APK build, and Android lint. All 9 JVM tests passed. Lint reported 0 errors and 14 warnings. The debug APK SHA-256 is `d9f2d75591c18e9c517b47a6db0edecff9a31d0f94483444fc83155567ca0b90`.

An ELF scan found 5 libraries with 4 KiB load-segment alignment inside the bundled FFmpeg archive. They are libsharpyuv and four libwebp libraries. APK alignment verification passed. FFmpeg merging on Android devices that use 16 KiB memory pages remains unverified and may fail.

The JVM suite covers supported host recognition, malformed links and share text, quality caps, size estimates, Pinterest image and video distinction, public Snapchat CDN metadata, safe names, and private-host rejection. The instrumented suite exercises the Android Python/QuickJS startup, Room, DataStore, a denied folder grant, real MediaStore PNG publication, publication rollback, and duplicate local imports.

## Live host checks

These checks used the bundled yt-dlp payload on the build host with Node where applicable. They exercised public URLs without account credentials. They do not substitute for Android results.

| Source | Result |
| --- | --- |
| YouTube | *Big Buck Bunny* metadata resolved. The attempted 360p transfer returned a 195-byte HTML error. ffprobe rejected it. A separate timed metadata run reached its 25-second deadline. |
| Instagram | One Reel returned 720 × 1280 metadata in 17.07 seconds. |
| TikTok | A sample video returned an IP restriction. |
| X | One upstream video fixture returned 720 × 720 metadata in 18.76 seconds. |
| Snapchat Spotlight | The stock extractor missed one public page. Its Open Graph data exposed a 540 × 960 MP4. The app parser resolved the captured page. The declared CDN returned HTTP 200 with `Content-Type: video/mp4`, a length of 583,698 bytes, and byte range support in a 4.80-second HEAD check. |
| Pinterest video | Two examples returned 1080 × 1920 and 640 × 1136 respectively. One timed metadata check took 12.12 seconds. The app parser correctly treated three captured video pages as non-image. |
| Pinterest image | JVM fixtures checked original asset selection and exclusion of video posters. Image gallery saving from a live Pin remains to be verified. |

A successful host transfer to playable media was not established. The build records monotonic startup, popup, engine initialization, metadata, queue acceptance and first transfer write or body-read timings. The host timings above describe these particular requests and are unsuitable for predicting mobile latency.

## Android runtime

The Android 13 x86-64 software emulator reached the package manager, then Android rejected the install while its Settings provider was still initializing. The follow-up emulator startup exited with a pending snapshot operation. No Android installation completed. The device instrumentation suite, Share Sheet launch, startup measurements, WhatsApp cache access, gallery playback, and slow-network recovery remain unverified on Android.

## Device test checklist

1. In Android 13 or newer, install the APK, open Home, paste a permitted source link, and check recognition, preview and the exact or approximate size.
2. Use the Android Share Sheet to open the compact dialog. Check quality selection, immediate dismissal after queue acceptance, and progress notifications.
3. Save a known authorized video. Check picture, audio, final byte count and gallery entry. Disconnect and reconnect the network during an eligible resumable transfer.
4. Try a public image Pin and a public video Pin. Check that the image retains its original file format.
5. View a WhatsApp status in WhatsApp first. Connect only the accessible status folder or use the import picker. Confirm offline playback or preview, duplicate detection, gallery saving and persisted access after reopening.
6. Exercise light and dark themes, large text, a small viewport, landscape and the keyboard. Read Android timings using `adb logcat -s SAOREO`.
