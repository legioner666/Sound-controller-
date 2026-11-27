# Протоколи

Позначки: ✅ підтверджено на реальному обладнанні (у публічних дослідженнях) · 🧪 гіпотеза / не перевірено · ⛔ заблоковано.
Жоден протокол ще не перевірено на JBL Tune Beam 3 — це перше завдання фізичного тестування.

## Універсальні (лише читання)

| Джерело | Транспорт | Дані | Статус |
|---|---|---|---|
| Bluetooth Battery Service | GATT `180F/2A19` | один рівень заряду | стандарт SIG |
| Google Fast Pair Message Stream | RFCOMM `df21fe2c-2515-4fdb-8886-f12c4d67927c` | `group u8, code u8, len u16 BE`; група 3 код 3 = заряд L/R/кейс (біт 7 — зарядка, `0x7F` — немає даних); код 9 — прошивка | ✅ (JBL TUNE230NC, Wave Buds 2) |
| Apple proximity pairing | BLE-оголошення, company `0x004C`, тип `0x07` | заряд по 10% | 🧪 |

## JBL

### Сервіс Harman (excelpoint): `65786365-6c70-6f69-6e74-2e636f6d0000`
notify `…0001`, write `…0002`.

**H-AA (Protocol 3)** — `AA <cmd> <len> <payload>`, без послідовності й контрольної суми. ✅ JBL TUNE230NC TWS, Wave Buds 2.

| Запит | Відповідь | Значення |
|---|---|---|
| `aa 21 01 30` | `aa 22 …` | версія |
| `aa 9b 02 01 01` | `aa 9b 03 02 01 00` | увімкнути сповіщення |
| `aa 25 01 00` | `aa 25 0d 01 …` | заряд: payload[3]=L, [4]=R, [6]=кейс |
| `aa 91 01 11` | `aa 91 07 12 01 a 02 b 03 c` | режим (1=ANC, 2=Ambient, 3=TalkThru) |
| `aa 91 07 10 01 a 02 b 03 c` | звіт `…12…` | встановити режим |
| `aa 95 …` | — | ⛔ factory reset, заблоковано |
| `aa 77 02 01 ff` / `aa 77 09 00 …` | `aa 77 <len> 02 …` | жести Tune Beam 2 (TASK 014, Experimental) — див. `JBL_GESTURE_PROTOCOL.md` |

**Protocol 4 (моделі 2025+)** — `00 DD | cmd u16 LE | count | index | len u16 LE | TLV`. 🧪
Реалізовано лише `GET_DEVICE_INFO (0x0001)` для ID: 4 product id, 12 прошивка, 13/14 заряд L/R, 7939 кейс. Будь-яка інша команда P4 ⛔.

**Airoha RACE** (Tour Pro 3, ймовірно інші Airoha-моделі JBL) — RFCOMM `00000000-0000-0000-0099-aabbccddeeff`,
`05 <type> <len u16 LE> <cmd u16 LE> <payload>`. ✅ Tour Pro 3.
`05 5a 04 00 01 09 05 00` — режим; встановлення `0x0E06`: Off `00 0b`, ANC `00 0a 01 00`, Ambient `00 0a 0a 04`, TalkThru `00 0a 09 04`. Усе інше ⛔ (канал має доступ до flash/RAM).

### JBL Tune Beam 3
Протокол невідомий. Модель 2025 року → найімовірніше Protocol 4 або Airoha RACE; не виключено H-AA (Live Beam 3 2024 року — H-AA).
Алгоритм у застосунку:
1. Пасивно: SDP-записи, GATT-сервіси (без надсилання команд), Fast Pair, BAS.
2. Якщо є сервіс Harman — підписка на notify і пасивне визначення за першим кадром (`AA` → H-AA, `00 DD` → P4).
3. **Lab → «Визначити протокол»** (лише з експериментальним режимом): `P4 GET [4,12]`, потім `aa 21 01 30`.
4. Якщо є RACE-запис — RFCOMM RACE, режим шуму як Experimental.

## Sony MDR (RFCOMM)
v2 `956c7b26-d49a-4ba8-b03f-b17d393cb6e2`, v1 `96cc203e-5068-46ad-b32d-e316f5e069ba`.
`3E type seq len(u32 BE) payload checksum 3C`; escape `3C/3D/3E → 3D (x & EF)`; checksum = сума байтів & FF; ACK `type 01, seq = 1 − seq`.
NC/ASM v2 блок `0x17`: GET `66 17`, SET `68 17 01 effect mode ambientMode level`; NTFY `69 17 …` — авторитетне підтвердження. ✅ WH-CH720N, WH-1000XM5, WH-1000XM6. v1 (XM4) — три кадри, зафіксовані на обладнанні ✅.

## Nothing NT Link (RFCOMM `aeac4a03-dff5-498f-843a-34487cf133eb`)
`55 60 01 cmd(lo) dir(hi) len u16 LE op payload crc16-modbus LE`. Напрямки: C0 get, F0 set, 40 answer, 70 ack, E0 unsolicited.
Заряд `C0 07`; шум `C0 1E`; set `F0 0F 01 <mode> 00` (01–04 ANC, 05 off, 07 прозорість). ✅ (Ear-серія)

## Xiaomi compact GAIA (SPP)
`FF 03 00 len vendor(BE) cmd(BE) payload`. Handshake `ff 03 00 00 00 0a 03 00`; режим `0x1003` get / `0x1004` set (00/01/02). Значення `0x04` перезавантажує навушники ⛔. ✅ Xiaomi Buds 5 Pro.

## Ще не реалізовано
Samsung (Galaxy Buds RFCOMM), Bose (BMAP), Soundcore, Huawei, OnePlus, Sennheiser, Edifier — лише розпізнавання моделі та Universal-функції. Еквалайзер, жести, просторовий звук — у жодного бренду ще немає перевірених команд у проєкті.


## Google Fast Pair Message Stream (read-mostly, universal)
Frame: `group u8 | code u8 | length u16 BE | payload`, group 0x03 = device information.
| Code | Payload | Used for |
|---|---|---|
| 0x0301 | Model ID | identity check of a learned endpoint |
| 0x0302 | 6 bytes, MSB first | priority GATT candidate (session only, masked in exports) |
| 0x0303 | 1 or 3 bytes, bit 7 charging, 0x7F unknown | battery L/R/case |
| 0x0304 | 1 byte or u16 BE, minutes | remaining time |
| 0x0305 | empty (request) | the only write, experimental opt-in, exact bytes `03050000` |
| 0x0306 | bit 0 right, bit 1 left | active earbuds |
| 0x0309 | ASCII | firmware |
Transports: RFCOMM `df21fe2c-2515-4fdb-8886-f12c4d67927c`, or L2CAP via PSM characteristic `FE2C1239-…` (service 0xFE2C).
Status: Experimental until a real headset delivers validated frames to a third-party app. Deferred: 0x0308 platform, 0x030B Find Hub id (privacy).
