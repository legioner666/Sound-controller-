# Архітектура Sonic Control

## Модулі (26)

```
app ─┬─ feature:{dashboard, devices, equalizer, noisecontrol, gestures, automation, battery, diagnostics, settings}
     ├─ drivers:{generic, jbl, sony, samsung, bose, soundcore, apple, nothing, xiaomi}
     └─ core:{bluetooth, audio, database, designsystem, devices, diagnostics, protocol}
```

| Модуль | Тип | Відповідальність |
|---|---|---|
| `core:protocol` | Kotlin/JVM | Hex, CRC, JSON-writer, `StreamBuffer` (нарізка кадрів) |
| `core:diagnostics` | Kotlin/JVM | журнал подій (кільцевий буфер), знеособлення MAC, JSON-звіт |
| `core:devices` | Kotlin/JVM | **HeadphoneDriver API**, `DeviceCapabilities`, статуси, каталог, `CommandExecutor`, `DeviceLink`, Fast Pair, `BaseDriverSession`, інтерфейси контролерів для UI |
| `core:bluetooth` | Android | `GattClient` (серіалізовані GATT-операції з тайм-аутами), GATT/RFCOMM-транспорти, `AndroidDeviceLink`, сканер, дозволи |
| `core:audio` | Android | гучність медіа (без boost), медіаклавіші, таймер сну (лише зменшення гучності) |
| `core:database` | Android | DataStore: налаштування, EQ-профілі, історія заряду, сіль для знеособлення |
| `core:designsystem` | Compose | тема (графіт + бірюза, темна/світла), компоненти, усі рядки (en/uk/pl) |
| `drivers:*` | Kotlin/JVM | по драйверу на виробника; без залежності від Android — тестуються на JVM |
| `feature:*` | Compose | екрани; отримують інтерфейси з `core:devices`, не знають про конкретні бренди |
| `app` | Android | `SonicController` (зв’язує драйвери з Bluetooth), навігація, DI-контейнер |

Драйвери не залежать один від одного й від Android. UI не залежить від драйверів.

## HeadphoneDriver API

```kotlin
interface HeadphoneDriver {
    val id: String; val brand: Brand
    val catalog: List<CatalogEntry>                         // декларація моделей і функцій
    fun match(identity: DeviceIdentity): DriverMatch?       // 0..100
    fun createSession(link: DeviceLink, identity, env): DriverSession
}
interface DriverSession {
    val state: StateFlow<HeadphoneState>                    // заряд, режим шуму, capabilities, протокол
    suspend fun start(); suspend fun stop()
    suspend fun refresh(): CommandOutcome
    suspend fun setNoiseMode(mode): CommandOutcome
    suspend fun probe(): CommandOutcome                     // лише з Lab
}
```

`DriverRegistry.resolve()` обирає драйвер із найвищим балом (ім’я в каталозі → SDP/GATT-сигнатура → префікс бренду). Невідомі пристрої отримують `GenericDriver`.

`DeviceLink` — усе, що драйвер може попросити в пристрою: SDP-записи, GATT-сервіси, читання, підписки, GATT-канал, RFCOMM-канал, BLE-оголошення. Реалізації: `AndroidDeviceLink` і фейки в тестах.

## Декларативні можливості

`DeviceCapabilities` = `Map<Feature, FeatureSupport(status, note)>`. Рівень (`Universal/Advanced/Full`) обчислюється автоматично:
- немає жодної розширеної функції зі статусом вище `Basic` → **Universal**;
- усі розширені функції `Verified` → **Full**;
- інакше → **Advanced**.

UI вирішує, що показувати, тільки через `capabilities.usable(feature, experimentalEnabled)`.

## Стани з’єднання (TASK 002)

«Підключено» більше не виводиться з того, що сесія стартувала. Кожен канал має власний стан (`LinkStatus`):

| Канал | Джерело | Коли «підтверджено» |
|---|---|---|
| Аудіо | Android A2DP/HFP, події ACL | Android повідомив про підключення |
| GATT | `GattClient` | сервіси знайдено |
| RFCOMM | `RfcommTransport` | сокет відкрито (`OPEN`; підтвердження дає лише канал керування) |
| Канал керування | драйвер | надійшов **повністю валідований** кадр (довжина, контрольна сума, відомий тип повідомлення) |

`ConnectionState.Active` означає лише «сесія драйвера працює». UI показує окремий рядок для кожного каналу; зелений колір — тільки для `CONFIRMED`.

## Життєвий цикл сесій — `SessionManager`

Актор у `core:devices` (чистий Kotlin, покритий тестами) серіалізує всі події: connect, disconnect, зміни аудіоз’єднання, результати старту, повтори.
- Кожна сесія має номер покоління; оновлення стану, результати старту, заплановані повтори й результати команд від заміненої сесії відкидаються.
- `session.start()` (GATT/RFCOMM I/O) виконується поза актором — disconnect не чекає на повільне підключення.
- При зупинці: `session.stop()` → `link.close()` (закриває GATT і всі сокети) → скасування scope сесії.
- `GattClient` ігнорує колбеки від закритого/заміненого `BluetoothGatt`; блокуючий `BluetoothSocket.connect()` переривається закриттям сокета при скасуванні.

## Приватність діагностики — `Redactor`

Експорт JSON пропускає кожен рядок, HEX-дамп і значення GATT через `Redactor`: MAC → солений хеш; відомі назви пристроїв (ASCII/UTF-8, без урахування регістру) замінюються в тексті й маскуються `xx` у HEX; MAC-адреси маскуються в обох порядках байтів; значення Device Name, Serial Number, System ID, Hardware Revision вилучаються.

## Еквалайзери

- **Еквалайзер телефона** (`core:audio/SystemEqualizer`) — `DynamicsProcessing` на аудіосесіях, про які повідомляють плеєри (`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`), і на глобальному виході, де телефон це дозволяє. Вхідне підсилення зменшується на найбільше позитивне підсилення смуги, далі лімітер −1 dBFS: гучніше, ніж без еквалайзера, не стає. Баланс L/R — лише послаблення протилежного каналу.
- **Еквалайзер навушників** — функція драйвера (`Feature.DEVICE_EQUALIZER`); поки в жодного драйвера немає перевіреної команди, тому вона `Unsupported`.

## Безпека команд (три рубежі)

1. **Allowlist драйвера** (`CommandGuard`) перевіряє *сирі байти* кадру: лише точні кадри з таблиці драйвера; для JBL додатково блокується factory reset `0x95`, будь-які команди Protocol 4, крім GET дозволених ID; для RACE — усе, крім керування режимом.
2. **Статусний бар’єр** у `CommandExecutor`: `Unsupported/Basic` не надсилаються ніколи, `Experimental` — лише після явного ввімкнення користувачем.
3. **Підтвердження відповіддю**: успіх (`Confirmed`) — лише за відповідним кадром від навушників у межах тайм-ауту; інакше `Timeout/Failed`. Зміна режиму там, де ACK лише відлунює запит (RACE, NT Link, GAIA), додатково перевіряється повторним читанням.

OTA, factory reset, записи в пам’ять, зміна сполучення не реалізовані в жодному драйвері й заблоковані на рівні байтів.

## Потоки даних

```
Android BT ──► AndroidDeviceLink ──► DriverSession ──► HeadphoneState (StateFlow)
                                                    └► SonicController.active ──► Compose UI
UI action ──► SonicController ──► DriverSession ──► CommandExecutor ──► guard ──► transport
```

Відновлення з’єднання: подійно, через `ACTION_ACL_CONNECTED/DISCONNECTED`; при невдалому старті сесії — експоненційні повтори (`ReconnectPolicy`: 1 с … 30 с, 8 спроб).
