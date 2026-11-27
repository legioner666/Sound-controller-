# Матриця підтримуваних пристроїв (v0.1)

Статуси: **V** Verified · **S** Supported · **E** Experimental · **B** Basic · — недоступно.
`Verified` поки не має жодна модель: фізичне тестування ще не проводилось. Повний перелік моделей — у застосунку: «Пристрої → Сумісність».

| Бренд | Моделі | Протокол | Заряд | Заряд L/R/кейс | Шум (ANC/прозорість) | EQ/жести |
|---|---|---|---|---|---|---|
| **JBL Tune Beam 3** | — | визначається в Lab | S (Fast Pair/BAS) | E | E | — |
| JBL H-AA | Tune 230NC, Wave Buds 2, Tune Beam (1), Tune Beam 2, Live Beam 3, Tour Pro 2 та ін. (28) | H-AA | S | S | S | — |
| JBL Tune Beam 2 (B484FB) | TASK 014 | H-AA `AA 77`/`AA A2`/`AA 06`/`AA 80` | S | S | S + рівні E | жести E (4 розкладки, читання/запис з перевіркою), EQ — лише читання; див. JBL_GESTURE_PROTOCOL.md |
| JBL Airoha | Tour Pro 3 | RACE | S | — | S | — |
| JBL Airoha | Live Buds 3, Live Free 2, Reflect Aero, Tune 670/770NC, Endurance Race 2, Wave/Vibe Beam 2 | RACE | S | — | E | — |
| JBL Protocol 4 | Tune 780NC | P4 (читання) | S | E | — | — |
| Sony | WH-1000XM6, WH-1000XM5, WH-CH720N, WH-1000XM4 | MDR v2/v1 | S | — | S | — |
| Sony | WF-1000XM5/XM4, LinkBuds S, WF-C700N, ULT WEAR | MDR v2 | S | — | E | — |
| Nothing | Ear (a), Ear (2), Ear, Ear (stick), Headphone (1) | NT Link | S | S | S | — |
| Nothing / CMF | Ear (open), Ear (3), CMF Headphone Pro, CMF Buds 2/Pro 2 | NT Link | E | E | E | — |
| Xiaomi | Buds 5 Pro (S), Buds 5 (E) | GAIA | S | — | S / E | — |
| Xiaomi | Redmi Buds 5 / 5 Pro / 3 Pro | RCSP (план) | S | — | — | — |
| Apple | AirPods, AirPods Pro/Max, Beats (15) | BLE-оголошення | E | E | — | — |
| Samsung | Galaxy Buds3 Pro … Buds+ (8) | план v0.2 | S* | — | — | — |
| Bose | QC Ultra, QC45, QC Earbuds II, NC 700, QC35 II | план v0.2 | S* | — | — | — |
| Soundcore | Liberty 4/4 NC/5, Space Q45/One, Life Q30, P40i, Sleep A30 | план v0.2 | S* | — | — | — |
| Huawei, OnePlus, Sennheiser, Edifier | 16 моделей | розпізнавання | S* | — | — | — |
| Будь-які інші | — | GenericDriver | S* | — | — | — |

\* Заряд показується лише якщо навушники надають Fast Pair Message Stream або Bluetooth Battery Service.
Universal-функції (статус, гучність, медіаклавіші, таймер сну) — **B** для всіх пристроїв.


## Physical test notes (v0.2.2)
- **JBL Tune Beam 2** (not Tune Beam 3) was the device actually tested. Audio, AVRCP and volume work through Android.
- SDP showed A2DP, AVRCP, Hands-Free and the Google Fast Pair Message Stream only. The Fast Pair stream is a Google
  service, **not** a JBL control protocol, and is never counted as a control channel.
- GATT on the classic address timed out (no callback at all in 15 s) and the Fast Pair stream closed immediately.
  Interpretation: the classic address does not expose a connectable GATT server; the LE endpoint (if any) uses another address.
  v0.3 scans first, ranks LE endpoints by several signs, tries LE/AUTO/BR-EDR in order and records every attempt in diagnostics v3.
- The H-AA stack assignment of Tune Beam 2 in the catalog comes from earlier desk research and is **unverified**; no model is Verified.
