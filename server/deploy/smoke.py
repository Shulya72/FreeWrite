"""
Живая проверка сервера после выкладки: создаёт временную доску, отправляет и получает правки и
картинку, проверяет «поздняя правка побеждает» и чужой ключ, потом удаляет доску.
В журнал — только «OK …» и короткие ошибки, без адреса и ключей.
"""
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

URL = os.environ["FUNC_URL"]


def call(op, **kw):
    data = json.dumps(dict(kw, op=op)).encode()
    req = urllib.request.Request(URL, data=data, method="POST", headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        body = e.read()
        try:
            o = json.loads(body)
        except ValueError:
            o = {"error": "http", "message": body[:300].decode("utf-8", "replace")}
        if isinstance(o, dict) and "error" not in o:
            # Ошибка самой платформы функций (502): errorMessage / errorType.
            o = {"error": o.get("errorType", "platform"), "message": str(o.get("errorMessage", o))[:300]}
        return e.code, o


def ok(op, **kw):
    status, body = call(op, **kw)
    if status != 200:
        fail("%s → %s %s: %s" % (op, status, body.get("error"), body.get("message")))
    return body


def fail(msg):
    print("::error::" + msg)
    sys.exit(1)


def check(cond, msg):
    if not cond:
        fail(msg)
    print("OK " + msg)


def rev(ms, n=0, dev="smoke1"):
    return "%013d.%04d.%s" % (ms, n, dev)


# Первый вызов после выкладки может быть долгим (запуск, права роботу доходят не сразу).
for attempt in range(12):
    status, body = call("ping")
    if status == 200 and body.get("ok"):
        break
    print("сервер ещё не готов (%s %s: %s), жду…" % (status, body.get("error"), body.get("message", "")[:300]))
    time.sleep(10)
else:
    fail("сервер не отвечает на ping")
print("OK ping, версия API %s" % body.get("api"))

board = str(uuid.uuid4())
c = ok("create", board=board, title="Проверка сервера", device="smoke1")
key = c["key"]
check(len(c["invite"]) == 8, "доска создана, код-приглашение из 8 знаков")
j = ok("join", invite=c["invite"].lower())
check(j["board"] == board and j["key"] == key, "вход по коду-приглашению")

try:
    now = int(time.time() * 1000)
    t1 = {"id": "t1", "rev": rev(now), "k": "text", "d": json.dumps({"id": "t1", "text": "Захватка №1"})}
    s1 = {"id": "s1", "rev": rev(now, 1), "k": "stroke", "d": json.dumps({"id": "s1", "pts": [1, 2, 0.5]})}
    ti = {"id": "~title", "rev": rev(now, 2), "k": "meta", "d": json.dumps({"title": "Проверка — секция 3"})}
    r = ok("push", board=board, key=key, device="smoke1", author="Проверка Сервера", changes=[t1, s1, ti])
    check(r["applied"] == 3 and r["seq"] == 3, "отправлены 3 правки")
    r = ok("push", board=board, key=key, changes=[t1, s1])
    check(r["applied"] == 0, "повтор тех же правок ничего не меняет")
    older = dict(t1, rev=rev(now - 60000, 0, "kolya2"), d=json.dumps({"id": "t1", "text": "старое"}))
    newer = dict(t1, rev=rev(now + 1000, 0, "kolya2"), d=json.dumps({"id": "t1", "text": "Захватка №1 — сдано"}))
    check(ok("push", board=board, key=key, changes=[older])["applied"] == 0, "более старая правка не принята")
    check(ok("push", board=board, key=key, changes=[newer])["applied"] == 1, "более новая правка принята")
    ok("push", board=board, key=key, changes=[{"id": "s1", "rev": rev(now + 2000), "k": "stroke", "x": 1}])

    p = ok("pull", board=board, key=key, since=0)
    by = {x["id"]: x for x in p["changes"]}
    check(p["seq"] == 5 and len(by) == 3 and not p["more"], "получено 3 предмета, последний номер 5")
    check("сдано" in json.loads(by["t1"]["d"])["text"], "у надписи — поздняя правка")
    check(by["s1"].get("x") == 1, "удаление штриха дошло")
    check(p["title"] == "Проверка — секция 3", "название доски обновилось")
    check(by["~title"].get("a") == "Проверка Сервера", "у правки виден автор")
    check(ok("pull", board=board, key=key, since=5)["changes"] == [], "новых правок после последней нет")

    many = [{"id": "m%04d" % i, "rev": rev(now + 3000, i), "k": "stroke", "d": "{}"} for i in range(700)]
    check(ok("push", board=board, key=key, changes=many)["applied"] == 700, "отправлено 700 штрихов одним запросом")
    got, since = 0, 5
    while True:
        p = ok("pull", board=board, key=key, since=since)
        got += len(p["changes"])
        since = p["next"]
        if not p["more"]:
            break
    check(got == 700, "700 штрихов получены по частям")

    status, body = call("pull", board=board, key="чужой-ключ", since=0)
    check(status == 403, "с чужим ключом доступа нет")

    data = os.urandom((1 << 20) + 5000)
    parts = [data[:1 << 20], data[1 << 20:]]
    for i, part in enumerate(parts):
        ok("img_put", board=board, key=key, file="smoke-%s.jpg" % board[:8], part=i, parts=2, size=len(data),
           data=base64.b64encode(part).decode())
    name = "smoke-%s.jpg" % board[:8]
    check(ok("img_has", board=board, key=key, files=[name])["have"] == [name], "картинка 1 МБ+ выгружена двумя частями")
    back = b"".join(base64.b64decode(ok("img_get", board=board, key=key, file=name, part=i)["data"]) for i in range(2))
    check(back == data, "картинка скачана обратно без изменений")

    # Сайт для руководства (FW-003): страница в браузере и код «только смотреть».
    with urllib.request.urlopen(urllib.request.Request(URL + "?v=TEST-CODE", method="GET"), timeout=60) as r:
        html = r.read().decode("utf-8", "replace")
        check(r.status == 200 and "text/html" in r.headers.get("Content-Type", "") and "<title>FreeWrite" in html,
              "сайт: адрес в браузере открывает страницу")
    code = ok("view_link", board=board, key=key)["code"]
    check(len(code) == 8 and code != c["invite"], "код «только смотреть» выдан")
    v = ok("view", code=code)
    check(v["board"] == board and v["key"] != key, "по коду просмотра — ключ для чтения")
    check(len(ok("pull", board=board, key=v["key"], since=0)["changes"]) > 0, "по ключу просмотра доску можно читать")
    status, body = call("push", board=board, key=v["key"], changes=[t1])
    check(status == 403, "по ключу просмотра править нельзя")
    status, body = call("join", invite=code)
    check(status == 403 and body.get("error") == "view_only", "кодом просмотра в приложение не войти")
finally:
    r = ok("delete", board=board, key=key)
    print("OK временная доска удалена (картинок: %s)" % r.get("images"))

print("Сервер работает")
