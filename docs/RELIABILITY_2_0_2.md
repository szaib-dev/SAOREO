# SAOREO 2.0.2 extraction and download validation

## YouTube engine decision

NewPipe Extractor v0.27.1 was compared with the app's pinned yt-dlp 2026.10.26 on the same build host and outbound proxy. The test used public Blender Foundation films. Each command started in a fresh host process. Java's HTTP connection handling differs from Python's, so the comparison describes these complete engine setups rather than CPU performance in isolation.

| Video | NewPipe extraction | yt-dlp extraction | NewPipe media GET |
| --- | ---: | ---: | --- |
| Big Buck Bunny | 8.364 s | 29.423 s | Video and audio each returned 195 bytes of HTML with HTTP 200 |
| Sintel | 12.510 s | 27.552 s | Video and audio each returned 195 bytes of HTML with HTTP 200 |

Three other NewPipe metadata runs took 10.850–11.316 seconds. NewPipe returned 18 video-only and 5 audio streams for Big Buck Bunny, and 14 video-only and 4 audio streams for Sintel. Both fixtures returned zero muxed streams. yt-dlp returned 44 and 37 format entries. These raw counts include ranges that differ between implementations and are unsuitable as a direct quality-equivalence check.

The playback gate failed on the available network. The release keeps yt-dlp as the production engine and preserves its original extraction path as the fallback whenever saved stream descriptors cannot be reused. No new platform client, authentication workaround, DRM path or request timeout change ships in this release. Fresh YouTube extraction did not meet the 2–5 second target on the build host.

The NewPipe benchmark harness is in `tools/NewPipeBenchmark.java`. It uses the v0.27.1 JitPack artifact, SHA-256 `154fd5efc333d8fdc9cc60abac12d42cd6bf49c8df2f93c5a850fd60c8d7534a`. The benchmark's dependencies come from the pinned upstream `gradle/libs.versions.toml`. No NewPipe code or dependency is included in the APK.

## Platform checks and changes

| Platform | Successful metadata entry count on host | Scoped change |
| --- | ---: | --- |
| Instagram | 9 HTTPS progressive entries | Reuse eligible published video URLs on Instagram and Meta CDNs and preserve safe Referer headers |
| X | 9 entries, 3 HTTPS progressive | Reuse eligible direct video.twimg.com formats and retain the extractor for HLS |
| TikTok | 0, the sample request was IP-blocked | Guarded replay for recognized TikTok CDN URLs when the existing extractor returns a public progressive stream |
| Pinterest video | 6 entries, 1 HTTPS progressive | Carry the already resolved Pin URL into extraction and replay recognizable progressive Pin video streams |
| Pinterest images | Existing Open Graph fixture coverage | Run up to four independent size HEAD probes concurrently |
| YouTube | 44 and 37 entries | Reuse one or two exact HTTPS media URLs when possible and retain the original runner for other formats |

Simultaneous metadata calls for the same link and timeout share one upstream fetch while at least one caller is waiting. Successful previews retain their original five-minute lifetime. Common campaign tags do not split the cache. Other parameters are preserved. The bundled runtime remains lazy and initializes once per app process. A reusable or already-verified completed download does not require another metadata extraction.

Stream replay uses a minimal, private temporary descriptor and the existing yt-dlp downloader, retries, partial-file handling and merger. It carries no cookies, credential headers, external scripts, or executable arguments. Only the selected format IDs and approved HTTPS origins are replayed. Each CONNECT hop stays within the platform's CDN allowlist and still uses the application's public DNS checks. A stale or invalid descriptor falls back to conventional extraction with the original selection. Rate limiting stops the fast path without an immediate second extraction.

## Download validation

An HTTP 200 response or a zero yt-dlp exit code is insufficient for completion. Direct downloads use a staging file, require a full HTTP 200 response and check declared length. Validation rejects document bodies, invalid MP4 box lengths, and missing readable samples on the tracks required by the selected format. An invalid finished file is removed before the original extractor path is tried. Transfer completion and gallery publication remain tied to the existing MediaStore workflow.

The controlled, trusted HTTPS test observed exactly two media GETs and zero metadata requests for a replayed video/audio pair. FFmpeg muxed the streams and independently decoded the finished output in full. A separate fixture returned HTML with HTTP 200 and reproduced a zero yt-dlp exit followed by media rejection. JVM regression tests cover document rejection, truncated MP4 boxes, source allowlists, and concurrent request behavior. The Android test APK includes a generated audio/video fixture for the native sample check.

Live YouTube CDN playback and Android behavior remain unverified. ADB showed no attached physical phone, KVM was unavailable, and the prior software emulator failed before an application could run. The older FFmpeg compatibility concern on Android devices that use 16 KiB memory pages remains open.
