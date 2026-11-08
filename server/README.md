# Сервер общей доски FreeWrite

Функция Yandex Cloud Functions (Python 3.12) + база YDB (бессерверная) + Object Storage для картинок.
Файлы: `index.py` (точка входа `index.handler`, запросы), `fw_store.py` (YDB), `fw_blob.py` (картинки),
`fw_errors.py` (ошибки). Приставка `fw_` нужна: в среде функций уже есть свой модуль `errors`, и он
перекрывал наш.
Всё в каталоге `freewrite` облака Владимира, в пределах бесплатных лимитов.

## Как выкладывается

Push в `main` с изменениями в `server/` → GitHub Actions «Yandex Cloud deploy» (`.github/workflows/yc-deploy.yml`):

1. проверки без облака — `python3 -m unittest discover -s server/tests`;
2. вход роботом `freewrite-deploy` (секрет `YC_SA_JSON_CREDENTIALS`, каталог — `YC_FOLDER_ID`);
3. `deploy/ensure.sh` — создаёт, чего нет: робот сервера `freewrite-server` (роли `ydb.editor`,
   `storage.editor`), база `freewrite-db` (до 1 ГБ, 10 RU/с по умолчанию), закрытый бакет картинок
   (до 1 ГБ), функция `freewrite-sync` (открыта для вызова — доступ к доскам по ключам);
4. новая версия функции;
5. `deploy/smoke.py` — живая проверка на временной доске (потом удаляется).

Номера, адреса и ключи в журнал не попадают (репозиторий публичный).

## Запросы

POST JSON `{"op": ...}` на адрес функции. Ошибка — `{"error": код, "message": текст}`.

| op | что | поля → ответ |
|---|---|---|
| `ping` | жив ли | → `ok`, `api` |
| `create` | выложить доску | `board`, `title`, `device` → `key`, `invite` |
| `join` | войти по коду | `invite` → `board`, `key`, `title`, `seq` |
| `info` | сводка | `board`, `key` → `title`, `invite`, `seq`, `objects` |
| `push` | правки | `board`, `key`, `device`, `changes:[{id, rev, k, d \| x:1}]` (до 1000) → `seq`, `applied`, `ignored` |
| `pull` | что нового | `board`, `key`, `since` → `seq`, `next`, `more`, `title`, `changes:[{id, rev, k, s, d \| x:1}]` (до 500 / 2 МБ) |
| `img_has` | какие картинки есть | `board`, `key`, `files` → `have` |
| `img_put` | часть картинки (≤ 1 МБ, base64) | `board`, `key`, `file`, `part`, `parts`, `size`, `data` |
| `img_get` | часть картинки | `board`, `key`, `file`, `part` → `data`, `parts`, `size` |
| `delete` | убрать доску с сервера | `board`, `key` |
| `view_link` | код «только смотреть» (сайт) | `board`, `key` (ключ доски) → `code` (один на доску, повторно — тот же) |
| `view` | войти по коду просмотра | `code` → `board`, `key` (ключ чтения), `title`, `seq`, `view` |

Ключом чтения (из `view`) можно `pull`, `info` (без `invite`), `img_has`, `img_get`; `push`, `img_put`, `delete`,
`view_link` — 403. `join` кодом просмотра — 403 `view_only`.

## Сайт для просмотра с компьютера (FW-003)

GET на адрес функции без `op` (например, `…?v=ABCD-EFGH`) — страница `viewer.html` (один файл, без внешних
библиотек): вход по коду, доска (мышь — сдвиг, колёсико — масштаб у курсора, тачпад и пальцы), картинки частями с
кэшем в браузере (IndexedDB), легенда статусов по разделам, сводка, «Скачать для Excel» (CSV с `;`), обновление
раз в 20 с. Код доски из приложения тоже подходит (тогда на сайте есть «Ссылка для руководства» → `view_link`).
Ссылку на сайт даёт приложение: Ежедневник → подержать общую доску → «Открыть на компьютере».
Проверка на своём компьютере: `python3 server/tests/devserver.py 8787` (база в памяти, показательная доска).

Правило слияния — как в приложении (`BoardSync`): по каждому предмету побеждает больший `rev`
(строки `%013d.%04d.<устройство>`), удаление (`x:1`) — тоже правка. Каждая принятая правка получает
свой номер `seq`; `pull` отдаёт правки по порядку `seq`.
