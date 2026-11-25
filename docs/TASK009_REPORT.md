# TASK 009 — Bluetooth Connection Engine: causes and fixes (v0.8.x stabilization build)

Source: owner's physical test of v0.8.11 (diagnostics JSON, session timeline in the owner's message). Nothing below is physically verified yet; green CI is not acceptance.

| # | Failure in v0.8.11 | Cause found in code | Fix |
|---|---|---|---|
| 1 | "attempt 5/5" but one connection cycle in the log | `AndroidDeviceLink.gattLocked()` returned `null` immediately when `gattTried && gatt == null`; after the first failed cycle attempts 2–5 never touched Bluetooth. The UI label was also set *before* the back-off wait, so "5/5" was shown at ~100 s although attempt 5 ran at ~223 s. | `reconnectGatt()` is now a fresh cycle every time: close old GATT and subscriptions, drop the stale LE address and all cached BLE observations, new scan, every candidate verified again. Label shows "waiting N s before attempt i/5" until the attempt really starts. Back-off 2/5/15/30/60 s. |
| 2 | Reconnect used old BLE addresses | Cached observations (≤90 s) and the previous Fast Pair-reported LE address were reused without a scan. | Observations and stale endpoint invalidated before each recovery attempt; identity re-verified (Fast Pair service + Model ID) for stream-reported endpoints. |
| 3 | L2CAP "Client security clearance failed" ×3 | Retry loop counted attempts, not the reason. | First security refusal sets `l2capDenied` for the session; no further L2CAP attempts, no bypass. |
| 4 | Audio state UNKNOWN | Audio state came from a one-shot bonded/A2DP snapshot and ACL events (ACL includes LE links and says nothing about audio). | New `AudioProfileMonitor`: held public A2DP and Headset profile proxies plus their connection-state broadcasts. CONNECTED / DISCONNECTED / UNKNOWN (UNKNOWN only without proxies). ACL events no longer stop or start sessions. |
| 5 | Stale data shown as current (`lastKnown:false`) | Nothing marked values stale when their source was lost. | On GATT loss and after repeated failed refreshes all battery components become `lastKnown=true`; new values clear it. Notification marks data stale whenever control is not connected. |
| 6 | Session "Active" with all channels closed | `ConnectionState.Active` was the only status. | `ActiveDevice.controlStatus` (CONNECTED / RECONNECTING / UNAVAILABLE) derived from real channel state; the background notification uses audio state + control status. |
| 7 | Fast Pair refresh every 5 min | Fixed long interval. | Event-driven refresh (`requestRefresh`) plus adaptive schedule 30 s → ×2 → 5 min, reset on change, min gap 5 s, one socket at a time. |
| 8 | JBL native `AA 06` notifications | Not handled. | Envelope decoded (`subtype`, body length); body is **not** interpreted (no verified Tune Beam 2 sample in hand). A native notification triggers a state refresh through the verified Fast Pair source. |
| 9 | Diagnostics export | Only headers were considered. | `HexPolicy`: allowlist by frame layout **and** payload length/value ranges; BLE-address payload always masked; unknown/odd frames exported as shape only. |

## Round 2 (owner/GPT remarks on 005f602)
1. `ControlStatus` has a new `DATA_ONLY` value: plain GATT or the Fast Pair stream no longer count as vendor control; `CONNECTED` requires a frame-confirmed vendor channel.
2. ACL events no longer start sessions (they are logged only); sessions follow the A2DP/HFP/LE Audio profile monitor.
3. `AudioProfileMonitor` also tracks LE Audio (public profile 22 / broadcast, Android 13+); it is process-lifetime (profile proxies + one receiver), started when Bluetooth is ready and permitted, also after a permission is granted later.
4. Every refresh branch (disabled, stream open, failed open, success) ends in the same 5 s minimum gap; test floods triggers and failed opens.
5. Real reopen test: each refresh gets a new transport that the "headset" closes after 5 s.
6. `HexPolicy` accepts 0xFF (charging, unknown level) and the empty-payload request `03 05 00 00`.
7. Independent time and source per battery component for percent and charging, and for the active components.
8. Experimental `AA 06` battery decoder (id 0x1A/0x1B/0x1C = L/R/case, from the owner's v0.6.7 capture), applied only when the Fast Pair model is B484FB and firmware starts with 0.2.8; strict structure; the second capture `AA 06 05 00 02 00 06 01 05` is never interpreted. Charging-only reports are no longer dropped.

## Round 3
- **Sleep timer.** First 90 % of the time: normal playback. Last 10 %: linear fade of the app player's own volume to 0 (250 ms steps; the phone's system volume is never touched). The faded volume is not restored; only a new/cancelled timer (manual action) resets it. At the end: playback stops, queue saved paused, `AppShutdown.suppress()` (persisted flag) stops Bluetooth sessions (last device is kept), the device monitor service, the audio profile monitor and all notifications, and closes the UI (`finishAndRemoveTask`). Both services, the auto-reconnect, audio-profile events and manual connect refuse to start while the flag is set; it is cleared only when `MainActivity` is opened by hand. Runs on the playback service's main-thread handler (service is foreground while playing, wake lock held), so it works with a locked screen.
- **AA 06 reached nothing before.** `JblProtocol.P3.validate()` rejected command 0x06 ("frame not recognised, ignored"), so the previous round's decoder was dead code. Fixed; new session tests push the real v0.6.7 packet and assert the reactive battery state (including a second update), and that other model/firmware and the unknown packet change nothing.
- **Dashboard** status line now shows audio (connected / disconnected / unknown) and control (connected / reading only / reconnecting / unavailable) from the same `ControlStatus` as the notification; active-earbud text is labelled "last known" when control is lost and the data is older than 90 s.

## Still open (honest list)
- Sleep timer lives in the playback service: if Android kills that service while music is paused, the timer is lost (no AlarmManager backup).
- Physical scenarios from TASK 009 (case in/out per earbud, BT off/on, lock screen, music, 60 min, 20 reconnects) are not tested.
- `AA 06` mapping L/R/case is inferred from one capture (right and case match Fast Pair); left is unconfirmed; id 0x06 meaning unknown.
- Fast Pair spec text could not be fetched; PSM byte order is from the owner's/GPT's table.
- TWS main-earbud switch handling relies on fresh scan + identity check; behaviour on the real device is unverified.
