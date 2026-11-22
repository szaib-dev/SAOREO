# SAOREO Together

Native two-person watch rooms, introduced in 2.8.0. Open **Home → Better, together**.

## Using the room

1. The creator enters their name and the private server access key, then creates a room.
2. Share the invitation with the partner. The partner selects **Join your partner**, enters their name, and pastes the invitation message/code. Only the creator needs the server key.
3. Choose a YouTube video, direct HTTPS media stream, or local video. Local files are verified by a full SHA-256 fingerprint on each device and are never uploaded.
4. Either participant controls both players. Playback waits for both participants to be ready; **Wait when a player buffers** can be changed in room settings. **Resync** corrects this phone to the shared timeline.
5. Either person can join a voice or video call. Microphone/camera permissions are requested at that action. Both people must join the call. Muting, camera switching, camera off, audio output selection, chat, reactions, and the shared queue are available in the room.

Calls are foreground-only in this version. Leaving/backgrounding the screen ends this device's call and pauses shared playback. Return and rejoin the call. Rooms reconnect after transient socket loss, with playback paused until resumed.

YouTube uses its official embedded player via android-youtube-player; provider controls/ads remain present. Restricted embedding, sign-in requirements, ads, and device/provider restrictions may prevent a particular video from playing or disrupt timing. No Netflix app control or screen sharing is implemented. IPTV requires authorized direct streams; DRM license provisioning is not implemented. Live-stream synchronization is best effort because independent streams can have different live windows; VOD supports shared seeking. Each participant can set a private stream URL for the same program in room settings; that override remains on their phone.

## Server

`server/together/service.py` runs an aiohttp WebSocket/HTTP service bound to loopback port 8770. A separate coturn instance handles authenticated UDP/TCP relay on 3478, with media relay ports 52000–52049/UDP. Room credentials and expiring TURN credentials are issued over HTTPS. Room state, the last 80 chat messages, and the queue live in memory; restart clears rooms. Idle rooms expire after 30 minutes. At most eight rooms and two members per room are accepted.

Mutating playback commands have per-member sequence numbers; the server applies them in receive order, assigns revisions, and anchors playback to its own time. Clients estimate server time using monotonic-clock ping round trips. Status messages for outdated media IDs are ignored and outdated playback commands are rejected. Either participant may pause, resume, or seek. Partner disconnection pauses the room. Camera/audio SDP and ICE messages are forwarded only between authenticated active call participants and are not retained as chat/history.

Deployment is additive to the existing server:

- `/opt/saoreo-together` — code and virtual environment.
- `/etc/saoreo-together.env` — private access key and TURN signing secret, root-readable.
- `saoreo-together.service` — unprivileged room service with CPU/memory limits.
- `saoreo-together-turn.service` — bounded TURN service.
- Caddy `/together/*` route on the existing HTTPS hostname/port. Original SMS/health routes are preserved; prior Caddy configuration is backed up as `Caddyfile.before-saoreo-together`.
- Existing coturn package service is disabled because SAOREO uses its own dedicated configuration/service. This package was newly installed for SAOREO.

The creator key is not bundled into the APK and SSH credentials are never included in source or the app. The creator key is stored in the app's private, non-backed-up preferences when entered. Keep invitations private. Rate limits, room limits, bounded messages/queues, TURN quotas, and relay destination restrictions limit abuse. The service does not fetch, transcode, store, or relay the watched movie. TURN may relay the encrypted call and consume VPS bandwidth. TURN/TLS on port 443 is not offered because an existing VPS service already owns that port; particularly restrictive networks may still prevent calls.

## Development checks

```
python -m pip install -r server/together/requirements.txt
python -m unittest discover -s server/together -p 'test_*.py' -v
```

`probe_live.py` additionally checks the public HTTPS endpoint, two-client shared controls/chat, and forced TURN UDP and TCP allocations with bidirectional relayed packets. It reads the private creator key from a local access file without printing it. Install `aioice` for this optional probe.

`TogetherProtocolTest` covers timeline extrapolation, drift tolerance, safe endpoints, YouTube URL parsing, and complete invitations. `TogetherAcceptanceTest` is an opt-in Android test requiring the `togetherKey` instrumentation argument. It connects two native WebRTC peers to the live room service using forced TURN, checks shared controls/chat/reactions, and requires received camera frames before passing. Do not treat a successful signaling exchange as successful audio/video delivery.

## Operations

Use `systemctl status saoreo-together saoreo-together-turn` and the corresponding systemd journals. Replacing the creator key in `/etc/saoreo-together.env` and restarting the room service revokes future room creation with the old key; service restart also clears current rooms. The VPS SSH password is independent of the creator key.
