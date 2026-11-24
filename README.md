# Sonic Control

Універсальний Android-застосунок для керування Bluetooth-навушниками різних виробників.
Без реклами, реєстрації, аналітики та доступу до інтернету (у маніфесті немає дозволу `INTERNET`).

> Робоча назва продукту — **Sonic Control**, репозиторій — `Sound-controller-`.
> Перший фізичний тестовий пристрій — **JBL Tune Beam 3**.

## Встановлення

Готовий APK збирається автоматично після кожного push у `main`:
**Releases → `latest` → `SonicControl-debug.apk`** (Android 10+).

## Що вміє версія 0.1

| Рівень | Що працює |
|---|---|
| **Universal** (будь-які навушники) | пошук і розпізнавання пристроїв, статус підключення, гучність медіа (без підсилення понад максимум), керування відтворенням, заряд через Google Fast Pair або Bluetooth Battery Service (лише читання), таймер сну, історія заряду |
| **Advanced** (драйвери) | JBL (H-AA, Airoha RACE, Protocol 4 — лише читання), Sony MDR, Nothing NT Link, Xiaomi GAIA: заряд і керування шумозаглушенням; AirPods/Beats: заряд з BLE-оголошень |
| **Lab** | GATT-дерево, SDP-записи, читання/підписки, журнал подій, визначення протоколу, експорт знеособленого JSON |

Повна матриця — [docs/DEVICE_MATRIX.md](docs/DEVICE_MATRIX.md).

## Статуси сумісності

`Verified` — фізично перевірено проєктом · `Supported` — реалізовано за протоколом, підтвердженим на реальному обладнанні · `Experimental` — потребує тестування, вмикається лише вручну · `Basic` — функції Android · `Unsupported` — недоступно, елемент вимкнено.

Застосунок ніколи не показує вигаданий заряд і не повідомляє про успіх команди без відповіді навушників.

## Документація

- [Архітектура](docs/ARCHITECTURE.md)
- [Протоколи](docs/PROTOCOL.md)
- [Матриця пристроїв](docs/DEVICE_MATRIX.md)
- [Ліцензії та джерела](LICENSES.md)
- [План тестування й результати](docs/TEST_PLAN.md)
- [Технічні обмеження](docs/LIMITATIONS.md)
- [План версій](docs/ROADMAP.md)
- [Google Play](docs/PLAY_RELEASE.md)

## Збірка

```bash
./gradlew test :app:assembleDebug        # APK
./gradlew :app:bundleRelease             # AAB
```

JDK 17+, Android SDK 36. Для підпису AAB ключем Google Play додайте секрети `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_STORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD`.

## Торгові марки

Sonic Control — незалежний проєкт, не пов’язаний із виробниками навушників. Назви брендів і моделей належать власникам і використовуються лише для ідентифікації сумісних пристроїв.
