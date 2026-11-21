# SAOREO YouTube diagnostics

This build adds opt-in tracing to the existing extractor. The production client selection, retry and timeout settings, output selection, and download path are unchanged. The installed Python tree and the app's five-minute metadata cache are reused as before. An uncached extraction still launches a new Python process.

## Observed root cause on the build host

The pinned yt-dlp 2026.10.26 payload was run with Node on the build host's network. A standalone call took about 28 seconds and returned 44 formats. Two profiled calls using default clients took about 28 and 29 seconds and returned 44 formats. These numbers include the host's network route and proxy. They do not quantify the user's mobile network or Android QuickJS performance.

The latest successful 29-second trace recorded these inclusive spans:

| Stage | Elapsed |
| --- | ---: |
| yt-dlp imports | 1.043 s |
| JavaScript runtime probe | 0.007 s |
| YouTube watch page | 5.334 s |
| Two player API requests | 4.719 s and 4.687 s |
| Player JavaScript HTTP request | 4.715 s |
| JavaScript challenge subprocess communication | 2.279 s |
| Googlevideo manifest request | 4.972 s |
| Whole upstream format and subtitle traversal | 12.266 s |

The last row includes the player-script fetch, JavaScript challenge, and manifest request. Summing those rows again would double-count them. The five listed HTTP spans consumed about 24.4 seconds. This isolates the slow part of the host run to network operations made by the upstream extraction path. It cannot isolate YouTube server handling from proxy and transport delays.

A separate profiled run took about 42 seconds and failed. Its watch-page span took 10.244 seconds and ended with HTTP 429. The engine made additional fallback calls, including three player API requests totaling 14.534 seconds. No intentional sleep was recorded in the timed profiled calls. The web-only and TV-only experiments finished in about 8 and 22 seconds but returned zero formats. Those settings were excluded from the app's normal extraction path.

An actual host media transfer produced a 195-byte HTML response even though the extractor process exited successfully. ffprobe rejected it. No complete playable download is verified in this session. The infrastructure had no connected physical Android device. The software emulator was killed during boot before installation could begin, so there are no measured Android cold or warm timings, no mobile-network diagnosis, and no speedup result.

See `youtube-host-traces.txt` for the sanitized event streams. Each nonblank stderr line is represented by a safe category. When recognized, the category includes a named player client, HTTP status or retry number. Unknown text is represented by a redacted marker. URLs, video IDs, arguments, IP addresses, credentials, tokens, headers and local paths are omitted.

## Capture a trace on a phone

1. Install the diagnostic APK. Its package and signing key match the prior debug build.
2. Open **Settings → Privacy and Storage → Diagnostics**. Set **Capture mode** to **Detailed profiler**.
3. Tap **Clear extraction cache** and analyze an authorized public YouTube URL from Home. The app also captures the Share Sheet and worker routes when used.
4. Return to **Diagnostics → Copy report**. The report is held in memory. Copy it before force-stopping the app.
5. Repeat with **Current integration** or **Standalone CLI**, clearing the extraction cache between comparisons. Leave the cache intact for a cache-hit measurement. Compare a different network only as a separate run.

The detailed path records app and worker timing, native package initialization, Python entry, yt-dlp imports, guarded proxy DNS and TCP timings, HTTP spans, player requests, JavaScript probes and challenges, observed sleeps, extractor traversal, JSON parsing, and result delivery. The worker measurement covers the call that sets up WorkManager's foreground execution. Home and Share Sheet metadata do not start that service. The standalone CLI uses the same bundled Python, QuickJS, ffmpeg paths, extractor flags and guarded proxy. The current-integration path timestamps the Kotlin wrapper boundary and captures its buffered stderr at return. Its stderr lines carry no individual arrival times.

## Repeatable Android benchmarks

Install the matching main and instrumentation APKs on one ADB-connected device, keep the network and reference link fixed, then run:

```bash
python tools/benchmark-youtube.py 'https://www.youtube.com/watch?v=VIDEO_ID' --mode WRAPPER --output baseline
python tools/benchmark-youtube.py 'https://www.youtube.com/watch?v=VIDEO_ID' --mode PROFILE --output profiled
python tools/benchmark-youtube.py 'https://www.youtube.com/watch?v=VIDEO_ID' --mode STANDALONE --output standalone
```

Each command records three process-cold attempts, three warm uncached attempts, and three warm cache-hit attempts if the primer succeeds. Discard the first attempt in each warm file. The harness does not clear the app's storage or the OS DNS cache. Compare outcomes, upstream counts, and selected-format fingerprints before comparing times. The optional `--trial WEB` and `--trial TV` flags change one client setting at a time for instrumentation only. Any candidate that loses formats or audio is unsuitable for production.

Use `--download` with a video you have permission to download to check for video and audio samples in a completed file via Android's MediaExtractor. Then play the media from the app to confirm playback. The trace reports unsuccessful downloads and doesn't treat an exit code alone as proof of a playable file.
