# Local playback and MP3 conversion

Tap a completed video or audio file in Downloads to play it. The row menu also offers conversion for video, sharing and an external app chooser. **Choose Video to Convert** opens Android’s local document picker. Conversions work on one file at a time and preserve the original. Find the resulting MP3 in Downloads → Audio or in Music/SAOREO. The Transcript tab opens existing TXT, SRT and VTT files locally.

## Build

Media3 1.11.1 uses compile SDK 36 and supports this project’s existing min SDK 33. Build the conversion component before Gradle:

```sh
tools/build-offline-encoder.sh /path/to/android-ndk-r28c
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Pass `arm64-v8a` or `x86_64` as the second script argument for a single ABI, and pass the same ABI as `-PsaoreoAbi` to Gradle. The `host` argument builds a Linux test executable with the same enabled formats. See the script for prerequisite versions and SHA-256 digests. It uses the official NDK revision 28.2.13676358. The official FFmpeg 8.1.3 source signature was checked against the FFmpeg release key with fingerprint `FCF9 86EA 15E6 E293 A564 4F10 B432 2F04 D676 58D8`. To build without fetching source archives, supply their directory through `SAOREO_ENCODER_SOURCES`. Android artifacts are PIE binaries with 16 KiB load alignment.

Normal builds keep package `com.codetic.saoreo`. The supplied test build uses `-PsaoreoSeparateInstall=true` and package `com.codetic.saoreo.media` because the previous signing keys were unavailable. Android keeps private settings and download history separate for different packages. Existing media files remain accessible through Android’s file picker when authorized.

## Conversion implementation

SAOREO builds FFmpeg 8.1.3 with LAME 3.100. This offline executable is independent of the existing downloader's FFmpeg bundle and uses no FFmpegKit dependency. The build explicitly enables MP4 and Matroska/WebM demuxing, common audio decoders, the MP3 muxer and `libmp3lame`. Video decoding and network support are disabled. FFmpeg selects the first audio stream, discards video, subtitles, other tracks and source metadata, and encodes stereo MP3 at 44.1 kHz. Long files are streamed between descriptors and temporary files in fixed-size buffers. A provider can supply non-seekable data, so conversion needs disk space for the staged source and output. Failure, cancellation and restart recovery delete tracked incomplete files. MediaStore assigns the actual filename when a collision occurs. Files are published only after validation.

Conversion runs in a non-exported foreground service started from the visible app. Android 15+ uses `mediaProcessing`; Android 13/14 uses `dataSync`. A local subprocess runs with a bounded FFmpeg allocation size and limited threads. It shares the app UID. A parent-death guard terminates the process when its parent exits. Audio uses a separate Media3 playback service and MediaSession, which keeps playback available when its UI closes. No broad storage permission is needed.

The converter binary uses the LGPL option set. The release's corresponding sources, original license files and exact build script accompany this application. See [engine documentation](ENGINE.md) for licensing of the pre-existing download bundle, which has separate obligations. Treat the signed debug APK as a device test build.

## Verification

The Linux build of the conversion executable encoded real H.264/AAC MP4 media at 128, 192, 256 and 320 kbps. ffprobe confirmed each nominal bitrate and the MP3 codec. Each output passed a full decode, and the source SHA-256 values stayed identical. An Opus/WebM fixture also encoded successfully. A one-hour, 44,090,249-byte MP4 yielded an 86,402,238-byte 192 kbps MP3 in 42.15 seconds on the build host, with 14,508 KiB peak process RSS. ffprobe measured 3,600.065 seconds. These are host measurements. The corrupt-input check rejected an HTML file with an MP4 extension without creating an MP3.

A second ARM64 build from a different working directory produced an identical executable. Its SHA-256 is `7febbddaf7259f98e97839dfcfb23397614f60a01346c065c67e2eca4ab4e0a2`. The x86-64 executable hash is `e77cca1a669b75d798e8f4a35958cafbe82987389bee771274b60bd146c35425`. The Gradle device test suite exercises MediaStore seeking and playback speed, real MP3 publication, source integrity, background playback, cancellation and low storage.

The x86-64 Android encoder also ran on the Android 15 emulator and produced an 8.046-second, 192 kbps MP3 from the real MP4 fixture. This exercises the executable and Android linker independently of the application UI. The ARM64 APK verification checks the Android signature, ZIP alignment and byte-for-byte inclusion of the reproducible ARM64 executable.
