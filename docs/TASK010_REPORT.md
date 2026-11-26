# TASK 010 — long field run (v0.8.12 log, 4 h 17 min): findings checked against the current code

The log came from v0.8.12; each finding was checked in the code at 6b65930 (v0.8.14) before changing anything.
Nothing below is physically verified. Green CI is not acceptance.

| # | Finding in the log | Present in 6b65930? | Cause | Fix |
|---|---|---|---|---|
| 1 | 0 successful GATT recoveries in 4 h | yes | Recovery stopped for good after 5 attempts ("gave up"); nothing re-armed it. | `ConnectionCoordinator` circuit breaker: after 5 failures recovery pauses; one trial per 10 min cooldown or immediately on a relevant event (audio profile connected, Bluetooth on, ACL to the headset, app opened). Never gives up for the session, never loops. |
| 2 | 20 failed GATT connects | yes (one cause) | `DeviceScanner` refused new BLE observations once 300 devices were seen (a drive passes hundreds), so the headset's rotated address could not be found again. | Least-recently-seen eviction at 300 entries. |
| 3 | 321 RFCOMM opens | yes | Interval reset to 30 s whenever any percentage changed; native notifications triggered extra opens. | Only charging/active changes reset the interval; event-triggered refresh at most once per 60 s; hard budget of 40 RFCOMM opens per rolling hour; breaker after 4 failures in a row. |
| 4 | Resource growth during long runs | yes | Every Fast Pair stream left a coroutine collecting its closed socket forever (`consumeStream`, `FramedTransport`), the session and link transport lists kept every closed socket, one-shot state watchers in `AndroidDeviceLink` and `GattChannelTransport` never ended. | Readers end with their transport; closed transports are pruned; watchers are one-shot. 12-hour simulation checks coroutines and tracked sockets stay flat. |
| 5 | Parallel reconnects | no | One session at a time (serialized op loop); one recovery job per session; link operations under one lock. Now also one process-wide coordinator. | — |
| 6 | Old `charging=true` shown as current | yes | Charging was shown whenever the last value was true. | `currentCharging(now)`: only reports younger than 10 min and not last-known; UI and notification re-check every 30 s / 60 s. Percent uses `isCurrent(now)`, active earbuds `currentActive(now)`. |
| 7 | "Audio connected, data and control dead" shown ambiguously | partly | Telemetry was folded into the control status (`DATA_ONLY`). | Three separate statuses: audio (A2DP/HFP/LE Audio), telemetry (`LIVE`/`STALE`/`NONE` by age of the newest data), control (vendor channel confirmed). Dashboard and notification use the same rules. |
| 8 | Diagnostics collection sizes | mostly bounded | Event log already capped at 3000; scanner map refused instead of evicting. | Scanner LRU; coordinator history bounded by its budget; denial messages logged once per reason. |
| 9 | Build identity in exports | no | — | `build` (versionName, versionCode, gitCommit, buildType) and `process` (random per-run sessionId, startedAtMillis, uptime) sections; `coordinator` and `telemetry` sections. |

## Tests added
- `ConnectionCoordinatorTest`: budget per rolling hour, breaker open/half-open/close, event wake, independent kinds.
- `FreshnessTest`: old charging is history, per-field ages, audio/telemetry/control separation with the log's numbers (last RX 5290 s ago).
- `SoakSimulationTest`: 12 h virtual run — stream closed by the headset every 5 s, GATT down 2 h, RFCOMM down 1 h; asserts hourly opens ≤ 40, never two streams at once, GATT attempts bounded, recovery on the audio event, tracked sockets ≤ 1, coroutine count flat.
- `LiveStateTest`: recovery pauses behind the circuit and resumes on a relevant event.
- `DiagnosticsTest`: build and process identity in the export.

## Not verified
- Real memory, CPU and socket usage on the phone (no profiler run here); the simulation checks the logic only.
- Whether the Tune Beam 2 really recovers GATT after the breaker trial; whether 40 RFCOMM opens per hour is enough for timely case-in/out updates.
