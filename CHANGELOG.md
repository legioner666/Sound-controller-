# Журнал змін

## 0.8.x (TASK 014, audit follow-up)
- Gesture writes are blocked (controller + driver) when the current touch controls or the backup contain an unknown combination, so the original can always be restored; regression test: no `AA 77 09` is sent.
- Lab experiments keep the full noise-mode path and count candidate touch frames; multi-step runs are shown separately and not used for the cycle verdict.
- Field results (owner's Tune Beam 2, fw 0.2.8.0): ANC level 7 -> 1 confirmed by read-back; level query `AA 91 01 21` answers; unsolicited `AA 23 03 …` precedes tap-driven mode changes (candidate touch event; its 3 bytes are now exported, meaning unconfirmed).

## 0.8.x (TASK 014)
- JBL Tune Beam 2 research (own captures + a published JBL-app capture of the same model + the JBL app's model profile + Fast Pair Hearable Controls): touch controls `AA 77` (read, and the 4 layouts per earbud the JBL app writes), the unknown mode-report slot is the ANC level, ANC/Ambient levels, read-only EQ preset / Smart Audio & Video / spatial sound / VoiceAware / limiter, passive Fast Pair ANC notify. Exact-frame allowlist only; Experimental; nothing ported from other models.
- Gesture editor: model-specific layouts, automatic backup before the first change, one earbud at a time, confirmed only by reading both earbuds back, restore from backup; calls untouched. ANC/Ambient strength on the Noise screen.
- AutomationEngine ("If -> then"): triggers (connect, playback start/stop, battery low per component, sleep timer start, time of day, confirmed noise-mode change, gesture where reported), conditions, actions (noise mode, player volume, pause/resume, EQ profile, sleep timer), delay, priority, enable, device binding, journal; protection against loops, repeated Bluetooth events, stale data and conflicts; manual changes win for 30 min and nothing is re-applied by polling.
- Lab: passive observation timeline, before/after gesture experiments with cycle analysis, btsnoop HCI import (ATT values recognised by a driver only, no addresses), JSON test package.
- Docs: docs/TASK014_REPORT.md, docs/JBL_GESTURE_PROTOCOL.md (compatibility matrix + physical experiments E1–E15).

## 0.8.x (TASK 013)
- Sleep timer: configurable fade of the player's own volume — on/off, length 5–100 % of the time or in minutes, end volume from 0 to the current volume, live graph of the planned volume; changes apply at once while the timer runs (no jumps) and are saved. Default unchanged (last 10 %, down to 0). The fade starts from the actual player volume (new "Player volume" slider); a manual volume change during the fade re-plans the rest from the new level. The system volume is never changed. The end is unchanged whatever the end volume (stop, save queue and position, full shutdown until opened by hand). Wall-clock checks plus a while-idle alarm backstop for screen-off. The second, system-volume sleep timer on the Automation page was removed; that page now shows the same single timer. UA/EN/PL, tests. See docs/TASK013_REPORT.md.

## 0.8.x (TASK 012)
- The ongoing battery notification is now optional and **off by default**. Battery, charging and earbud activity (plus reconnect state) are on the main screen. Setting "Headphone battery notification" (UA/EN/PL) applies at once, persists across restarts and APK updates (new key; the old default-on value is not carried over), and a reconnecting headset never re-creates the notification while it is off. Android's mandatory foreground-service notification is respected: off means the service does not run. Adaptive refresh, background playback and the sleep timer are unchanged. See docs/TASK012_REPORT.md.

## 0.8.x (TASK 011)
- Adaptive refresh by app lifecycle: one AdaptiveRefreshCoordinator for the process (main screen 5 s, other sections 10 s, background and locked screen 60 s; immediate refresh on return to the foreground and on Bluetooth notifications; stops when the headset disconnects and after the sleep timer). Source priority: notifications, read over the open GATT link, the open Fast Pair stream, a new RFCOMM socket only when its data is old and within the 40/h budget. Settings 5/10/15/30 s and 30/60/120/300 s. Age of last-known values shown. See docs/TASK011_REPORT.md.

## 0.8.x (TASK 010)
- Single connection coordinator: hourly socket budget, circuit breaker, event-driven recovery that never gives up for good; leaks of stream readers and transports fixed; scanner LRU; time-based freshness for battery, charging and active earbuds; separate audio / telemetry / control status; build and process identity in diagnostics; 12-hour simulation test. See docs/TASK010_REPORT.md.

## 0.8.x stabilization (TASK 009)
- Reconnect rebuilt: fresh scan and endpoint verification on every attempt; audio state from A2DP/HFP profiles; L2CAP stops after a security refusal; stale data labelled; adaptive event-driven refresh; JBL AA 06 envelope; stricter diagnostics allowlist. See docs/TASK009_REPORT.md.

## 0.8 — TASK 008 (Sonic Music & background)
- **Music player** (Media3 ExoPlayer + MediaSessionService): local library through MediaStore, import through the system file picker, MP3/FLAC/WAV/AAC/M4A/OGG within the phone's decoders, search (case/accent-insensitive), sorting, albums/artists/folders, playlists, favourites, queue, shuffle/repeat, saved queue restored paused, mini player on every screen, full-screen player with sleep timer, bass boost (if the phone supports it) and the headphone's listening modes.
- **Background**: playback service (lock screen, media buttons, audio focus, auto-pause when headphones disconnect, no start after reboot) and a separate `connectedDevice` foreground service for headphone monitoring (battery, charging, ANC, reconnect state; stoppable from the notification; user setting).
- Equaliser/balance are the existing software effects attached to the player's audio session — no duplicate effect code.
- Tests: library search/sort/group, codec round-trip and corruption tolerance, repository persistence, sleep timer, notification text. See `docs/PLAYER.md` for the list of features not yet verified on a device.


## 0.7 — TASK 007 (live device state)
- **Root cause (by code analysis; not yet confirmed on the phone)**: the UI path was already a single live flow (`session.state` → `SessionManager.active` → `collectAsStateWithLifecycle`). The data feeding it stopped: the Fast Pair stream closes ~5 s after opening and was never re-opened, L2CAP never opened (fixed in 0.6), and after a GATT loss the link kept a dead client and a "tried" flag, so nothing ever reconnected and the JBL vendor channel stayed bound to the dead GATT.
- **GATT recovery** (all drivers, in `BaseDriverSession`): loss is detected, then 5 bounded attempts with back-off (3/10/30/60/120 s); sources are restarted without piling up subscriptions; JBL releases the dead channel and opens a fresh one once. Audio is never touched.
- **Safety-net polling, long intervals only**: Fast Pair stream re-opened every 5 min (never stacked on an open one); JBL listening mode re-read with the confirmed GET every 60 s while the channel is confirmed. Unsolicited reports (gesture changes, BAS notifications) update the state immediately.
- **Return to the app**: confirmed read on resume; battery shown as "no fresh data for 10+ min — last known values" when nothing arrived.
- **Diagnostics**: `live` section (last RX age, state-updated age, state-delivered age, subscriptions, stop reason, reconnect status) and a `battery` section with per-component percent/charging/lastKnown/age.
- Tests: unsolicited notification → flow; gesture → NoiseStatus; GATT loss → recovery/bounded/give-up/no duplicate recovery; JBL channel re-opened once; poll interval; no stacked streams.
- Not covered: RFCOMM-only drivers (Sony, Bose, …) have no RFCOMM auto-reopen yet; the 30-minute on-device soak test has not been run.


## 0.6 — TASK 006 (battery & L2CAP hotfix)
- **L2CAP PSM**: the FE2C1239 characteristic is parsed per the Fast Pair BLE spec as exactly 3 bytes `state | PSM uint16` (00 unknown, 01 ready, 02 unavailable; ready PSM 0x0080–0x00FF). Real value `01 00 A1` = ready, PSM 161. The old 1/2-byte parser rejected it, so L2CAP never opened. Secure L2CAP only, on the actual GATT endpoint.
- **L2CAP lifecycle**: after the RFCOMM stream closes, wait (bounded) for GATT, re-read an "unknown" PSM up to 3 times, at most 3 attempts per session with back-off; no endless loop; JBL GATT control untouched.
- **Battery model**: component-wise merge. A report without a percentage keeps the last known value (marked "last known" in the UI); charging, percent and time update independently; charging with an unknown level (0xFF) is kept; unknown is never shown as 0 %. A single BLE Battery Service value never replaces Left/Right/Case readings. RFCOMM close does not erase readings.
- UI: ⚡ charging, "not active" for the inactive earbud (inactive ≠ physically disconnected), "last known".
- Tests: PSM vector table, the real 0.5.6 battery packets, the three battery vectors from the task, last-known/charging/source-conflict cases, RFCOMM-close → L2CAP integration, bounded retries. Not physically verified: L2CAP on the real device.


## 0.5 — TASK 005 (JBL AA hotfix + Fast Pair hardening)
- **JBL H-AA decoder**: `AA 91 len 12` reports with slots 01 ANC / 02 Ambient / 03 TalkThru; slot 04 (value 0x07 on Tune Beam 2) is logged and never interpreted. All five real RX vectors decode; incomplete, duplicate, unknown-slot, out-of-range and contradictory frames are rejected and never confirm the channel.
- `AA EE 01 <cmd>` is a separate response type: logged with diagnostics, never a success ACK, never confirms a command or the control channel.
- Control channel is re-selected once when GATT comes up after the session started (reconnect via the Fast Pair 0x0302 address); no duplicate sessions or subscriptions.
- Privacy: unknown Fast Pair messages (e.g. 030A, 030B) are logged as group/code/length only.
- Regression tests from the real capture (`JblRealCaptureTest`).
- Not done yet: system volume, L/R test, test tone, profiles, battery history, compatibility catalog UI, P2 research. No model is marked Verified.




## 0.4 — TASK 004 (Google Fast Pair Message Stream)
- Message Stream decoder extended: Model ID (0x0301), **BLE address (0x0302)**, battery L/R/case with charging (0x0303), remaining time in 1- or 2-byte form (0x0304), active components (0x0306), firmware (0x0309). Stateful `FastPairSession`, `FastPairCapabilities`.
- Corrupt headers resynchronise byte by byte (payload cap 256), fragmented and coalesced buffers handled, invalid frames never change state, charge values 101–126 are "unknown".
- RFCOMM stream is tried **first**, GATT after it. A BLE address from 0x0302 becomes the first GATT candidate **for that session only**; the endpoint must expose Fast Pair (0xFE2C) and its Model ID must match. Rotation is logged; a working GATT endpoint is never swapped.
- L2CAP Message Stream through the GATT PSM characteristic as a read-only fallback (PSM must be in 0x0080–0x00FF).
- Only one write exists: the "active components request" `03 05 00 00`, exact-bytes allowlist, experimental opt-in only, 3 s reply timeout. No AccountKey, no FHN, no firmware, no Google Play Services interference.
- Home screen: remaining time, active earbuds, firmware; unknown stays unknown. Diagnostics: Fast Pair section, learned endpoints masked.
- Tests: every vector from the task plus negative cases and session integration tests with fakes.
- **Not verified:** whether a third-party Android app can open the Message Stream on a given headset (on Tune Beam 2 it closed instantly in v0.2.2), and the specification text itself could not be fetched by Claude; the layouts follow the task's tables.

## 0.3 — TASK 003 (after physical test on JBL Tune Beam 2)
- Scan before connect; LE endpoints ranked by name twin, Fast Pair data, signal and connectability (never by name alone).
- Ordered, budgeted attempts: manual endpoint → resolved LE endpoints → classic address LE/AUTO → BR/EDR last. GATT and RFCOMM open one after another.
- RFCOMM: secure then insecure socket; errors classified (remote closed, timeout, busy, …). A failed Fast Pair listen-only stream no longer marks RFCOMM as failed.
- Diagnostics format v3: every attempt (transport, GATT status, new state, bond, duration), redacted BLE observations, endpoint ranking.
- Passive Fast Pair advertisement battery (strict parser, Experimental, only when the advert can be attributed to the headset).
- Lab: scan and choose the LE endpoint by hand, attempt list, HCI snoop instructions (docs/HCI_SNOOP.md).
- Home screen: compact panel with earbuds drawing, three battery gauges, noise quick switch, mini EQ, human-readable recovery hints; technical statuses live in Lab.
- Fixed one-line names, bottom bar truncation, English command errors (now localised, details only in Lab).

## 0.2 — TASK 002 (аудит GPT першого MVP)

### P0
- **Окремі стани каналів**: аудіо (Android), GATT, RFCOMM і канал керування показуються окремо. «Підтверджено» — лише для каналу, з якого прийшов повністю валідований кадр. `Connected` замінено на «Сесія активна» + стани каналів.
- **Підпис релізу**: release-збірки підписуються тільки приватним upload-ключем; без ключа вони не підписуються, а CI для тегу `v*` падає. Debug-ключ використовується лише для тестових збірок (`com.soniccontrol.debug`).
- **CI**: опублікований раніше реліз `latest` із release-APK, підписаним debug-ключем, видаляється; замість нього — pre-release `test-build` з тестовим APK, `SHA256SUMS.txt` і номером коміту. Постійне посилання: `releases/download/test-build/SonicControl-test.apk`.

### P1
- JBL: протокол визначається й канал підтверджується лише після повної валідації кадру (довжина, відомий тип повідомлення, діапазони полів; для Protocol 4 — заголовок, лічильники пакетів і повний TLV).
- `SessionManager` (core:devices): connect/disconnect/reconnect серіалізовані актором; покоління сесій відкидають застарілі стани, результати й повтори; старт не блокує відключення; ресурси закриваються.
- `GattClient` ігнорує колбеки застарілих `BluetoothGatt`; RFCOMM `connect()` переривається при скасуванні без витоку сокета.
- Відповідь Protocol 4 має містити запитані ID; зміна режиму RACE/NT Link/GAIA підтверджується повторним читанням стану.
- Знеособлення діагностики (`Redactor`): MAC (обидва порядки байтів) і назви пристроїв маскуються в тексті й HEX; ідентифікаційні характеристики GATT вилучаються.
- Android Lint блокує CI; перевіряються всі модулі. Виправлено знайдений lint баг: статус запису GATT на Android 13+.
- Інтеграційні тести сесій із фейковими навушниками; фаз-тести guards і парсерів. Усього 87 тестів.

### P2
- Еквалайзер телефона (DynamicsProcessing) окремо від апаратного EQ навушників; ніколи не гучніше, ніж без нього; баланс L/R.
- Визначення можливостей: читання Device Information (прошивка), локалізовані повідомлення драйверів (`Notice`) і примітки каталогу.
- Розділ «Шум» приховується, якщо навушники його не підтримують.
- Майстер першого запуску: статуси, дозволи, підключення, підказка про діагностику.
- Адаптивність і доступність: прокручувані вкладки, плитки без фіксованої висоти, `stateDescription` для повзунків, заголовки, без блокування орієнтації.
