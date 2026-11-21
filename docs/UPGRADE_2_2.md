# SAOREO 2.2

## Application behavior

- Transcript accepts supported video URLs, imports TXT/SRT/VTT, retrieves public caption files, and offers on-device speech recognition when needed. Caption lookup uses the existing extractor with media saving disabled. Caption languages can be refreshed independently of the saved transcript. Successful results are stored privately with a 40-entry cache and a clear action. Export uses Android's document picker and UTF-8. Very large clipboard results use a read-only text URI to fit Android's transaction size.
- Tiny and Base multilingual models are downloaded only after confirmation. Model files are SHA-256 checked on download and before every use. Media is opened through a read grant or downloaded through the established extractor. FFmpeg prepares mono 16 kHz audio in eight-minute chunks. The isolated CLI runs two compute threads and performs speech recognition without networking. A single foreground task handles model installation or recognition at a time. Android can stop background execution under platform limits.
- Downloads opens on All, with saved items displayed in the appropriate internal player or image viewer. Playback controls use vector icons and adaptive chrome. The five destinations remain available by icon and accessible name when there is insufficient room for labels.
- The status gallery queries the permitted folder's immediate children and supports local document imports. File timestamps and neutral labels identify the displayed media. A cache file can be deleted only after granting write access and confirming the specific operation. A saved copy or local visibility entry can be managed separately.
- WorkManager continues to own transfers, retries and foreground execution. Foreground notifications report measured byte counts and transfer speed when provided, and distinguish processing, merging, saving and connection delays. A direct CDN transfer can resume with an unchanged strong ETag and a valid Content-Range. yt-dlp retains its existing resumable download path. Android notification permission, battery settings and system execution quotas still apply.

## Reproducible native build

Build both ABIs for a universal debug APK, or use the same single ABI in each command and in the Gradle property.

```sh
tools/build-offline-encoder.sh /path/to/android-ndk-r28c arm64-v8a
tools/build-whisper.sh /path/to/android-ndk-r28c arm64-v8a
./gradlew -PsaoreoAbi=arm64-v8a :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Use `SAOREO_ENCODER_SOURCES="$PWD/encoder-sources"` to build FFmpeg/LAME from the accompanying archives. The Whisper script can download the pinned source archive or use the bundled archive renamed `whisper-1.8.3.tar.gz` in `SAOREO_WHISPER_WORK`. CMake, Make and the Android NDK r28c are required. Whisper.cpp 1.8.3 and its associated model distribution use MIT notices. The offline FFmpeg/LAME executable retains its LGPL configuration. See [offline media](OFFLINE_MEDIA.md) and [engine licensing](ENGINE.md) for additional component notes.

The regular application ID is unchanged. The supplied APK uses the alternate debug package because the previous signing key was unavailable. It has separate private settings, history, and model storage.

## Verification

- The bundled yt-dlp extracted 47 manual and 157 automatic language entries for a public YouTube test video. An actual English SRT track was retrieved successfully (16,956 bytes). A separately tested URL exposed zero caption tracks.
- A real HTTPS MP3 download exercised the exact production yt-dlp progress template. It emitted 1,024 through 76,447 transferred bytes, a real total of 76,447, measured speeds and post-processing markers. The resulting file probed as MP3.
- The rebuilt host FFmpeg normalized that same recording, and the pinned Whisper engine recognized the sample speech from a SHA-256-verified Tiny model with automatic language detection. A separate MP4-to-MP3 conversion yielded a decodable 192 kbps MP3, with an identical SHA-256 for the untouched source video.
- Unit coverage includes caption parsing, Unicode, duplicate cues, timestamp offsets, and determinate and indeterminate progress. Android app-side interactions require a usable device or emulator.
- A two-stream HTTPS transfer reported distinct video and audio records totaling 911,010 bytes, emitted a merge event, and produced a file with both tracks.
