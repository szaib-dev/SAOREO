# Together Games

Games reuse the authenticated Together room WebSocket and its two participant slots. They advertise a separate capability at connection time. Playback, call signaling, chat, and reaction messages use their existing handlers.

`games.py` owns invitations, readiness, votes, forfeits, deadlines and match state. `game_physics.py` owns the five game simulations. The Android client sends aim, shot parameters, and driving inputs. It renders server-authored shot frames and never supplies scores, damage, ball positions, or goal decisions.

Turn-based games simulate a whole shot on the server, send the authoritative path, then commit the result once the replay interval ends. Car Soccer uses 30 simulation steps per second, publishes snapshots at about 10 Hz, and expires driving input after 360 ms. The Android client interpolates the shared bodies and predicts only its local car, with a bounded correction on each new snapshot. Disconnected players receive neutral steering input. The server clocks continue until both players explicitly agree to pause.

Android keeps the game view separate from the room and call controller. Back closes the game view, and Together keeps a return entry for the match. The app retains an active room credential in private, non-backed-up storage for process recovery. The server's 90-second reconnect window applies to disconnections. Match and room state are in memory, so a server restart ends active matches.

The five game environments use original native Canvas artwork and 2D arcade physics. The supplied deploy script packages both game modules with Together. The Android client shows server availability explicitly so it can coexist with older Together deployments.

Run `python -m unittest discover -s server/together -p 'test_*.py'` from the repository root. The game suite drives two authenticated WebSocket clients and checks game lifecycle, synchronized replays, two-player driving, reconnects, pause votes, chat and reactions.
