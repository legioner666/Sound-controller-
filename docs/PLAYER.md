# Sonic Music & background (v0.8)

## Modules
| Module | Role |
|---|---|
| `core:musiclibrary` | `Track`, search/sort/group (`LibraryQuery`), MediaStore scanner, file-picker import, `MusicRepository` (playlists, favourites, saved queue; one private file, atomic writes) |
| `core:player` | Media3 `SonicPlaybackService` (ExoPlayer + MediaSession), `PlayerConnection` (MediaController wrapper), `LibraryController`, sleep timer |
| `core:background` | `DeviceMonitorService` (foreground, type `connectedDevice`), notification text, `DeviceMonitorSource` |
| `feature:player` | Music tab (tracks/albums/artists/folders/playlists/favourites, search, sort), full-screen player, mini player |

The software equaliser and L/R balance are **not duplicated**: the player announces its audio session
(`OPEN_AUDIO_EFFECT_CONTROL_SESSION`) and the existing `core:audio` `SystemEqualizer` attaches to it. They are phone-side
software effects, clearly separate from the headphones' own equaliser. Bass boost uses `android.media.audiofx.BassBoost`
only when the device reports it.

## Two independent services
* **Playback** (`mediaPlayback`): audio focus (calls, other apps), auto-pause when headphones disconnect
  (`setHandleAudioBecomingNoisy`), lock-screen / notification / media-button control via the media session. The queue is
  restored **paused**; no boot receiver and no playback-resumption callback exist, so nothing plays after a reboot.
* **Battery notification** (`connectedDevice`, optional, **off by default** since TASK 012): runs only while the
  "Headphone battery notification" setting is on and a headset is active; shows L/R/Case, charging, "last known" and
  reconnect state; "Turn off" in the notification switches the setting off. With the setting off the same data is on the
  main screen only. It is not sticky and never bypasses Android's start restrictions: if Android refuses, it is retried
  when the app is opened. See docs/TASK012_REPORT.md.

## Privacy
No INTERNET permission, no account, no metadata leaves the phone. Only `READ_MEDIA_AUDIO` (or `READ_EXTERNAL_STORAGE` up to
Android 12) is requested, never `MANAGE_EXTERNAL_STORAGE`. Files can also be added through the system file picker with no permission.

## Not verified on a device (honest list)
60-minute screen-off playback, incoming call and focus loss, headphone disconnect auto-pause, restore of the queue after a
process kill, lock-screen controls, headphone gesture buttons (standard media commands), ANC switching during playback,
foreground-service start on Android 14–16 from background, FLAC/OGG/M4A decoding on the Xiaomi 25053PC47G. Automated tests cover the
library logic, persistence, sleep timer, notification text, GATT recovery and the JBL protocol; the Media3 service itself is built
and lint-checked but not exercised by unit tests.
Not built yet: gapless cross-fade, internet radio, Spotify (all P2 / out of scope), separate `feature:library` and
`feature:playlists` modules (both live in `feature:player`), crossfade.
