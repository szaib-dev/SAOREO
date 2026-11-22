# Instagram transfers and playlist imports

## Instagram

Direct CDN transfers and extractor transfers now share an Instagram preparation step. Already readable MP4 files pass through unchanged. Files rejected by the Android media reader are remuxed into a fresh MP4; if that still fails validation, FFmpeg converts the video to H.264 / yuv420p and audio to AAC. The replacement must pass the existing readable-track checks before publication. HTML, JSON, empty files and incomplete transfers remain rejected. The direct-transfer fallback clears stale metadata before extractor recovery.

This is a recovery path for unreadable containers, not evidence that every Instagram failure has the same cause. A specific failing Reel and an Android device are required for end-to-end confirmation.

## YouTube

Paste or share a playlist URL, including a watch URL with a list parameter. Playlist links always open the picker, even with automatic shared downloads enabled. Flat extraction no longer silently stops at 100 entries. The picker supports select all, clear, individual choices, disabled unavailable entries, MP3 or MP4, and per-item queuing failures. Audio imports can create a named Music playlist and preserve the source cover and link. Existing queue constraints and notifications apply to every queued download.

## Spotify

Paste a public `open.spotify.com/playlist/...` URL in the regular link field or use Music > Playlists > Import YouTube / Spotify playlist. This reads metadata from Spotify's public embed page without collecting account credentials. It does not download Spotify audio or substitute 30-second previews for full songs.

Selected tracks are matched against YouTube search results using title, artist and duration when available; obvious cover, remix, live, slowed and karaoke mismatches are rejected unless requested in the original title. Matches are still heuristic and cannot guarantee identical recordings. Original track names and artists are retained as editable local metadata. Failed matches remain selected for retry, while successful jobs stay queued.

The public Spotify embed is not a complete-playlist API. It can return only a subset of a long playlist, omit private/unavailable tracks, or change its page structure. The picker explicitly reports the number of returned tracks and warns about this limitation. Complete authenticated Spotify pagination would require a registered Spotify application and an OAuth flow; that is not configured in this repository.

## Persistence

Room schema 4 adds nullable playlist cover and source URL fields with a non-destructive 3-to-4 migration. Rename, add/remove songs and delete playlist continue using the existing collection operations. Deleting a playlist keeps saved audio files.

## Validation (22 November 2026)

- `:app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin -x :app:preBuild`: successful with JDK 21 and Android SDK 36. All 58 JVM tests passed; lint reported 0 errors and 49 warnings. An existing Together screen indentation error was corrected without changing behavior.
- The native pre-build guard was excluded for source validation because generated offline MP3/Whisper native executables are not checked into this repository. No APK was assembled or installed.
- Android instrumentation test sources compile, including Instagram container repair/rejection and playlist migration/artwork persistence tests. No device or emulator was connected, so these tests were not executed.
- A public Instagram test Reel was extracted and downloaded using the bundled engine. Host FFmpeg successfully remuxed a copy with trailing container damage; ffprobe confirmed its video stream. This does not establish Android playback or reproduce the user's specific failing Reel.
- Public Spotify embed metadata was retrieved and its current JSON structure inspected. No complete Spotify-to-YouTube playlist download was verified on an Android device.
- Schema comparison confirms only the two nullable playlist fields were added. SQLite migration checks preserved an existing playlist. Imported playlist membership is reserved when queuing to keep source order regardless of completion order.
