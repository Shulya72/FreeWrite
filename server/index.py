"""
Сервер общей доски FreeWrite — Yandex Cloud Functions, Python 3.12.

Все запросы — POST с JSON {"op": "...", ...}, ответ — JSON. Ошибка — {"error": код, "message": текст}.

Доска на сервере — набор предметов: номер (id) → отметка правки (rev), вид, данные (строка JSON
предмета, как в приложении). Побеждает более поздняя правка: rev сравниваются как строки
(«гибридные часы» приложения, BoardSync). Удаление — тоже правка (deleted). У каждой принятой
правки свой номер seq по порядку — приложение спрашивает «что нового после seq N».

Доступ: доску создаёт приложение (create) и получает ключ доски и код-приглашение. Коллега вводит
код (join) и получает тот же ключ. Все остальные запросы — с номером доски и ключом.

Картинки — частями по 1 МБ в Object Storage (img_put / img_get), список готовых — img_has.
"""
import base64
import hmac
import json
import os
import re
import secrets
import time

from fw_errors import ApiError, forbidden, not_found

API_VERSION = 1
CHUNK = 1 << 20                     # часть картинки до base64
MAX_CHANGES = 1000                  # правок в одном запросе push
MAX_BODY = 512 * 1024               # данные одного предмета
MAX_PULL = 500                      # предметов в одном ответе pull
MAX_PULL_BYTES = 2 << 20            # данных в одном ответе pull
MAX_PARTS = 64                      # частей у одной картинки
MAX_IMAGE_SIZE = MAX_PARTS * CHUNK  # одна картинка — до 64 МБ
MAX_FILES = 500                     # имён в одном запросе img_has
MAX_BOARDS = int(os.environ.get("MAX_BOARDS", "300"))
MAX_OBJECTS = int(os.environ.get("MAX_OBJECTS", "200000"))
MAX_IMAGE_BYTES = int(os.environ.get("MAX_IMAGE_BYTES", str(900 << 20)))  # под лимит бакета 1 ГБ

BOARD_RE = re.compile(r"^[A-Za-z0-9-]{8,64}$")
ID_RE = re.compile(r"^[A-Za-z0-9~_-]{1,64}$")
REV_RE = re.compile(r"^[0-9]{13}\.[0-9]{4}\.[A-Za-z0-9]{1,16}$")
DEVICE_RE = re.compile(r"^[A-Za-z0-9]{1,16}$")
FILE_RE = re.compile(r"^[A-Za-z0-9_-][A-Za-z0-9._-]{0,119}$")
KINDS = {"stroke", "text", "image", "sticker", "fill", "callout", "status", "meta", ""}
INVITE_ABC = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

_store = None
_blob = None


def store():
    """База (YDB). Создаётся один раз на экземпляр функции; в тестах подменяется."""
    global _store
    if _store is None:
        from fw_store import YdbStore
        _store = YdbStore(os.environ["YDB_ENDPOINT"], os.environ["YDB_DATABASE"])
    return _store


def blob():
    global _blob
    if _blob is None:
        from fw_blob import Blob
        _blob = Blob(os.environ["BUCKET"])
    return _blob


def now_ms() -> int:
    return int(time.time() * 1000)


# ---------------------------------------------------------------- разбор запроса

def _parse(event) -> dict:
    if not isinstance(event, dict):
        raise ApiError(400, "bad_request", "Неверный запрос")
    if "httpMethod" not in event:
        return event  # прямой вызов (integration=raw): событие — сам запрос
    if event.get("httpMethod") == "GET":
        q = event.get("queryStringParameters") or {}
        return {"op": q.get("op", "ping")}
    body = event.get("body") or ""
    if event.get("isBase64Encoded"):
        body = base64.b64decode(body).decode("utf-8")
    try:
        req = json.loads(body) if body else {}
    except ValueError:
        raise ApiError(400, "bad_json", "Запрос — не JSON")
    if not isinstance(req, dict):
        raise ApiError(400, "bad_request", "Неверный запрос")
    return req


def _reply(status: int, obj: dict) -> dict:
    return {
        "statusCode": status,
        "headers": {"Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store"},
        "body": json.dumps(obj, ensure_ascii=False, separators=(",", ":")),
        "isBase64Encoded": False,
    }


def _str(req: dict, name: str, pattern=None, required=True, limit=200) -> str:
    v = req.get(name)
    if v is None and not required:
        return ""
    if not isinstance(v, str) or len(v) > limit or (pattern is not None and not pattern.match(v)):
        raise ApiError(400, "bad_" + name, "Неверное поле " + name)
    return v


def _int(req: dict, name: str, lo: int, hi: int, default=None) -> int:
    v = req.get(name, default)
    if isinstance(v, bool) or not isinstance(v, int) or v < lo or v > hi:
        raise ApiError(400, "bad_" + name, "Неверное поле " + name)
    return v


def _board(req: dict):
    return _str(req, "board", BOARD_RE), _str(req, "key", limit=100)


def _device(req: dict) -> str:
    return _str(req, "device", DEVICE_RE, required=False) or "-"


def _check(board: dict, key: str) -> dict:
    if board is None:
        raise not_found()
    if not hmac.compare_digest(board["secret"].encode(), key.encode()):
        raise forbidden()
    return board


def _token(context) -> str:
    tok = getattr(context, "token", None) or {}
    t = tok.get("access_token") if isinstance(tok, dict) else None
    if not t:
        raise ApiError(500, "no_token", "У сервера нет доступа к хранилищу картинок")
    return t


# ---------------------------------------------------------------- запросы

def op_ping(req, ctx):
    return {"ok": True, "api": API_VERSION, "time": now_ms()}


def _new_invite() -> str:
    return "".join(secrets.choice(INVITE_ABC) for _ in range(8))


def normalize_invite(code: str) -> str:
    c = re.sub(r"[\s-]", "", code.upper()).replace("0", "O").replace("1", "I")
    return c


def op_create(req, ctx):
    """Выложить доску на сервер: ключ доски и код-приглашение для коллег."""
    b = _str(req, "board", BOARD_RE)
    title = _str(req, "title", required=False, limit=200)
    device = _device(req)
    st = store()
    existing = st.get_board(b)
    if existing is not None:
        # Тот же планшет, что выкладывал доску (ответ мог потеряться), — вернуть его же ключ и код.
        if device != "-" and existing.get("owner") == device:
            return {"board": b, "key": existing["secret"], "invite": existing["invite"],
                    "seq": existing["seq"], "again": True}
        raise ApiError(409, "exists", "Доска уже есть на сервере")
    if st.count_boards() >= MAX_BOARDS:
        raise ApiError(507, "full", "На сервере нет места для новых досок")
    key = secrets.token_urlsafe(18)
    for _ in range(5):
        invite = _new_invite()
        if st.find_invite(invite) is None:
            break
    else:
        raise ApiError(500, "invite", "Не удалось придумать код-приглашение")
    if not st.create_board(b, key, title, invite, device, now_ms()):
        raise ApiError(409, "exists", "Доска уже есть на сервере")
    return {"board": b, "key": key, "invite": invite, "seq": 0}


def op_join(req, ctx):
    """Код-приглашение → доска и её ключ."""
    code = normalize_invite(_str(req, "invite", limit=40))
    if len(code) != 8:
        raise ApiError(404, "no_invite", "Код не найден — проверьте, нет ли опечатки")
    st = store()
    b = st.find_invite(code)
    board = st.get_board(b) if b else None
    if board is None:
        raise ApiError(404, "no_invite", "Код не найден — проверьте, нет ли опечатки")
    return {"board": board["board_id"], "key": board["secret"], "title": board["title"],
            "invite": board["invite"], "seq": board["seq"]}


def op_info(req, ctx):
    b, key = _board(req)
    board = _check(store().get_board(b), key)
    return {"board": b, "title": board["title"], "invite": board["invite"], "seq": board["seq"],
            "objects": board["n_objects"]}


def _changes(req) -> list:
    items = req.get("changes")
    if not isinstance(items, list) or len(items) > MAX_CHANGES:
        raise ApiError(400, "bad_changes", "Неверный список правок")
    best = {}
    for c in items:
        if not isinstance(c, dict):
            raise ApiError(400, "bad_changes", "Неверный список правок")
        cid = _str(c, "id", ID_RE)
        rev = _str(c, "rev", REV_RE)
        deleted = bool(c.get("x"))
        kind = _str(c, "k", required=False, limit=16)
        if kind not in KINDS:
            raise ApiError(400, "bad_k", "Неизвестный вид предмета")
        body = "" if deleted else _str(c, "d", limit=MAX_BODY)
        prev = best.get(cid)
        if prev is None or rev > prev["rev"]:
            best[cid] = {"id": cid, "rev": rev, "kind": kind, "deleted": deleted, "body": body}
    return list(best.values())


def op_push(req, ctx):
    """Отправить правки. Принимаются только более поздние, чем на сервере."""
    b, key = _board(req)
    changes = _changes(req)
    seq, applied = store().push(b, key, changes, _device(req), now_ms(), MAX_OBJECTS)
    return {"seq": seq, "applied": applied, "ignored": len(changes) - applied}


def op_pull(req, ctx):
    """Правки после seq since (по порядку). more — есть ещё, спросить с since = next."""
    b, key = _board(req)
    since = _int(req, "since", 0, 1 << 62, 0)
    limit = _int(req, "limit", 1, MAX_PULL, MAX_PULL)
    seq, title, rows = store().pull(b, key, since, limit)
    out = []
    size = 0
    nxt = since
    for r in rows:
        size += len(r["body"]) + 96
        if out and size > MAX_PULL_BYTES:
            break
        e = {"id": r["id"], "rev": r["rev"], "k": r["kind"], "s": r["seq"]}
        if r["deleted"]:
            e["x"] = 1
        else:
            e["d"] = r["body"]
        out.append(e)
        nxt = r["seq"]
    # Нет новых правок — курсор остаётся. Если since > seq (доску на сервере пересоздали),
    # приложение увидит это и начнёт с нуля.
    return {"seq": seq, "title": title, "next": nxt, "more": nxt < seq, "changes": out}


def _files(req) -> list:
    files = req.get("files")
    if not isinstance(files, list) or len(files) > MAX_FILES:
        raise ApiError(400, "bad_files", "Неверный список картинок")
    for f in files:
        if not isinstance(f, str) or not FILE_RE.match(f):
            raise ApiError(400, "bad_files", "Неверное имя картинки")
    return files


def op_img_has(req, ctx):
    """Какие из картинок уже есть на сервере целиком."""
    b, key = _board(req)
    files = _files(req)
    st = store()
    _check(st.get_board(b), key)
    have = st.images(files)
    return {"have": sorted(have.keys())}


def op_img_put(req, ctx):
    """Часть картинки (до 1 МБ). Последняя часть — картинка готова."""
    b, key = _board(req)
    name = _str(req, "file", FILE_RE, limit=120)
    parts = _int(req, "parts", 1, MAX_PARTS)
    part = _int(req, "part", 0, parts - 1)
    size = _int(req, "size", 1, MAX_IMAGE_SIZE)
    try:
        raw = base64.b64decode(_str(req, "data", limit=CHUNK * 2), validate=True)
    except ValueError:
        raise ApiError(400, "bad_data", "Часть картинки повреждена")
    if not raw or len(raw) > CHUNK:
        raise ApiError(400, "bad_data", "Часть картинки слишком большая")
    st = store()
    _check(st.get_board(b), key)
    if part == 0:
        if name in st.images([name]):
            return {"ok": True, "exists": True}
        if st.images_total() + size > MAX_IMAGE_BYTES:
            raise ApiError(507, "full", "В хранилище картинок нет места")
    blob().put("img/%s/%d" % (name, part), raw, _token(ctx))
    if part == parts - 1:
        st.put_image(name, b, parts, size, now_ms())
    return {"ok": True}


def op_img_get(req, ctx):
    """Часть картинки (base64)."""
    b, key = _board(req)
    name = _str(req, "file", FILE_RE, limit=120)
    st = store()
    _check(st.get_board(b), key)
    info = st.images([name]).get(name)
    if info is None:
        raise ApiError(404, "no_image", "Картинки нет на сервере")
    part = _int(req, "part", 0, info["parts"] - 1, 0)
    raw = blob().get("img/%s/%d" % (name, part), _token(ctx))
    return {"data": base64.b64encode(raw).decode("ascii"), "parts": info["parts"], "size": info["size"]}


def op_delete(req, ctx):
    """Убрать доску с сервера (вместе с её картинками). У всех на планшетах доска остаётся."""
    b, key = _board(req)
    st = store()
    _check(st.get_board(b), key)
    removed = st.delete_board(b, key)
    if removed:
        token = _token(ctx)
        for name, parts in removed:
            for p in range(parts):
                blob().delete("img/%s/%d" % (name, p), token)
    return {"ok": True, "images": len(removed)}


OPS = {
    "ping": op_ping,
    "create": op_create,
    "join": op_join,
    "info": op_info,
    "push": op_push,
    "pull": op_pull,
    "img_has": op_img_has,
    "img_put": op_img_put,
    "img_get": op_img_get,
    "delete": op_delete,
}


def handler(event, context):
    op = "?"
    try:
        req = _parse(event)
        op = req.get("op") if isinstance(req.get("op"), str) else "?"
        fn = OPS.get(op)
        if fn is None:
            raise ApiError(400, "bad_op", "Неизвестный запрос")
        return _reply(200, fn(req, context))
    except ApiError as e:
        return _reply(e.status, {"error": e.code, "message": e.message})
    except Exception as e:  # noqa: BLE001 — любая другая ошибка: в журнал функции, приложению — коротко
        print("ERROR op=%s %s: %s" % (op, type(e).__name__, e))
        return _reply(500, {"error": "server", "message": "Ошибка сервера (%s)" % type(e).__name__})
