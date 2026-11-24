# Журнал змін


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
