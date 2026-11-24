# Підготовка до Google Play

- **Пакет**: `com.soniccontrol` (debug: `com.soniccontrol.debug`), target SDK 36, min SDK 29.
- **Підпис**: release-збірки підписуються **лише** приватним upload-ключем (секрети `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_STORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`). Без ключа release не підписується, а CI для тегу `v*` завершується помилкою — стабільний реліз з публічним debug-ключем неможливий.
- **Тестові збірки**: реліз `test-build` (pre-release) містить лише debug-APK (`com.soniccontrol.debug`), SHA-256 і номер коміту.
- **Дозволи**: `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS` (звичайний, для еквалайзера); на Android ≤ 11 `ACCESS_FINE_LOCATION` (`maxSdkVersion=30`). Немає `INTERNET`.
- **Data safety**: дані не збираються і не передаються; усе зберігається локально; експорт діагностики — лише вручну користувачем.
- **Торгові марки**: назви брендів лише в описі сумісності; в іконці та графіці — оригінальні елементи. Дисклеймер про незалежність — у застосунку та описі.
- **Залишилось**: політика конфіденційності (URL), скриншоти, feature graphic, вибір ліцензії проєкту, рішення щодо монетизації.
