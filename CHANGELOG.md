# Журнал змін

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
