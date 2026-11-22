# Together playback, local streaming and full-call mode (2.9.2)

## Playback failure

`TogetherPlayback.report()` previously compared a normalized `(ready, buffering)` value that excluded YouTube buffering, but sent the raw buffering flag. The server could therefore receive `buffering=true`, pause the room, and never receive the corresponding clear because the cached normalized status had not changed. The outgoing payload now uses the same normalized status as the comparison. Native Media3 buffering still participates in the optional shared wait policy.

The iframe's PLAYING callback also immediately paused against the currently cached room timeline, which could lag a play command. Playback decisions now stay with the shared timeline driver. The driver tracks actual playback and retries a rejected play command after 2.5 seconds when the player is not buffering, without repeatedly loading the video. Released iframe callbacks are ignored.

Regression tests exercise the production driver with fake room state, clocks and command sinks; they verify the exact reported buffering flags, readiness after reconnect, retries and stable playing behavior.

## One local file, two viewers

The picker now registers a seekable content URI selected on the source phone. The URI stays on that phone. The server receives only an opaque source ID, size and MIME type; the room media descriptor contains the title/duration. The source phone plays its own URI directly. The partner opens an authenticated HTTP range endpoint through Media3.

For each requested range, the server asks the source phone over its authenticated room WebSocket for at most 128 KiB. The phone seeks directly to that offset, reads the requested bytes and responds. The server forwards bytes while awaiting receiver backpressure before requesting more. Seeking near the end of a movie does not require transferring everything before it, including MP4 files whose index is at the end.

The relay stores no movie on disk. There are at most eight active HTTP readers and eight outstanding chunk requests, with a 15-second source-read timeout. Chunks must come from the registered owner, belong to the same room and have the exact requested length. Invalid or truncated first chunks return an error; failures after response headers close the connection rather than marking a partial movie complete. Room participants use their own Bearer tokens; invitation links do not provide media access.

MP4, WebM and MKV files up to 20 GiB are accepted when the Android provider permits seeking and the file has a readable video track. Playback still depends on each phone's codec support and the source phone's upload bandwidth. The source phone must remain in the room with the selected file available. The app does not enable background screen capture or DRM extraction.

The old upload routes remain available for older clients. New local selections use the byte-range relay, not the old 100 MiB full-upload flow or the same-file-on-both-phones workflow.

## Full-screen call

Full-call mode is a room setting synchronized to both participants. Entering it requires both participants to have already joined the call; it does not activate either person's microphone or camera without their existing permission/action. Returning to the watch view updates both phones.

The call is rendered as an edge-to-edge overlay. It no longer sits below a reserved 110 dp movie area. The main camera uses aspect-fill, the local camera is a floating preview, and controls overlay the camera. Opening chat splits the available area equally between video and chat; tapping the video or pressing Back closes chat first. The movie player stays composed behind the call so switching views does not replace its player or call connection.

Netflix continues to open in its own app. Netflix shared playback/DRM streaming is not implemented by this change.

## Deployment and verification

Both phones need 2.9.2 and the Together server needs protocol 3. Updated health responses advertise version 1.1, `protocol: 3`, `rangeRelay` and `sharedFullCall`.

The live server was inspected read-only during implementation and reported `{"status":"ok","version":"1.0"}`. It has not been updated. The deployment script now packages the relay and its tests, but deployment requires SSH access to that server. Deploying restarts the service and ends existing in-memory rooms, so create a new room after updating both clients.

No APK is packaged or installed by the source checks. The repository's generated MP3/Whisper native executables remain prerequisites for a full APK build. No physical phone or emulator is connected; actual two-device playback, camera rendering and layout checks remain outstanding.

Source validation completed on 22 November 2026:

- `:app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin -x :app:preBuild`: successful. 61 JVM tests passed, including the production playback-driver regression tests. Android test sources compile, including the selected-file metadata/seek check. Lint: 0 errors, 51 warnings. The native preBuild guard was excluded for the source-only checks, as in the preceding upgrade.
- `python -m unittest discover -s server/together -p 'test_*.py'`: 18 tests passed. Coverage includes a synthetic 500 MiB source with a direct seek to byte 300,000,000, multi-chunk streaming with exact lengths, malformed ranges, truncated/offline sources, room authorization, spoofed chunk replies, owner removal, old-client rejection and shared full-call transitions that preserve movie playback.
- `git diff --check`: passed.
