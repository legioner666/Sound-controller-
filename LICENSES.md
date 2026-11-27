# Звіт про ліцензії та джерела

Ліцензію самого Sonic Control ще не обрано (рішення власника проєкту). Нижче — усі сторонні джерела, як вони використані і чому це допустимо.

## Код, використаний у проєкті

| Джерело | Ліцензія | Що взято | Де |
|---|---|---|---|
| [Earshot](https://github.com/HellzYeahh/earshot) | MIT | Парсер Apple proximity-оголошень (`ContinuityAdvert`, `AppleModels`) — адаптовано; таблиця моделей JBL і product id (дані) | `drivers/apple/.../ContinuityAdvert.kt`, `drivers/jbl/.../JblCatalog.kt` |
| AndroidX, Jetpack Compose, Material 3, Navigation, DataStore | Apache 2.0 | бібліотеки | Gradle-залежності |
| Kotlin, kotlinx.coroutines | Apache 2.0 | мова та бібліотека | Gradle-залежності |

## Джерела фактів про протоколи (код не копіювався)

| Джерело | Ліцензія | Використання |
|---|---|---|
| [ncr/omarchy-headphones](https://github.com/ncr/omarchy-headphones) `PROTOCOL.md` | MIT | Формати кадрів JBL H-AA, Airoha RACE (JBL Tour Pro 3), Sony MDR, Nothing NT Link, Xiaomi GAIA, Fast Pair Message Stream. Реалізація власна. |
| [FajkPes/JBL-Headphones-App](https://github.com/FajkPes/JBL-Headphones-App) | **ліцензії немає** (усі права захищено) | Лише факти: структура кадру JBL Protocol 4, ідентифікатори функцій (батарея 13/14/7939, product id 4, прошивка 12), список руйнівних команд для блокування. Жодного рядка коду не взято. |
| [Omega-Destroyer/PairFree](https://github.com/Omega-Destroyer/PairFree) | GPL-3.0 | Переглянуто для аналізу конкурентів; код **не використано** (несумісно з можливим закритим релізом). |
| [timschneeb/GalaxyBudsClient](https://github.com/timschneeb/GalaxyBudsClient) | GPL-3.0 | Лише як майбутнє джерело фактів для незалежної реалізації Samsung; код не використано. |
| [aimpr0xy/jbl-on-mac](https://github.com/aimpr0xy/jbl-on-mac) (issue #39, PR #40) | GPL-3.0 | TASK 014: лише факти з опублікованого запису трафіку застосунку JBL з Tune Beam 2 (`AA 77`, рівні `AA 91 2x`, `AA 06`, `AA 80`, `AA A2`). Реалізація власна, код не копіювався. |
| [gijogadds/JBL-Headphones](https://github.com/gijogadds/JBL-Headphones) | MIT | TASK 014: лише факти — профіль моделі Tune Beam 2 (PID, групи жестів, значення Smart Audio & Video, діапазон EQ) і опис Harman Protocol 3/4. Код не копіювався. |
| Google Fast Pair Hearable Controls | специфікація | TASK 014: формат Notify ANC state (група 0x08), лише пасивне читання. |
| Gadgetbridge | AGPL-3.0 | Не використано. |
| [AutoEq](https://github.com/jaakkopasanen/AutoEq) | MIT | Запланований імпорт кривих корекції (v0.2). |

## Повний текст MIT для Earshot

```
MIT License

Copyright (c) 2026 Earshot contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Повний текст MIT для omarchy-headphones (джерело документації)

```
MIT License

Copyright (c) 2026 Jacek Becela

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
