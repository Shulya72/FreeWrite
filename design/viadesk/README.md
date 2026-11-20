# ViaDesk — знак и макет

- `viadesk-znak.svg` — знак (бирюзовая «V», слои-листы, план с янтарной захваткой), вектор.
- `viadesk-znachok.svg` — значок приложения на ночном синем.
- `maket.html` — макет: три вкладки (Заметки, Задачи, Доски), стиль бренда, цвета. Опубликован:
  https://claude.ai/artifact/GqhCgMFLYwVKWbnFbtFtV7

Цвета: ночной синий `#092638`, бирюза `#08C9B5`, янтарь `#FFB52E`, белый. Текст и кнопки в светлой теме — тёмная
бирюза `#0A8F82` (яркая на белом плохо читается).

Шрифт названий — Montserrat Bold (© The Montserrat Project Authors, SIL Open Font License 1.1),
`app/src/main/res/font/montserrat_bold.ttf`.

Значок в приложении (с 0.29.2) — картинка Владимира `viadesk-icon-1024.png`: белая рамка вокруг квадрата убрана,
тёмно-синий фон продлён до краёв, знак уменьшен так, чтобы целиком помещался и в круге, и в «скруглённом квадрате»
(радиус знака — 30 % стороны: безопасная зона значка Android — круг 66 из 108 dp). Из неё —
`res/drawable-*/ic_launcher_foreground.png` (108…432 px, без прозрачности) и `res/drawable/ic_launcher_background.xml`
(тот же градиент под ней). Прежний знак — `viadesk-znak.svg`, `viadesk-znachok.svg` (до 0.29.2).
