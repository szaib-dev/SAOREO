# Together calls in SAOREO 2.10.2

The fullscreen call and Stream views share one Android activity, one Together room, and one WebRTC call. Back closes the call view. Stream opens the media controls for the same room on either participant's phone. The existing Games entry stays available in the call view.

Camera capture starts at 720p and 30 fps. WebRTC keeps its hardware codec factories, congestion controller, and audio track. The Android sender uses selected-path bandwidth, RTT, video loss and jitter feedback, and encoder CPU limits to adjust the video envelope. Sustained headroom on capable cameras allows 1080p. Screen sharing retains its separate video settings.

The room socket reconnects with the same member token. Media recovery uses signed ICE restart negotiation on the existing peer connection and retains the media tracks. The UI shows the current media transport state and offers Retry connection when the bounded automatic attempts are exhausted. Both clients should run 2.10.2 for coordinated recovery.

## Deploy the room service update

Existing deployments need the updated `server/together/service.py` to preserve call flags and playback intent across an unplanned socket closure. Streaming waits for both players to report ready before it resumes. The pairing database, Firebase credential, and reverse proxy use their existing setup.

1. Run `python -m unittest discover -s server/together -p 'test_*.py'` from the repository root.
2. Back up `/opt/saoreo-together/service.py` on the VPS, stage the new file, check its Python syntax, and replace the deployed copy.
3. Restart `saoreo-together` during a quiet period. Live room state is in memory, so a process restart ends any current rooms.
4. Verify `/together/health` through the public HTTPS endpoint and check that `saoreo-together` is active. Restore the backup and restart if the update fails health checks.

When TURN reports allocation error 486 during network changes, deploy the matching quota settings from `server/together/deploy.py` to the dedicated TURN configuration. Set `user-quota=8`, `total-quota=32`, and `bps-capacity=16000000` so an incoming ICE path can overlap the previous route. Coturn reserves bandwidth as it creates allocations. Keep TURN authentication, denied peer ranges, the per-allocation bandwidth cap, and the existing relay port range. Restart `saoreo-together-turn` during the same maintenance window and verify UDP and TCP relay allocations with `server/together/probe_live.py` using the private access file.

Build the Android APK with `gradlew.bat :app:assembleDebug`. To enable Firebase call wake-ups in a local build, use the Android Firebase client configuration at `app/google-services.json` for `com.codetic.saoreo`. The VPS service account stays on the VPS.
