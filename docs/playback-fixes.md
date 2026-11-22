# Desktop playback fixes

Modified on 22 November 2026. This branch fixes playback and window behavior in the upstream CloudStream desktop application.

- Correct maximize/restore behavior and preserve the previous placement across fullscreen transitions.
- Keep keyboard focus, lock state and drawer shortcuts consistent across player and window controls.
- Start new episodes at zero; preserve position and pause intent when switching sources.
- Scope callbacks, resume work, frames, subtitles and completion to the active playback request.
- Recover a confirmed audio, video or presentation stall after 45 seconds. Reload the same source once, then advance through remaining mirrors without wrapping. Intentional pauses and seeks reset monitoring.
- Distinguish premature input exhaustion from normal completion before auto-next. Preserve seekable resume metadata after native stop.
- Close truncated proxy transfers, cancel released sessions, and resolve redirected HLS/DASH resources through the protected proxy.
- Convert video pixels in bulk off the UI thread while preserving channel order, opacity and frame ownership.

## Verification

The local regression suite contains 44 tests for playback sessions, health thresholds, subtitle caching, pixel conversion, DASH manifests and loopback proxy failures. Controlled VLC/Compose checks verified same-source recovery after an interrupted MP4 and resume beyond the interruption point. Earlier controlled checks covered pause, seek, subtitles, drawer/lock keys and fullscreen transitions.

Live provider outages, physical audio devices, all GPU/driver combinations and clean-machine MSI installation are not established by these tests. Unknown-duration and live streams may not permit exact position restoration. External DASH XLink/PatchLocation and provider-specific DRM extensions are outside manifest rewriting coverage.
